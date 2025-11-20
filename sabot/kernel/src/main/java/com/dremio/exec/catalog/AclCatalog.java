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
package com.dremio.exec.catalog;

import com.dremio.catalog.exception.UnsupportedForgetTableException;
import com.dremio.catalog.model.CatalogEntityId;
import com.dremio.catalog.model.CatalogEntityKey;
import com.dremio.catalog.model.ResolvedVersionContext;
import com.dremio.catalog.model.VersionContext;
import com.dremio.catalog.model.dataset.TableVersionContext;
import com.dremio.common.concurrent.bulk.BulkRequest;
import com.dremio.common.concurrent.bulk.BulkResponse;
import com.dremio.common.expression.CompleteType;
import com.dremio.connector.metadata.AttributeValue;
import com.dremio.datastore.SearchTypes;
import com.dremio.datastore.api.Document;
import com.dremio.datastore.api.FindByCondition;
import com.dremio.exec.dotfile.View;
import com.dremio.exec.physical.base.ViewOptions;
import com.dremio.exec.physical.base.WriterOptions;
import com.dremio.exec.planner.logical.CreateTableEntry;
import com.dremio.exec.planner.sql.parser.SqlGrant;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.store.ColumnExtendedProperty;
import com.dremio.exec.store.DatasetRetrievalOptions;
import com.dremio.exec.store.NoDefaultBranchException;
import com.dremio.exec.store.PartitionNotFoundException;
import com.dremio.exec.store.ReferenceConflictException;
import com.dremio.exec.store.ReferenceNotFoundException;
import com.dremio.exec.store.StoragePlugin;
import com.dremio.exec.store.dfs.IcebergTableProps;
import com.dremio.service.acl.AuthorizationService;
import com.dremio.service.acl.Privilege;
import com.dremio.service.catalog.Schema;
import com.dremio.service.catalog.SearchQuery;
import com.dremio.service.catalog.Table;
import com.dremio.service.catalog.TableSchema;
import com.dremio.service.namespace.NamespaceAttribute;
import com.dremio.service.namespace.NamespaceException;
import com.dremio.service.namespace.NamespaceKey;
import com.dremio.service.namespace.NamespaceNotFoundException;
import com.dremio.service.namespace.SourceState;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import com.dremio.service.namespace.dataset.proto.DatasetType;
import com.dremio.service.namespace.proto.EntityId;
import com.dremio.service.namespace.proto.NameSpaceContainer;
import com.dremio.service.namespace.source.proto.SourceConfig;
import com.dremio.service.users.SystemUser;
import com.google.common.base.Function;
import java.io.IOException;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nonnull;
import org.apache.arrow.vector.types.pojo.Field;

/** Catalog decorator that enforces ACL-based access control on catalog operations. */
final class AclCatalog implements Catalog {

  private static final org.slf4j.Logger logger =
      org.slf4j.LoggerFactory.getLogger(AclCatalog.class);

  private final MetadataRequestOptions options;
  private final Catalog delegate;
  private final AuthorizationService aclService;

  private AclCatalog(
      MetadataRequestOptions options, Catalog delegate, AuthorizationService aclService) {
    this.options = options;
    this.delegate = delegate;
    this.aclService = aclService;
  }

  private String getCurrentUser() {
    return options.getSchemaConfig().getUserName();
  }

  private void checkPermission(NamespaceKey key, Privilege privilege) {
    if (aclService == null || !aclService.isEnabled()) {
      return; // ACL disabled, allow all access
    }

    String username = getCurrentUser();

    // System users bypass ACL checks
    if (SystemUser.isSystemUserName(username)) {
      logger.debug("Skipping ACL check for system user: {}", username);
      return;
    }

    // Check permission
    boolean allowed = aclService.checkPermission(username, key, privilege);

    if (!allowed) {
      // Use enforcePermission to throw proper UserException
      aclService.enforcePermission(username, key, privilege);
    }
  }

  @Override
  public DremioTable getTableNoResolve(NamespaceKey key) {
    DremioTable table = delegate.getTableNoResolve(key);
    if (table != null) {
      checkPermission(key, Privilege.SELECT);
    }
    return table;
  }

