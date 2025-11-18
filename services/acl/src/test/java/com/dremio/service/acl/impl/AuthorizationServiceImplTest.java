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

import com.dremio.service.acl.proto.GranteeType;
import com.dremio.service.acl.proto.PrivilegeGrant;
import com.dremio.service.acl.proto.PrivilegeType;
import com.dremio.service.acl.store.PrivilegeStore;
import com.dremio.service.namespace.NamespaceKey;
import com.google.common.collect.ImmutableList;
import java.util.Arrays;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for AuthorizationServiceImpl.
 */
public class AuthorizationServiceImplTest {

  private PrivilegeStore mockPrivilegeStore;
  private AuthorizationServiceImpl authService;

  @Before
  public void setup() {
    mockPrivilegeStore = mock(PrivilegeStore.class);
    authService = new AuthorizationServiceImpl(mockPrivilegeStore);
  }

  @Test
  public void testGrantPrivilege_NewGrant() {
    // Arrange
    String userName = "testuser";
    NamespaceKey resourcePath = new NamespaceKey(Arrays.asList("myspace", "mytable"));
    String privilege = "SELECT";
    String grantedBy = "admin";

    when(mockPrivilegeStore.findGrant(any(), anyString(), any())).thenReturn(null);
    when(mockPrivilegeStore.create(any())).thenReturn("grant-123");

    // Act
    authService.grantPrivilege(userName, resourcePath, privilege, grantedBy, false);

    // Assert
    ArgumentCaptor<PrivilegeGrant> grantCaptor = ArgumentCaptor.forClass(PrivilegeGrant.class);
    verify(mockPrivilegeStore).create(grantCaptor.capture());

    PrivilegeGrant capturedGrant = grantCaptor.getValue();
    assertNotNull(capturedGrant);
    assertTrue(capturedGrant.getGranteeName().equals(userName));
    assertTrue(capturedGrant.getPrivilegesList().contains(PrivilegeType.SELECT));
  }

  @Test
  public void testGrantPrivilege_UpdateExisting() {
    // Arrange
    String userName = "testuser";
    NamespaceKey resourcePath = new NamespaceKey(Arrays.asList("myspace", "mytable"));

    PrivilegeGrant existingGrant = new PrivilegeGrant();
    existingGrant.setGrantId("grant-123");
    existingGrant.setGranteeType(GranteeType.USER);
    existingGrant.setGranteeName(userName);
    existingGrant.setResourcePathList(resourcePath.getPathComponents());
    existingGrant.setPrivilegesList(ImmutableList.of(PrivilegeType.SELECT));
    existingGrant.setGrantedBy("admin");
    existingGrant.setGrantedAt(System.currentTimeMillis());

    when(mockPrivilegeStore.findGrant(any(), eq(userName), any())).thenReturn(existingGrant);
    when(mockPrivilegeStore.create(any())).thenReturn("grant-123");

    // Act - grant INSERT privilege
    authService.grantPrivilege(userName, resourcePath, "INSERT", "admin", false);

    // Assert - should delete old and create new grant with both privileges
    verify(mockPrivilegeStore).delete("grant-123");
    verify(mockPrivilegeStore).create(any());
  }

  @Test
  public void testRevokePrivilege_RemoveOne() {
    // Arrange
    String userName = "testuser";
    NamespaceKey resourcePath = new NamespaceKey(Arrays.asList("myspace", "mytable"));

    PrivilegeGrant existingGrant = new PrivilegeGrant();
    existingGrant.setGrantId("grant-123");
    existingGrant.setGranteeType(GranteeType.USER);
    existingGrant.setGranteeName(userName);
    existingGrant.setResourcePathList(resourcePath.getPathComponents());
    existingGrant.setPrivilegesList(ImmutableList.of(PrivilegeType.SELECT, PrivilegeType.INSERT));
    existingGrant.setGrantedBy("admin");
    existingGrant.setGrantedAt(System.currentTimeMillis());
    existingGrant.setWithGrantOption(false);

    when(mockPrivilegeStore.findGrant(any(), eq(userName), any())).thenReturn(existingGrant);
    when(mockPrivilegeStore.create(any())).thenReturn("grant-123");

    // Act - revoke SELECT privilege
    authService.revokePrivilege(userName, resourcePath, "SELECT");

    // Assert - should delete old and create new grant with only INSERT
    verify(mockPrivilegeStore).delete("grant-123");
    verify(mockPrivilegeStore).create(any());
  }

  @Test
  public void testRevokePrivilege_RemoveLast() {
    // Arrange
    String userName = "testuser";
    NamespaceKey resourcePath = new NamespaceKey(Arrays.asList("myspace", "mytable"));

    PrivilegeGrant existingGrant = new PrivilegeGrant();
    existingGrant.setGrantId("grant-123");
    existingGrant.setGranteeType(GranteeType.USER);
    existingGrant.setGranteeName(userName);
    existingGrant.setResourcePathList(resourcePath.getPathComponents());
    existingGrant.setPrivilegesList(ImmutableList.of(PrivilegeType.SELECT));
    existingGrant.setGrantedBy("admin");
    existingGrant.setGrantedAt(System.currentTimeMillis());

    when(mockPrivilegeStore.findGrant(any(), eq(userName), any())).thenReturn(existingGrant);

    // Act - revoke last privilege
    authService.revokePrivilege(userName, resourcePath, "SELECT");

    // Assert - should delete the grant entirely
    verify(mockPrivilegeStore).delete("grant-123");
  }

  @Test
  public void testHasPrivilege_DirectGrant() {
    // Arrange
    String userName = "testuser";
    NamespaceKey resourcePath = new NamespaceKey(Arrays.asList("myspace", "mytable"));

    PrivilegeGrant grant = new PrivilegeGrant();
    grant.setGranteeType(GranteeType.USER);
    grant.setGranteeName(userName);
    grant.setPrivilegesList(ImmutableList.of(PrivilegeType.SELECT));

    when(mockPrivilegeStore.findByGrantee(any(), eq(userName)))
        .thenReturn(ImmutableList.of(grant));

    // Act
    boolean hasPrivilege = authService.hasPrivilege(userName, resourcePath, "SELECT");

    // Assert
    assertTrue(hasPrivilege);
  }

  @Test
  public void testHasPrivilege_NoGrant() {
    // Arrange
    String userName = "testuser";
    NamespaceKey resourcePath = new NamespaceKey(Arrays.asList("myspace", "mytable"));

    when(mockPrivilegeStore.findByGrantee(any(), eq(userName)))
        .thenReturn(ImmutableList.of());

    // Act
    boolean hasPrivilege = authService.hasPrivilege(userName, resourcePath, "SELECT");

    // Assert
    assertFalse(hasPrivilege);
  }

  @Test
  public void testHasPrivilege_AllPrivilege() {
    // Arrange
    String userName = "testuser";
    NamespaceKey resourcePath = new NamespaceKey(Arrays.asList("myspace", "mytable"));

    PrivilegeGrant grant = new PrivilegeGrant();
    grant.setGranteeType(GranteeType.USER);
    grant.setGranteeName(userName);
    grant.setPrivilegesList(ImmutableList.of(PrivilegeType.ALL));

    when(mockPrivilegeStore.findByGrantee(any(), eq(userName)))
        .thenReturn(ImmutableList.of(grant));

    // Act
    boolean hasSelect = authService.hasPrivilege(userName, resourcePath, "SELECT");
    boolean hasInsert = authService.hasPrivilege(userName, resourcePath, "INSERT");

    // Assert - ALL privilege should grant everything
    assertTrue(hasSelect);
    assertTrue(hasInsert);
  }
}
