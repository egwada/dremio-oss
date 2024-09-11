/*
 * Copyright (C) 2017-2019 Dremio Corporation
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
package com.dremio.services.pubsub;

import com.google.protobuf.Message;

/**
 * After registering topics at infrastructure layer, they must be registered in code by implementing
 * this interface.
 */
public interface Topic<M extends Message> {
  /** Topic name as defined by the implementation. */
  String getName();

  /**
   * Message class for validation of the published messages. It must match the parser defined in the
   * subscriptions.
   */
  Class<M> getMessageClass();
}