  @Override
  public DremioTable getTableNoResolve(CatalogEntityKey catalogEntityKey) {
    DremioTable table = delegate.getTableNoResolve(catalogEntityKey);
    if (table != null) {
      checkPermission(catalogEntityKey.toNamespaceKey(), Privilege.SELECT);
    }
    return table;
  }

  @Override
  public DremioTable getTableNoColumnCount(NamespaceKey key) {
    DremioTable table = delegate.getTableNoColumnCount(key);
    if (table != null) {
      checkPermission(key, Privilege.SELECT);
    }
    return table;
  }

  @Override
  public void addOrUpdateDataset(NamespaceKey key, DatasetConfig dataset)
      throws NamespaceException {
    checkPermission(key, Privilege.ALTER);
    delegate.addOrUpdateDataset(key, dataset);
  }

  @Override
  public DremioTable getTable(String datasetId) {
    DremioTable table = delegate.getTable(datasetId);
    if (table != null) {
      checkPermission(table.getPath(), Privilege.SELECT);
    }
    return table;
  }

  @Override
  public DremioTable getTable(NamespaceKey key) {
    DremioTable table = delegate.getTable(key);
    if (table != null) {
      checkPermission(key, Privilege.SELECT);
    }
    return table;
  }

  @Override
  public DremioTable getTable(CatalogEntityKey key) {
    DremioTable table = delegate.getTable(key);
    if (table != null) {
      checkPermission(key.toNamespaceKey(), Privilege.SELECT);
    }
    return table;
  }

  @Override
  public DremioTable getTableForQuery(NamespaceKey key) {
    DremioTable table = delegate.getTableForQuery(key);
    if (table != null) {
      checkPermission(key, Privilege.SELECT);
    }
    return table;
  }

  @Override
  public String getDatasetId(NamespaceKey key) {
    // No permission check for getting ID - actual data access will be checked
    return delegate.getDatasetId(key);
  }

  @Override
  public DremioTable getTableSnapshotForQuery(CatalogEntityKey catalogEntityKey) {
    DremioTable table = delegate.getTableSnapshotForQuery(catalogEntityKey);
    if (table != null) {
      checkPermission(catalogEntityKey.toNamespaceKey(), Privilege.SELECT);
    }
    return table;
  }

  @Override
  public DatasetType getDatasetType(CatalogEntityKey key) {
    // No permission check for dataset type lookup
    return delegate.getDatasetType(key);
  }

  @Override
  public DremioTable getTableSnapshot(CatalogEntityKey catalogEntityKey) {
    DremioTable table = delegate.getTableSnapshot(catalogEntityKey);
    if (table != null) {
      checkPermission(catalogEntityKey.toNamespaceKey(), Privilege.SELECT);
    }
    return table;
  }

  @Nonnull
  @Override
  public Optional<TableMetadataVerifyResult> verifyTableMetadata(
      CatalogEntityKey key, TableMetadataVerifyRequest metadataVerifyRequest) {
    return delegate.verifyTableMetadata(key, metadataVerifyRequest);
  }

  @Override
  public BulkResponse<NamespaceKey, Optional<DremioTable>> bulkGetTables(
      BulkRequest<NamespaceKey> keys) {
    // Bulk operations - check permissions for each table returned
    BulkResponse<NamespaceKey, Optional<DremioTable>> response = delegate.bulkGetTables(keys);
    // TODO: Add ACL checks for bulk operations
    return response;
  }

  @Override
  public BulkResponse<NamespaceKey, Optional<DremioTable>> bulkGetTablesForQuery(
      BulkRequest<NamespaceKey> keys) {
    // Bulk operations - check permissions for each table returned
    BulkResponse<NamespaceKey, Optional<DremioTable>> response =
        delegate.bulkGetTablesForQuery(keys);
    // TODO: Add ACL checks for bulk operations
    return response;
  }

  @Override
  public Iterable<DremioTable> getAllRequestedTables() {
    return delegate.getAllRequestedTables();
  }

  @Override
  public NamespaceKey resolveSingle(NamespaceKey key) {
    return delegate.resolveSingle(key);
  }

  @Override
  public boolean containerExists(CatalogEntityKey path) {
    return delegate.containerExists(path);
  }

  @Override
  public NamespaceKey resolveToDefault(NamespaceKey key) {
    return delegate.resolveToDefault(key);
  }

