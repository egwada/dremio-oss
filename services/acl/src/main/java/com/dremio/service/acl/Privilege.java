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
 * This enum mirrors com.dremio.exec.planner.sql.parser.SqlGrant.Privilege
 * but is defined here to avoid circular dependency with sabot/kernel.
 */
public enum Privilege {
  SELECT,
  INSERT,
  UPDATE,
  DELETE,
  CREATE_TABLE,
  CREATE_VIEW,
  CREATE_FOLDER,
  ALTER,
  DROP,
  MODIFY,
  VIEW_JOB_HISTORY,
  ALTER_REFLECTION,
  MANAGE_GRANTS,
  USAGE,
  OWNERSHIP,
  ALL
}
