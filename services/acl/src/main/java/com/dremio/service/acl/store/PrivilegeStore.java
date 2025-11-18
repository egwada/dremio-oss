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
package com.dremio.service.acl.store;

import com.dremio.datastore.SearchTypes.SearchQuery;
import com.dremio.datastore.api.Document;
import com.dremio.datastore.api.LegacyIndexedStore;
import com.dremio.datastore.api.LegacyIndexedStore.LegacyFindByCondition;
import com.dremio.datastore.api.LegacyKVStoreProvider;
import com.dremio.service.acl.proto.GranteeType;
import com.dremio.service.acl.proto.PrivilegeGrant;
import com.dremio.service.namespace.NamespaceKey;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.Iterables;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.util.UUID;

/**
 * Store pour les privilèges (grants).
 *
 * <p>Provides CRUD operations for privilege grants and indexed searches.
 */
public class PrivilegeStore {

  private static final Joiner PATH_JOINER = Joiner.on(".");

  private final LegacyIndexedStore<String, PrivilegeGrant> store;

  public PrivilegeStore(LegacyKVStoreProvider kvStoreProvider) {
    Preconditions.checkNotNull(kvStoreProvider, "kvStoreProvider is required");
    this.store = kvStoreProvider.getStore(PrivilegeStoreCreator.class);
  }

  /**
   * Create a new privilege grant.
   *
   * @param grant The privilege grant to create
   * @return The grant ID
   */
  public String create(PrivilegeGrant grant) {
    String grantId = UUID.randomUUID().toString();
    PrivilegeGrant grantWithId = PrivilegeGrant.newBuilder(grant)
        .setGrantId(grantId)
        .setGrantedAt(System.currentTimeMillis())
        .build();
    store.put(grantId, grantWithId);
    return grantId;
  }

  /**
   * Get a privilege grant by ID.
   *
   * @param grantId The grant ID
   * @return The privilege grant, or null if not found
   */
  public PrivilegeGrant get(String grantId) {
    return store.get(grantId);
  }

  /**
   * Delete a privilege grant.
   *
   * @param grantId The grant ID to delete
   */
  public void delete(String grantId) {
    store.delete(grantId);
  }

  /**
   * Find all grants for a specific grantee (user or role).
   *
   * @param granteeType USER or ROLE
   * @param granteeName Name of the user or role
   * @return Iterable of privilege grants
   */
  public Iterable<PrivilegeGrant> findByGrantee(GranteeType granteeType, String granteeName) {
    // Build search query
    SearchQuery granteeNameQuery = SearchQuery.newBuilder()
        .setEquals(PrivilegeGrantConverter.GRANTEE_NAME, granteeName)
        .build();

    SearchQuery granteeTypeQuery = SearchQuery.newBuilder()
        .setEquals(PrivilegeGrantConverter.GRANTEE_TYPE, granteeType.name())
        .build();

    SearchQuery combinedQuery = SearchQuery.newBuilder()
        .setAnd(granteeNameQuery, granteeTypeQuery)
        .build();

    LegacyFindByCondition condition = new LegacyFindByCondition()
        .setCondition(combinedQuery);

    return toGrantList(store.find(condition));
  }

  /**
   * Find all grants on a specific resource.
   *
   * @param resourcePath The resource path
   * @return Iterable of privilege grants
   */
  public Iterable<PrivilegeGrant> findByResource(NamespaceKey resourcePath) {
    String resourcePathStr = PATH_JOINER.join(resourcePath.getPathComponents());

    SearchQuery query = SearchQuery.newBuilder()
        .setEquals(PrivilegeGrantConverter.RESOURCE_PATH, resourcePathStr)
        .build();

    LegacyFindByCondition condition = new LegacyFindByCondition()
        .setCondition(query);

    return toGrantList(store.find(condition));
  }

  /**
   * Find a specific grant (for checking if it exists before revoke).
   *
   * @param granteeType USER or ROLE
   * @param granteeName Name of the user or role
   * @param resourcePath The resource path
   * @return The matching grant, or null if not found
   */
  public PrivilegeGrant findGrant(GranteeType granteeType, String granteeName, NamespaceKey resourcePath) {
    String resourcePathStr = PATH_JOINER.join(resourcePath.getPathComponents());

    SearchQuery granteeNameQuery = SearchQuery.newBuilder()
        .setEquals(PrivilegeGrantConverter.GRANTEE_NAME, granteeName)
        .build();

    SearchQuery granteeTypeQuery = SearchQuery.newBuilder()
        .setEquals(PrivilegeGrantConverter.GRANTEE_TYPE, granteeType.name())
        .build();

    SearchQuery resourceQuery = SearchQuery.newBuilder()
        .setEquals(PrivilegeGrantConverter.RESOURCE_PATH, resourcePathStr)
        .build();

    SearchQuery combinedQuery = SearchQuery.newBuilder()
        .setAnd(granteeNameQuery, granteeTypeQuery, resourceQuery)
        .build();

    LegacyFindByCondition condition = new LegacyFindByCondition()
        .setCondition(combinedQuery);

    Iterable<PrivilegeGrant> results = toGrantList(store.find(condition));
    return Iterables.getFirst(results, null);
  }

  /**
   * Get all privilege grants (for admin/debugging).
   *
   * @return Iterable of all privilege grants
   */
  public Iterable<PrivilegeGrant> getAll() {
    return toGrantList(store.find());
  }

  /**
   * Convert Document entries to PrivilegeGrant list.
   */
  private Iterable<PrivilegeGrant> toGrantList(Iterable<Entry<String, PrivilegeGrant>> entries) {
    List<PrivilegeGrant> grants = new ArrayList<>();
    for (Entry<String, PrivilegeGrant> entry : entries) {
      grants.add(entry.getValue());
    }
    return grants;
  }
}
