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

import com.dremio.common.config.SabotConfig;
import com.dremio.common.exceptions.UserException;
import com.dremio.datastore.api.LegacyKVStoreProvider;
import com.dremio.exec.planner.sql.parser.SqlGrant.Privilege;
import com.dremio.service.acl.AuthorizationService;
import com.dremio.service.acl.exception.AclException;
import com.dremio.service.acl.proto.GranteeType;
import com.dremio.service.acl.proto.PrivilegeGrant;
import com.dremio.service.acl.proto.PrivilegeType;
import com.dremio.service.acl.proto.ResourceType;
import com.dremio.service.acl.store.PrivilegeStore;
import com.dremio.service.namespace.NamespaceKey;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.inject.Provider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementation of AuthorizationService.
 *
 * <p>This service manages ACL operations and provides caching for performance.
 *
 * <p>Phase 1 MVP: USER privileges only, no roles, no hierarchical inheritance
 */
public class AuthorizationServiceImpl implements AuthorizationService {
  private static final Logger logger = LoggerFactory.getLogger(AuthorizationServiceImpl.class);
  private static final Logger auditLogger = LoggerFactory.getLogger("dremio.acl.audit");

  private static final String CONFIG_PREFIX = "dremio.acl";
  private static final Joiner PATH_JOINER = Joiner.on(".");

  private final Provider<LegacyKVStoreProvider> kvStoreProviderProvider;
  private final SabotConfig config;

  private PrivilegeStore privilegeStore;
  private PermissionEvaluator permissionEvaluator;
  private Cache<PermissionCacheKey, Boolean> permissionCache;

  // Configuration
  private boolean enabled;
  private boolean strictMode;
  private List<String> publicSources;
  private List<String> superAdmins;

  public AuthorizationServiceImpl(
      Provider<LegacyKVStoreProvider> kvStoreProviderProvider,
      SabotConfig config) {
    this.kvStoreProviderProvider = Preconditions.checkNotNull(kvStoreProviderProvider);
    this.config = Preconditions.checkNotNull(config);
  }

  @Override
  public void start() throws Exception {
    logger.info("Starting AuthorizationService");

    // Load configuration
    loadConfiguration();

    if (!enabled) {
      logger.info("ACL service is disabled");
      return;
    }

    // Initialize stores
    LegacyKVStoreProvider kvStoreProvider = kvStoreProviderProvider.get();
    this.privilegeStore = new PrivilegeStore(kvStoreProvider);
    this.permissionEvaluator = new PermissionEvaluator(privilegeStore);

    // Initialize cache
    initializeCache();

    logger.info("AuthorizationService started (mode: {}, strictMode: {})",
        enabled ? "enabled" : "disabled", strictMode);
  }

  @Override
  public void close() throws Exception {
    logger.info("Stopping AuthorizationService");
    if (permissionCache != null) {
      permissionCache.invalidateAll();
    }
  }

  private void loadConfiguration() {
    // Load from SabotConfig or use defaults
    this.enabled = config.hasPath(CONFIG_PREFIX + ".enabled")
        ? config.getBoolean(CONFIG_PREFIX + ".enabled")
        : true;

    String mode = config.hasPath(CONFIG_PREFIX + ".mode")
        ? config.getString(CONFIG_PREFIX + ".mode")
        : "strict";
    this.strictMode = "strict".equalsIgnoreCase(mode);

    // Load public sources (accessible without grant)
    this.publicSources = config.hasPath(CONFIG_PREFIX + ".defaults.public_sources")
        ? config.getStringList(CONFIG_PREFIX + ".defaults.public_sources")
        : Arrays.asList("samples", "sys");

    // Load super admins (bypass all checks)
    this.superAdmins = config.hasPath(CONFIG_PREFIX + ".defaults.super_admins")
        ? config.getStringList(CONFIG_PREFIX + ".defaults.super_admins")
        : Arrays.asList("admin");

    logger.info("ACL Configuration: enabled={}, strictMode={}, publicSources={}, superAdmins={}",
        enabled, strictMode, publicSources, superAdmins);
  }