  @Override
  public void createEmptyTable(
      NamespaceKey key, BatchSchema batchSchema, WriterOptions writerOptions) {
    checkPermission(key, Privilege.CREATE_TABLE);
    delegate.createEmptyTable(key, batchSchema, writerOptions);
  }

  @Override
  public CreateTableEntry createNewTable(
      NamespaceKey key,
      IcebergTableProps icebergTableProps,
      WriterOptions writerOptions,
      Map<String, Object> storageOptions) {
    checkPermission(key, Privilege.CREATE_TABLE);
    return delegate.createNewTable(key, icebergTableProps, writerOptions, storageOptions);
  }

  @Override
  public CreateTableEntry createNewTable(
      NamespaceKey key,
      IcebergTableProps icebergTableProps,
      WriterOptions writerOptions,
      Map<String, Object> storageOptions,
      boolean isResultsTable) {
    checkPermission(key, Privilege.CREATE_TABLE);
    return delegate.createNewTable(
        key, icebergTableProps, writerOptions, storageOptions, isResultsTable);
  }

  @Override
  public void createView(
      NamespaceKey key, View view, ViewOptions viewOptions, NamespaceAttribute... attributes)
      throws IOException {
    checkPermission(key, Privilege.CREATE_VIEW);
    delegate.createView(key, view, viewOptions, attributes);
  }

  @Override
  public void updateView(
      NamespaceKey key, View view, ViewOptions viewOptions, NamespaceAttribute... attributes)
      throws IOException {
    checkPermission(key, Privilege.ALTER);
    delegate.updateView(key, view, viewOptions, attributes);
  }

  @Override
  public void dropView(NamespaceKey key, ViewOptions viewOptions) throws IOException {
    checkPermission(key, Privilege.DROP);
    delegate.dropView(key, viewOptions);
  }

  @Override
  public void dropTable(NamespaceKey key, TableMutationOptions tableMutationOptions) {
    checkPermission(key, Privilege.DROP);
    delegate.dropTable(key, tableMutationOptions);
  }

  @Override
  public void alterTable(
      NamespaceKey key,
      DatasetConfig datasetConfig,
      AlterTableOption alterTableOption,
      TableMutationOptions tableMutationOptions) {
    checkPermission(key, Privilege.ALTER);
    delegate.alterTable(key, datasetConfig, alterTableOption, tableMutationOptions);
  }

  @Override
  public void forgetTable(NamespaceKey key) throws UnsupportedForgetTableException {
    checkPermission(key, Privilege.DROP);
    delegate.forgetTable(key);
  }

  @Override
  public void truncateTable(NamespaceKey key, TableMutationOptions tableMutationOptions) {
    checkPermission(key, Privilege.DELETE);
    delegate.truncateTable(key, tableMutationOptions);
  }

  @Override
  public void rollbackTable(
      NamespaceKey key,
      DatasetConfig datasetConfig,
      RollbackOption rollbackOption,
      TableMutationOptions tableMutationOptions) {
    checkPermission(key, Privilege.ALTER);
    delegate.rollbackTable(key, datasetConfig, rollbackOption, tableMutationOptions);
  }

  @Override
  public void addColumns(
      NamespaceKey table,
      DatasetConfig datasetConfig,
      List<Field> colsToAdd,
      TableMutationOptions tableMutationOptions) {
    checkPermission(table, Privilege.ALTER);
    delegate.addColumns(table, datasetConfig, colsToAdd, tableMutationOptions);
  }

  @Override
  public void dropColumn(
      NamespaceKey table,
      DatasetConfig datasetConfig,
      String columnToDrop,
      TableMutationOptions tableMutationOptions) {
    checkPermission(table, Privilege.ALTER);
    delegate.dropColumn(table, datasetConfig, columnToDrop, tableMutationOptions);
  }

  @Override
  public void changeColumn(
      NamespaceKey table,
      DatasetConfig datasetConfig,
      String columnToChange,
      Field fieldFromSqlColDeclaration,
      TableMutationOptions tableMutationOptions) {
    checkPermission(table, Privilege.ALTER);
    delegate.changeColumn(
        table, datasetConfig, columnToChange, fieldFromSqlColDeclaration, tableMutationOptions);
  }

