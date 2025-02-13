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
package com.dremio.exec.catalog;

import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

import com.dremio.common.collections.Tuple;
import com.dremio.connector.ConnectorException;
import com.dremio.connector.metadata.BytesOutput;
import com.dremio.connector.metadata.DatasetHandle;
import com.dremio.connector.metadata.DatasetHandleListing;
import com.dremio.connector.metadata.DatasetMetadata;
import com.dremio.connector.metadata.DatasetNotFoundException;
import com.dremio.connector.metadata.GetDatasetOption;
import com.dremio.connector.metadata.SourceMetadata;
import com.dremio.connector.metadata.extensions.SupportsListingDatasets;
import com.dremio.connector.metadata.extensions.SupportsReadSignature;
import com.dremio.connector.metadata.extensions.SupportsReadSignature.MetadataValidity;
import com.dremio.exec.store.DatasetRetrievalOptions;
import com.dremio.service.namespace.NamespaceException;
import com.dremio.service.namespace.NamespaceKey;
import com.dremio.service.namespace.NamespaceNotFoundException;
import com.dremio.service.namespace.NamespaceService;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import com.dremio.service.namespace.source.proto.MetadataPolicy;
import com.dremio.service.namespace.source.proto.UpdateMode;
import com.dremio.service.namespace.space.proto.FolderConfig;
import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import com.google.common.collect.Sets;

import io.protostuff.ByteString;

/**
 * Synchronizes metadata from the connector to the namespace.
 */
public class MetadataSynchronizer {
  private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(MetadataSynchronizer.class);

  private static final int NUM_RETRIES = 1;

  private final NamespaceService systemNamespace;
  private final NamespaceKey sourceKey;
  private final SourceMetadata sourceMetadata;
  private final DatasetSaver saver;
  private final BooleanSupplier cancelWork;
  private final DatasetRetrievalOptions options;

  private final UpdateMode updateMode;
  private final Set<NamespaceKey> ancestorsToKeep;
  private final List<Tuple<String, String>> failedDatasets;

  private Set<NamespaceKey> existingDatasets;

  MetadataSynchronizer(
      NamespaceService systemNamespace,
      NamespaceKey sourceKey,
      SourceMetadata sourceMetadata,
      MetadataPolicy metadataPolicy,
      DatasetSaver saver,
      BooleanSupplier cancelWork,
      DatasetRetrievalOptions options
  ) {
    this.systemNamespace = Preconditions.checkNotNull(systemNamespace);
    this.sourceKey = Preconditions.checkNotNull(sourceKey);
    this.sourceMetadata = Preconditions.checkNotNull(sourceMetadata);
    this.saver = saver;
    this.cancelWork = cancelWork;
    this.options = options;

    this.updateMode = metadataPolicy.getDatasetUpdateMode();
    this.ancestorsToKeep = new HashSet<>();
    this.failedDatasets = new ArrayList<>();
  }

  /**
   * Set up the synchronizer.
   */
  public void setup() throws NamespaceException {
    Preconditions.checkState(updateMode == UpdateMode.PREFETCH || updateMode == UpdateMode.PREFETCH_QUERIED,
        "only PREFETCH and PREFETCH_QUERIED are supported");

    existingDatasets = Sets.newHashSet(systemNamespace.getAllDatasets(sourceKey));

    logger.debug("Source '{}' sync setup ({} datasets)", sourceKey, existingDatasets.size());
    logger.trace("Source '{}' has datasets: '{}'", sourceKey, existingDatasets);
  }

