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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dremio.common.config.SabotConfig;
import com.dremio.datastore.api.LegacyKVStoreProvider;
import com.dremio.exec.planner.sql.parser.SqlGrant.Privilege;
import com.dremio.service.acl.proto.GranteeType;
import com.dremio.service.acl.proto.PrivilegeGrant;
import com.dremio.service.acl.proto.PrivilegeType;
import com.dremio.service.acl.store.PrivilegeStore;
import com.dremio.service.namespace.NamespaceKey;
import com.google.common.collect.ImmutableList;
import java.util.Arrays;
import javax.inject.Provider;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for AuthorizationServiceImpl.
 */
public class AuthorizationServiceImplTest {

  private LegacyKVStoreProvider mockKVStoreProvider;
  private Provider<LegacyKVStoreProvider> mockProvider;
  private SabotConfig mockConfig;
  private AuthorizationServiceImpl authService;

  @SuppressWarnings("unchecked")
  @Before
  public void setup() throws Exception {
    mockKVStoreProvider = mock(LegacyKVStoreProvider.class);
    mockProvider = mock(Provider.class);
    mockConfig = mock(SabotConfig.class);

    when(mockProvider.get()).thenReturn(mockKVStoreProvider);
    when(mockConfig.getBoolean("dremio.acl.enabled")).thenReturn(true);
    when(mockConfig.getBoolean("dremio.acl.strict_mode")).thenReturn(false);

    authService = new AuthorizationServiceImpl(mockProvider, mockConfig);
  }

  @Test
  public void testStart() throws Exception {
    // Act
    authService.start();

    // Assert
    assertTrue(authService.isEnabled());
    verify(mockProvider).get();
  }

  @Test
  public void testIsEnabled() throws Exception {
    // Arrange
    when(mockConfig.getBoolean("dremio.acl.enabled")).thenReturn(true);

    // Act & Assert
    // Note: isEnabled() returns value from configuration, which needs start() to be called
    authService.start();
    assertTrue(authService.isEnabled());
  }

  @Test
  public void testIsStrictMode() throws Exception {
    // Arrange
    when(mockConfig.getBoolean("dremio.acl.strict_mode")).thenReturn(true);

    // Act
    authService.start();

    // Assert
    assertTrue(authService.isStrictMode());
  }
}