  @Override
  public void createDataset(
      NamespaceKey key, Function<DatasetConfig, DatasetConfig> datasetMutator) {
    checkPermission(key, Privilege.CREATE_TABLE);
    delegate.createDataset(key, datasetMutator);
  }

  @Override
  public UpdateStatus refreshDataset(NamespaceKey key, DatasetRetrievalOptions retrievalOptions) {
    return delegate.refreshDataset(key, retrievalOptions);
  }

  @Override
  public UpdateStatus refreshDataset(
      NamespaceKey key,
      DatasetRetrievalOptions retrievalOptions,
      boolean isPrivilegeValidationNeeded) {
    return delegate.refreshDataset(key, retrievalOptions, isPrivilegeValidationNeeded);
  }

  @Override
  public SourceState refreshSourceStatus(NamespaceKey key) throws Exception {
    return delegate.refreshSourceStatus(key);
  }

  @Override
  public Iterable<String> getSubPartitions(
      NamespaceKey key, List<String> partitionColumns, List<String> partitionValues)
      throws PartitionNotFoundException {
    checkPermission(key, Privilege.SELECT);
    return delegate.getSubPartitions(key, partitionColumns, partitionValues);
  }

  @Override
  public boolean createOrUpdateDataset(
      NamespaceKey source,
      NamespaceKey datasetPath,
      DatasetConfig datasetConfig,
      NamespaceAttribute... attributes)
      throws NamespaceException {
    checkPermission(datasetPath, Privilege.ALTER);
    return delegate.createOrUpdateDataset(source, datasetPath, datasetConfig, attributes);
  }

  @Override
  public void updateDatasetSchema(NamespaceKey datasetKey, BatchSchema newSchema) {
    checkPermission(datasetKey, Privilege.ALTER);
    delegate.updateDatasetSchema(datasetKey, newSchema);
  }

  @Override
  public void updateDatasetField(
      NamespaceKey datasetKey, String originField, CompleteType fieldSchema) {
    checkPermission(datasetKey, Privilege.ALTER);
    delegate.updateDatasetField(datasetKey, originField, fieldSchema);
  }

  @Override
  public <T extends StoragePlugin> T getSource(String name) {
    return delegate.getSource(name);
  }

  @Override
  public <T extends StoragePlugin> T getSource(String name, boolean skipStateCheck) {
    return delegate.getSource(name, skipStateCheck);
  }

  @Override
  public void createSource(SourceConfig config, NamespaceAttribute... attributes) {
    delegate.createSource(config, attributes);
  }

  @Override
  public void updateSource(SourceConfig config, NamespaceAttribute... attributes) {
    delegate.updateSource(config, attributes);
  }

  @Override
  public void deleteSource(SourceConfig config) {
    delegate.deleteSource(config);
  }

  @Override
  public Iterable<String> listSchemas(NamespaceKey path) {
    return delegate.listSchemas(path);
  }

  @Override
  public Iterable<Table> listDatasets(NamespaceKey path) {
    return delegate.listDatasets(path);
  }

  @Override
  public Collection<org.apache.calcite.schema.Function> getFunctions(
      CatalogEntityKey path, FunctionType functionType) {
    return delegate.getFunctions(path, functionType);
  }

  @Override
  public NamespaceKey getDefaultSchema() {
    return delegate.getDefaultSchema();
  }

  @Override
  public Catalog resolveCatalog(CatalogIdentity subject) {
    return wrapIfNeeded(
        options.cloneWith(
            subject, options.getSchemaConfig().getDefaultSchema(), options.checkValidity()),
        delegate.resolveCatalog(subject));
  }

  @Override
  public Catalog resolveCatalog(NamespaceKey newDefaultSchema) {
    return wrapIfNeeded(
        options.cloneWith(
            options.getSchemaConfig().getAuthContext().getSubject(),
            newDefaultSchema,
            options.checkValidity()),
        delegate.resolveCatalog(newDefaultSchema));
  }

  @Override
  public Catalog resolveCatalog(Map<String, VersionContext> sourceVersionMapping) {
    return wrapIfNeeded(
        options.cloneWith(sourceVersionMapping), delegate.resolveCatalog(sourceVersionMapping));
  }

  @Override
  public Catalog resolveCatalogResetContext(String sourceName, VersionContext versionContext) {
    return wrapIfNeeded(
        options.cloneWith(sourceName, versionContext),
        delegate.resolveCatalogResetContext(sourceName, versionContext));
  }

