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
package com.dremio.service.acl;

import com.dremio.common.AutoCloseables;
import com.dremio.exec.planner.sql.parser.SqlGrant.Privilege;
import com.dremio.service.Service;
import com.dremio.service.acl.exception.AclException;
import com.dremio.service.acl.proto.GranteeType;
import com.dremio.service.acl.proto.PrivilegeGrant;
import com.dremio.service.namespace.NamespaceKey;

/**
 * Service de gestion des autorisations (ACL).
 *
 * <p>Ce service est le point d'entrée pour toutes les opérations ACL :
 * - Vérification de permissions
 * - Grant/Revoke de privilèges
 * - Gestion des rôles (Phase 2)
 *
 * <p>Phase 1 MVP : Support uniquement USER (pas de ROLE)
 */
public interface AuthorizationService extends Service {

  // === Permission Checking ===

  /**
   * Vérifie si un user a un privilège sur une ressource.
   *
   * @param username Nom de l'utilisateur
   * @param resourcePath Chemin de la ressource (ex: ["S3", "data", "sales"])
   * @param privilege Privilège requis
   * @return true si autorisé
   */
  boolean checkPermission(String username, NamespaceKey resourcePath, Privilege privilege);

  /**
   * Vérifie et lève une exception si permission refusée.
   *
   * @param username Nom de l'utilisateur
   * @param resourcePath Chemin de la ressource
   * @param privilege Privilège requis
   * @throws com.dremio.common.exceptions.UserException.permissionError si non autorisé
   */
  void enforcePermission(String username, NamespaceKey resourcePath, Privilege privilege);

  // === Privilege Management ===

  /**
   * Accorde un privilège à un user ou role.
   *
   * @param granteeType USER ou ROLE
   * @param granteeName Nom du user ou role
   * @param resourcePath Chemin de la ressource
   * @param privilege Privilège à accorder
   * @param grantedBy Qui fait le grant
   * @param withGrantOption Si true, le grantee peut re-granter ce privilège
   * @throws AclException si erreur
   */
  void grantPrivilege(
      GranteeType granteeType,
      String granteeName,
      NamespaceKey resourcePath,
      Privilege privilege,
      String grantedBy,
      boolean withGrantOption
  ) throws AclException;

  /**
   * Révoque un privilège.
   *
   * @param granteeType USER ou ROLE
   * @param granteeName Nom du user ou role
   * @param resourcePath Chemin de la ressource
   * @param privilege Privilège à révoquer
   * @param revokedBy Qui fait le revoke
   * @throws AclException si erreur
   */
  void revokePrivilege(
      GranteeType granteeType,
      String granteeName,
      NamespaceKey resourcePath,
      Privilege privilege,
      String revokedBy
  ) throws AclException;

  /**
   * Liste tous les privilèges d'un user/role.
   *
   * @param type USER ou ROLE
   * @param name Nom du user ou role
   * @return Liste des privilèges
   */
  Iterable<PrivilegeGrant> listPrivileges(GranteeType type, String name);

  /**
   * Liste tous les privilèges sur une ressource.
   *
   * @param resourcePath Chemin de la ressource
   * @return Liste des privilèges
   */
  Iterable<PrivilegeGrant> listPrivilegesOnResource(NamespaceKey resourcePath);

  // === Cache Management ===

  /**
   * Vide le cache de permissions (après modification des grants).
   */
  void clearPermissionCache();

  /**
   * Vide le cache pour un user spécifique.
   *
   * @param userName Nom du user
   */
  void clearPermissionCacheForUser(String userName);

  // === Configuration ===

  /**
   * Vérifie si le service ACL est activé.
   *
   * @return true si enabled
   */
  boolean isEnabled();

  /**
   * Vérifie si on est en mode strict (rejeter les accès non autorisés).
   *
   * @return true si strict mode
   */
  boolean isStrictMode();
}
