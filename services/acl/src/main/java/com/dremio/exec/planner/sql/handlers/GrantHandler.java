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
import com.dremio.exec.catalog.Catalog;
import com.dremio.exec.ops.QueryContext;
import com.dremio.exec.planner.sql.handlers.direct.SimpleCommandResult;
import com.dremio.exec.planner.sql.handlers.direct.SimpleDirectHandler;
import com.dremio.exec.planner.sql.parser.SqlGrant;
import com.dremio.exec.planner.sql.parser.SqlGrant.Privilege;
import com.dremio.service.acl.AuthorizationService;
import com.dremio.service.acl.exception.AclException;
import com.dremio.service.acl.proto.GranteeType;
import com.dremio.service.namespace.NamespaceKey;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlNode;

/**
 * Handler for SQL GRANT command.
 *
 * <p>Syntax: GRANT privilege ON resource TO USER/ROLE grantee
 *
 * <p>This handler is dynamically loaded by SqlGrant via reflection.
 */
public class GrantHandler extends SimpleDirectHandler {

  private final QueryContext context;

  public GrantHandler(QueryContext context) {
    this.context = context;
  }

  @Override
  public List<SimpleCommandResult> toResult(String sql, SqlNode sqlNode) throws Exception {
    if (!(sqlNode instanceof SqlGrant)) {
      throw UserException.validationError()
          .message("Invalid SQL node type for GrantHandler")
          .buildSilently();
    }

    SqlGrant grant = (SqlGrant) sqlNode;

    // Get ACL service from context
    AuthorizationService aclService = getAclService();

    if (!aclService.isEnabled()) {
      throw UserException.unsupportedError()
          .message("ACL service is not enabled")
          .buildSilently();
    }

    // Extract grant parameters
    String currentUser = context.getQueryUserName();
    GranteeType granteeType = convertGranteeType(grant.getGranteeType());
    String granteeName = getGranteeName(grant.getGrantee());
    NamespaceKey resourcePath = getResourcePath(grant);
    List<Privilege> privileges = getPrivileges(grant);
    boolean withGrantOption = false; // Phase 1 MVP: always false

    // Execute grant for each privilege
    for (Privilege privilege : privileges) {
      try {
        aclService.grantPrivilege(
            granteeType,
            granteeName,
            resourcePath,
            privilege,
            currentUser,
            withGrantOption
        );
      } catch (AclException e) {
        throw UserException.validationError(e)
            .message("Failed to grant privilege: %s", e.getMessage())
            .buildSilently();
      }
    }

    String message = String.format(
        "Granted %s on %s to %s %s",
        privileges,
        resourcePath,
        granteeType.name(),
        granteeName
    );

    return Collections.singletonList(SimpleCommandResult.successful(message));
  }

  private AuthorizationService getAclService() {
    AuthorizationService service = context.getAuthorizationService();
    if (service == null) {
      throw UserException.unsupportedError()
          .message("ACL service is not available. " +
                   "The AuthorizationService must be registered in SabotContext to enable GRANT commands. " +
                   "See services/acl/INTEGRATION_GUIDE.md for setup instructions.")
          .buildSilently();
    }
    return service;
  }

  private GranteeType convertGranteeType(SqlLiteral granteeTypeLiteral) {
    SqlGrant.GranteeType sqlGranteeType =
        (SqlGrant.GranteeType) granteeTypeLiteral.getValue();

    switch (sqlGranteeType) {
      case USER:
        return GranteeType.USER;
      case ROLE:
        return GranteeType.ROLE;
      default:
        throw UserException.validationError()
            .message("Unsupported grantee type: %s", sqlGranteeType)
            .buildSilently();
    }
  }

  private String getGranteeName(SqlIdentifier grantee) {
    // Grantee can be a simple name or qualified
    return grantee.getSimple();
  }

  private NamespaceKey getResourcePath(SqlGrant grant) {
    // For Phase 1 MVP, we need to extract the resource path from the grant
    // The resource is embedded in the SQL context
    // For now, parse from the grantType which contains the resource path

    // This is a simplified version - full implementation needs proper parsing
    SqlLiteral grantTypeLiteral = grant.getGrantType();
    String grantTypeStr = grantTypeLiteral.toValue();

    // Parse the resource path (e.g., "source.folder.table" -> ["source", "folder", "table"])
    List<String> pathComponents = Arrays.asList(grantTypeStr.split("\\."));

    return new NamespaceKey(pathComponents);
  }

  private List<Privilege> getPrivileges(SqlGrant grant) {
    List<Privilege> privileges = new java.util.ArrayList<>();

    for (SqlNode node : grant.getPrivilegeList().getList()) {
      if (node instanceof SqlLiteral) {
        SqlLiteral privilegeLiteral = (SqlLiteral) node;
        Privilege privilege = (Privilege) privilegeLiteral.getValue();
        privileges.add(privilege);
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
