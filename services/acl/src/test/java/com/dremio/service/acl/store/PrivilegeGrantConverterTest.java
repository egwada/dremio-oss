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

import com.dremio.datastore.SearchTypes;
import com.dremio.datastore.indexed.IndexKey;
import com.dremio.service.acl.proto.GranteeType;
import com.dremio.service.acl.proto.PrivilegeGrant;
import com.dremio.service.acl.proto.PrivilegeType;
import com.dremio.service.acl.proto.ResourceType;
import com.google.common.collect.ImmutableList;
import java.util.Arrays;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for PrivilegeGrantConverter.
 */
public class PrivilegeGrantConverterTest {

  private PrivilegeGrantConverter converter;

  @Before
  public void setup() {
    converter = new PrivilegeGrantConverter();
  }

  @Test
  public void testConvert() {
    // Arrange
    PrivilegeGrant grant = new PrivilegeGrant();
    grant.setGrantId("grant-123");
    grant.setGranteeType(GranteeType.USER);
    grant.setGranteeName("testuser");
    grant.setResourceType(ResourceType.DATASET);
    grant.setResourcePathList(Arrays.asList("myspace", "mytable"));
    grant.setPrivilegesList(ImmutableList.of(PrivilegeType.SELECT));
    grant.setGrantedBy("admin");
    grant.setGrantedAt(System.currentTimeMillis());
    grant.setWithGrantOption(false);

    // Act
    Iterable<java.util.Map.Entry<String, SearchTypes.SearchFieldSorting.FieldType>> fields =
        converter.convert(grant, null);

    // Assert
    assertNotNull(fields);
    // Verify fields are generated
    int fieldCount = 0;
    for (java.util.Map.Entry<String, SearchTypes.SearchFieldSorting.FieldType> field : fields) {
      fieldCount++;
      assertNotNull(field.getKey());
      assertNotNull(field.getValue());
    }
    assertEquals(3, fieldCount); // grantee_name, grantee_type, resource_path
  }

  @Test
  public void testGetVersion() {
    // Act
    Integer version = converter.getVersion();

    // Assert
    assertNotNull(version);
    assertEquals(Integer.valueOf(0), version);
  }

  @Test
  public void testGetIndexes() {
    // Act
    Iterable<IndexKey> indexes = converter.getIndexes();

    // Assert
    assertNotNull(indexes);
    int indexCount = 0;
    for (IndexKey index : indexes) {
      indexCount++;
      assertNotNull(index);
    }
    assertEquals(3, indexCount); // grantee_name, grantee_type, resource_path
  }

  @Test
  public void testConvert_WithMultiplePrivileges() {
    // Arrange
    PrivilegeGrant grant = new PrivilegeGrant();
    grant.setGrantId("grant-456");
    grant.setGranteeType(GranteeType.ROLE);
    grant.setGranteeName("admin_role");
    grant.setResourceType(ResourceType.FOLDER);
    grant.setResourcePathList(Arrays.asList("myspace"));
    grant.setPrivilegesList(ImmutableList.of(PrivilegeType.SELECT, PrivilegeType.INSERT, PrivilegeType.UPDATE));
    grant.setGrantedBy("superadmin");
    grant.setGrantedAt(System.currentTimeMillis());
    grant.setWithGrantOption(true);

    // Act
    Iterable<java.util.Map.Entry<String, SearchTypes.SearchFieldSorting.FieldType>> fields =
        converter.convert(grant, null);

    // Assert
    assertNotNull(fields);
    int fieldCount = 0;
    for (java.util.Map.Entry<String, SearchTypes.SearchFieldSorting.FieldType> field : fields) {
      fieldCount++;
    }
    assertEquals(3, fieldCount);
  }

  @Test
  public void testConvert_WithEmptyResourcePath() {
    // Arrange
    PrivilegeGrant grant = new PrivilegeGrant();
    grant.setGrantId("grant-789");
    grant.setGranteeType(GranteeType.USER);
    grant.setGranteeName("testuser2");
    grant.setResourceType(ResourceType.CATALOG);
    grant.setResourcePathList(Arrays.asList());
    grant.setPrivilegesList(ImmutableList.of(PrivilegeType.ALL));
    grant.setGrantedBy("admin");
    grant.setGrantedAt(System.currentTimeMillis());
    grant.setWithGrantOption(false);

    // Act
    Iterable<java.util.Map.Entry<String, SearchTypes.SearchFieldSorting.FieldType>> fields =
        converter.convert(grant, null);

    // Assert - should still work with empty resource path
    assertNotNull(fields);
  }
}