  /**
   * Perform synchronization.
   *
   * @return status
   */
  public SyncStatus go() {
    boolean refreshed = false;

    final Stopwatch stopwatch = Stopwatch.createStarted();
    try {
      logger.debug("Source '{}' sync started", sourceKey);

      // 1. update datasets in namespace with the ones in source
      final SyncStatus syncStatus = synchronizeDatasets();
      if (syncStatus.interrupted) {
        // if interrupted, skip next steps to avoid deleting valid entries in existingDatasets
        return syncStatus;
      }
      refreshed = syncStatus.refreshed;

      // remaining entries in existingDatasets must be deleted

      // 2. delete all the folders that have no references
      refreshed |= deleteOrphanFolders();

      // 3. delete all the orphaned datasets
      refreshed |= deleteOrphanedDatasets();
    } catch (Exception e) {
      logger.warn("Source '{}' sync failed unexpectedly. Will try again later", sourceKey, e);
    } finally {
      if (!failedDatasets.isEmpty()) {
        logger.warn("Source '{}' sync failed for datasets:\n{}",
            failedDatasets.stream()
                .map(tuple -> "\t" + tuple.first + ": " + tuple.second)
                .collect(Collectors.joining("\n"))
        );
      }
      if (logger.isDebugEnabled()) {
        logger.debug("Source '{}' sync ended. Took {} milliseconds",
            sourceKey, stopwatch.elapsed(TimeUnit.MILLISECONDS));
      }
    }

    return new SyncStatus(refreshed, cancelWork.getAsBoolean());
  }

  private DatasetHandleListing getDatasetHandleListing(GetDatasetOption... options) throws ConnectorException {
    if (sourceMetadata instanceof SupportsListingDatasets) {
      return ((SupportsListingDatasets) sourceMetadata).listDatasetHandles(options);
    }

    return new NamespaceListing(systemNamespace, sourceKey, sourceMetadata, this.options);
  }

  /**
   * Brings the namespace up to date by gathering metadata from the source about existing and new datasets.
   *
   * @return if the namespace was updated
   * @throws NamespaceException if it cannot be handled
   */
  private SyncStatus synchronizeDatasets() throws NamespaceException, ConnectorException {
    boolean refreshed = false;

    logger.trace("Source '{}' syncing datasets", sourceKey);
    try (DatasetHandleListing datasetListing = getDatasetHandleListing(options.asGetDatasetOptions(null))) {
      final Iterator<? extends DatasetHandle> iterator = datasetListing.iterator();
      while (true) {
        if (cancelWork.getAsBoolean()) {
          logger.info("Source '{}' sync aborted. At least {} more pending updates", sourceKey,
              existingDatasets.size());
          return new SyncStatus(refreshed, true);
        }

        if (!iterator.hasNext()) {
          break;
        }

        final DatasetHandle handle = iterator.next();
        final NamespaceKey datasetKey = MetadataObjectsUtils.toNamespaceKey(handle.getDatasetPath());
        final boolean existing = existingDatasets.remove(datasetKey);
        if (logger.isTraceEnabled()) {
          logger.trace("Dataset '{}' sync started ({})", datasetKey, existing ? "existing" : "new");
        }

        if (existing) {
          addAncestors(datasetKey, ancestorsToKeep);
          refreshed |= handleExistingDataset(datasetKey, handle);
        } else {
          refreshed |= handleNewDataset(datasetKey, handle);
        }
      }
    }

    return new SyncStatus(refreshed, false);
  }

  /**
   * Handle metadata sync for the given existing dataset.
   *
   * @param datasetKey dataset key
   * @param handle     dataset handle
   * @return true iff dataset metadata was updated
   */
  private boolean handleExistingDataset(NamespaceKey datasetKey, DatasetHandle handle) {
    boolean refreshed = false;
    int tryCount = 0;
    while (true) {
      if (tryCount++ > NUM_RETRIES) {
        logger.debug("Dataset '{}' sync failed {} times (CME). Will retry next sync", datasetKey, NUM_RETRIES);
        break;
      }

      final Stopwatch stopwatch = Stopwatch.createStarted();
      try {
        refreshed = tryHandleExistingDataset(datasetKey, handle);
        break;
      } catch (ConcurrentModificationException ignored) {
        // retry
        // continue;
      } catch (DatasetNotFoundException e) {
        // race condition: metadata will be removed from catalog in next sync
        logger.debug("Dataset '{}' is no longer valid, skipping sync", datasetKey, e);
        break;
      } catch (Exception e) {
        // TODO: this should not be an Exception. Once exception handling is defined, change this. This is unfortunately
        //  the current behavior.
        logger.trace("Dataset '{}' sync failed unexpectedly. Will retry next sync", datasetKey, e);
        failedDatasets.add(Tuple.of(datasetKey.getSchemaPath(), e.getMessage()));
        break;
      } finally {
        if (logger.isDebugEnabled()) {
          logger.debug("Dataset '{}' sync took {} milliseconds",
              datasetKey, stopwatch.elapsed(TimeUnit.MILLISECONDS));
        }
      }
    }

    return refreshed;
  }

