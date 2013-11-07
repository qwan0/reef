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
package com.microsoft.reef.runtime.common.evaluator.activity;

import com.microsoft.reef.activity.events.SuspendEvent;
import com.microsoft.reef.annotations.audience.Private;
import com.microsoft.wake.EventHandler;

import javax.inject.Inject;

/**
 * Default handler for SuspendEvent
 */
@Private
public final class DefaultSuspendHandler implements EventHandler<SuspendEvent> {

  @Inject
  public DefaultSuspendHandler() {
  }

  @Override
  public void onNext(final SuspendEvent suspendEvent) {
    throw new RuntimeException("No handler for SuspendEvent registered. event: " + suspendEvent);
  }
}