  private void initializeCache() {
    long ttlMillis = config.hasPath(CONFIG_PREFIX + ".cache.ttl")
        ? config.getLong(CONFIG_PREFIX + ".cache.ttl")
        : 300000; // 5 minutes default

    int maxSize = config.hasPath(CONFIG_PREFIX + ".cache.max_size")
        ? config.getInt(CONFIG_PREFIX + ".cache.max_size")
        : 10000;

    boolean recordStats = config.hasPath(CONFIG_PREFIX + ".cache.record_stats")
        ? config.getBoolean(CONFIG_PREFIX + ".cache.record_stats")
        : true;

    Caffeine<Object, Object> cacheBuilder = Caffeine.newBuilder()
        .maximumSize(maxSize)
        .expireAfterWrite(ttlMillis, TimeUnit.MILLISECONDS);

    if (recordStats) {
      cacheBuilder.recordStats();
    }

    this.permissionCache = cacheBuilder.build();

    logger.info("Permission cache initialized (ttl={}ms, maxSize={}, recordStats={})",
        ttlMillis, maxSize, recordStats);
  }

  @Override
  public boolean checkPermission(String username, NamespaceKey resourcePath, Privilege privilege) {
    if (!enabled) {
      return true; // ACL disabled, allow all
    }

    Preconditions.checkNotNull(username, "username is required");
    Preconditions.checkNotNull(resourcePath, "resourcePath is required");
    Preconditions.checkNotNull(privilege, "privilege is required");

    // Super admins bypass all checks
    if (superAdmins.contains(username)) {
      logger.debug("User {} is super admin, bypassing ACL check", username);
      return true;
    }

    // Public sources are accessible to all
    if (!resourcePath.getPathComponents().isEmpty() &&
        publicSources.contains(resourcePath.getPathComponents().get(0))) {
      logger.debug("Resource {} is in public source, allowing access", resourcePath);
      return true;
    }

    // Check cache first
    PermissionCacheKey cacheKey = new PermissionCacheKey(username, resourcePath, privilege);
    Boolean cached = permissionCache.getIfPresent(cacheKey);
    if (cached != null) {
      logger.debug("Cache hit for permission check: {}", cacheKey);
      return cached;
    }

    // Cache miss - evaluate permission
    boolean allowed = permissionEvaluator.evaluate(username, resourcePath, privilege);

    // Cache the result
    permissionCache.put(cacheKey, allowed);

    // Audit log denial
    if (!allowed && config.getBoolean(CONFIG_PREFIX + ".audit.log_denials")) {
      auditLogger.warn("PERMISSION_DENIED: user={}, resource={}, privilege={}",
          username, resourcePath, privilege);
    }

    return allowed;
  }

  @Override
  public void enforcePermission(String username, NamespaceKey resourcePath, Privilege privilege) {
    if (!strictMode || !enabled) {
      // Permissive mode or disabled - log but don't enforce
      if (!checkPermission(username, resourcePath, privilege)) {
        logger.warn("Permission check failed but running in permissive mode: user={}, resource={}, privilege={}",
            username, resourcePath, privilege);
      }
      return;
    }

    // Strict mode - enforce
    if (!checkPermission(username, resourcePath, privilege)) {
      throw UserException.permissionError()
          .message("User [%s] does not have [%s] privilege on [%s]",
              username, privilege, resourcePath)
          .buildSilently();
    }
  }