  /**
   * Try handling metadata sync for the given existing dataset.
   *
   * @param datasetKey    dataset key
   * @param datasetHandle dataset handle
   * @return true iff dataset metadata was updated
   * @throws NamespaceException if it cannot be handled
   */
  private boolean tryHandleExistingDataset(NamespaceKey datasetKey, DatasetHandle datasetHandle)
      throws NamespaceException, ConnectorException {
    // invariant: only metadata attributes of currentConfig are overwritten, and then the same currentConfig is saved,
    // so the rest of the attributes are as is; so CME is handled by retrying this entire block

    final DatasetConfig currentConfig;
    try {
      currentConfig = systemNamespace.getDataset(datasetKey);
    } catch (NamespaceNotFoundException ignored) {
      // race condition
      logger.debug("Dataset '{}' no longer in namespace, skipping", datasetKey);

      return false;
    }

    final boolean isExtended = currentConfig.getReadDefinition() != null;
    if (updateMode == UpdateMode.PREFETCH_QUERIED && !isExtended) {
      // this run only refreshes, and not create new entries

      logger.trace("Dataset '{}' does not have extended attributes, skipping", datasetKey);
      return false;
    }

    if (isExtended && sourceMetadata instanceof SupportsReadSignature) {
      final SupportsReadSignature supportsReadSignature = (SupportsReadSignature) sourceMetadata;
      final DatasetMetadata currentExtended = new DatasetMetadataAdapter(currentConfig);

      final ByteString readSignature = currentConfig.getReadDefinition().getReadSignature();
      final MetadataValidity metadataValidity = supportsReadSignature.validateMetadata(
          readSignature == null ? BytesOutput.NONE : os -> ByteString.writeTo(os, readSignature),
          datasetHandle, currentExtended);

      if (metadataValidity == MetadataValidity.VALID) {
        logger.trace("Dataset '{}' metadata is valid, skipping", datasetKey);
        return false;
      }
    }

    saver.save(currentConfig, datasetHandle, sourceMetadata, false, options);
    logger.trace("Dataset '{}' metadata saved to namespace", datasetKey);
    return true;
  }

  /**
   * Handle new dataset based on the metadata policy.
   *
   * @param datasetKey dataset key
   * @param handle     dataset handle
   * @return true iff dataset metadata was added
   * @throws NamespaceException if it cannot be handled
   */
  private boolean handleNewDataset(NamespaceKey datasetKey, DatasetHandle handle)
      throws NamespaceException {
    switch (updateMode) {

    case PREFETCH:
      // this mode will soon be deprecated, for now save, perform name sync

      // fall-through

    case PREFETCH_QUERIED: {
      final DatasetConfig newConfig = MetadataObjectsUtils.newShallowConfig(handle);
      try {
        systemNamespace.addOrUpdateDataset(datasetKey, newConfig);
      } catch (ConcurrentModificationException ignored) {
        // race condition
        logger.debug("Dataset '{}' add failed (CME)", datasetKey);
      }
      return true;
    }

    default:
      throw new IllegalStateException("unknown dataset update mode: " + updateMode);
    }
  }

