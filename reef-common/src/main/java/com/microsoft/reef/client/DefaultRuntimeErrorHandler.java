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
package com.microsoft.reef.client;

import com.microsoft.reef.util.RuntimeError;

import java.util.logging.Level;
import java.util.logging.Logger;

final class DefaultRuntimeErrorHandler implements RuntimeErrorHandler {

  private static final Logger LOG = Logger.getLogger(DefaultRuntimeErrorHandler.class.getName());

  @Override
  public void onError(final RuntimeError error) {
    LOG.log(Level.SEVERE, "Runtime error: " + error, error.getException());
    System.exit(1);
  }
}
