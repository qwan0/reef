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
package com.microsoft.reef.examples.persistent_eval;

import com.microsoft.reef.client.*;
import com.microsoft.reef.util.RuntimeError;
import com.microsoft.reef.utils.EnvironmentUtils;
import com.microsoft.tang.Configuration;
import com.microsoft.tang.annotations.NamedParameter;
import com.microsoft.tang.annotations.Parameter;
import com.microsoft.tang.exceptions.BindException;
import com.microsoft.wake.remote.impl.ObjectSerializableCodec;

import javax.inject.Inject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Persistent Evaluator Shell Client.
 */
public class JobClient implements JobObserver, RuntimeErrorHandler {

  /**
   * Standard java logger.
   */
  private static final Logger LOG = Logger.getLogger(JobClient.class.getName());

  /**
   * Codec to translate messages to and from the job driver
   */
  private static final ObjectSerializableCodec<String> CODEC = new ObjectSerializableCodec<>();

  /**
   * Reference to the REEF framework.
   * This variable is injected automatically in the constructor.
   */
  private final REEF reef;

  /**
   * Shell command to submitActivity to the job driver.
   */
  private final String command;

  /**
   * Job Driver configuration.
   */
  private final Configuration driverConfiguration;

  /**
   * If true, take commands from stdin; otherwise, use -cmd parameter in batch mode.
   */
  private final boolean isInteractive;

  /**
   * Total number of experiments to run.
   */
  private final int maxRuns;

  /**
   * Command prompt reader for the interactive mode (stdin).
   */
  private final BufferedReader prompt;

  /**
   * A reference to the running job that allows client to send messages back to the job driver
   */
  private RunningJob runningJob;

  /**
   * Start timestamp of the current task.
   */
  private long startTime = 0;

  /**
   * Total time spent performing tasks in Evaluators.
   */
  private long totalTime = 0;

  /**
   * Number of experiments ran so far.
   */
  private int numRuns = 0;

  /**
   * Set to false when job driver is done.
   */
  private boolean isBusy = true;

  /**
   * Persistent Evaluator client.
   * Parameters are injected automatically by TANG.
   *
   * @param command Shell command to run on each Evaluator.
   * @param reef    Reference to the REEF framework.
   */
  @Inject
  JobClient(final REEF reef,
            @Parameter(Launch.Command.class) final String command,
            @Parameter(Launch.NumRuns.class) final Integer numRuns) throws BindException {

    this.reef = reef;
    this.command = command;
    this.maxRuns = numRuns;

    // If command is not set, switch to interactive mode. (Yes, we compare pointers here)
    this.isInteractive = this.command ==
        Launch.Command.class.getAnnotation(NamedParameter.class).default_value();

    this.prompt = this.isInteractive ?
        new BufferedReader(new InputStreamReader(System.in)) : null;

    this.driverConfiguration =
        EnvironmentUtils.addClasspath(DriverConfiguration.CONF, DriverConfiguration.GLOBAL_LIBRARIES)
          .set(DriverConfiguration.DRIVER_IDENTIFIER, "eval-" + System.currentTimeMillis())
          .set(DriverConfiguration.ON_EVALUATOR_ALLOCATED, JobDriver.AllocatedEvaluatorHandler.class)
          .set(DriverConfiguration.ON_EVALUATOR_FAILED, JobDriver.FailedEvaluatorHandler.class)
          .set(DriverConfiguration.ON_CONTEXT_ACTIVE, JobDriver.ActiveContextHandler.class)
          .set(DriverConfiguration.ON_CONTEXT_CLOSED, JobDriver.ClosedContextHandler.class)
          .set(DriverConfiguration.ON_CONTEXT_FAILED, JobDriver.FailedContextHandler.class)
          .set(DriverConfiguration.ON_ACTIVITY_COMPLETED, JobDriver.CompletedActivityHandler.class)
          .set(DriverConfiguration.ON_CLIENT_MESSAGE, JobDriver.ClientMessageHandler.class)
          .set(DriverConfiguration.ON_DRIVER_STARTED, JobDriver.StartHandler.class)
          .set(DriverConfiguration.ON_DRIVER_STOP, JobDriver.StopHandler.class)
        .build();
  }

