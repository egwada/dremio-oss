/*
 * Copyright (C) 2017-2018 Dremio Corporation
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
package com.dremio.exec.store.parquet.columnreaders;

import java.io.IOException;

import org.apache.arrow.vector.ValueVector;
import org.apache.parquet.column.ColumnDescriptor;
import org.apache.parquet.format.Encoding;
import org.apache.parquet.format.SchemaElement;
import org.apache.parquet.hadoop.metadata.ColumnChunkMetaData;
import org.apache.parquet.io.api.Binary;

import com.dremio.common.exceptions.ExecutionSetupException;

public abstract class VarLengthColumn<V extends ValueVector> extends ColumnReader<V> {
  static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(VarLengthColumn.class);

  Binary currDictVal;

  VarLengthColumn(DeprecatedParquetVectorizedReader parentReader, int allocateSize, ColumnDescriptor descriptor,
                  ColumnChunkMetaData columnChunkMetaData, boolean fixedLength, V v,
                  SchemaElement schemaElement) throws ExecutionSetupException {
    super(parentReader, allocateSize, descriptor, columnChunkMetaData, fixedLength, v, schemaElement);
      if (columnChunkMetaData.getEncodings().contains(Encoding.PLAIN_DICTIONARY)) {
        usingDictionary = true;
      }
      else {
        usingDictionary = false;
      }
  }

  @Override
  protected boolean processPageData(int recordsToReadInThisPass) throws IOException {
    return readAndStoreValueSizeInformation();
  }

  @Override
  public void reset() {
    super.reset();
    pageReader.valuesReadyToRead = 0;
  }

  protected abstract boolean readAndStoreValueSizeInformation() throws IOException;

  public abstract boolean skipReadyToReadPositionUpdate();

}
