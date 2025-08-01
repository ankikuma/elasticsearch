/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.upgrades;

import com.carrotsearch.randomizedtesting.annotations.Name;

import org.elasticsearch.client.Request;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.test.rest.ObjectPath;

import java.io.IOException;
import java.util.Map;
import java.util.stream.Collectors;

import static org.elasticsearch.client.RestClient.IGNORE_RESPONSE_CODES_PARAM;
import static org.elasticsearch.upgrades.SnapshotBasedRecoveryIT.indexDocs;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

public class RunningSnapshotIT extends AbstractRollingUpgradeTestCase {

    public RunningSnapshotIT(@Name("upgradedNodes") int upgradedNodes) {
        super(upgradedNodes);
    }

    public void testRunningSnapshotCompleteAfterUpgrade() throws Exception {
        final String indexName = "index";
        final String repositoryName = "repo";
        final String snapshotName = "snapshot";
        final Map<String, Map<?, ?>> nodesInfo = getNodesInfo(client());
        final var nodeIdToNodeNames = nodesInfo.entrySet()
            .stream()
            .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> entry.getValue().get("name").toString()));
        assertThat(nodeIdToNodeNames.values(), containsInAnyOrder("test-cluster-0", "test-cluster-1", "test-cluster-2"));

        final var lastUpgradeNodeId = nodeIdToNodeNames.entrySet()
            .stream()
            .filter(entry -> "test-cluster-2".equals(entry.getValue()))
            .map(Map.Entry::getKey)
            .findFirst()
            .orElseThrow(() -> new AssertionError("node id not found in " + nodeIdToNodeNames));

        if (isOldCluster()) {
            registerRepository(repositoryName, "fs", randomBoolean(), Settings.builder().put("location", "backup").build());
            // create an index to have one shard per node
            createIndex(indexName, indexSettings(3, 0).put("index.routing.allocation.total_shards_per_node", 1).build());
            ensureGreen(indexName);
            if (randomBoolean()) {
                indexDocs(indexName, between(10, 50));
            }
            flush(indexName, true);
            // Signal shutdown for the last node to upgrade to prevent snapshot from being completed during the upgrade process
            putShutdownMetadata(lastUpgradeNodeId);
            createSnapshot(repositoryName, snapshotName, false);
            assertRunningSnapshot(repositoryName, snapshotName);
        } else {
            if (isUpgradedCluster()) {
                deleteShutdownMetadata(lastUpgradeNodeId);
                assertNoShutdownMetadata(lastUpgradeNodeId);
                ensureGreen(indexName);
                assertBusy(() -> assertCompletedSnapshot(repositoryName, snapshotName));
            } else {
                assertRunningSnapshot(repositoryName, snapshotName);
            }
        }
    }

    private void putShutdownMetadata(String nodeId) throws IOException {
        final Request putShutdownRequest = new Request("PUT", "/_nodes/" + nodeId + "/shutdown");
        putShutdownRequest.setJsonEntity("""
            {
              "type": "remove",
              "reason": "test"
            }""");
        client().performRequest(putShutdownRequest);
    }

    private void deleteShutdownMetadata(String nodeId) throws IOException {
        final Request request = new Request("DELETE", "/_nodes/" + nodeId + "/shutdown");
        request.addParameter(IGNORE_RESPONSE_CODES_PARAM, "404");
        client().performRequest(request);
    }

    private void assertNoShutdownMetadata(String nodeId) throws IOException {
        final ObjectPath responsePath = assertOKAndCreateObjectPath(
            client().performRequest(new Request("GET", "/_nodes/" + nodeId + "/shutdown"))
        );
        assertThat(responsePath.evaluate("nodes"), empty());
    }

    private void assertRunningSnapshot(String repositoryName, String snapshotName) throws IOException {
        final Request request = new Request("GET", "/_snapshot/" + repositoryName + "/_current");
        final ObjectPath responsePath = assertOKAndCreateObjectPath(client().performRequest(request));
        assertThat(responsePath.evaluate("total"), equalTo(1));
        assertThat(responsePath.evaluate("snapshots.0.snapshot"), equalTo(snapshotName));
    }

    private void assertCompletedSnapshot(String repositoryName, String snapshotName) throws IOException {
        {
            final Request request = new Request("GET", "/_snapshot/" + repositoryName + "/_current");
            final ObjectPath responsePath = assertOKAndCreateObjectPath(client().performRequest(request));
            assertThat(responsePath.evaluate("total"), equalTo(0));
        }

        {
            final Request request = new Request("GET", "/_snapshot/" + repositoryName + "/" + snapshotName);
            final ObjectPath responsePath = assertOKAndCreateObjectPath(client().performRequest(request));
            assertThat(responsePath.evaluate("total"), equalTo(1));
            assertThat(responsePath.evaluate("snapshots.0.snapshot"), equalTo(snapshotName));
            assertThat(responsePath.evaluate("snapshots.0.state"), not(equalTo("IN_PROGRESS")));
        }
    }
}
