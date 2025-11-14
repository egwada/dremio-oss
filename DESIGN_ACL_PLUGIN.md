# Design Document: Plugin ACL pour Dremio Open Source

**Version:** 1.0
**Date:** 2025-11-14
**Auteur:** Architecture Team
**Statut:** Proposition

---

## Table des Matières

1. [Contexte et Objectifs](#1-contexte-et-objectifs)
2. [Architecture Globale](#2-architecture-globale)
3. [Modèle de Données](#3-modèle-de-données)
4. [Composants du Plugin](#4-composants-du-plugin)
5. [Points d'Intégration avec Dremio](#5-points-dintégration-avec-dremio)
6. [Flux d'Autorisation](#6-flux-dautorisation)
7. [API et Interfaces](#7-api-et-interfaces)
8. [Structure du Plugin](#8-structure-du-plugin)
9. [Implémentation par Phases](#9-implémentation-par-phases)
10. [Considérations de Performance](#10-considérations-de-performance)
11. [Configuration et Déploiement](#11-configuration-et-déploiement)
12. [Sécurité](#12-sécurité)
13. [Tests](#13-tests)
14. [Migration et Compatibilité](#14-migration-et-compatibilité)

---

## 1. Contexte et Objectifs

### 1.1 Problématique

Dremio Open Source ne dispose **pas de système ACL (Access Control List)** fonctionnel :
- Les méthodes de validation (`CatalogImpl.validatePrivilege()`, `validateOwnership()`) sont des no-ops
- Les commandes SQL GRANT/REVOKE sont parsées mais non implémentées
- La gestion des rôles existe en syntaxe uniquement
- Toute la logique de sécurité est dans l'édition Enterprise (code propriétaire)

### 1.2 Objectifs du Plugin

Créer un **plugin modulaire et maintenable** qui ajoute les capacités ACL à Dremio OSS :

1. **Gestion des privilèges** sur les ressources (datasets, folders, sources)
2. **Système de rôles** avec assignation utilisateur/rôle
3. **Commandes SQL** GRANT/REVOKE fonctionnelles
4. **Intégration transparente** avec le catalogue Dremio
5. **Architecture plugin** pour faciliter maintenance et évolution
6. **Performance** : cache, évaluation rapide des permissions
7. **Compatibilité** avec l'architecture existante de Dremio

### 1.3 Contraintes

- **Non-intrusive** : Minimum de modifications du code core Dremio
- **Plugin-based** : Utilisation de l'architecture Guice/Service de Dremio
- **Rétrocompatible** : Ne pas casser les installations existantes
- **Performant** : Pas d'impact sur les requêtes sans ACL
- **Open Source** : Code disponible sous licence Apache 2.0

---

## 2. Architecture Globale

### 2.1 Vue d'Ensemble

```
┌─────────────────────────────────────────────────────────────┐
│                    Dremio Core                               │
│  ┌────────────┐  ┌────────────┐  ┌────────────────┐        │
│  │   SQL      │  │  Catalog   │  │  UserService   │        │
│  │  Handlers  │  │   Impl     │  │                │        │
│  └─────┬──────┘  └─────┬──────┘  └────────┬───────┘        │
│        │               │                   │                 │
│        │ validatePrivilege()               │                 │
│        ▼               ▼                   ▼                 │
│  ┌─────────────────────────────────────────────────┐       │
│  │        ACL Plugin (Service)                      │       │
│  │  ┌───────────────────────────────────────────┐  │       │
│  │  │  AuthorizationService (Interface)         │  │       │
│  │  │  - checkPermission()                      │  │       │
│  │  │  - grantPrivilege()                       │  │       │
│  │  │  - revokePrivilege()                      │  │       │
│  │  └───────────────────────────────────────────┘  │       │
│  │                                                   │       │
│  │  ┌──────────────┐  ┌──────────────────────┐    │       │
│  │  │  ACL Store   │  │  Role Store          │    │       │
│  │  │  (KVStore)   │  │  (KVStore)           │    │       │
│  │  └──────────────┘  └──────────────────────┘    │       │
│  │                                                   │       │
│  │  ┌──────────────────────────────────────────┐  │       │
│  │  │  Permission Cache                         │  │       │
│  │  │  (user, resource, privilege) → boolean   │  │       │
│  │  └──────────────────────────────────────────┘  │       │
│  └─────────────────────────────────────────────────┘       │
└─────────────────────────────────────────────────────────────┘
```

### 2.2 Composants Principaux

| Composant | Description | Responsabilité |
|-----------|-------------|----------------|
| **AuthorizationService** | Service principal du plugin | API publique pour toutes les opérations ACL |
| **PrivilegeStore** | Stockage KV des privilèges | CRUD des grants (user/role → resource → privileges) |
| **RoleStore** | Stockage KV des rôles | CRUD des rôles et memberships |
| **PermissionEvaluator** | Moteur d'évaluation | Logique de résolution des permissions |
| **PermissionCache** | Cache des décisions | Performance (réutilise `PermissionCheckCache` existant) |
| **SQL Handlers** | GRANT/REVOKE/ROLE handlers | Intégration avec le parser SQL Dremio |
| **CatalogInterceptor** | Wrapper du Catalog | Injection des validations ACL |

---

## 3. Modèle de Données

### 3.1 Schéma Protobuf

#### 3.1.1 Privilege Grant

```protobuf
// services/acl/src/main/protobuf/privilege.proto

syntax = "proto3";
package com.dremio.service.acl;

option java_package = "com.dremio.service.acl.proto";
option java_outer_classname = "PrivilegeProto";

// Type de privilège
enum PrivilegeType {
  SELECT = 0;
  INSERT = 1;
  UPDATE = 2;
  DELETE = 3;
  CREATE_TABLE = 4;
  CREATE_VIEW = 5;
  CREATE_FOLDER = 6;
  ALTER = 7;
  DROP = 8;
  MODIFY = 9;
  MANAGE_GRANTS = 10;
  USAGE = 11;
  ALL = 99;
}

// Type de ressource
enum ResourceType {
  DATASET = 0;      // Table ou View
  FOLDER = 1;       // Dossier/Space
  SOURCE = 2;       // Data source
  CATALOG = 3;      // Catalogue entier
  FUNCTION = 4;     // UDF
}

// Type de bénéficiaire
enum GranteeType {
  USER = 0;
  ROLE = 1;
}

// Un grant de privilège
message PrivilegeGrant {
  string grant_id = 1;              // UUID unique
  GranteeType grantee_type = 2;     // USER ou ROLE
  string grantee_name = 3;          // Nom du user ou role
  ResourceType resource_type = 4;   // Type de ressource
  repeated string resource_path = 5; // Path complet (ex: ["source", "folder", "table"])
  repeated PrivilegeType privileges = 6; // Liste des privilèges
  string granted_by = 7;            // Qui a fait le grant
  int64 granted_at = 8;             // Timestamp
  bool with_grant_option = 9;       // Peut re-granter
  string version = 10;              // Pour optimistic locking
}

// Index pour recherches efficaces
message PrivilegeGrantIndex {
  string grantee_name = 1;
  string resource_path_str = 2;  // Path serialisé
}
```

#### 3.1.2 Role

```protobuf
// services/acl/src/main/protobuf/role.proto

syntax = "proto3";
package com.dremio.service.acl;

option java_package = "com.dremio.service.acl.proto";
option java_outer_classname = "RoleProto";

// Définition d'un rôle
message Role {
  string role_id = 1;           // UUID unique
  string role_name = 2;         // Nom du rôle (unique)
  string description = 3;       // Description
  string owner = 4;             // Propriétaire du rôle
  int64 created_at = 5;         // Timestamp création
  int64 modified_at = 6;        // Timestamp modification
  string version = 7;           // Pour optimistic locking
}

// Membership user-role
message RoleMembership {
  string membership_id = 1;     // UUID unique
  string role_name = 2;         // Nom du rôle
  string member_name = 3;       // Nom du membre (user ou role)
  GranteeType member_type = 4;  // USER ou ROLE
  string granted_by = 5;        // Qui a assigné
  int64 granted_at = 6;         // Timestamp
  string version = 7;           // Pour optimistic locking
}
```

### 3.2 Structure des Clés KVStore

#### PrivilegeStore

```
Key: "privilege:<grant_id>"
Value: PrivilegeGrant (protobuf)

Indexes:
- by_grantee: "grantee:<grantee_type>:<grantee_name>"
- by_resource: "resource:<resource_type>:<resource_path_hash>"
- by_grantee_resource: "gr:<grantee_name>:<resource_path_hash>"
```

#### RoleStore

```
Key: "role:<role_name>"
Value: Role (protobuf)

Indexes:
- by_name: "name:<role_name>"
```

#### RoleMembershipStore

```
Key: "membership:<membership_id>"
Value: RoleMembership (protobuf)

Indexes:
- by_role: "role:<role_name>"
- by_member: "member:<member_type>:<member_name>"
```

### 3.3 Modèle d'Héritage

```
Privileges effectifs =
  Privilèges directs USER
  + Privilèges de tous les ROLES du user
  + Héritage hiérarchique des ressources (optionnel Phase 2)

Exemple:
Source "S3"
  └── Folder "data"
       └── Table "sales"

GRANT SELECT ON "S3"."data" TO USER alice;
→ Alice peut SELECT sur "S3"."data" et tous les enfants
  (dont "S3"."data"."sales")
```

---

## 4. Composants du Plugin

### 4.1 AuthorizationService

**Interface principale du service ACL.**

```java
package com.dremio.service.acl;

import com.dremio.service.Service;
import com.dremio.exec.planner.sql.parser.SqlGrant.Privilege;
import com.dremio.service.namespace.NamespaceKey;

/**
 * Service de gestion des autorisations (ACL).
 *
 * Ce service est le point d'entrée pour toutes les opérations ACL :
 * - Vérification de permissions
 * - Grant/Revoke de privilèges
 * - Gestion des rôles
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
   * @throws UserException.permissionError si non autorisé
   */
  void enforcePermission(String username, NamespaceKey resourcePath, Privilege privilege);

  // === Privilege Management ===

  /**
   * Accorde un privilège à un user ou role.
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
   */
  Iterable<PrivilegeGrant> listPrivileges(GranteeType type, String name);

  /**
   * Liste tous les privilèges sur une ressource.
   */
  Iterable<PrivilegeGrant> listPrivilegesOnResource(NamespaceKey resourcePath);

  // === Role Management ===

  /**
   * Crée un rôle.
   */
  Role createRole(String roleName, String description, String owner) throws AclException;

  /**
   * Supprime un rôle.
   */
  void dropRole(String roleName) throws AclException;

  /**
   * Assigne un rôle à un user.
   */
  void grantRole(String roleName, String userName, String grantedBy) throws AclException;

  /**
   * Révoque un rôle d'un user.
   */
  void revokeRole(String roleName, String userName, String revokedBy) throws AclException;

  /**
   * Liste tous les rôles.
   */
  Iterable<Role> listRoles();

  /**
   * Liste les rôles d'un user.
   */
  Iterable<String> getUserRoles(String userName);

  // === Cache Management ===

  /**
   * Vide le cache de permissions (après modification des grants).
   */
  void clearPermissionCache();

  /**
   * Vide le cache pour un user spécifique.
   */
  void clearPermissionCacheForUser(String userName);
}
```

### 4.2 PermissionEvaluator

**Moteur de résolution des permissions.**

```java
package com.dremio.service.acl.impl;

/**
 * Évalue les permissions en combinant :
 * - Privilèges directs du user
 * - Privilèges des rôles du user
 * - Héritage hiérarchique (optionnel)
 */
class PermissionEvaluator {

  private final PrivilegeStore privilegeStore;
  private final RoleMembershipStore membershipStore;

  /**
   * Évalue si le user a le privilège sur la ressource.
   */
  public boolean evaluate(String username, NamespaceKey resourcePath, Privilege privilege) {

    // 1. Vérifier privilèges directs du user
    if (hasDirectPrivilege(username, resourcePath, privilege)) {
      return true;
    }

    // 2. Vérifier privilèges via les rôles
    Set<String> userRoles = getUserRoles(username);
    for (String role : userRoles) {
      if (hasDirectPrivilege(role, resourcePath, privilege)) {
        return true;
      }
    }

    // 3. Vérifier héritage hiérarchique (Phase 2)
    if (hasInheritedPrivilege(username, userRoles, resourcePath, privilege)) {
      return true;
    }

    return false;
  }

  private boolean hasDirectPrivilege(
      String granteeName,
      NamespaceKey resourcePath,
      Privilege privilege) {
    // Recherche dans le PrivilegeStore
    // ...
  }

  private Set<String> getUserRoles(String username) {
    // Recherche dans le RoleMembershipStore
    // ...
  }

  private boolean hasInheritedPrivilege(...) {
    // Phase 2: Parcours de l'arbre de ressources parent
    // ...
  }
}
```

### 4.3 PrivilegeStore / RoleStore

**Abstraction du stockage KV.**

```java
package com.dremio.service.acl.store;

import com.dremio.datastore.api.LegacyKVStore;
import com.dremio.datastore.api.LegacyKVStoreProvider;
import com.dremio.service.acl.proto.PrivilegeGrant;

/**
 * Store pour les privilèges.
 */
public class PrivilegeStore {

  private final LegacyKVStore<String, PrivilegeGrant> store;

  public PrivilegeStore(LegacyKVStoreProvider kvStoreProvider) {
    this.store = kvStoreProvider.getStore(PrivilegeStoreCreator.class);
  }

  public void put(String grantId, PrivilegeGrant grant) {
    store.put(grantId, grant);
  }

  public PrivilegeGrant get(String grantId) {
    return store.get(grantId);
  }

  public void delete(String grantId) {
    store.delete(grantId);
  }

  /**
   * Recherche par bénéficiaire.
   */
  public Iterable<PrivilegeGrant> findByGrantee(GranteeType type, String name) {
    // Utilise l'index by_grantee
    // ...
  }

  /**
   * Recherche par ressource.
   */
  public Iterable<PrivilegeGrant> findByResource(NamespaceKey resourcePath) {
    // Utilise l'index by_resource
    // ...
  }
}
```

### 4.4 CatalogInterceptor

**Wrapper du Catalog qui intercepte les appels pour validation ACL.**

```java
package com.dremio.service.acl.catalog;

import com.dremio.exec.catalog.Catalog;
import com.dremio.exec.catalog.DelegatingCatalog;
import com.dremio.service.acl.AuthorizationService;

/**
 * Decorator du Catalog qui injecte les vérifications ACL.
 */
public class AclCatalog extends DelegatingCatalog {

  private final AuthorizationService aclService;

  public AclCatalog(Catalog delegate, AuthorizationService aclService) {
    super(delegate);
    this.aclService = aclService;
  }

  @Override
  public DremioTable getTable(CatalogEntityKey key) {
    // Vérifier permission SELECT avant de retourner la table
    String username = getCurrentUsername();
    aclService.enforcePermission(username, key.toNamespaceKey(), Privilege.SELECT);

    return super.getTable(key);
  }

  @Override
  public void validatePrivilege(NamespaceKey key, SqlGrant.Privilege privilege) {
    // Implémentation réelle (n'est plus un no-op)
    String username = getCurrentUsername();
    aclService.enforcePermission(username, key, privilege);
  }

  @Override
  public void validateOwnership(CatalogEntityKey key) {
    // Vérifier que le user est owner ou a MANAGE_GRANTS
    String username = getCurrentUsername();
    aclService.enforcePermission(username, key.toNamespaceKey(), Privilege.MANAGE_GRANTS);
  }

  // Intercepter les autres méthodes : createTable, dropTable, etc.
  // ...
}
```

---

## 5. Points d'Intégration avec Dremio

### 5.1 Enregistrement du Service (DACDaemonModule)

```java
// Dans DACDaemonModule.java

// 1. Créer le service ACL
AuthorizationServiceImpl aclService = new AuthorizationServiceImpl(
    provider(LegacyKVStoreProvider.class),
    provider(UserService.class),
    sabotConfig
);

// 2. Enregistrer dans le registry
registry.bind(AuthorizationService.class, aclService);
registry.bindSelf(aclService);

// 3. Wrapper le CatalogService avec AclCatalog
CatalogServiceImpl catalogService = new CatalogServiceImpl(...);
AclCatalog aclCatalog = new AclCatalog(catalogService, aclService);

// 4. Exposer AclCatalog comme implémentation de CatalogService
registry.replace(CatalogService.class, aclCatalog);
```

### 5.2 Handlers SQL (Dynamiquement chargés)

Dremio charge les handlers via reflection. Créer les classes attendues :

**Package:** `com.dremio.exec.planner.sql.handlers`

```java
package com.dremio.exec.planner.sql.handlers;

/**
 * Handler pour GRANT privilege ON resource TO user/role
 */
public class GrantHandler implements SqlNodeHandler<SqlGrant> {

  @Inject
  private AuthorizationService aclService;

  @Override
  public PhysicalPlan getPlan(SqlHandlerConfig config, String sql, SqlNode sqlNode) {
    SqlGrant grant = (SqlGrant) sqlNode;

    // Extraire les infos du AST
    GranteeType granteeType = grant.getGranteeType();
    String granteeName = grant.getGranteeName();
    NamespaceKey resourcePath = grant.getPath();
    Privilege privilege = grant.getPrivilege();

    // Appeler le service ACL
    String currentUser = config.getContext().getQueryUserName();
    aclService.grantPrivilege(
        granteeType,
        granteeName,
        resourcePath,
        privilege,
        currentUser,
        grant.isWithGrantOption()
    );

    return DirectPlan.createSuccessPlan();
  }
}
```

**Autres handlers à créer :**
- `RevokeHandler` → REVOKE privilege
- `GrantRoleHandler` → GRANT ROLE
- `RevokeRoleHandler` → REVOKE ROLE
- `CreateRoleHandler` → CREATE ROLE
- `DropRoleHandler` → DROP ROLE

### 5.3 System Tables (AccessControlListingManager)

Implémenter l'interface pour exposer les données ACL dans les system tables.

```java
package com.dremio.service.acl.impl;

import com.dremio.exec.store.sys.accesscontrol.AccessControlListingManager;

public class AclListingManager implements AccessControlListingManager {

  private final AuthorizationService aclService;

  @Override
  public Iterable<RoleInfo> listRoles(String catalogName) {
    return aclService.listRoles().stream()
        .map(this::toRoleInfo)
        .collect(Collectors.toList());
  }

  @Override
  public Iterable<PrivilegeInfo> listPrivileges(String catalogName) {
    // Retourner tous les grants pour sys.privileges
    // ...
  }

  @Override
  public Iterable<MembershipInfo> listMemberships(String catalogName) {
    // Retourner tous les role memberships pour sys.membership
    // ...
  }
}
```

**Enregistrement dans DACDaemonModule :**

```java
AclListingManager aclListingManager = new AclListingManager(aclService);
registry.bind(AccessControlListingManager.class, aclListingManager);
```

### 5.4 Configuration (dremio.conf)

```hocon
# services/acl/src/main/resources/reference.conf

dremio.acl {
  # Activer/désactiver le plugin ACL
  enabled: true

  # Cache de permissions
  cache {
    # TTL du cache en millisecondes (5 minutes)
    ttl: 300000

    # Taille max du cache
    max_size: 10000
  }

  # Privilèges par défaut pour les nouveaux users
  default_privileges {
    # Permettre SELECT sur les sources publiques
    public_sources: ["samples"]
  }

  # Mode strict : rejeter les requêtes non autorisées
  # Mode permissif : logger mais autoriser (pour migration)
  strict_mode: true
}
```

---

## 6. Flux d'Autorisation

### 6.1 Vérification de Permission (Query)

```
┌─────────────┐
│ User Query  │ SELECT * FROM S3.data.sales
└──────┬──────┘
       │
       ▼
┌─────────────────────┐
│  SQL Handler        │ Parsing
└──────┬──────────────┘
       │
       ▼
┌─────────────────────┐
│  Catalog.getTable() │
└──────┬──────────────┘
       │
       ▼
┌──────────────────────────────┐
│  AclCatalog                  │
│  - getCurrentUsername()      │
│  - enforcePermission(SELECT) │
└──────┬───────────────────────┘
       │
       ▼
┌──────────────────────────────┐
│  AuthorizationService        │
│  - checkPermission()         │
└──────┬───────────────────────┘
       │
       ▼
┌──────────────────────────────┐
│  PermissionCache             │
│  - Lookup cached decision    │
└──────┬───────────────────────┘
       │
       │ (Cache MISS)
       ▼
┌──────────────────────────────┐
│  PermissionEvaluator         │
│  1. Check user privileges    │
│  2. Check role privileges    │
│  3. Check inherited (Phase2) │
└──────┬───────────────────────┘
       │
       ▼
┌──────────────────────────────┐
│  PrivilegeStore / RoleStore  │
│  - Query KVStore             │
└──────┬───────────────────────┘
       │
       ▼
┌──────────────────────────────┐
│  Decision: ALLOW / DENY      │
└──────┬───────────────────────┘
       │
       ▼ (ALLOW)
┌──────────────────────────────┐
│  Return DremioTable          │
└──────────────────────────────┘
```

### 6.2 Grant de Privilège (SQL)

```
┌─────────────────────────────────────────┐
│ GRANT SELECT ON S3.data.sales TO alice  │
└──────┬──────────────────────────────────┘
       │
       ▼
┌──────────────────────────────┐
│  SQL Parser                  │
│  - Creates SqlGrant AST      │
└──────┬───────────────────────┘
       │
       ▼
┌──────────────────────────────┐
│  SqlHandlerUtil              │
│  - Load GrantHandler (reflect)│
└──────┬───────────────────────┘
       │
       ▼
┌──────────────────────────────┐
│  GrantHandler                │
│  - Extract AST info          │
│  - Call aclService.grant()   │
└──────┬───────────────────────┘
       │
       ▼
┌──────────────────────────────┐
│  AuthorizationService        │
│  - Validate current user     │
│    has MANAGE_GRANTS         │
│  - Create PrivilegeGrant     │
└──────┬───────────────────────┘
       │
       ▼
┌──────────────────────────────┐
│  PrivilegeStore.put()        │
│  - Write to KVStore          │
└──────┬───────────────────────┘
       │
       ▼
┌──────────────────────────────┐
│  Clear PermissionCache       │
│  - Invalidate for alice      │
└──────┬───────────────────────┘
       │
       ▼
┌──────────────────────────────┐
│  Success                     │
└──────────────────────────────┘
```

---

## 7. API et Interfaces

### 7.1 API REST (Optionnel - Phase 3)

```
POST /api/v3/acl/privileges
{
  "granteeType": "USER",
  "granteeName": "alice",
  "resourcePath": ["S3", "data", "sales"],
  "privilege": "SELECT",
  "withGrantOption": false
}

DELETE /api/v3/acl/privileges/{grantId}

GET /api/v3/acl/privileges?user=alice
GET /api/v3/acl/privileges?resource=S3.data.sales

POST /api/v3/acl/roles
{
  "roleName": "analyst",
  "description": "Data Analyst Role"
}

POST /api/v3/acl/roles/{roleName}/members
{
  "memberName": "alice",
  "memberType": "USER"
}
```

### 7.2 CLI Commands (Optionnel)

```bash
# Grant privilege
dremio-admin acl grant --privilege SELECT --resource "S3.data.sales" --user alice

# Revoke privilege
dremio-admin acl revoke --privilege SELECT --resource "S3.data.sales" --user alice

# Create role
dremio-admin acl create-role --name analyst --description "Data Analyst"

# Grant role
dremio-admin acl grant-role --role analyst --user alice

# List privileges
dremio-admin acl list-privileges --user alice
```

---

## 8. Structure du Plugin

### 8.1 Maven Module Structure

```
dremio-oss/
├── services/
│   └── acl/                              # Nouveau module
│       ├── pom.xml
│       └── src/
│           ├── main/
│           │   ├── java/
│           │   │   └── com/dremio/service/acl/
│           │   │       ├── AuthorizationService.java
│           │   │       ├── impl/
│           │   │       │   ├── AuthorizationServiceImpl.java
│           │   │       │   ├── PermissionEvaluator.java
│           │   │       │   └── AclListingManager.java
│           │   │       ├── store/
│           │   │       │   ├── PrivilegeStore.java
│           │   │       │   ├── PrivilegeStoreCreator.java
│           │   │       │   ├── RoleStore.java
│           │   │       │   ├── RoleStoreCreator.java
│           │   │       │   ├── RoleMembershipStore.java
│           │   │       │   └── RoleMembershipStoreCreator.java
│           │   │       ├── catalog/
│           │   │       │   └── AclCatalog.java
│           │   │       └── exception/
│           │   │           ├── AclException.java
│           │   │           ├── PrivilegeNotFoundException.java
│           │   │           └── RoleNotFoundException.java
│           │   ├── protobuf/
│           │   │   ├── privilege.proto
│           │   │   └── role.proto
│           │   └── resources/
│           │       └── reference.conf
│           └── test/
│               └── java/
│                   └── com/dremio/service/acl/
│                       ├── AuthorizationServiceTest.java
│                       ├── PermissionEvaluatorTest.java
│                       └── StoreTest.java
│
└── sabot/
    └── kernel/
        └── src/main/java/com/dremio/exec/planner/sql/handlers/
            ├── GrantHandler.java         # Handlers SQL
            ├── RevokeHandler.java
            ├── GrantRoleHandler.java
            ├── RevokeRoleHandler.java
            ├── CreateRoleHandler.java
            └── DropRoleHandler.java
```

### 8.2 Dependencies (pom.xml)

```xml
<project>
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>com.dremio</groupId>
    <artifactId>dremio-services-parent</artifactId>
    <version>25.2.0-SNAPSHOT</version>
  </parent>

  <artifactId>dremio-services-acl</artifactId>
  <name>Dremio ACL Service</name>

  <dependencies>
    <!-- Dremio Core -->
    <dependency>
      <groupId>com.dremio</groupId>
      <artifactId>dremio-common</artifactId>
    </dependency>

    <dependency>
      <groupId>com.dremio.services</groupId>
      <artifactId>dremio-services-users</artifactId>
    </dependency>

    <dependency>
      <groupId>com.dremio.services</groupId>
      <artifactId>dremio-services-namespace</artifactId>
    </dependency>

    <dependency>
      <groupId>com.dremio</groupId>
      <artifactId>dremio-datastore-api</artifactId>
    </dependency>

    <!-- Sabot (pour SqlGrant, Catalog, etc.) -->
    <dependency>
      <groupId>com.dremio.sabot</groupId>
      <artifactId>dremio-sabot-kernel</artifactId>
    </dependency>

    <!-- Guice -->
    <dependency>
      <groupId>com.google.inject</groupId>
      <artifactId>guice</artifactId>
    </dependency>

    <!-- Protobuf -->
    <dependency>
      <groupId>com.google.protobuf</groupId>
      <artifactId>protobuf-java</artifactId>
    </dependency>

    <!-- Testing -->
    <dependency>
      <groupId>junit</groupId>
      <artifactId>junit</artifactId>
      <scope>test</scope>
    </dependency>

    <dependency>
      <groupId>org.mockito</groupId>
      <artifactId>mockito-core</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <!-- Protobuf compiler -->
      <plugin>
        <groupId>com.github.os72</groupId>
        <artifactId>protoc-jar-maven-plugin</artifactId>
      </plugin>
    </plugins>
  </build>
</project>
```

---

## 9. Implémentation par Phases

### Phase 1 : MVP (Minimum Viable Product) - 2-3 semaines

**Objectif :** Fonctionnalité de base utilisable

**Livrables :**
1. ✅ Service ACL avec API `checkPermission()`, `grantPrivilege()`, `revokePrivilege()`
2. ✅ PrivilegeStore (KVStore) pour grants USER → DATASET
3. ✅ PermissionEvaluator simple (sans héritage)
4. ✅ AclCatalog wrapper avec `validatePrivilege()` implémenté
5. ✅ Handlers SQL : `GrantHandler`, `RevokeHandler`
6. ✅ Cache de permissions basique
7. ✅ Tests unitaires

**Privilèges supportés :**
- SELECT
- INSERT
- CREATE_TABLE
- DROP
- MODIFY

**Limitations Phase 1 :**
- Pas de rôles (uniquement USER)
- Pas d'héritage hiérarchique
- Pas de WITH GRANT OPTION
- Pas de REST API

**Test d'acceptation :**
```sql
-- Alice crée une table
CREATE TABLE myspace.mytable AS SELECT * FROM samples.airline;

-- Admin accorde SELECT à Bob
GRANT SELECT ON myspace.mytable TO USER bob;

-- Bob peut lire
SELECT * FROM myspace.mytable;  -- ✅ Succès

-- Bob ne peut pas modifier
INSERT INTO myspace.mytable VALUES (...);  -- ❌ Permission denied

-- Admin révoque
REVOKE SELECT ON myspace.mytable FROM USER bob;

-- Bob ne peut plus lire
SELECT * FROM myspace.mytable;  -- ❌ Permission denied
```

---

### Phase 2 : Rôles et Héritage - 2-3 semaines

**Objectif :** Système complet de rôles

**Livrables :**
1. ✅ RoleStore et RoleMembershipStore
2. ✅ PermissionEvaluator avec résolution de rôles
3. ✅ Handlers SQL : `CreateRoleHandler`, `GrantRoleHandler`, etc.
4. ✅ Héritage hiérarchique des privilèges (folder → datasets)
5. ✅ System tables : `sys.roles`, `sys.privileges`, `sys.membership`
6. ✅ AclListingManager implémenté
7. ✅ WITH GRANT OPTION support

**Test d'acceptation :**
```sql
-- Créer rôle
CREATE ROLE analyst;

-- Grant privilège au rôle
GRANT SELECT ON myspace.sales TO ROLE analyst;

-- Assigner rôle à Bob
GRANT ROLE analyst TO USER bob;

-- Bob peut lire via le rôle
SELECT * FROM myspace.sales;  -- ✅ Succès

-- Tester héritage
GRANT SELECT ON myspace TO ROLE analyst;
-- Bob peut maintenant lire tous les datasets sous myspace
SELECT * FROM myspace.another_table;  -- ✅ Succès

-- Voir les privilèges
SELECT * FROM sys.privileges WHERE grantee_name = 'bob';
SELECT * FROM sys.roles;
SELECT * FROM sys.membership;
```

---

### Phase 3 : Features Avancées - 2-3 semaines

**Objectif :** Production-ready

**Livrables :**
1. ✅ REST API complète
2. ✅ CLI commands (dremio-admin acl)
3. ✅ Privilèges par défaut configurables
4. ✅ Mode permissif (pour migration progressive)
5. ✅ Audit logging des changements ACL
6. ✅ UI basique dans Dremio Web (optionnel)
7. ✅ Documentation utilisateur complète
8. ✅ Migration tool (import/export ACL)
9. ✅ Performance tuning (indexes, cache)

**Test d'acceptation :**
```bash
# Via REST API
curl -X POST http://localhost:9047/api/v3/acl/privileges \
  -d '{"granteeType":"USER","granteeName":"bob","resourcePath":["sales"],"privilege":"SELECT"}'

# Via CLI
dremio-admin acl grant --privilege SELECT --resource sales --user bob

# Export ACL
dremio-admin acl export --output acl_backup.json

# Import ACL
dremio-admin acl import --input acl_backup.json
```

---

## 10. Considérations de Performance

### 10.1 Stratégies d'Optimisation

| Problème | Solution | Impact |
|----------|----------|--------|
| **Latency sur checkPermission()** | Cache in-memory (Caffeine) | Réduction de 99% des appels KVStore |
| **Résolution de rôles coûteuse** | Cache des rôles par user | Pas de requête KVStore répétée |
| **Héritage hiérarchique lent** | Index inversé (child → parent) | O(1) lookup au lieu de parcours d'arbre |
| **Queries lourdes sur KVStore** | Indexes secondaires optimisés | Recherche directe par grantee/resource |
| **Cache invalidation** | Invalidation ciblée par user | Pas de flush global |

### 10.2 Cache Configuration

```java
// Configuration du cache (Caffeine)
Cache<PermissionCacheKey, Boolean> permissionCache = Caffeine.newBuilder()
    .maximumSize(10_000)
    .expireAfterWrite(5, TimeUnit.MINUTES)
    .recordStats()
    .build();

// Métriques
CacheStats stats = permissionCache.stats();
logger.info("ACL Cache - Hit rate: {}, Evictions: {}",
    stats.hitRate(), stats.evictionCount());
```

### 10.3 KVStore Indexes

```java
// PrivilegeStoreCreator
@Override
public void build(StoreCreationHelper helper) {
  // Index par grantee (pour "list privileges of user")
  helper.addIndex("by_grantee", IndexType.UNIQUE_COMPOUND)
      .addField("grantee_type")
      .addField("grantee_name")
      .addField("resource_path_str")
      .build();

  // Index par resource (pour "list grants on resource")
  helper.addIndex("by_resource", IndexType.NON_UNIQUE)
      .addField("resource_type")
      .addField("resource_path_str")
      .build();
}
```

### 10.4 Benchmarks Cibles

| Opération | Latency (p50) | Latency (p99) | Throughput |
|-----------|---------------|---------------|------------|
| checkPermission() (cached) | < 1 ms | < 5 ms | > 100k ops/s |
| checkPermission() (uncached) | < 10 ms | < 50 ms | > 10k ops/s |
| grantPrivilege() | < 50 ms | < 200 ms | > 1k ops/s |
| revokePrivilege() | < 50 ms | < 200 ms | > 1k ops/s |
| getUserRoles() (cached) | < 1 ms | < 5 ms | > 100k ops/s |

---

## 11. Configuration et Déploiement

### 11.1 Configuration de Déploiement

**dremio.conf**

```hocon
dremio.acl {
  # Activer le plugin ACL
  enabled: true

  # Mode d'opération
  # - strict: Rejeter les requêtes non autorisées (production)
  # - permissive: Logger mais autoriser (migration)
  # - disabled: Désactiver complètement les checks
  mode: strict

  # Configuration du cache
  cache {
    # Activer le cache
    enabled: true

    # TTL en millisecondes (5 minutes)
    ttl: 300000

    # Taille maximale
    max_size: 10000

    # Enregistrer les métriques
    record_stats: true
  }

  # Privilèges par défaut
  defaults {
    # Sources accessibles à tous (sans grant)
    public_sources: ["samples", "sys"]

    # Privilèges par défaut pour les nouveaux users
    new_user_privileges: [
      {
        resource: "samples"
        privilege: "SELECT"
      }
    ]
  }

  # Audit
  audit {
    # Logger tous les changements ACL
    log_changes: true

    # Logger les rejets de permission
    log_denials: true
  }

  # Super admin (bypass tous les checks)
  super_admins: ["admin", "dremio"]
}
```

### 11.2 Migration depuis installation sans ACL

**Script de migration :**

```bash
#!/bin/bash
# migrate_to_acl.sh

# 1. Activer le mode permissif
echo "Enabling ACL in permissive mode..."
sed -i 's/mode: strict/mode: permissive/' /opt/dremio/conf/dremio.conf

# 2. Redémarrer Dremio
systemctl restart dremio

# 3. Analyser les accès actuels (via logs)
echo "Analyzing current access patterns..."
dremio-admin acl analyze-logs --days 30 --output access_patterns.json

# 4. Générer les grants recommandés
echo "Generating recommended grants..."
dremio-admin acl recommend --input access_patterns.json --output recommended_grants.sql

# 5. Review manuel des grants
echo "Review recommended_grants.sql and apply"
echo "Then run: dremio-admin acl import --input recommended_grants.sql"

# 6. Passer en mode strict après validation
echo "After validation, switch to strict mode in dremio.conf"
```

### 11.3 Rollback Plan

Si problème en production :

```bash
# Option 1: Désactiver les checks ACL (emergency)
dremio.acl.mode: disabled

# Option 2: Passer en mode permissif
dremio.acl.mode: permissive

# Option 3: Désactiver complètement le plugin
dremio.acl.enabled: false
```

---

## 12. Sécurité

### 12.1 Principes de Sécurité

1. **Deny by Default**
   - Tout accès est refusé sauf grant explicite
   - Exception : sources dans `public_sources`

2. **Privilege Escalation Prevention**
   - Seul le propriétaire ou MANAGE_GRANTS peut granter
   - WITH GRANT OPTION contrôle qui peut re-granter

3. **Admin Separation**
   - Super admins configurés explicitement
   - Audit trail de toutes les actions admin

4. **Secure Storage**
   - Grants stockés dans KVStore avec encryption at rest
   - Version tag pour éviter race conditions

### 12.2 Validation et Sanitization

```java
// Validation des inputs
public void grantPrivilege(...) throws AclException {
  // 1. Valider que le grantee existe
  if (!userService.userExists(granteeName)) {
    throw new AclException("User not found: " + granteeName);
  }

  // 2. Valider que la ressource existe
  if (!catalogService.resourceExists(resourcePath)) {
    throw new AclException("Resource not found: " + resourcePath);
  }

  // 3. Valider que le granter a MANAGE_GRANTS
  if (!hasManageGrantsPrivilege(grantedBy, resourcePath)) {
    throw new UserException.permissionError()
        .message("User %s cannot grant on %s", grantedBy, resourcePath)
        .build();
  }

  // 4. Procéder au grant
  // ...
}
```

### 12.3 Audit Logging

```java
// Audit logger
private static final Logger AUDIT_LOGGER = LoggerFactory.getLogger("dremio.acl.audit");

public void grantPrivilege(...) {
  // ... grant logic ...

  // Log l'action
  AUDIT_LOGGER.info(
      "GRANT {} ON {} TO {} BY {} (WITH_GRANT_OPTION: {})",
      privilege,
      resourcePath,
      granteeName,
      grantedBy,
      withGrantOption
  );
}

// Format de log audit
// [timestamp] [ACL_AUDIT] GRANT SELECT ON S3.data.sales TO alice BY admin (WITH_GRANT_OPTION: false)
// [timestamp] [ACL_AUDIT] REVOKE SELECT ON S3.data.sales FROM alice BY admin
// [timestamp] [ACL_AUDIT] CREATE_ROLE analyst BY admin
// [timestamp] [ACL_AUDIT] GRANT_ROLE analyst TO alice BY admin
```

---

## 13. Tests

### 13.1 Stratégie de Test

| Type de Test | Coverage | Outils |
|--------------|----------|--------|
| **Unit Tests** | 80%+ | JUnit, Mockito |
| **Integration Tests** | Services + KVStore | Dremio TestBase |
| **SQL Tests** | Handlers SQL | SqlTestBase |
| **Performance Tests** | Latency, throughput | JMH Benchmarks |
| **Security Tests** | Privilege escalation, bypass | Manual + Automated |

### 13.2 Test Scenarios

**Unit Tests (AuthorizationServiceTest.java)**

```java
@Test
public void testGrantAndCheckPermission() {
  // Grant SELECT to alice
  aclService.grantPrivilege(
      GranteeType.USER,
      "alice",
      new NamespaceKey(Arrays.asList("S3", "sales")),
      Privilege.SELECT,
      "admin",
      false
  );

  // Alice should have SELECT
  assertTrue(aclService.checkPermission("alice",
      new NamespaceKey(Arrays.asList("S3", "sales")),
      Privilege.SELECT));

  // Alice should NOT have INSERT
  assertFalse(aclService.checkPermission("alice",
      new NamespaceKey(Arrays.asList("S3", "sales")),
      Privilege.INSERT));
}

@Test
public void testRevokePermission() {
  // Grant then revoke
  aclService.grantPrivilege(...);
  aclService.revokePrivilege(...);

  // Should be denied
  assertFalse(aclService.checkPermission(...));
}

@Test
public void testRoleBasedPermission() {
  // Create role
  aclService.createRole("analyst", "Data Analyst", "admin");

  // Grant to role
  aclService.grantPrivilege(GranteeType.ROLE, "analyst", ...);

  // Assign role to alice
  aclService.grantRole("analyst", "alice", "admin");

  // Alice should have permission via role
  assertTrue(aclService.checkPermission("alice", ...));
}
```

**Integration Tests (AclIntegrationTest.java)**

```java
@Test
public void testEndToEndSqlGrant() throws Exception {
  // Create table as admin
  runSQL("CREATE TABLE myspace.mytable AS SELECT * FROM (VALUES (1, 'a')) AS t(id, name)");

  // Grant to alice
  runSQL("GRANT SELECT ON myspace.mytable TO USER alice");

  // Alice should be able to query
  runSQLAsUser("SELECT * FROM myspace.mytable", "alice");

  // Alice should NOT be able to insert
  assertThrows(UserException.class, () -> {
    runSQLAsUser("INSERT INTO myspace.mytable VALUES (2, 'b')", "alice");
  });
}

@Test
public void testCatalogIntegration() {
  Catalog catalog = getCatalog();

  // Grant permission
  aclService.grantPrivilege(...);

  // getTable should succeed
  DremioTable table = catalog.getTable(...);
  assertNotNull(table);

  // Revoke permission
  aclService.revokePrivilege(...);

  // getTable should fail
  assertThrows(UserException.permissionError(), () -> {
    catalog.getTable(...);
  });
}
```

**Performance Tests (AclBenchmark.java)**

```java
@Benchmark
@BenchmarkMode(Mode.Throughput)
public void benchmarkCheckPermissionCached(Blackhole bh) {
  boolean result = aclService.checkPermission("alice", testPath, Privilege.SELECT);
  bh.consume(result);
}

@Benchmark
@BenchmarkMode(Mode.AverageTime)
public void benchmarkGrantPrivilege(Blackhole bh) {
  aclService.grantPrivilege(...);
  bh.consume(true);
}
```

---

## 14. Migration et Compatibilité

### 14.1 Compatibilité avec Dremio Existant

**Backward Compatibility :**
- Si `dremio.acl.enabled: false` → Comportement actuel (no-op)
- Pas de changement de schéma dans les stores existants
- Aucune modification des API publiques de Dremio

**Forward Compatibility :**
- Format de stockage versioned (protobuf)
- Migration automatique des schémas KVStore si nécessaire

### 14.2 Migration Plan

**Étape 1 : Installation**
```bash
# 1. Backup
dremio-admin backup

# 2. Installer le nouveau module ACL
# (inclus dans la distribution Dremio)

# 3. Activer en mode permissif
vim /opt/dremio/conf/dremio.conf
# Set: dremio.acl.mode: permissive

# 4. Redémarrer
systemctl restart dremio
```

**Étape 2 : Initialisation**
```sql
-- Créer rôles de base
CREATE ROLE admin_role;
CREATE ROLE analyst_role;
CREATE ROLE viewer_role;

-- Assigner admins existants au rôle admin
GRANT ROLE admin_role TO USER admin;

-- Grants globaux pour backward compat
GRANT SELECT ON samples TO ROLE viewer_role;
```

**Étape 3 : Migration progressive**
```bash
# Analyser les accès actuels
dremio-admin acl analyze-logs --days 30

# Générer recommandations
dremio-admin acl recommend > grants.sql

# Review et appliquer
vim grants.sql
dremio-admin run-sql grants.sql
```

**Étape 4 : Activation stricte**
```bash
# Après validation, passer en strict
vim /opt/dremio/conf/dremio.conf
# Set: dremio.acl.mode: strict

systemctl restart dremio
```

### 14.3 Upgrade Path

**De Phase 1 → Phase 2 (Ajout de rôles)**
- Migration automatique : Aucune action requise
- Les grants USER existants sont préservés
- Nouveaux grants ROLE disponibles

**De Phase 2 → Phase 3 (Features avancées)**
- Migration automatique des schémas
- Nouvelles APIs optionnelles
- Backward compatible

---

## Annexes

### A. Références

- [Dremio Architecture](https://docs.dremio.com/architecture/)
- [Dremio Security Model](https://docs.dremio.com/security/)
- [Google Guice Documentation](https://github.com/google/guice/wiki)
- [Protobuf Guide](https://developers.google.com/protocol-buffers)

### B. Glossaire

| Terme | Définition |
|-------|------------|
| **ACL** | Access Control List - Liste de contrôle d'accès |
| **Privilege** | Droit d'effectuer une action (SELECT, INSERT, etc.) |
| **Grant** | Attribution d'un privilège à un user/role |
| **Grantee** | Bénéficiaire d'un grant (user ou role) |
| **Grantor** | Celui qui accorde un grant |
| **Resource** | Objet protégé (dataset, folder, source) |
| **Principal** | Entité qui s'authentifie (user) |
| **Role** | Groupe de privilèges assignable à des users |
| **Membership** | Association user-role |

### C. Métriques et Monitoring

**Métriques à exposer (Prometheus format) :**

```
# Nombre de checks de permission
acl_permission_checks_total{result="allow|deny"} counter

# Latency des checks
acl_permission_check_duration_seconds histogram

# Cache hit rate
acl_cache_hit_rate gauge
acl_cache_size gauge
acl_cache_evictions_total counter

# Nombre de grants actifs
acl_grants_total{type="user|role"} gauge

# Nombre de rôles
acl_roles_total gauge

# Opérations GRANT/REVOKE
acl_grant_operations_total{type="grant|revoke"} counter
```

**Healthcheck endpoint :**
```
GET /api/v3/acl/health

Response:
{
  "status": "healthy",
  "store_accessible": true,
  "cache_hit_rate": 0.95,
  "grants_count": 1234,
  "roles_count": 56
}
```

---

## Conclusion

Ce design propose une **architecture plugin modulaire** pour ajouter les ACL à Dremio OSS de manière :

✅ **Non-intrusive** : Minimum de modifications du core
✅ **Performante** : Cache multi-niveaux, indexes optimisés
✅ **Extensible** : Architecture par phases, API ouverte
✅ **Compatible** : Pas de breaking changes, migration douce
✅ **Maintenable** : Code bien structuré, tests complets

**Prochaines étapes :**
1. Review et validation du design
2. Implémentation Phase 1 (MVP)
3. Tests et validation
4. Phase 2 et 3 selon besoins

**Questions ouvertes :**
- Besoin de column-level security ? (ajouterait complexité)
- Intégration avec LDAP/AD pour roles externes ?
- UI pour gestion visuelle des ACL ?
