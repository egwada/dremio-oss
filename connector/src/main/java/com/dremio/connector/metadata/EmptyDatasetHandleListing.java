/*
 * Copyright (C) 2017-2018 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dremio.connector.metadata;

import java.util.Collections;
import java.util.Iterator;

/**
 * Empty listing of {@link DatasetHandle dataset handles}.
 */
public class EmptyDatasetHandleListing implements DatasetHandleListing {
  @Override
  public Iterator<DatasetHandle> iterator() {
    return Collections.emptyIterator();
  }

  @Override
  public void close() {
  }
}
