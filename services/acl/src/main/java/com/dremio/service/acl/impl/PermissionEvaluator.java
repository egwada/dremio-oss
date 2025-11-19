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

import com.dremio.service.acl.Privilege;
import com.dremio.service.acl.proto.GranteeType;
import com.dremio.service.acl.proto.PrivilegeGrant;
import com.dremio.service.acl.proto.PrivilegeType;
import com.dremio.service.acl.store.PrivilegeStore;
import com.dremio.service.namespace.NamespaceKey;
import com.google.common.base.Preconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Évalue les permissions en combinant :
 * - Privilèges directs du user
 * - Privilèges des rôles du user (Phase 2)
 * - Héritage hiérarchique (Phase 2)
 *
 * <p>Phase 1 MVP : Uniquement privilèges directs USER
 */
public class PermissionEvaluator {
  private static final Logger logger = LoggerFactory.getLogger(PermissionEvaluator.class);

  private final PrivilegeStore privilegeStore;

  public PermissionEvaluator(PrivilegeStore privilegeStore) {
    this.privilegeStore = Preconditions.checkNotNull(privilegeStore, "privilegeStore is required");
  }

  /**
   * Évalue si le user a le privilège sur la ressource.
   *
   * @param username Nom de l'utilisateur
   * @param resourcePath Chemin de la ressource
   * @param privilege Privilège requis
   * @return true si autorisé
   */
  public boolean evaluate(String username, NamespaceKey resourcePath, Privilege privilege) {
    Preconditions.checkNotNull(username, "username is required");
    Preconditions.checkNotNull(resourcePath, "resourcePath is required");
    Preconditions.checkNotNull(privilege, "privilege is required");

    logger.debug("Evaluating permission: user={}, resource={}, privilege={}",
        username, resourcePath, privilege);

    // Phase 1 MVP: Check direct USER privileges only
    if (hasDirectPrivilege(username, resourcePath, privilege)) {
      logger.debug("Permission granted (direct privilege)");
      return true;
    }

    // Phase 2: Check privileges via roles
    // TODO: Implement role-based evaluation

    // Phase 2: Check inherited privileges (hierarchical)
    // TODO: Implement hierarchical evaluation

    logger.debug("Permission denied (no matching grant found)");
    return false;
  }

  /**
   * Vérifie si le user/role a un privilège direct sur la ressource.
   *
   * @param granteeName Nom du user ou role
   * @param resourcePath Chemin de la ressource
   * @param privilege Privilège requis
   * @return true si le privilège existe
   */
  private boolean hasDirectPrivilege(String granteeName, NamespaceKey resourcePath, Privilege privilege) {
    // Rechercher le grant dans le store
    PrivilegeGrant grant = privilegeStore.findGrant(GranteeType.USER, granteeName, resourcePath);

    if (grant == null) {
      return false;
    }

    // Vérifier si le privilège demandé est dans la liste
    PrivilegeType requestedPrivilege = convertToPrivilegeType(privilege);

    for (PrivilegeType grantedPrivilege : grant.getPrivilegesList()) {
      // ALL privilege donne tous les droits
      if (grantedPrivilege == PrivilegeType.ALL) {
        return true;
      }

      // Check exact match
      if (grantedPrivilege == requestedPrivilege) {
        return true;
      }

      // MODIFY includes ALTER, DROP
      if (grantedPrivilege == PrivilegeType.MODIFY &&
          (requestedPrivilege == PrivilegeType.ALTER || requestedPrivilege == PrivilegeType.DROP)) {
        return true;
      }
    }

    return false;
  }

  /**
   * Convert SqlGrant.Privilege to PrivilegeType proto enum.
   */
  private PrivilegeType convertToPrivilegeType(Privilege privilege) {
    switch (privilege) {
      case SELECT:
        return PrivilegeType.SELECT;
      case INSERT:
        return PrivilegeType.INSERT;
      case UPDATE:
        return PrivilegeType.UPDATE;
      case DELETE:
        return PrivilegeType.DELETE;
      case CREATE_TABLE:
        return PrivilegeType.CREATE_TABLE;
      case CREATE_VIEW:
        return PrivilegeType.CREATE_VIEW;
      case CREATE_FOLDER:
        return PrivilegeType.CREATE_FOLDER;
      case ALTER:
        return PrivilegeType.ALTER;
      case DROP:
        return PrivilegeType.DROP;
      case MODIFY:
        return PrivilegeType.MODIFY;
      case MANAGE_GRANTS:
        return PrivilegeType.MANAGE_GRANTS;
      case USAGE:
        return PrivilegeType.USAGE;
      case ALL:
        return PrivilegeType.ALL;
      default:
        throw new IllegalArgumentException("Unsupported privilege: " + privilege);
    }
  }
}
