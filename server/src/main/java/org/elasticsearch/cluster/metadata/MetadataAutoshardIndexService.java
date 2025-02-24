/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.cluster.metadata;

import org.elasticsearch.ResourceAlreadyExistsException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.indices.autoshard.AutoshardIndexClusterStateUpdateRequest;
import org.elasticsearch.action.support.ActiveShardsObserver;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.action.support.master.ShardsAcknowledgedResponse;
import org.elasticsearch.cluster.AckedClusterStateUpdateTask;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ClusterStateUpdateTask;
import org.elasticsearch.cluster.routing.RoutingTable;
import org.elasticsearch.cluster.routing.allocation.AllocationService;
import org.elasticsearch.cluster.routing.allocation.allocator.AllocationActionListener;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.Priority;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.core.SuppressForbidden;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.index.IndexNotFoundException;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.indices.InvalidIndexNameException;
import org.elasticsearch.logging.LogManager;
import org.elasticsearch.logging.Logger;
import org.elasticsearch.threadpool.ThreadPool;

public class MetadataAutoshardIndexService {
    private static final Logger logger = LogManager.getLogger(MetadataAutoshardIndexService.class);

    private final Settings settings;
    private final ClusterService clusterService;
    private final IndicesService indicesService;
    private final AllocationService allocationService;
    private final ThreadPool threadPool;

    public MetadataAutoshardIndexService(
        final Settings settings,
        final ClusterService clusterService,
        final IndicesService indicesService,
        final AllocationService allocationService,
        final ThreadPool threadPool
    ) {
        this.settings = settings;
        this.clusterService = clusterService;
        this.indicesService = indicesService;
        this.allocationService = allocationService;
        this.threadPool = threadPool;
    }

    public static void validateIndexName(String index, Metadata metadata, RoutingTable routingTable) {
        if (routingTable.hasIndex(index) == false) {
            throw new InvalidIndexNameException(index, "index does not exist");
        }
        /* Throw and error for datastream and system indexes.
         * Datastream indices are autosharded using a different code path.
         */
    }

    public void autoshardIndex(
        final TimeValue masterNodeTimeout,
        final TimeValue ackTimeout,
        @Nullable final TimeValue waitForActiveShardsTimeout,
        final AutoshardIndexClusterStateUpdateRequest request,
        final ActionListener<ShardsAcknowledgedResponse> listener
    ) {
        logger.trace("autoshardIndex[{}]", request);
        onlyAutoshardIndex(masterNodeTimeout, ackTimeout, request, listener.delegateFailureAndWrap((delegate, response) -> {
            if (response.isAcknowledged()) {
                logger.trace(
                    "[{}] index autoshard acknowledged, waiting for active shards [{}]",
                    request.index(),
                    request.waitForActiveShards()
                );
                ActiveShardsObserver.waitForActiveShards(
                    clusterService,
                    new String[] { request.index() },
                    request.waitForActiveShards(),
                    waitForActiveShardsTimeout,
                    delegate.map(shardsAcknowledged -> {
                        if (shardsAcknowledged == false) {
                            logger.debug(
                                "[{}] index autoshard complete, but the operation timed out while waiting for enough shards to be started.",
                                request.index()
                            );
                        } else {
                            logger.trace("[{}] index autoshard complete and shards acknowledged", request.index());
                        }
                        return ShardsAcknowledgedResponse.of(true, shardsAcknowledged);
                    })
                );
            } else {
                logger.trace("index autoshard not acknowledged for [{}]", request);
                delegate.onResponse(ShardsAcknowledgedResponse.NOT_ACKNOWLEDGED);
            }
        }));
    }

    private void onlyAutoshardIndex(
        final TimeValue masterNodeTimeout,
        final TimeValue ackTimeout,
        final AutoshardIndexClusterStateUpdateRequest request,
        final ActionListener<AcknowledgedResponse> listener
    ) {
        var delegate = new AllocationActionListener<>(listener, threadPool.getThreadContext());
        submitUnbatchedTask(
            "autoshard-index [" + request.index() + "], cause [" + request.cause() + "]",
            new AckedClusterStateUpdateTask(Priority.URGENT, masterNodeTimeout, ackTimeout, delegate.clusterStateUpdate()) {

                @Override
                public ClusterState execute(ClusterState currentState) throws Exception {
                    return applyAutoshardIndexRequest(currentState, request, false, delegate.reroute());
                }

                @Override
                public void onFailure(Exception e) {
                    if (e instanceof ResourceAlreadyExistsException) {
                        logger.trace(() -> "[" + request.index() + "] failed to autoshard", e);
                    } else {
                        logger.debug(() -> "[" + request.index() + "] failed to autoshard", e);
                    }
                    super.onFailure(e);
                }
            }
        );
    }

    public ClusterState applyAutoshardIndexRequest(
        final ClusterState currentState,
        final AutoshardIndexClusterStateUpdateRequest request,
        boolean silent,
        final ActionListener<Void> rerouteListener
    ) {
        final String indexName = request.index();
        final IndexMetadata sourceMetadata = currentState.metadata().index(indexName);
        int sourceNumShards = sourceMetadata.getNumberOfShards();
        int targetNumShards = sourceNumShards * 2;

        var routingTableBuilder = RoutingTable.builder(allocationService.getShardRoutingRoleStrategy(), currentState.routingTable())
            .updateNumberOfShards(targetNumShards, indexName);

        // Metadata newMetadata = Metadata.builder(currentState.metadata()).updateNumberOfShards(targetNumShards, indexName).build();
        Metadata newMetadata = incrementNumberOfShards(currentState, targetNumShards, indexName).build();
        ClusterState updated = ClusterState.builder(currentState)
            .incrementVersion()
            .routingTable(routingTableBuilder)
            .metadata(newMetadata)
            .build();
        updated = allocationService.reroute(updated, "index [" + indexName + "] autosharded", rerouteListener);
        return updated;
    }

    /**
     * Builder to update numberOfShards of an Index.
     * The new shard count must be a multiple of the original shardcount.
     * We do not support shrinking the shard count.
     * @param currentState    Current clusterstate
     * @param numberOfShards  Target number of shards
     * @param index           Index whose shard count is being modified
     * @return
     */
    public static Metadata.Builder incrementNumberOfShards(
        final ClusterState currentState,
        final int numberOfShards,
        final String index) {
        Metadata.Builder metadataBuilder = Metadata.builder(currentState.metadata());
        IndexMetadata indexMetadata = metadataBuilder.get(index);
        if (indexMetadata == null) {
            throw new IndexNotFoundException(index);
        }
        metadataBuilder.put(
            IndexMetadata.builder(indexMetadata)
                .settingsVersion(indexMetadata.getSettingsVersion() + 1)
                .reshardAddShards(numberOfShards)
        );
        return metadataBuilder;
    }

    @SuppressForbidden(reason = "legacy usage of unbatched task") // TODO add support for batching here
    private void submitUnbatchedTask(@SuppressWarnings("SameParameterValue") String source, ClusterStateUpdateTask task) {
        clusterService.submitUnbatchedStateUpdateTask(source, task);
    }
}
