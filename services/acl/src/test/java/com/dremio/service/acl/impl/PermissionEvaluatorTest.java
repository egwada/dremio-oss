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
package com.dremio.service.acl.impl;

import static org.junit.Assert.assertNotNull;
import static org.mockito.Mockito.mock;

import com.dremio.service.acl.store.PrivilegeStore;
import org.junit.Test;

/** Unit tests for PermissionEvaluator. */
public class PermissionEvaluatorTest {

  @Test
  public void testConstructor() {
    // Arrange
    PrivilegeStore mockStore = mock(PrivilegeStore.class);

    // Act
    PermissionEvaluator evaluator = new PermissionEvaluator(mockStore);

    // Assert
    assertNotNull(evaluator);
  }
}
