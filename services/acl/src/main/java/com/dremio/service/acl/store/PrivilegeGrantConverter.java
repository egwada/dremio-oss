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

import com.dremio.datastore.IndexedStore.DocumentConverter;
import com.dremio.datastore.indexed.IndexKey;
import com.dremio.service.acl.proto.AclProtobuf.GranteeType;
import com.dremio.service.acl.proto.AclProtobuf.PrivilegeGrant;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;

/**
 * Document converter for PrivilegeGrant to enable indexing.
 */
public class PrivilegeGrantConverter implements DocumentConverter<String, PrivilegeGrant> {

  // Index keys for efficient search
  public static final IndexKey GRANTEE_NAME = IndexKey.newBuilder("grantee_name", "GRANTEE_NAME", String.class)
      .build();

  public static final IndexKey GRANTEE_TYPE = IndexKey.newBuilder("grantee_type", "GRANTEE_TYPE", String.class)
      .build();

  public static final IndexKey RESOURCE_PATH = IndexKey.newBuilder("resource_path", "RESOURCE_PATH", String.class)
      .build();

  private static final Joiner PATH_JOINER = Joiner.on(".");

  @Override
  public void convert(DocumentWriter writer, String key, PrivilegeGrant grant) {
    writer.write(GRANTEE_NAME, grant.getGranteeName());
    writer.write(GRANTEE_TYPE, grant.getGranteeType().name());

    // Serialize resource path as string for indexing
    String resourcePathStr = PATH_JOINER.join(grant.getResourcePathList());
    writer.write(RESOURCE_PATH, resourcePathStr);
  }

  @Override
  public Iterable<IndexKey> getIndexes() {
    return ImmutableList.of(GRANTEE_NAME, GRANTEE_TYPE, RESOURCE_PATH);
  }
}