  /**
   * Delete orphan folders. These are folders that are no longer contain datasets.
   *
   * @return true iff any folders were deleted
   */
  private boolean deleteOrphanFolders() {
    boolean deleted = false;

    logger.trace("Source '{}' deleting orphan folders", sourceKey);
    for (NamespaceKey toBeDeleted : existingDatasets) {
      if (cancelWork.getAsBoolean()) {
        logger.info("Source '{}' sync aborted. At least {} more pending updates", sourceKey,
            existingDatasets.size());
        return deleted;
      }

      final Iterator<NamespaceKey> ancestors = getAncestors(toBeDeleted);

      while (ancestors.hasNext()) {
        final NamespaceKey ancestorKey = ancestors.next();
        if (ancestorsToKeep.contains(ancestorKey)) {
          continue;
        }

        try {
          final FolderConfig folderConfig = systemNamespace.getFolder(ancestorKey);
          systemNamespace.deleteFolder(ancestorKey, folderConfig.getTag());
          logger.trace("Folder '{}' deleted", ancestorKey);
          deleted = true;
        } catch (NamespaceNotFoundException ignored) {
          // either race condition, or ancestorKey is not a folder
          logger.trace("Folder '{}' not found", ancestorKey);
        } catch (NamespaceException ex) {
          logger.debug("Folder '{}' delete failed", ancestorKey, ex);
        }
      }
    }

    return deleted;
  }

  /**
   * Deleted orphan datasets. These are datasets that are no longer present in the source.
   *
   * @return true iff any orphan datasets were deleted
   */
  private boolean deleteOrphanedDatasets() {
    if (!options.deleteUnavailableDatasets()) {
      logger.debug("Source '{}' has {} unavailable datasets, but not deleted: {}", existingDatasets.size(),
          existingDatasets);
      return false;
    }

    boolean deleted = false;

    for (NamespaceKey toBeDeleted : existingDatasets) {
      if (cancelWork.getAsBoolean()) {
        logger.info("Source '{}' sync aborted. At least {} more pending updates", sourceKey,
            existingDatasets.size());
        return deleted;
      }

      final DatasetConfig datasetConfig;
      try {
        datasetConfig = systemNamespace.getDataset(toBeDeleted);
        systemNamespace.deleteDataset(toBeDeleted, datasetConfig.getTag());
        deleted = true;
        logger.trace("Dataset '{}' deleted", toBeDeleted);
      } catch (NamespaceNotFoundException ignored) {
        // race condition
        logger.debug("Dataset '{}' not found", toBeDeleted);
        // continue;
      } catch (NamespaceException e) {
        logger.trace("Dataset '{}' to be deleted, but lookup failed", toBeDeleted, e);
        failedDatasets.add(Tuple.of(toBeDeleted.getSchemaPath(), e.getMessage()));
        // continue;
      }
    }

    return deleted;
  }

  private static void addAncestors(NamespaceKey datasetKey, Set<NamespaceKey> ancestors) {
    NamespaceKey key = datasetKey.getParent();
    while (key.hasParent()) {
      ancestors.add(key);
      key = key.getParent();
    }
  }

  private static Iterator<NamespaceKey> getAncestors(NamespaceKey datasetKey) {
    return new Iterator<NamespaceKey>() {
      NamespaceKey currentKey = datasetKey;

      @Override
      public boolean hasNext() {
        return currentKey.hasParent();
      }

      @Override
      public NamespaceKey next() {
        if (!currentKey.hasParent()) {
          throw new NoSuchElementException();
        }
        currentKey = currentKey.getParent();
        return currentKey;
      }
    };
  }

  /**
   * Sync status.
   */
  public static class SyncStatus {
    private final boolean refreshed;
    private final boolean interrupted;

    private SyncStatus(boolean refreshed, boolean interrupted) {
      this.refreshed = refreshed;
      this.interrupted = interrupted;
    }

    public boolean isRefreshed() {
      return refreshed;
    }

    public boolean isInterrupted() {
      return interrupted;
    }
  }
}