  @Override
  public void grantPrivilege(
      GranteeType granteeType,
      String granteeName,
      NamespaceKey resourcePath,
      Privilege privilege,
      String grantedBy,
      boolean withGrantOption) throws AclException {

    if (!enabled) {
      throw new AclException("ACL service is disabled");
    }

    Preconditions.checkNotNull(granteeType, "granteeType is required");
    Preconditions.checkNotNull(granteeName, "granteeName is required");
    Preconditions.checkNotNull(resourcePath, "resourcePath is required");
    Preconditions.checkNotNull(privilege, "privilege is required");
    Preconditions.checkNotNull(grantedBy, "grantedBy is required");

    logger.info("Granting privilege: granteeType={}, granteeName={}, resource={}, privilege={}, grantedBy={}, withGrantOption={}",
        granteeType, granteeName, resourcePath, privilege, grantedBy, withGrantOption);

    // Phase 1 MVP: Only support USER
    if (granteeType == GranteeType.ROLE) {
      throw new AclException("ROLE privileges not supported in Phase 1 MVP");
    }

    // Check if grant already exists
    PrivilegeGrant existingGrant = privilegeStore.findGrant(granteeType, granteeName, resourcePath);

    if (existingGrant != null) {
      // Update existing grant (add privilege if not already present)
      List<PrivilegeType> existingPrivileges = Lists.newArrayList(existingGrant.getPrivilegesList());
      PrivilegeType newPrivilege = convertToPrivilegeType(privilege);

      if (!existingPrivileges.contains(newPrivilege)) {
        existingPrivileges.add(newPrivilege);

        // Create new PrivilegeGrant with updated privileges
        PrivilegeGrant updatedGrant = new PrivilegeGrant();
        updatedGrant.setGrantId(existingGrant.getGrantId());
        updatedGrant.setGranteeType(existingGrant.getGranteeType());
        updatedGrant.setGranteeName(existingGrant.getGranteeName());
        updatedGrant.setResourceType(existingGrant.getResourceType());
        updatedGrant.setResourcePathList(existingGrant.getResourcePathList());
        updatedGrant.setPrivilegesList(existingPrivileges);
        updatedGrant.setGrantedBy(existingGrant.getGrantedBy());
        updatedGrant.setGrantedAt(existingGrant.getGrantedAt());
        updatedGrant.setWithGrantOption(withGrantOption);
        updatedGrant.setTag(existingGrant.getTag());

        privilegeStore.delete(existingGrant.getGrantId());
        privilegeStore.create(updatedGrant);
      }
    } else {
      // Create new grant
      PrivilegeGrant newGrant = new PrivilegeGrant();
      newGrant.setGranteeType(granteeType);
      newGrant.setGranteeName(granteeName);
      newGrant.setResourceType(ResourceType.DATASET); // Phase 1: assume DATASET
      newGrant.setResourcePathList(resourcePath.getPathComponents());
      newGrant.setPrivilegesList(Lists.newArrayList(convertToPrivilegeType(privilege)));
      newGrant.setGrantedBy(grantedBy);
      newGrant.setWithGrantOption(withGrantOption);

      privilegeStore.create(newGrant);
    }

    // Clear cache for this user
    clearPermissionCacheForUser(granteeName);

    // Audit log
    if (config.getBoolean(CONFIG_PREFIX + ".audit.log_changes")) {
      auditLogger.info("GRANT {} ON {} TO {} BY {} (WITH_GRANT_OPTION: {})",
          privilege, resourcePath, granteeName, grantedBy, withGrantOption);
    }
  }

