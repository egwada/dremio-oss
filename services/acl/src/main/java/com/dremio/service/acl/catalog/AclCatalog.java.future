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
package com.dremio.service.acl.catalog;

import com.dremio.catalog.model.CatalogEntityKey;
import com.dremio.context.RequestContext;
import com.dremio.context.UserContext;
import com.dremio.exec.catalog.Catalog;
import com.dremio.exec.catalog.DelegatingCatalog;
import com.dremio.exec.catalog.DremioTable;
import com.dremio.exec.planner.sql.parser.SqlGrant.Privilege;
import com.dremio.service.acl.AuthorizationService;
import com.dremio.service.namespace.NamespaceKey;
import com.google.common.base.Preconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decorator du Catalog qui injecte les vérifications ACL.
 *
 * <p>Ce wrapper intercepte les appels au Catalog pour valider les permissions
 * avant d'exécuter les opérations.
 *
 * <p>Phase 1 MVP : Override uniquement validatePrivilege et validateOwnership
 */
public class AclCatalog extends DelegatingCatalog {
  private static final Logger logger = LoggerFactory.getLogger(AclCatalog.class);

  private final AuthorizationService aclService;

  public AclCatalog(Catalog delegate, AuthorizationService aclService) {
    super(delegate);
    this.aclService = Preconditions.checkNotNull(aclService, "aclService is required");
  }

  @Override
  public DremioTable getTable(NamespaceKey key) {
    // Phase 1 MVP: Check SELECT permission before returning table
    if (aclService.isEnabled()) {
      String username = getCurrentUsername();
      aclService.enforcePermission(username, key, Privilege.SELECT);
    }

    return super.getTable(key);
  }

  @Override
  public DremioTable getTable(CatalogEntityKey key) {
    // Phase 1 MVP: Check SELECT permission before returning table
    if (aclService.isEnabled()) {
      String username = getCurrentUsername();
      NamespaceKey namespaceKey = key.toNamespaceKey();
      aclService.enforcePermission(username, namespaceKey, Privilege.SELECT);
    }

    return super.getTable(key);
  }

  @Override
  public DremioTable getTableForQuery(NamespaceKey key) {
    // Phase 1 MVP: Check SELECT permission
    if (aclService.isEnabled()) {
      String username = getCurrentUsername();
      aclService.enforcePermission(username, key, Privilege.SELECT);
    }

    return super.getTableForQuery(key);
  }

  @Override
  public void validatePrivilege(NamespaceKey key, Privilege privilege) {
    // This is the main hook! Previously a no-op in CatalogImpl, now we implement it
    if (aclService.isEnabled()) {
      String username = getCurrentUsername();
      logger.debug("validatePrivilege: user={}, resource={}, privilege={}", username, key, privilege);
      aclService.enforcePermission(username, key, privilege);
    }

    // Call delegate (which is still a no-op in OSS CatalogImpl, but we honor the chain)
    super.validatePrivilege(key, privilege);
  }

  @Override
  public void validateOwnership(CatalogEntityKey key) {
    // Ownership requires MANAGE_GRANTS privilege
    if (aclService.isEnabled()) {
      String username = getCurrentUsername();
      NamespaceKey namespaceKey = key.toNamespaceKey();
      logger.debug("validateOwnership: user={}, resource={}", username, namespaceKey);
      aclService.enforcePermission(username, namespaceKey, Privilege.MANAGE_GRANTS);
    }

    // Call delegate
    super.validateOwnership(key);
  }

  /**
   * Implements the visit method required by Catalog interface.
   *
   * @param catalogRewrite Function to transform the catalog
   * @return The transformed catalog
   */
  @Override
  public Catalog visit(java.util.function.Function<Catalog, Catalog> catalogRewrite) {
    return catalogRewrite.apply(this);
  }

  /**
   * Get current username from RequestContext.
   *
   * @return username (userId), or "UNKNOWN" if not available
   */
  private String getCurrentUsername() {
    try {
      // Get userId from Dremio RequestContext
      UserContext userContext = RequestContext.current().get(UserContext.CTX_KEY);
      if (userContext != null && userContext.getUserId() != null) {
        return userContext.getUserId();
      }
    } catch (Exception e) {
      logger.warn("Failed to get current username from context", e);
    }

    // Fallback - should not happen in normal operation
    logger.warn("Could not determine current username, using UNKNOWN");
    return "UNKNOWN";
  }
}
