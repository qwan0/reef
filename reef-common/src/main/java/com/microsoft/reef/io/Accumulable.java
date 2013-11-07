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
package com.microsoft.reef.io;

import com.microsoft.reef.annotations.Unstable;
import com.microsoft.reef.exception.evaluator.ServiceException;

/**
 * Similar to {@link Iterable}, this is an interface for objects that can return
 * {@link Accumulator}s.
 *
 * @param <T> The type acumulted
 */
@Unstable
public interface Accumulable<T> {
  /**
   * Returns a new Accumulator.
   *
   * @return the new {@link Accumulator}
   * @throws ServiceException
   */
  public Accumulator<T> accumulator() throws ServiceException;
}
