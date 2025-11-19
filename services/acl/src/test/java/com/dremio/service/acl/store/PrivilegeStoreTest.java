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
package com.dremio.service.acl.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dremio.datastore.api.LegacyIndexedStore;
import com.dremio.datastore.api.LegacyKVStoreProvider;
import com.dremio.service.acl.proto.GranteeType;
import com.dremio.service.acl.proto.PrivilegeGrant;
import com.dremio.service.acl.proto.PrivilegeType;
import com.dremio.service.acl.proto.ResourceType;
import com.dremio.service.namespace.NamespaceKey;
import com.google.common.collect.ImmutableList;
import java.util.Arrays;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

/** Unit tests for PrivilegeStore. */
public class PrivilegeStoreTest {

  private LegacyKVStoreProvider mockKvStoreProvider;
  private LegacyIndexedStore<String, PrivilegeGrant> mockIndexedStore;
  private PrivilegeStore privilegeStore;

  @SuppressWarnings("unchecked")
  @Before
  public void setup() {
    mockKvStoreProvider = mock(LegacyKVStoreProvider.class);
    mockIndexedStore = mock(LegacyIndexedStore.class);

    when(mockKvStoreProvider.getStore(PrivilegeStoreCreator.class))
        .thenReturn(mockIndexedStore);

    privilegeStore = new PrivilegeStore(mockKvStoreProvider);
  }

  @Test
  public void testCreate() {
    // Arrange
    PrivilegeGrant grant = new PrivilegeGrant();
    grant.setGranteeType(GranteeType.USER);
    grant.setGranteeName("testuser");
    grant.setResourceType(ResourceType.DATASET);
    grant.setResourcePathList(Arrays.asList("myspace", "mytable"));
    grant.setPrivilegesList(ImmutableList.of(PrivilegeType.SELECT));
    grant.setGrantedBy("admin");
    grant.setWithGrantOption(false);

    // Act
    String grantId = privilegeStore.create(grant);

    // Assert
    assertNotNull(grantId);
    ArgumentCaptor<PrivilegeGrant> captor = ArgumentCaptor.forClass(PrivilegeGrant.class);
    verify(mockIndexedStore).put(anyString(), captor.capture());

    PrivilegeGrant savedGrant = captor.getValue();
    assertNotNull(savedGrant.getGrantId());
    assertTrue(savedGrant.getGrantedAt() > 0);
    assertEquals("testuser", savedGrant.getGranteeName());
  }

  @Test
  public void testGet() {
    // Arrange
    String grantId = "grant-123";
    PrivilegeGrant expectedGrant = new PrivilegeGrant();
    expectedGrant.setGrantId(grantId);

    when(mockIndexedStore.get(grantId)).thenReturn(expectedGrant);

    // Act
    PrivilegeGrant result = privilegeStore.get(grantId);

    // Assert
    assertNotNull(result);
    assertEquals(grantId, result.getGrantId());
  }

  @Test
  public void testGet_NotFound() {
    // Arrange
    when(mockIndexedStore.get("nonexistent")).thenReturn(null);

    // Act
    PrivilegeGrant result = privilegeStore.get("nonexistent");

    // Assert
    assertNull(result);
  }

  @Test
  public void testDelete() {
    // Arrange
    String grantId = "grant-123";

    // Act
    privilegeStore.delete(grantId);

    // Assert
    verify(mockIndexedStore).delete(grantId);
  }

  @Test
  public void testFindByGrantee() {
    // Arrange
    GranteeType granteeType = GranteeType.USER;
    String granteeName = "testuser";

    // Act
    privilegeStore.findByGrantee(granteeType, granteeName);

    // Assert
    verify(mockIndexedStore).find(any(LegacyIndexedStore.LegacyFindByCondition.class));
  }

  @Test
  public void testFindByResource() {
    // Arrange
    NamespaceKey resourcePath = new NamespaceKey(Arrays.asList("myspace", "mytable"));

    // Act
    privilegeStore.findByResource(resourcePath);

    // Assert
    verify(mockIndexedStore).find(any(LegacyIndexedStore.LegacyFindByCondition.class));
  }

  @Test
  public void testFindGrant() {
    // Arrange
    GranteeType granteeType = GranteeType.USER;
    String granteeName = "testuser";
    NamespaceKey resourcePath = new NamespaceKey(Arrays.asList("myspace", "mytable"));

    // Act
    privilegeStore.findGrant(granteeType, granteeName, resourcePath);

    // Assert
    verify(mockIndexedStore).find(any(LegacyIndexedStore.LegacyFindByCondition.class));
  }

  @Test
  public void testGetAll() {
    // Act
    privilegeStore.getAll();

    // Assert
    verify(mockIndexedStore).find();
  }
}
