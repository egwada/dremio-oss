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
import static org.junit.Assert.assertTrue;

import com.dremio.service.acl.proto.GranteeType;
import com.dremio.service.acl.proto.PrivilegeGrant;
import com.dremio.service.acl.proto.PrivilegeType;
import com.dremio.service.acl.proto.ResourceType;
import com.google.common.collect.ImmutableList;
import java.util.Arrays;
import org.junit.Test;

/**
 * Unit tests for PermissionEvaluator.
 */
public class PermissionEvaluatorTest {

  @Test
  public void testHasPrivilege_ExactMatch() {
    // Arrange
    PrivilegeGrant grant = createGrant("user1", Arrays.asList("myspace", "mytable"), PrivilegeType.SELECT);

    // Act
    boolean result = PermissionEvaluator.hasPrivilege(
        Arrays.asList("myspace", "mytable"),
        PrivilegeType.SELECT,
        grant
    );

    // Assert
    assertTrue(result);
  }

  @Test
  public void testHasPrivilege_NoMatch() {
    // Arrange
    PrivilegeGrant grant = createGrant("user1", Arrays.asList("myspace", "mytable"), PrivilegeType.SELECT);

    // Act - different privilege
    boolean result = PermissionEvaluator.hasPrivilege(
        Arrays.asList("myspace", "mytable"),
        PrivilegeType.INSERT,
        grant
    );

    // Assert
    assertFalse(result);
  }

  @Test
  public void testHasPrivilege_DifferentResource() {
    // Arrange
    PrivilegeGrant grant = createGrant("user1", Arrays.asList("myspace", "mytable"), PrivilegeType.SELECT);

    // Act - different resource
    boolean result = PermissionEvaluator.hasPrivilege(
        Arrays.asList("myspace", "othertable"),
        PrivilegeType.SELECT,
        grant
    );

    // Assert
    assertFalse(result);
  }

  @Test
  public void testHasPrivilege_AllPrivilege() {
    // Arrange - grant with ALL privilege
    PrivilegeGrant grant = createGrant("user1", Arrays.asList("myspace", "mytable"), PrivilegeType.ALL);

    // Act - check for SELECT (should be granted by ALL)
    boolean selectResult = PermissionEvaluator.hasPrivilege(
        Arrays.asList("myspace", "mytable"),
        PrivilegeType.SELECT,
        grant
    );

    boolean insertResult = PermissionEvaluator.hasPrivilege(
        Arrays.asList("myspace", "mytable"),
        PrivilegeType.INSERT,
        grant
    );

    // Assert - ALL privilege grants everything
    assertTrue(selectResult);
    assertTrue(insertResult);
  }

  @Test
  public void testHasPrivilege_ParentResource() {
    // Arrange - grant on parent folder
    PrivilegeGrant grant = createGrant("user1", Arrays.asList("myspace"), PrivilegeType.SELECT);

    // Act - check access to child table (hierarchical check)
    boolean result = PermissionEvaluator.hasPrivilege(
        Arrays.asList("myspace", "mytable"),
        PrivilegeType.SELECT,
        grant
    );

    // Assert - should inherit from parent
    assertTrue(result);
  }

  @Test
  public void testHasPrivilege_ChildResource() {
    // Arrange - grant on child table
    PrivilegeGrant grant = createGrant("user1", Arrays.asList("myspace", "mytable"), PrivilegeType.SELECT);

    // Act - check access to parent (should NOT inherit upward)
    boolean result = PermissionEvaluator.hasPrivilege(
        Arrays.asList("myspace"),
        PrivilegeType.SELECT,
        grant
    );

    // Assert - child grant should NOT apply to parent
    assertFalse(result);
  }

  @Test
  public void testHasPrivilege_EmptyPath() {
    // Arrange
    PrivilegeGrant grant = createGrant("user1", Arrays.asList("myspace"), PrivilegeType.SELECT);

    // Act
    boolean result = PermissionEvaluator.hasPrivilege(
        Arrays.asList(),
        PrivilegeType.SELECT,
        grant
    );

    // Assert
    assertFalse(result);
  }

  private PrivilegeGrant createGrant(String granteeName, java.util.List<String> resourcePath, PrivilegeType privilege) {
    PrivilegeGrant grant = new PrivilegeGrant();
    grant.setGrantId(java.util.UUID.randomUUID().toString());
    grant.setGranteeType(GranteeType.USER);
    grant.setGranteeName(granteeName);
    grant.setResourceType(ResourceType.DATASET);
    grant.setResourcePathList(resourcePath);
    grant.setPrivilegesList(ImmutableList.of(privilege));
    grant.setGrantedBy("admin");
    grant.setGrantedAt(System.currentTimeMillis());
    grant.setWithGrantOption(false);
    return grant;
  }
}
