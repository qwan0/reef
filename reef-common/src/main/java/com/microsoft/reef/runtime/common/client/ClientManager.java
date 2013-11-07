/**
 * Copyright (C) 2013 Microsoft Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.microsoft.reef.runtime.common.client;

import com.microsoft.reef.client.DriverConfigurationOptions;
import com.microsoft.reef.client.REEF;
import com.microsoft.reef.client.RuntimeErrorHandler;
import com.microsoft.reef.proto.ClientRuntimeProtocol.JobSubmissionProto;
import com.microsoft.reef.proto.ReefServiceProtos;
import com.microsoft.reef.proto.ReefServiceProtos.FileResourceProto;
import com.microsoft.reef.proto.ReefServiceProtos.FileType;
import com.microsoft.reef.proto.ReefServiceProtos.JobStatusProto;
import com.microsoft.reef.proto.ReefServiceProtos.RuntimeErrorProto;
import com.microsoft.reef.runtime.common.client.api.JobSubmissionHandler;
import com.microsoft.reef.runtime.common.utils.RemoteManager;
import com.microsoft.reef.util.RuntimeError;
import com.microsoft.reef.utils.IOUtils;
import com.microsoft.reef.utils.JARFileMaker;
import com.microsoft.tang.Configuration;
import com.microsoft.tang.InjectionFuture;
import com.microsoft.tang.Injector;
import com.microsoft.tang.Tang;
import com.microsoft.tang.annotations.Name;
import com.microsoft.tang.annotations.NamedParameter;
import com.microsoft.tang.exceptions.BindException;
import com.microsoft.tang.exceptions.InjectionException;
import com.microsoft.tang.formats.ConfigurationFile;
import com.microsoft.wake.EventHandler;
import com.microsoft.wake.remote.RemoteMessage;

import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class ClientManager implements REEF, EventHandler<RemoteMessage<JobStatusProto>> {

  private static final String REEF = new StringBuilder()
      .append("\nPowered by\n")
      .append("     ___________  ______  ______  _______" + "\n")
      .append("    /  ______  / /  ___/ /  ___/ /  ____/" + "\n")
      .append("   /     _____/ /  /__  /  /__  /  /___" + "\n")
      .append("  /  /\\  \\     /  ___/ /  ___/ /  ____/" + "\n")
      .append(" /  /  \\  \\   /  /__  /  /__  /  /" + "\n")
      .append("/__/    \\__\\ /_____/ /_____/ /__/" + "    version 0.1.0\n\n")
      .append("From Microsoft CISL\n\n").toString();

  static {
    System.out.println(REEF);
  }

  private static final File tempFolder;

  static {
    try {
      tempFolder = IOUtils.makeTempFolder("reef-tmp-tempFolder", "");
    } catch (IOException e) {
      throw new RuntimeException("Can't create temp tempFolder.", e);
    }
  }

  private final static Logger LOG = Logger.getLogger(ClientManager.class.toString());
  private final Injector injector;
  private final RemoteManager remoteManager;
  private final JobSubmissionHandler jobSubmissionHandler;
  private final AutoCloseable masterChannel;
  private final AutoCloseable errorChannel;
  private final String userName = System.getProperty("user.name");
  private final Map<String, RunningJobImpl> runningJobMap = new HashMap<>();


  @Inject
  ClientManager(final Injector injector, final InjectionFuture<RuntimeErrorHandler> runtimeErrorHandlerFuture, final RemoteManager remoteManager, final JobSubmissionHandler jobSubmissionHandler) {
    this.injector = injector;
    this.remoteManager = remoteManager;
    this.jobSubmissionHandler = jobSubmissionHandler;

    this.masterChannel = this.remoteManager.registerHandler(JobStatusProto.class, this);
    this.errorChannel = this.remoteManager.registerHandler(RuntimeErrorProto.class, new RuntimeErrorProtoHandler(runtimeErrorHandlerFuture));
  }

  @Override
  public final void close() {

    if (this.runningJobMap.size() > 0) {
      LOG.warning("unclean shutdown: " + this.runningJobMap.size() + " jobs still running.");
      for (final RunningJobImpl runningJob : this.runningJobMap.values()) {
        LOG.warning("Force close job " + runningJob.getId());
        runningJob.close();
      }
    }

    // This pushes the close into a separate thread in the absense of a fix for Wake-78
    final Runnable r = new Runnable() {
      @Override
      public void run() {
        LOG.info("REEF client manager closing");
        try {
          ClientManager.this.masterChannel.close();
        } catch (final Exception e) {
          LOG.log(Level.SEVERE, "Unable to close masterChannel", e);
        }

        try {
          ClientManager.this.errorChannel.close();
        } catch (final Exception e) {
          LOG.log(Level.SEVERE, "Unable to close errorChannel", e);
        }

        try {
          ClientManager.this.remoteManager.close();
        } catch (final Exception e) {
          LOG.log(Level.SEVERE, "Unable to close remoteManager", e);
        }

        ClientManager.this.jobSubmissionHandler.close();
        LOG.info("REEF client manager closed");
      }
    };
    final ExecutorService ex = Executors.newSingleThreadExecutor();
    ex.submit(r);
    ex.shutdown();
  }

  @Override
  public void submit(final Configuration driverConf) {
    final Injector injector = Tang.Factory.getTang().newInjector(driverConf);
    try {
      final JobSubmissionProto.Builder jbuilder = JobSubmissionProto.newBuilder()
          .setIdentifier(injector.getNamedInstance(DriverConfigurationOptions.DriverIdentifier.class))
          .setRemoteId(this.remoteManager.getMyIdentifier())
          .setUserName(this.userName)
          .setDriverSize(ReefServiceProtos.SIZE.valueOf(injector.getNamedParameter(DriverConfigurationOptions.DriverSize.class)))
          .setConfiguration(ConfigurationFile.toConfigurationString(driverConf));


      for (final String globalFileName : injector.getNamedParameter(DriverConfigurationOptions.GlobalFiles.class)) {
        LOG.log(Level.FINE, "Adding global file: " + globalFileName);
        jbuilder.addGlobalFile(getFileResourceProto(new File(globalFileName), FileType.PLAIN));
      }

      for (final String globalLibraryName : injector.getNamedParameter(DriverConfigurationOptions.GlobalLibraries.class)) {
        LOG.log(Level.FINE, "Adding global library: " + globalLibraryName);
        jbuilder.addGlobalFile(getFileResourceProto(new File(globalLibraryName), FileType.LIB));
      }

      for (final String localFileName : injector.getNamedParameter(DriverConfigurationOptions.LocalFiles.class)) {
        LOG.log(Level.FINE, "Adding local file: " + localFileName);
        jbuilder.addLocalFile(getFileResourceProto(new File(localFileName), FileType.PLAIN));
      }

      for (final String localLibraryName : injector.getNamedParameter(DriverConfigurationOptions.LocalLibraries.class)) {
        LOG.log(Level.FINE, "Adding local library: " + localLibraryName);
        jbuilder.addLocalFile(getFileResourceProto(new File(localLibraryName), FileType.LIB));
      }


      this.jobSubmissionHandler.onNext(jbuilder.build());
    } catch (final Exception e) {
      LOG.log(Level.SEVERE, "Exception while processing driver configuration.");
      try {
        this.masterChannel.close();
        this.errorChannel.close();
      } catch (final Exception e1) {
        LOG.log(Level.SEVERE, "Exception while closing communication channels.");
      }
      throw new RuntimeException(e);
    }
  }

  private final FileResourceProto getFileResourceProto(final File file, final FileType type) throws IOException {
    if (file.isDirectory()) {
      return getFileResourceProto(toJar(file), type);
    } else {
      return FileResourceProto.newBuilder().setName(file.getName()).setPath(file.getPath()).setType(type).build();
    }
  }

  @Override
  public synchronized final void onNext(final RemoteMessage<JobStatusProto> message) {
    try {
      final JobStatusProto status = message.getMessage();

      if (status.getState() == ReefServiceProtos.State.INIT) {
        assert (!this.runningJobMap.containsKey(status.getIdentifier()));
        LOG.info("Initializing running job " + status.getIdentifier());
        final Injector child = this.injector.createChildInjector();
        child.bindVolatileParameter(DriverRemoteIdentifier.class, message.getIdentifier().toString());
        child.bindVolatileInstance(JobStatusProto.class, status);

        final RunningJobImpl runningJob = child.getInstance(RunningJobImpl.class);
        this.runningJobMap.put(status.getIdentifier(), runningJob);
        LOG.info("Launched running job " + status.getIdentifier());
      } else if (this.runningJobMap.containsKey(status.getIdentifier())) {
        this.runningJobMap.get(status.getIdentifier()).onNext(status);
        if (status.getState() != ReefServiceProtos.State.RUNNING) {
          this.runningJobMap.remove(status.getIdentifier());
        }
      } else {
        throw new RuntimeException("Unknown running job status: " + status);
      }
    } catch (final BindException | InjectionException e) {
      try {
        this.masterChannel.close();
      } catch (Exception e1) {
      }
      throw new RuntimeException(e);
    }
  }

  @NamedParameter(doc = "The driver remote identifier.")
  public final static class DriverRemoteIdentifier implements Name<String> {
  }

  /**
   * Turns a tempFolder "foo" into a jar file "foo.jar"
   *
   * @param file
   * @return
   * @throws IOException
   */
  private static File toJar(final File file) throws IOException {
    final File jarFile = File.createTempFile(file.getName(), ".jar", tempFolder);
    LOG.log(Level.INFO, "Adding contents of folder " + file + " to " + jarFile);
    try (final JARFileMaker jarMaker = new JARFileMaker(jarFile)) {
      jarMaker.addChildren(file);
    }
    return jarFile;
  }


  private final static class RuntimeErrorProtoHandler implements EventHandler<RemoteMessage<RuntimeErrorProto>> {

    private final InjectionFuture<RuntimeErrorHandler> runtimeErrorHandlerFuture;

    RuntimeErrorProtoHandler(final InjectionFuture<RuntimeErrorHandler> runtimeErrorHandlerFuture) {
      this.runtimeErrorHandlerFuture = runtimeErrorHandlerFuture;
    }

    @Override
    public void onNext(final RemoteMessage<RuntimeErrorProto> value) {
      LOG.warning("Runtime Error: " + value.getMessage().getMessage());
      this.runtimeErrorHandlerFuture.get().onError(new RuntimeError(value.getMessage()));
    }
  }


}