  /**
   * Launch the job driver.
   *
   * @throws BindException configuration error.
   */
  public void submit() {
    this.reef.submit(this.driverConfiguration);
  }

  /**
   * Send command to the job driver. Record timestamp when the command was sent.
   * If this.command is set, use it; otherwise, ask user for the command.
   */
  private synchronized void submitTask() {
    if (this.isInteractive) {
      String cmd;
      try {
        do {
          System.out.print("\nPE> ");
          cmd = this.prompt.readLine();
        } while (cmd != null && cmd.trim().isEmpty());
      } catch (final IOException ex) {
        LOG.log(Level.FINE, "Error reading from stdin: {0}", ex);
        cmd = null;
      }
      if (cmd == null || cmd.equals("exit")) {
        this.runningJob.close();
        this.runningJob = null;
        this.isBusy = false;
        this.notify();
      } else {
        this.submitTask(cmd);
      }
    } else {
      // non-interactive batch mode:
      this.submitTask(this.command);
    }
  }

  /**
   * Send command to the job driver. Record timestamp when the command was sent.
   *
   * @param cmd shell command to execute in all Evaluators.
   */
  private synchronized void submitTask(final String cmd) {
    LOG.log(Level.INFO, "Submit task {0} \"{1}\" to {2}",
        new Object[]{this.numRuns + 1, cmd, this.runningJob});
    this.startTime = System.currentTimeMillis();
    this.runningJob.send(CODEC.encode(cmd));
  }

  /**
   * Receive notification from the job driver that the job is running.
   * This method is inherited from the JobObserver interface.
   */
  @Override
  public synchronized void onNext(final RunningJob job) {
    LOG.log(Level.INFO, "Running job: {0}", job.getId());
    this.runningJob = job;
    this.submitTask();
  }

  /**
   * Receive message from the job driver.
   * There is only one message, which comes at the end of the driver execution
   * and contains shell command output on each node.
   * This method is inherited from the JobObserver interface.
   */
  @Override
  public synchronized void onNext(final JobMessage message) {

    final String result = CODEC.decode(message.get());
    final long jobTime = System.currentTimeMillis() - this.startTime;
    this.totalTime += jobTime;
    ++this.numRuns;

    LOG.log(Level.INFO, "Task {0} completed in {1} msec.:\n{2}",
        new Object[]{this.numRuns, jobTime, result});

    System.out.println(result);

    if (this.runningJob != null) {
      if (this.isInteractive || this.numRuns < this.maxRuns) {
        this.submitTask();
      } else {
        LOG.log(Level.INFO,
            "All {0} tasks complete; Average task time: {1}. Closing the job driver.",
            new Object[]{this.maxRuns, this.totalTime / (double) this.maxRuns});
        this.runningJob.close();
        this.runningJob = null;
        this.isBusy = false;
        this.notify();
      }
    }
  }

  /**
   * Receive notification from the job driver that the job had failed.
   * This method is inherited from the JobObserver interface.
   */
  @Override
  public synchronized void onError(final FailedJob job) {
    LOG.log(Level.SEVERE, "Failed job: " + job.getId(), job.getJobException());
    this.runningJob = null;
    this.isBusy = false;
    this.notify();
  }

  /**
   * Receive notification from the job driver that the job had completed successfully.
   * This method is inherited from the JobObserver interface.
   */
  @Override
  public synchronized void onNext(final CompletedJob job) {
    LOG.log(Level.INFO, "Completed job: {0}", job.getId());
    this.runningJob = null;
    this.isBusy = false;
    this.notify();
  }

  /**
   * Receive notification that there was an exception thrown from the job driver.
   * This method is inherited from the RuntimeErrorHandler interface.
   */
  @Override
  public synchronized void onError(final RuntimeError error) {
    LOG.log(Level.SEVERE, "Error in job driver: " + error, error.getException());
    this.runningJob = null;
    this.isBusy = false;
    this.notify();
  }

  /**
   * Wait for the job driver to complete. This method is called from Launcher.main()
   */
  public void waitForCompletion() {
    while (this.isBusy) {
      LOG.info("Waiting for the Job Driver to complete.");
      try {
        synchronized (this) {
          this.wait();
        }
      } catch (final InterruptedException ex) {
        LOG.log(Level.WARNING, "Waiting for result interrupted.", ex);
      }
    }
    this.reef.close();
  }
}