  /**
   * Wraps the given catalog with ACL checking if the ACL service is available and enabled.
   *
   * @param options metadata request options
   * @param delegate delegate catalog
   * @return wrapped catalog if ACL is enabled, otherwise the delegate
   */
  public static Catalog wrapIfNeeded(
      MetadataRequestOptions options, Catalog delegate, AuthorizationService aclService) {
    if (aclService == null || !aclService.isEnabled()) {
      logger.debug("ACL service not available or disabled, skipping ACL catalog wrapper");
      return delegate;
    }

    // Skip ACL for system users
    if (SystemUser.isSystemUserName(options.getSchemaConfig().getUserName())) {
      logger.debug("System user detected, skipping ACL catalog wrapper");
      return delegate;
    }

    logger.debug(
        "Wrapping catalog with ACL enforcement for user: {}",
        options.getSchemaConfig().getUserName());
    return new AclCatalog(options, delegate, aclService);
  }

  private Catalog wrapIfNeeded(MetadataRequestOptions newOptions, Catalog newDelegate) {
    return wrapIfNeeded(newOptions, newDelegate, aclService);
  }

  @Override
  public boolean alterDataset(
      final CatalogEntityKey catalogEntityKey, final Map<String, AttributeValue> attributes) {
    checkPermission(catalogEntityKey.toNamespaceKey(), Privilege.ALTER);
    return delegate.alterDataset(catalogEntityKey, attributes);
  }

  @Override
  public boolean alterColumnOption(
      final NamespaceKey key,
      String columnToChange,
      final String attributeName,
      final AttributeValue attributeValue) {
    checkPermission(key, Privilege.ALTER);
    return delegate.alterColumnOption(key, columnToChange, attributeName, attributeValue);
  }

  @Override
  public void addPrimaryKey(
      NamespaceKey namespaceKey, List<String> columns, VersionContext statementSourceVersion) {
    checkPermission(namespaceKey, Privilege.ALTER);
    delegate.addPrimaryKey(namespaceKey, columns, statementSourceVersion);
  }

  @Override
  public void dropPrimaryKey(NamespaceKey namespaceKey, VersionContext statementVersion) {
    checkPermission(namespaceKey, Privilege.ALTER);
    delegate.dropPrimaryKey(namespaceKey, statementVersion);
  }

  @Override
  public List<String> getPrimaryKey(NamespaceKey namespaceKey) {
    return delegate.getPrimaryKey(namespaceKey);
  }

  @Override
  public boolean toggleSchemaLearning(NamespaceKey table, boolean enableSchemaLearning) {
    checkPermission(table, Privilege.ALTER);
    return delegate.toggleSchemaLearning(table, enableSchemaLearning);
  }

  @Override
  public void alterSortOrder(
      NamespaceKey table,
      DatasetConfig datasetConfig,
      BatchSchema schema,
      List<String> sortOrderColumns,
      TableMutationOptions tableMutationOptions) {
    checkPermission(table, Privilege.ALTER);
    delegate.alterSortOrder(table, datasetConfig, schema, sortOrderColumns, tableMutationOptions);
  }

  @Override
  public void updateTableProperties(
      NamespaceKey table,
      DatasetConfig datasetConfig,
      BatchSchema schema,
      Map<String, String> tableProperties,
      TableMutationOptions tableMutationOptions,
      boolean isRemove) {
    checkPermission(table, Privilege.ALTER);
    delegate.updateTableProperties(
        table, datasetConfig, schema, tableProperties, tableMutationOptions, isRemove);
  }

  @Override
  public Iterator<com.dremio.service.catalog.Catalog> listCatalogs(SearchQuery searchQuery) {
    return delegate.listCatalogs(searchQuery);
  }

  @Override
  public Iterator<Schema> listSchemata(SearchQuery searchQuery) {
    return delegate.listSchemata(searchQuery);
  }

  @Override
  public Iterator<Table> listTables(SearchQuery searchQuery) {
    return delegate.listTables(searchQuery);
  }

  @Override
  public Iterator<com.dremio.service.catalog.View> listViews(SearchQuery searchQuery) {
    return delegate.listViews(searchQuery);
  }

