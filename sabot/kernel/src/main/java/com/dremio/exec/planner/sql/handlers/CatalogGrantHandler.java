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
import com.dremio.exec.planner.sql.parser.SqlGrantOnCatalog;
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
 * Handler for SQL GRANT command on catalog entities (TABLE, VIEW, FOLDER).
 *
 * <p>Syntax: GRANT privilege [,...] ON entityType entity TO granteeType grantee
 *
 * <p>This handler is dynamically loaded by SqlGrantOnCatalog via reflection.
 */
public class CatalogGrantHandler extends SimpleDirectHandler {

  private final QueryContext context;

  public CatalogGrantHandler(QueryContext context) {
    this.context = context;
  }

  @Override
  public List<SimpleCommandResult> toResult(String sql, SqlNode sqlNode) throws Exception {
    if (!(sqlNode instanceof SqlGrantOnCatalog)) {
      throw UserException.validationError()
          .message("Invalid SQL node type for CatalogGrantHandler")
          .buildSilently();
    }

    SqlGrantOnCatalog grant = (SqlGrantOnCatalog) sqlNode;

    // Get ACL service from context
    AuthorizationService aclService = getAclService();

    if (!aclService.isEnabled()) {
      throw UserException.unsupportedError().message("ACL service is not enabled").buildSilently();
    }

    // Extract grant parameters
    String currentUser = context.getQueryUserName();
    GranteeType granteeType = convertGranteeType(grant.getGranteeType());
    String granteeName = grant.getGrantee().getSimple();
    NamespaceKey resourcePath = getResourcePath(grant);
    List<Privilege> privileges = getPrivileges(grant);
    boolean withGrantOption = false; // Phase 1 MVP: always false

    // Execute grant for each privilege
    for (Privilege privilege : privileges) {
      try {
        aclService.grantPrivilege(
            granteeType, granteeName, resourcePath, privilege, currentUser, withGrantOption);
      } catch (AclException e) {
        throw UserException.validationError(e)
            .message("Failed to grant privilege: %s", e.getMessage())
            .buildSilently();
      }
    }

    String message =
        String.format(
            "Granted %s on %s %s to %s %s",
            privileges,
            grant.getEntityType().toValue(),
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
                  + "The AuthorizationService must be registered in SabotContext to enable GRANT commands. "
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

  private NamespaceKey getResourcePath(SqlGrantOnCatalog grant) {
    // Extract the entity path from the SqlIdentifier
    // e.g., Samples."samples.dremio.com"."NYC-taxi-trips"
    List<String> pathComponents = grant.getEntity().names;
    return new NamespaceKey(pathComponents);
  }

  private List<Privilege> getPrivileges(SqlGrantOnCatalog grant) {
    List<Privilege> privileges = new ArrayList<>();

    for (SqlNode node : grant.getPrivilegeList().getList()) {
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
          .message("No privileges specified in GRANT statement")
          .buildSilently();
    }

    return privileges;
  }
}
