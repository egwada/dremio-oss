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
package com.dremio.exec.store.iceberg.dremioudf.core.udf;

import static org.apache.iceberg.TableProperties.COMMIT_MAX_RETRY_WAIT_MS;
import static org.apache.iceberg.TableProperties.COMMIT_MAX_RETRY_WAIT_MS_DEFAULT;
import static org.apache.iceberg.TableProperties.COMMIT_MIN_RETRY_WAIT_MS;
import static org.apache.iceberg.TableProperties.COMMIT_MIN_RETRY_WAIT_MS_DEFAULT;
import static org.apache.iceberg.TableProperties.COMMIT_NUM_RETRIES;
import static org.apache.iceberg.TableProperties.COMMIT_NUM_RETRIES_DEFAULT;
import static org.apache.iceberg.TableProperties.COMMIT_TOTAL_RETRY_TIME_MS;
import static org.apache.iceberg.TableProperties.COMMIT_TOTAL_RETRY_TIME_MS_DEFAULT;

import com.dremio.exec.store.iceberg.dremioudf.api.udf.UpdateUdfProperties;
import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import java.util.Map;
import java.util.Set;
import org.apache.iceberg.exceptions.CommitFailedException;
import org.apache.iceberg.util.PropertyUtil;
import org.apache.iceberg.util.Tasks;

class PropertiesUpdate implements UpdateUdfProperties {
  private final UdfOperations ops;
  private final Map<String, String> updates = Maps.newHashMap();
  private final Set<String> removals = Sets.newHashSet();
  private UdfMetadata base;

  PropertiesUpdate(UdfOperations ops) {
    this.ops = ops;
    this.base = ops.current();
  }

  @Override
  public Map<String, String> apply() {
    return internalApply().properties();
  }

  private UdfMetadata internalApply() {
    this.base = ops.refresh();

    return UdfMetadata.buildFrom(base).setProperties(updates).removeProperties(removals).build();
  }

  @Override
  public void commit() {
    Tasks.foreach(ops)
        .retry(
            PropertyUtil.propertyAsInt(
                base.properties(), COMMIT_NUM_RETRIES, COMMIT_NUM_RETRIES_DEFAULT))
        .exponentialBackoff(
            PropertyUtil.propertyAsInt(
                base.properties(), COMMIT_MIN_RETRY_WAIT_MS, COMMIT_MIN_RETRY_WAIT_MS_DEFAULT),
            PropertyUtil.propertyAsInt(
                base.properties(), COMMIT_MAX_RETRY_WAIT_MS, COMMIT_MAX_RETRY_WAIT_MS_DEFAULT),
            PropertyUtil.propertyAsInt(
                base.properties(), COMMIT_TOTAL_RETRY_TIME_MS, COMMIT_TOTAL_RETRY_TIME_MS_DEFAULT),
            2.0 /* exponential */)
        .onlyRetryOn(CommitFailedException.class)
        .run(taskOps -> taskOps.commit(base, internalApply()));
  }

  @Override
  public UpdateUdfProperties set(String key, String value) {
    Preconditions.checkArgument(null != key, "Invalid key: null");
    Preconditions.checkArgument(null != value, "Invalid value: null");
    Preconditions.checkArgument(
        !removals.contains(key), "Cannot remove and update the same key: %s", key);

    updates.put(key, value);
    return this;
  }

  @Override
  public UpdateUdfProperties remove(String key) {
    Preconditions.checkArgument(null != key, "Invalid key: null");
    Preconditions.checkArgument(
        !updates.containsKey(key), "Cannot remove and update the same key: %s", key);

    removals.add(key);
    return this;
  }
}