  @Override
  public void revokePrivilege(
      GranteeType granteeType,
      String granteeName,
      NamespaceKey resourcePath,
      Privilege privilege,
      String revokedBy) throws AclException {

    if (!enabled) {
      throw new AclException("ACL service is disabled");
    }

    Preconditions.checkNotNull(granteeType, "granteeType is required");
    Preconditions.checkNotNull(granteeName, "granteeName is required");
    Preconditions.checkNotNull(resourcePath, "resourcePath is required");
    Preconditions.checkNotNull(privilege, "privilege is required");
    Preconditions.checkNotNull(revokedBy, "revokedBy is required");

    logger.info("Revoking privilege: granteeType={}, granteeName={}, resource={}, privilege={}, revokedBy={}",
        granteeType, granteeName, resourcePath, privilege, revokedBy);

    // Find existing grant
    PrivilegeGrant existingGrant = privilegeStore.findGrant(granteeType, granteeName, resourcePath);

    if (existingGrant == null) {
      throw new AclException("No grant found for " + granteeName + " on " + resourcePath);
    }

    // Remove privilege from list
    List<PrivilegeType> remainingPrivileges = Lists.newArrayList(existingGrant.getPrivilegesList());
    PrivilegeType privilegeToRemove = convertToPrivilegeType(privilege);
    remainingPrivileges.remove(privilegeToRemove);

    if (remainingPrivileges.isEmpty()) {
      // No more privileges - delete the grant
      privilegeStore.delete(existingGrant.getGrantId());
    } else {
      // Update grant with remaining privileges
      PrivilegeGrant updatedGrant = new PrivilegeGrant();
      updatedGrant.setGrantId(existingGrant.getGrantId());
      updatedGrant.setGranteeType(existingGrant.getGranteeType());
      updatedGrant.setGranteeName(existingGrant.getGranteeName());
      updatedGrant.setResourceType(existingGrant.getResourceType());
      updatedGrant.setResourcePathList(existingGrant.getResourcePathList());
      updatedGrant.setPrivilegesList(remainingPrivileges);
      updatedGrant.setGrantedBy(existingGrant.getGrantedBy());
      updatedGrant.setGrantedAt(existingGrant.getGrantedAt());
      updatedGrant.setWithGrantOption(existingGrant.getWithGrantOption());
      updatedGrant.setTag(existingGrant.getTag());

      privilegeStore.delete(existingGrant.getGrantId());
      privilegeStore.create(updatedGrant);
    }

    // Clear cache for this user
    clearPermissionCacheForUser(granteeName);

    // Audit log
    if (config.getBoolean(CONFIG_PREFIX + ".audit.log_changes")) {
      auditLogger.info("REVOKE {} ON {} FROM {} BY {}",
          privilege, resourcePath, granteeName, revokedBy);
    }
  }

  @Override
  public Iterable<PrivilegeGrant> listPrivileges(GranteeType type, String name) {
    if (!enabled) {
      return Lists.newArrayList();
    }
    return privilegeStore.findByGrantee(type, name);
  }

  @Override
  public Iterable<PrivilegeGrant> listPrivilegesOnResource(NamespaceKey resourcePath) {
    if (!enabled) {
      return Lists.newArrayList();
    }
    return privilegeStore.findByResource(resourcePath);
  }

  @Override
  public void clearPermissionCache() {
    if (permissionCache != null) {
      permissionCache.invalidateAll();
      logger.info("Permission cache cleared");
    }
  }

  @Override
  public void clearPermissionCacheForUser(String userName) {
    if (permissionCache != null) {
      // Invalidate all entries for this user
      permissionCache.asMap().keySet().removeIf(key -> key.username.equals(userName));
      logger.debug("Permission cache cleared for user: {}", userName);
    }
  }

  @Override
  public boolean isEnabled() {
    return enabled;
  }

  @Override
  public boolean isStrictMode() {
    return strictMode;
  }

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

  /**
   * Cache key for permission lookups.
   */
  @VisibleForTesting
  static class PermissionCacheKey {
    final String username;
    final String resourcePath;
    final Privilege privilege;

    PermissionCacheKey(String username, NamespaceKey resourcePath, Privilege privilege) {
      this.username = username;
      this.resourcePath = PATH_JOINER.join(resourcePath.getPathComponents());
      this.privilege = privilege;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      PermissionCacheKey that = (PermissionCacheKey) o;
      return Objects.equals(username, that.username) &&
          Objects.equals(resourcePath, that.resourcePath) &&
          privilege == that.privilege;
    }

    @Override
    public int hashCode() {
      return Objects.hash(username, resourcePath, privilege);
    }

    @Override
    public String toString() {
      return "PermissionCacheKey{" +
          "username='" + username + '\'' +
          ", resourcePath='" + resourcePath + '\'' +
          ", privilege=" + privilege +
          '}';
    }
  }
}
