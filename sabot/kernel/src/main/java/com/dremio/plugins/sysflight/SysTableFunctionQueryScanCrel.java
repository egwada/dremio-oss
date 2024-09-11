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
package com.dremio.plugins.sysflight;

import java.util.List;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.type.RelDataType;

public class SysTableFunctionQueryScanCrel extends SysTableFunctionQueryRelBase {

  public SysTableFunctionQueryScanCrel(
      RelOptCluster cluster,
      RelTraitSet traitSet,
      RelDataType rowType,
      SysTableFunctionCatalogMetadata metadata,
      String user) {
    super(cluster, traitSet, rowType, metadata, user);
  }

  @Override
  public RelNode copy(RelTraitSet traitSet, List<RelNode> inputs) {
    return new SysTableFunctionQueryScanCrel(
        getCluster(), getTraitSet(), getRowType(), getMetadata(), getUser());
  }
}
