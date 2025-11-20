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
package com.dremio.exec.planner.sql.handlers;

import com.dremio.common.exceptions.UserException;
import com.dremio.exec.ops.QueryContext;
import com.dremio.exec.planner.sql.handlers.direct.SimpleCommandResult;
import com.dremio.exec.planner.sql.handlers.direct.SimpleDirectHandler;
import com.dremio.exec.planner.sql.parser.SqlRevokeOnCatalog;
import com.dremio.service.acl.AuthorizationService;
import com.dremio.service.acl.Privilege;
import com.dremio.service.acl.exception.AclException;
import com.dremio.service.acl.proto.GranteeType;
import com.dremio.service.namespace.NamespaceKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlNode;

/**
 * Handler for SQL REVOKE command on catalog entities (TABLE, VIEW, FOLDER).
 *
 * <p>Syntax: REVOKE privilege [,...] ON entityType entity FROM granteeType grantee
 *
 * <p>This handler is dynamically loaded by SqlRevokeOnCatalog via reflection.
 */
public class CatalogRevokeHandler extends SimpleDirectHandler {

  private final QueryContext context;

  public CatalogRevokeHandler(QueryContext context) {
    this.context = context;
  }

  @Override
  public List<SimpleCommandResult> toResult(String sql, SqlNode sqlNode) throws Exception {
    if (!(sqlNode instanceof SqlRevokeOnCatalog)) {
      throw UserException.validationError()
          .message("Invalid SQL node type for CatalogRevokeHandler")
          .buildSilently();
    }

    SqlRevokeOnCatalog revoke = (SqlRevokeOnCatalog) sqlNode;

    // Get ACL service from context
    AuthorizationService aclService = getAclService();

    if (!aclService.isEnabled()) {
      throw UserException.unsupportedError().message("ACL service is not enabled").buildSilently();
    }

    // Extract revoke parameters
    String currentUser = context.getQueryUserName();
    GranteeType granteeType = convertGranteeType(revoke.getRevokeeType());
    String granteeName = revoke.getRevokee().getSimple();
    NamespaceKey resourcePath = getResourcePath(revoke);
    List<Privilege> privileges = getPrivileges(revoke);

    // Execute revoke for each privilege
    for (Privilege privilege : privileges) {
      try {
        aclService.revokePrivilege(granteeType, granteeName, resourcePath, privilege, currentUser);
      } catch (AclException e) {
        throw UserException.validationError(e)
            .message("Failed to revoke privilege: %s", e.getMessage())
            .buildSilently();
      }
    }

    String message =
        String.format(
            "Revoked %s on %s %s from %s %s",
            privileges,
            revoke.getEntityType().toValue(),
            resourcePath,
            granteeType.name(),
            granteeName);

    return Collections.singletonList(SimpleCommandResult.successful(message));
  }

  private AuthorizationService getAclService() {
    AuthorizationService service = context.getAuthorizationService();
    if (service == null) {
      throw UserException.unsupportedError()
          .message(
              "ACL service is not available. "
                  + "The AuthorizationService must be registered in SabotContext to enable REVOKE commands. "
                  + "See services/acl/INTEGRATION_GUIDE.md for setup instructions.")
          .buildSilently();
    }
    return service;
  }

  private GranteeType convertGranteeType(SqlLiteral granteeTypeLiteral) {
    // Convert from parser GranteeType to ACL GranteeType
    Object value = granteeTypeLiteral.getValue();

    // Handle both string and enum values
    String granteeTypeStr = value.toString().toUpperCase();

    switch (granteeTypeStr) {
      case "USER":
        return GranteeType.USER;
      case "ROLE":
        return GranteeType.ROLE;
      default:
        throw UserException.validationError()
            .message("Unsupported grantee type: %s", granteeTypeStr)
            .buildSilently();
    }
  }

  private NamespaceKey getResourcePath(SqlRevokeOnCatalog revoke) {
    // Extract the entity path from the SqlIdentifier
    // e.g., Samples."samples.dremio.com"."NYC-taxi-trips"
    List<String> pathComponents = revoke.getEntity().names;
    return new NamespaceKey(pathComponents);
  }

  private List<Privilege> getPrivileges(SqlRevokeOnCatalog revoke) {
    List<Privilege> privileges = new ArrayList<>();

    for (SqlNode node : revoke.getPrivilegeList().getList()) {
      if (node instanceof SqlLiteral) {
        SqlLiteral privilegeLiteral = (SqlLiteral) node;
        Object value = privilegeLiteral.getValue();

        // Convert privilege string/enum to ACL Privilege
        String privilegeStr = value.toString().toUpperCase();
        try {
          Privilege privilege = Privilege.valueOf(privilegeStr);
          privileges.add(privilege);
        } catch (IllegalArgumentException e) {
          throw UserException.validationError()
              .message("Unknown privilege: %s", privilegeStr)
              .buildSilently();
        }
      }
    }

    if (privileges.isEmpty()) {
      throw UserException.validationError()
          .message("No privileges specified in REVOKE statement")
          .buildSilently();
    }

    return privileges;
  }
}
