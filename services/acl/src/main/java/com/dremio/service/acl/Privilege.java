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

/**
 * SQL privileges that can be granted or revoked.
 *
 * <p>This enum mirrors com.dremio.exec.planner.sql.parser.SqlGrant.Privilege but is defined here
 * to avoid circular dependency with sabot/kernel.
 */
public enum Privilege {
  /** Privilege to read data. */
  SELECT,
  /** Privilege to insert data. */
  INSERT,
  /** Privilege to update data. */
  UPDATE,
  /** Privilege to delete data. */
  DELETE,
  /** Privilege to create tables. */
  CREATE_TABLE,
  /** Privilege to create views. */
  CREATE_VIEW,
  /** Privilege to create folders. */
  CREATE_FOLDER,
  /** Privilege to alter objects. */
  ALTER,
  /** Privilege to drop objects. */
  DROP,
  /** Privilege to modify objects. */
  MODIFY,
  /** Privilege to view job history. */
  VIEW_JOB_HISTORY,
  /** Privilege to alter reflections. */
  ALTER_REFLECTION,
  /** Privilege to manage grants. */
  MANAGE_GRANTS,
  /** Privilege for usage. */
  USAGE,
  /** Privilege indicating ownership. */
  OWNERSHIP,
  /** All privileges. */
  ALL
}
