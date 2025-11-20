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

import com.dremio.datastore.api.LegacyIndexedStore;
import com.dremio.datastore.api.LegacyIndexedStoreCreationFunction;
import com.dremio.datastore.api.LegacyStoreBuildingFactory;
import com.dremio.datastore.format.Format;
import com.dremio.service.acl.proto.PrivilegeGrant;

/**
 * PrivilegeStore creator.
 *
 * <p>Ce store contient tous les grants de privilèges. Clé: grant_id (UUID) Valeur: PrivilegeGrant
 * protobuf
 */
public final class PrivilegeStoreCreator
    implements LegacyIndexedStoreCreationFunction<String, PrivilegeGrant> {

  public static final String PRIVILEGE_STORE_NAME = "acl_privileges";

  @Override
  public LegacyIndexedStore<String, PrivilegeGrant> build(
      final LegacyStoreBuildingFactory factory) {
    return factory
        .<String, PrivilegeGrant>newStore()
        .name(PRIVILEGE_STORE_NAME)
        .keyFormat(Format.ofString())
        .valueFormat(Format.ofProtostuff(PrivilegeGrant.class))
        .buildIndexed(new PrivilegeGrantConverter());
  }
}