  @Override
  public Iterator<TableSchema> listTableSchemata(SearchQuery searchQuery) {
    return delegate.listTableSchemata(searchQuery);
  }

  @Override
  public Map<String, List<ColumnExtendedProperty>> getColumnExtendedProperties(DremioTable table) {
    return delegate.getColumnExtendedProperties(table);
  }

  @Override
  public Catalog visit(java.util.function.Function<Catalog, Catalog> catalogRewrite) {
    Catalog newDelegate = delegate.visit(catalogRewrite);
    return catalogRewrite.apply(new AclCatalog(options, newDelegate, aclService));
  }

  @Override
  public ResolvedVersionContext resolveVersionContext(
      String sourceName, VersionContext versionContext)
      throws ReferenceNotFoundException, NoDefaultBranchException, ReferenceConflictException {
    return delegate.resolveVersionContext(sourceName, versionContext);
  }

  @Override
  public void validatePrivilege(NamespaceKey key, SqlGrant.Privilege privilege) {
    delegate.validatePrivilege(key, privilege);
  }

  @Override
  public void validateOwnership(CatalogEntityKey key) {
    delegate.validateOwnership(key);
  }

  @Override
  public void invalidateNamespaceCache(final NamespaceKey key) {
    delegate.invalidateNamespaceCache(key);
  }

  @Override
  public MetadataRequestOptions getMetadataRequestOptions() {
    return options;
  }

  @Override
  public void clearDatasetCache(NamespaceKey dataset, TableVersionContext context) {
    delegate.clearDatasetCache(dataset, context);
  }

  @Override
  public void clearPermissionCache(String sourceName) {
    delegate.clearPermissionCache(sourceName);
  }

  //// Begin: NamespacePassthrough Methods
  @Override
  public boolean existsById(CatalogEntityId id) {
    return delegate.existsById(id);
  }

  @Override
  public List<NameSpaceContainer> getEntities(List<NamespaceKey> lookupKeys) {
    return delegate.getEntities(lookupKeys);
  }

  @Override
  public void addOrUpdateDataset(
      NamespaceKey datasetPath, DatasetConfig dataset, NamespaceAttribute... attributes)
      throws NamespaceException {
    checkPermission(datasetPath, Privilege.ALTER);
    delegate.addOrUpdateDataset(datasetPath, dataset, attributes);
  }

  @Override
  public DatasetConfig renameDataset(NamespaceKey oldDatasetPath, NamespaceKey newDatasetPath)
      throws NamespaceException {
    checkPermission(oldDatasetPath, Privilege.ALTER);
    checkPermission(newDatasetPath, Privilege.CREATE_TABLE);
    return delegate.renameDataset(oldDatasetPath, newDatasetPath);
  }

  @Override
  public String getEntityIdByPath(NamespaceKey entityPath) throws NamespaceNotFoundException {
    return delegate.getEntityIdByPath(entityPath);
  }

  @Override
  public NameSpaceContainer getEntityByPath(NamespaceKey entityPath) throws NamespaceException {
    return delegate.getEntityByPath(entityPath);
  }

  @Override
  public DatasetConfig getDataset(NamespaceKey datasetPath) throws NamespaceException {
    return delegate.getDataset(datasetPath);
  }

  @Override
  public Iterable<NamespaceKey> getAllDatasets(final NamespaceKey parent)
      throws NamespaceException {
    return delegate.getAllDatasets(parent);
  }

  @Override
  public void deleteDataset(
      NamespaceKey datasetPath, String version, NamespaceAttribute... attributes)
      throws NamespaceException {
    checkPermission(datasetPath, Privilege.DROP);
    delegate.deleteDataset(datasetPath, version, attributes);
  }

  @Override
  public List<Integer> getCounts(SearchTypes.SearchQuery... queries) throws NamespaceException {
    return delegate.getCounts(queries);
  }

  @Override
  public Iterable<Document<NamespaceKey, NameSpaceContainer>> find(FindByCondition condition) {
    return delegate.find(condition);
  }

  @Override
  public List<NameSpaceContainer> getEntitiesByIds(List<EntityId> ids) {
    return delegate.getEntitiesByIds(ids);
  }

  @Override
  public List<SourceConfig> getSourceConfigs() {
    return delegate.getSourceConfigs();
  }
  //// End: NamespacePassthrough Methods
}
