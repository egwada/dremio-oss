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
import com.dremio.exec.planner.sql.parser.SqlGrant;
import com.dremio.exec.planner.sql.parser.SqlGrant.Privilege;
import com.dremio.exec.planner.sql.parser.SqlRevoke;
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
 * Handler for SQL REVOKE command.
 *
 * <p>Syntax: REVOKE privilege ON resource FROM USER/ROLE grantee
 *
 * <p>This handler is dynamically loaded by SqlRevoke via reflection.
 */
public class RevokeHandler extends SimpleDirectHandler {

  private final QueryContext context;

  public RevokeHandler(QueryContext context) {
    this.context = context;
  }

  @Override
  public List<SimpleCommandResult> toResult(String sql, SqlNode sqlNode) throws Exception {
    if (!(sqlNode instanceof SqlRevoke)) {
      throw UserException.validationError()
          .message("Invalid SQL node type for RevokeHandler")
          .buildSilently();
    }

    SqlRevoke revoke = (SqlRevoke) sqlNode;

    // Get ACL service from context
    AuthorizationService aclService = getAclService();

    if (!aclService.isEnabled()) {
      throw UserException.unsupportedError().message("ACL service is not enabled").buildSilently();
    }

    // Extract revoke parameters
    String currentUser = context.getQueryUserName();
    GranteeType granteeType = convertGranteeType(revoke.getGranteeType());
    String granteeName = getGranteeName(revoke.getGrantee());
    NamespaceKey resourcePath = getResourcePath(revoke);
    List<Privilege> privileges = getPrivileges(revoke);

    // Execute revoke for each privilege
    for (Privilege privilege : privileges) {
      try {
        aclService.revokePrivilege(
            granteeType, granteeName, resourcePath, toAclPrivilege(privilege), currentUser);
      } catch (AclException e) {
        throw UserException.validationError(e)
            .message("Failed to revoke privilege: %s", e.getMessage())
            .buildSilently();
      }
    }

    String message =
        String.format(
            "Revoked %s on %s from %s %s",
            privileges, resourcePath, granteeType.name(), granteeName);

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
    SqlGrant.GranteeType sqlGranteeType = (SqlGrant.GranteeType) granteeTypeLiteral.getValue();

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
    return grantee.getSimple();
  }

  private NamespaceKey getResourcePath(SqlRevoke revoke) {
    // Simplified version - needs proper parsing
    SqlLiteral grantTypeLiteral = revoke.getGrantType();
    String grantTypeStr = grantTypeLiteral.toValue();
    List<String> pathComponents = Arrays.asList(grantTypeStr.split("\\."));
    return new NamespaceKey(pathComponents);
  }

  private List<Privilege> getPrivileges(SqlRevoke revoke) {
    List<Privilege> privileges = new java.util.ArrayList<>();

    for (SqlNode node : revoke.getPrivilegeList().getList()) {
      if (node instanceof SqlLiteral) {
        SqlLiteral privilegeLiteral = (SqlLiteral) node;
        Privilege privilege = (Privilege) privilegeLiteral.getValue();
        privileges.add(privilege);
      }
    }

    if (privileges.isEmpty()) {
      throw UserException.validationError()
          .message("No privileges specified in REVOKE statement")
          .buildSilently();
    }

    return privileges;
  }

  /**
   * Converts SQL parser Privilege enum to ACL service Privilege enum. This conversion is necessary
   * to avoid circular dependency between sabot/kernel and services/acl.
   */
  private com.dremio.service.acl.Privilege toAclPrivilege(Privilege sqlPrivilege) {
    return com.dremio.service.acl.Privilege.valueOf(sqlPrivilege.name());
  }
}
