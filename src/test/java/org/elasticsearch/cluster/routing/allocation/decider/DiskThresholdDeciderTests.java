/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.cluster.routing.allocation.decider;

import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableMap;
import org.elasticsearch.cluster.ClusterInfo;
import org.elasticsearch.cluster.ClusterInfoService;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.DiskUsage;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.cluster.routing.*;
import org.elasticsearch.cluster.routing.allocation.AllocationService;
import org.elasticsearch.cluster.routing.allocation.allocator.ShardsAllocators;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.test.ElasticsearchAllocationTestCase;
import org.elasticsearch.test.junit.annotations.TestLogging;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import static org.elasticsearch.cluster.routing.ShardRoutingState.*;
import static org.elasticsearch.common.settings.ImmutableSettings.settingsBuilder;
import static org.hamcrest.Matchers.equalTo;

public class DiskThresholdDeciderTests extends ElasticsearchAllocationTestCase {

    @Test
    public void diskThresholdTest() {
        Settings diskSettings = settingsBuilder()
                .put(DiskThresholdDecider.CLUSTER_ROUTING_ALLOCATION_DISK_THRESHOLD_ENABLED, true)
                .put(DiskThresholdDecider.CLUSTER_ROUTING_ALLOCATION_LOW_DISK_WATERMARK, 0.7)
                .put(DiskThresholdDecider.CLUSTER_ROUTING_ALLOCATION_HIGH_DISK_WATERMARK, 0.8).build();

        Map<String, DiskUsage> usages = new HashMap<>();
        usages.put("node1", new DiskUsage("node1", 100, 10)); // 90% used
        usages.put("node2", new DiskUsage("node2", 100, 35)); // 65% used
        usages.put("node3", new DiskUsage("node3", 100, 60)); // 40% used
        usages.put("node4", new DiskUsage("node4", 100, 80)); // 20% used

        Map<String, Long> shardSizes = new HashMap<>();
        shardSizes.put("[test][0][p]", 10L); // 10 bytes
        shardSizes.put("[test][0][r]", 10L);
        final ClusterInfo clusterInfo = new ClusterInfo(ImmutableMap.copyOf(usages), ImmutableMap.copyOf(shardSizes));

        AllocationDeciders deciders = new AllocationDeciders(ImmutableSettings.EMPTY,
                new HashSet<>(Arrays.asList(
                        new SameShardAllocationDecider(ImmutableSettings.EMPTY),
                        new DiskThresholdDecider(diskSettings))));

        ClusterInfoService cis = new ClusterInfoService() {
            @Override
            public ClusterInfo getClusterInfo() {
                logger.info("--> calling fake getClusterInfo");
                return clusterInfo;
            }
        };

        AllocationService strategy = new AllocationService(settingsBuilder()
                .put("cluster.routing.allocation.concurrent_recoveries", 10)
                .put(ClusterRebalanceAllocationDecider.CLUSTER_ROUTING_ALLOCATION_ALLOW_REBALANCE, "always")
                .put("cluster.routing.allocation.cluster_concurrent_rebalance", -1)
                .build(), deciders, new ShardsAllocators(), cis);

        MetaData metaData = MetaData.builder()
                .put(IndexMetaData.builder("test").numberOfShards(1).numberOfReplicas(1))
                .build();

        RoutingTable routingTable = RoutingTable.builder()
                .addAsNew(metaData.index("test"))
                .build();

        ClusterState clusterState = ClusterState.builder(org.elasticsearch.cluster.ClusterName.DEFAULT).metaData(metaData).routingTable(routingTable).build();

        logger.info("--> adding two nodes");
        clusterState = ClusterState.builder(clusterState).nodes(DiscoveryNodes.builder()
                .put(newNode("node1"))
                .put(newNode("node2"))
        ).build();
        routingTable = strategy.reroute(clusterState).routingTable();
        clusterState = ClusterState.builder(clusterState).routingTable(routingTable).build();
        logShardStates(clusterState);

        // Primary shard should be initializing, replica should not
        assertThat(clusterState.routingNodes().shardsWithState(INITIALIZING).size(), equalTo(1));

        logger.info("--> start the shards (primaries)");
        routingTable = strategy.applyStartedShards(clusterState, clusterState.routingNodes().shardsWithState(INITIALIZING)).routingTable();
        clusterState = ClusterState.builder(clusterState).routingTable(routingTable).build();

        logShardStates(clusterState);
        // Assert that we're able to start the primary
        assertThat(clusterState.routingNodes().shardsWithState(ShardRoutingState.STARTED).size(), equalTo(1));
        // Assert that node1 didn't get any shards because its disk usage is too high
        assertThat(clusterState.getRoutingNodes().node("node1").size(), equalTo(0));

        logger.info("--> start the shards (replicas)");
        routingTable = strategy.applyStartedShards(clusterState, clusterState.routingNodes().shardsWithState(INITIALIZING)).routingTable();
        clusterState = ClusterState.builder(clusterState).routingTable(routingTable).build();

        logShardStates(clusterState);
        // Assert that the replica couldn't be started since node1 doesn't have enough space
        assertThat(clusterState.routingNodes().shardsWithState(ShardRoutingState.STARTED).size(), equalTo(1));

        logger.info("--> adding node3");

        clusterState = ClusterState.builder(clusterState).nodes(DiscoveryNodes.builder(clusterState.nodes())
                .put(newNode("node3"))
        ).build();
        routingTable = strategy.reroute(clusterState).routingTable();
        clusterState = ClusterState.builder(clusterState).routingTable(routingTable).build();

        logShardStates(clusterState);
        // Assert that the replica is initialized now that node3 is available with enough space
        assertThat(clusterState.routingNodes().shardsWithState(ShardRoutingState.STARTED).size(), equalTo(1));
        assertThat(clusterState.routingNodes().shardsWithState(ShardRoutingState.INITIALIZING).size(), equalTo(1));

        logger.info("--> start the shards (replicas)");
        routingTable = strategy.applyStartedShards(clusterState, clusterState.routingNodes().shardsWithState(INITIALIZING)).routingTable();
        clusterState = ClusterState.builder(clusterState).routingTable(routingTable).build();

        logShardStates(clusterState);
        // Assert that the replica couldn't be started since node1 doesn't have enough space
        assertThat(clusterState.routingNodes().shardsWithState(ShardRoutingState.STARTED).size(), equalTo(2));
        assertThat(clusterState.getRoutingNodes().node("node1").size(), equalTo(0));
        assertThat(clusterState.getRoutingNodes().node("node2").size(), equalTo(1));
        assertThat(clusterState.getRoutingNodes().node("node3").size(), equalTo(1));

        logger.info("--> changing decider settings");

        // Set the low threshold to 60 instead of 70
        // Set the high threshold to 70 instead of 80
        // node2 now should not have new shards allocated to it, but shards can remain
        diskSettings = settingsBuilder()
                .put(DiskThresholdDecider.CLUSTER_ROUTING_ALLOCATION_DISK_THRESHOLD_ENABLED, true)
                .put(DiskThresholdDecider.CLUSTER_ROUTING_ALLOCATION_LOW_DISK_WATERMARK, "60%")
                .put(DiskThresholdDecider.CLUSTER_ROUTING_ALLOCATION_HIGH_DISK_WATERMARK, 0.7).build();

        deciders = new AllocationDeciders(ImmutableSettings.EMPTY,
                new HashSet<>(Arrays.asList(
                        new SameShardAllocationDecider(ImmutableSettings.EMPTY),
                        new DiskThresholdDecider(diskSettings))));

        strategy = new AllocationService(settingsBuilder()
                .put("cluster.routing.allocation.concurrent_recoveries", 10)
                .put(ClusterRebalanceAllocationDecider.CLUSTER_ROUTING_ALLOCATION_ALLOW_REBALANCE, "always")
                .put("cluster.routing.allocation.cluster_concurrent_rebalance", -1)
                .build(), deciders, new ShardsAllocators(), cis);

        routingTable = strategy.reroute(clusterState).routingTable();
        clusterState = ClusterState.builder(clusterState).routingTable(routingTable).build();
        logShardStates(clusterState);

        // Shards remain started
        assertThat(clusterState.routingNodes().shardsWithState(STARTED).size(), equalTo(2));
        assertThat(clusterState.getRoutingNodes().node("node1").size(), equalTo(0));
        assertThat(clusterState.getRoutingNodes().node("node2").size(), equalTo(1));
        assertThat(clusterState.getRoutingNodes().node("node3").size(), equalTo(1));

        logger.info("--> changing settings again");

        // Set the low threshold to 50 instead of 60
        // Set the high threshold to 60 instead of 70
        // node2 now should not have new shards allocated to it, and shards cannot remain
        diskSettings = settingsBuilder()
                .put(DiskThresholdDecider.CLUSTER_ROUTING_ALLOCATION_DISK_THRESHOLD_ENABLED, true)
                .put(DiskThresholdDecider.CLUSTER_ROUTING_ALLOCATION_LOW_DISK_WATERMARK, 0.5)
                .put(DiskThresholdDecider.CLUSTER_ROUTING_ALLOCATION_HIGH_DISK_WATERMARK, 0.6).build();

        deciders = new AllocationDeciders(ImmutableSettings.EMPTY,
                new HashSet<>(Arrays.asList(
                        new SameShardAllocationDecider(ImmutableSettings.EMPTY),
                        new DiskThresholdDecider(diskSettings))));

        strategy = new AllocationService(settingsBuilder()
                .put("cluster.routing.allocation.concurrent_recoveries", 10)
                .put(ClusterRebalanceAllocationDecider.CLUSTER_ROUTING_ALLOCATION_ALLOW_REBALANCE, "always")
                .put("cluster.routing.allocation.cluster_concurrent_rebalance", -1)
                .build(), deciders, new ShardsAllocators(), cis);

        routingTable = strategy.reroute(clusterState).routingTable();
        clusterState = ClusterState.builder(clusterState).routingTable(routingTable).build();

        logShardStates(clusterState);
        // Shards remain started
        assertThat(clusterState.routingNodes().shardsWithState(STARTED).size(), equalTo(2));
        assertThat(clusterState.getRoutingNodes().node("node1").size(), equalTo(0));
        // Shard hasn't been moved off of node2 yet because there's nowhere for it to go
        assertThat(clusterState.getRoutingNodes().node("node2").size(), equalTo(1));
        assertThat(clusterState.getRoutingNodes().node("node3").size(), equalTo(1));

        logger.info("--> adding node4");

        clusterState = ClusterState.builder(clusterState).nodes(DiscoveryNodes.builder(clusterState.nodes())
                .put(newNode("node4"))
        ).build();
        routingTable = strategy.reroute(clusterState).routingTable();
        clusterState = ClusterState.builder(clusterState).routingTable(routingTable).build();

        logShardStates(clusterState);
        // Shards remain started
        assertThat(clusterState.routingNodes().shardsWithState(STARTED).size(), equalTo(1));
        assertThat(clusterState.routingNodes().shardsWithState(INITIALIZING).size(), equalTo(1));

        logger.info("--> apply INITIALIZING shards");
        routingTable = strategy.applyStartedShards(clusterState, clusterState.routingNodes().shardsWithState(INITIALIZING)).routingTable();
        clusterState = ClusterState.builder(clusterState).routingTable(routingTable).build();

        logShardStates(clusterState);
        assertThat(clusterState.getRoutingNodes().node("node1").size(), equalTo(0));
        // Node4 is available now, so the shard is moved off of node2
        assertThat(clusterState.getRoutingNodes().node("node2").size(), equalTo(0));
        assertThat(clusterState.getRoutingNodes().node("node3").size(), equalTo(1));
        assertThat(clusterState.getRoutingNodes().node("node4").size(), equalTo(1));
    }

    @Test
    @TestLogging("cluster.routing.allocation.decider:TRACE")
    public void diskThresholdWithAbsoluteSizesTest() {
        Settings diskSettings = settingsBuilder()
                .put(DiskThresholdDecider.CLUSTER_ROUTING_ALLOCATION_DISK_THRESHOLD_ENABLED, true)
                .put(DiskThresholdDecider.CLUSTER_ROUTING_ALLOCATION_LOW_DISK_WATERMARK, "30b")
                .put(DiskThresholdDecider.CLUSTER_ROUTING_ALLOCATION_HIGH_DISK_WATERMARK, "9b").build();

        Map<String, DiskUsage> usages = new HashMap<>();
        usages.put("node1", new DiskUsage("node1", 100, 10)); // 90% used
        usages.put("node2", new DiskUsage("node2", 100, 10)); // 90% used
        usages.put("node3", new DiskUsage("node3", 100, 60)); // 40% used
        usages.put("node4", new DiskUsage("node4", 100, 80)); // 20% used
        usages.put("node5", new DiskUsage("node5", 100, 85)); // 15% used

        Map<String, Long> shardSizes = new HashMap<>();
        shardSizes.put("[test][0][p]", 10L); // 10 bytes
        shardSizes.put("[test][0][r]", 10L);
        final ClusterInfo clusterInfo = new ClusterInfo(ImmutableMap.copyOf(usages), ImmutableMap.copyOf(shardSizes));

        AllocationDeciders deciders = new AllocationDeciders(ImmutableSettings.EMPTY,
                new HashSet<>(Arrays.asList(
                        new SameShardAllocationDecider(ImmutableSettings.EMPTY),
                        new DiskThresholdDecider(diskSettings))));

        ClusterInfoService cis = new ClusterInfoService() {
            @Override
            public ClusterInfo getClusterInfo() {
                logger.info("--> calling fake getClusterInfo");
                return clusterInfo;
            }
        };

        AllocationService strategy = new AllocationService(settingsBuilder()
                .put("cluster.routing.allocation.concurrent_recoveries", 10)
                .put(ClusterRebalanceAllocationDecider.CLUSTER_ROUTING_ALLOCATION_ALLOW_REBALANCE, "always")
                .put("cluster.routing.allocation.cluster_concurrent_rebalance", -1)
                .build(), deciders, new ShardsAllocators(), cis);

        MetaData metaData = MetaData.builder()
                .put(IndexMetaData.builder("test").numberOfShards(1).numberOfReplicas(2))
                .build();

        RoutingTable routingTable = RoutingTable.builder()
                .addAsNew(metaData.index("test"))
                .build();

        ClusterState clusterState = ClusterState.builder(org.elasticsearch.cluster.ClusterName.DEFAULT).metaData(metaData).routingTable(routingTable).build();

        logger.info("--> adding node1 and node2 node");
        clusterState = ClusterState.builder(clusterState).nodes(DiscoveryNodes.builder()
                        .put(newNode("node1"))
                        .put(newNode("node2"))
        ).build();

        routingTable = strategy.reroute(clusterState).routingTable();
        clusterState = ClusterState.builder(clusterState).routingTable(routingTable).build();
        logShardStates(clusterState);

        // Primary should initialize, even though both nodes are over the limit initialize
        assertThat(clusterState.routingNodes().shardsWithState(INITIALIZING).size(), equalTo(1));

        String nodeWithPrimary, nodeWithoutPrimary;
        if (clusterState.getRoutingNodes().node("node1").size() == 1) {
            nodeWithPrimary = "node1";
            nodeWithoutPrimary = "node2";
        } else {
            nodeWithPrimary = "node2";
            nodeWithoutPrimary = "node1";
        }

        // Make node without the primary now habitable to replicas
        usages.put(nodeWithoutPrimary, new DiskUsage(nodeWithoutPrimary, 100, 35)); // 65% used
        final ClusterInfo clusterInfo2 = new ClusterInfo(ImmutableMap.copyOf(usages), ImmutableMap.copyOf(shardSizes));
        cis = new ClusterInfoService() {
            @Override
            public ClusterInfo getClusterInfo() {
                logger.info("--> calling fake getClusterInfo");
                return clusterInfo2;
            }
        };
        strategy = new AllocationService(settingsBuilder()
                .put("cluster.routing.allocation.concurrent_recoveries", 10)
                .put(ClusterRebalanceAllocationDecider.CLUSTER_ROUTING_ALLOCATION_ALLOW_REBALANCE, "always")
                .put("cluster.routing.allocation.cluster_concurrent_rebalance", -1)
                .build(), deciders, new ShardsAllocators(), cis);

        routingTable = strategy.reroute(clusterState).routingTable();
        clusterState = ClusterState.builder(clusterState).routingTable(routingTable).build();
        logShardStates(clusterState);

        // Now the replica should be able to initialize
        assertThat(clusterState.routingNodes().shardsWithState(INITIALIZING).size(), equalTo(2));

        logger.info("--> start the shards (primaries)");
        routingTable = strategy.applyStartedShards(clusterState, clusterState.routingNodes().shardsWithState(INITIALIZING)).routingTable();
        clusterState = ClusterState.builder(clusterState).routingTable(routingTable).build();

        logShardStates(clusterState);
        // Assert that we're able to start the primary and replica, since they were both initializing
        assertThat(clusterState.routingNodes().shardsWithState(ShardRoutingState.STARTED).size(), equalTo(2));
        // Assert that node1 got a single shard (the primary), even though its disk usage is too high
        assertThat(clusterState.getRoutingNodes().node("node1").size(), equalTo(1));
        // Assert that node2 got a single shard (a replica)
        assertThat(clusterState.getRoutingNodes().node("node2").size(), equalTo(1));

        // Assert that one replica is still unassigned
        //assertThat(clusterState.routingNodes().shardsWithState(ShardRoutingState.UNASSIGNED).size(), equalTo(1));

        logger.info("--> adding node3");

        clusterState = ClusterState.builder(clusterState).nodes(DiscoveryNodes.builder(clusterState.nodes())
                .put(newNode("node3"))
        ).build();
        routingTable = strategy.reroute(clusterState).routingTable();
        clusterState = ClusterState.builder(clusterState).routingTable(routingTable).build();

        logShardStates(clusterState);
        // Assert that the replica is initialized now that node3 is available with enough space
        assertThat(clusterState.routingNodes().shardsWithState(ShardRoutingState.STARTED).size(), equalTo(2));
        assertThat(clusterState.routingNodes().shardsWithState(ShardRoutingState.INITIALIZING).size(), equalTo(1));

        logger.info("--> start the shards (replicas)");
        routingTable = strategy.applyStartedShards(clusterState, clusterState.routingNodes().shardsWithState(INITIALIZING)).routingTable();
        clusterState = ClusterState.builder(clusterState).routingTable(routingTable).build();

        logShardStates(clusterState);
        // Assert that all replicas could be started
        assertThat(clusterState.routingNodes().shardsWithState(ShardRoutingState.STARTED).size(), equalTo(3));
        assertThat(clusterState.getRoutingNodes().node("node1").size(), equalTo(1));
        assertThat(clusterState.getRoutingNodes().node("node2").size(), equalTo(1));
        assertThat(clusterState.getRoutingNodes().node("node3").size(), equalTo(1));

        logger.info("--> changing decider settings");

        // Set the low threshold to 60 instead of 70
        // Set the high threshold to 70 instead of 80
        // node2 now should not have new shards allocated to it, but shards can remain
        diskSettings = settingsBuilder()
                .put(DiskThresholdDecider.CLUSTER_ROUTING_ALLOCATION_DISK_THRESHOLD_ENABLED, true)
                .put(DiskThresholdDecider.CLUSTER_ROUTING_ALLOCATION_LOW_DISK_WATERMARK, "40b")
                .put(DiskThresholdDecider.CLUSTER_ROUTING_ALLOCATION_HIGH_DISK_WATERMARK, "30b").build();

        deciders = new AllocationDeciders(ImmutableSettings.EMPTY,
                new HashSet<>(Arrays.asList(
                        new SameShardAllocationDecider(ImmutableSettings.EMPTY),
                        new DiskThresholdDecider(diskSettings))));

        strategy = new AllocationService(settingsBuilder()
                .put("cluster.routing.allocation.concurrent_recoveries", 10)
                .put(ClusterRebalanceAllocationDecider.CLUSTER_ROUTING_ALLOCATION_ALLOW_REBALANCE, "always")
                .put("cluster.routing.allocation.cluster_concurrent_rebalance", -1)
                .build(), deciders, new ShardsAllocators(), cis);

        routingTable = strategy.reroute(clusterState).routingTable();
        clusterState = ClusterState.builder(clusterState).routingTable(routingTable).build();
        logShardStates(clusterState);

        // Shards remain started
        assertThat(clusterState.routingNodes().shardsWithState(STARTED).size(), equalTo(3));
        assertThat(clusterState.getRoutingNodes().node("node1").size(), equalTo(1));
        assertThat(clusterState.getRoutingNodes().node("node2").size(), equalTo(1));
        assertThat(clusterState.getRoutingNodes().node("node3").size(), equalTo(1));

        logger.info("--> changing settings again");

        // Set the low threshold to 50 instead of 60
        // Set the high threshold to 60 instead of 70
        // node2 now should not have new shards allocated to it, and shards cannot remain
        diskSettings = settingsBuilder()
                .put(DiskThresholdDecider.CLUSTER_ROUTING_ALLOCATION_DISK_THRESHOLD_ENABLED, true)
                .put(DiskThresholdDecider.CLUSTER_ROUTING_ALLOCATION_LOW_DISK_WATERMARK, "50b")
                .put(DiskThresholdDecider.CLUSTER_ROUTING_ALLOCATION_HIGH_DISK_WATERMARK, "40b").build();

        deciders = new AllocationDeciders(ImmutableSettings.EMPTY,
                new HashSet<>(Arrays.asList(
                        new SameShardAllocationDecider(ImmutableSettings.EMPTY),
                        new DiskThresholdDecider(diskSettings))));

        strategy = new AllocationService(settingsBuilder()
                .put("cluster.routing.allocation.concurrent_recoveries", 10)
                .put(ClusterRebalanceAllocationDecider.CLUSTER_ROUTING_ALLOCATION_ALLOW_REBALANCE, "always")
                .put("cluster.routing.allocation.cluster_concurrent_rebalance", -1)
                .build(), deciders, new ShardsAllocators(), cis);

        routingTable = strategy.reroute(clusterState).routingTable();
        clusterState = ClusterState.builder(clusterState).routingTable(routingTable).build();

        logShardStates(clusterState);
        // Shards remain started
        assertThat(clusterState.routingNodes().shardsWithState(STARTED).size(), equalTo(3));
        assertThat(clusterState.getRoutingNodes().node("node1").size(), equalTo(1));
        // Shard hasn't been moved off of node2 yet because there's nowhere for it to go
        assertThat(clusterState.getRoutingNodes().node("node2").size(), equalTo(1));
        assertThat(clusterState.getRoutingNodes().node("node3").size(), equalTo(1));

        logger.info("--> adding node4");

        clusterState = ClusterState.builder(clusterState).nodes(DiscoveryNodes.builder(clusterState.nodes())
                .put(newNode("node4"))
        ).build();
        routingTable = strategy.reroute(clusterState).routingTable();
        clusterState = ClusterState.builder(clusterState).routingTable(routingTable).build();

        logShardStates(clusterState);
        // Shards remain started
        assertThat(clusterState.routingNodes().shardsWithState(STARTED).size(), equalTo(2));
        // One shard is relocating off of node1
        assertThat(clusterState.routingNodes().shardsWithState(RELOCATING).size(), equalTo(1));
        assertThat(clusterState.routingNodes().shardsWithState(INITIALIZING).size(), equalTo(1));

        logger.info("--> apply INITIALIZING shards");
        routingTable = strategy.applyStartedShards(clusterState, clusterState.routingNodes().shardsWithState(INITIALIZING)).routingTable();
        clusterState = ClusterState.builder(clusterState).routingTable(routingTable).build();

        logShardStates(clusterState);
        // primary shard already has been relocated away
        assertThat(clusterState.getRoutingNodes().node(nodeWithPrimary).size(), equalTo(0));
        // node with increased space still has its shard
        assertThat(clusterState.getRoutingNodes().node(nodeWithoutPrimary).size(), equalTo(1));
        assertThat(clusterState.getRoutingNodes().node("node3").size(), equalTo(1));
        assertThat(clusterState.getRoutingNodes().node("node4").size(), equalTo(1));

        logger.info("--> adding node5");

        clusterState = ClusterState.builder(clusterState).nodes(DiscoveryNodes.builder(clusterState.nodes())
                        .put(newNode("node5"))
        ).build();
        routingTable = strategy.reroute(clusterState).routingTable();
        clusterState = ClusterState.builder(clusterState).routingTable(routingTable).build();

        logShardStates(clusterState);
        // Shards remain started on node3 and node4
        assertThat(clusterState.routingNodes().shardsWithState(STARTED).size(), equalTo(2));
        // One shard is relocating off of node2 now
        assertThat(clusterState.routingNodes().shardsWithState(RELOCATING).size(), equalTo(1));
        // Initializing on node5
        assertThat(clusterState.routingNodes().shardsWithState(INITIALIZING).size(), equalTo(1));

        logger.info("--> apply INITIALIZING shards");
        routingTable = strategy.applyStartedShards(clusterState, clusterState.routingNodes().shardsWithState(INITIALIZING)).routingTable();
        clusterState = ClusterState.builder(clusterState).routingTable(routingTable).build();

        logger.info("--> final cluster state:");
        logShardStates(clusterState);
        // Node1 still has no shards because it has no space for them
        assertThat(clusterState.getRoutingNodes().node("node1").size(), equalTo(0));
        // Node5 is available now, so the shard is moved off of node2
        assertThat(clusterState.getRoutingNodes().node("node2").size(), equalTo(0));
        assertThat(clusterState.getRoutingNodes().node("node3").size(), equalTo(1));
        assertThat(clusterState.getRoutingNodes().node("node4").size(), equalTo(1));
        assertThat(clusterState.getRoutingNodes().node("node5").size(), equalTo(1));
    }

    @Test
    public void diskThresholdWithShardSizes() {
        Settings diskSettings = settingsBuilder()
                .put(DiskThresholdDecider.CLUSTER_ROUTING_ALLOCATION_DISK_THRESHOLD_ENABLED, true)
                .put(DiskThresholdDecider.CLUSTER_ROUTING_ALLOCATION_LOW_DISK_WATERMARK, 0.7)
                .put(DiskThresholdDecider.CLUSTER_ROUTING_ALLOCATION_HIGH_DISK_WATERMARK, "71%").build();

        Map<String, DiskUsage> usages = new HashMap<>();
        usages.put("node1", new DiskUsage("node1", 100, 31)); // 69% used
        usages.put("node2", new DiskUsage("node2", 100, 1));  // 99% used

        Map<String, Long> shardSizes = new HashMap<>();
        shardSizes.put("[test][0][p]", 10L); // 10 bytes
        final ClusterInfo clusterInfo = new ClusterInfo(ImmutableMap.copyOf(usages), ImmutableMap.copyOf(shardSizes));

        AllocationDeciders deciders = new AllocationDeciders(ImmutableSettings.EMPTY,
                new HashSet<>(Arrays.asList(
                        new SameShardAllocationDecider(ImmutableSettings.EMPTY),
                        new DiskThresholdDecider(diskSettings))));

        ClusterInfoService cis = new ClusterInfoService() {
            @Override
            public ClusterInfo getClusterInfo() {
                logger.info("--> calling fake getClusterInfo");
                return clusterInfo;
            }
        };

        AllocationService strategy = new AllocationService(settingsBuilder()
                .put("cluster.routing.allocation.concurrent_recoveries", 10)
                .put(ClusterRebalanceAllocationDecider.CLUSTER_ROUTING_ALLOCATION_ALLOW_REBALANCE, "always")
                .put("cluster.routing.allocation.cluster_concurrent_rebalance", -1)
                .build(), deciders, new ShardsAllocators(), cis);

        MetaData metaData = MetaData.builder()
                .put(IndexMetaData.builder("test").numberOfShards(1).numberOfReplicas(0))
                .build();

        RoutingTable routingTable = RoutingTable.builder()
                .addAsNew(metaData.index("test"))
                .build();

        ClusterState clusterState = ClusterState.builder(org.elasticsearch.cluster.ClusterName.DEFAULT).metaData(metaData).routingTable(routingTable).build();
        logger.info("--> adding node1");
        clusterState = ClusterState.builder(clusterState).nodes(DiscoveryNodes.builder()
                .put(newNode("node1"))
                .put(newNode("node2")) // node2 is added because DiskThresholdDecider automatically ignore single-node clusters
        ).build();
        routingTable = strategy.reroute(clusterState).routingTable();
        clusterState = ClusterState.builder(clusterState).routingTable(routingTable).build();
        logger.info("--> start the shards (primaries)");
        routingTable = strategy.applyStartedShards(clusterState, clusterState.routingNodes().shardsWithState(INITIALIZING)).routingTable();
        clusterState = ClusterState.builder(clusterState).routingTable(routingTable).build();
        logShardStates(clusterState);

        // Shard can't be allocated to node1 (or node2) because it would cause too much usage
        assertThat(clusterState.routingNodes().shardsWithState(INITIALIZING).size(), equalTo(0));
        // No shards are started, no nodes have enough disk for allocation
        assertThat(clusterState.routingNodes().shardsWithState(STARTED).size(), equalTo(0));
    }

    @Test
    public void unknownDiskUsageTest() {
        Settings diskSettings = settingsBuilder()
                .put(DiskThresholdDecider.CLUSTER_ROUTING_ALLOCATION_DISK_THRESHOLD_ENABLED, true)
                .put(DiskThresholdDecider.CLUSTER_ROUTING_ALLOCATION_LOW_DISK_WATERMARK, 0.7)
                .put(DiskThresholdDecider.CLUSTER_ROUTING_ALLOCATION_HIGH_DISK_WATERMARK, 0.85).build();

        Map<String, DiskUsage> usages = new HashMap<>();
        usages.put("node2", new DiskUsage("node2", 100, 50)); // 50% used
        usages.put("node3", new DiskUsage("node3", 100, 0));  // 100% used

        Map<String, Long> shardSizes = new HashMap<>();
        shardSizes.put("[test][0][p]", 10L); // 10 bytes
        shardSizes.put("[test][0][r]", 10L); // 10 bytes
        final ClusterInfo clusterInfo = new ClusterInfo(ImmutableMap.copyOf(usages), ImmutableMap.copyOf(shardSizes));

        AllocationDeciders deciders = new AllocationDeciders(ImmutableSettings.EMPTY,
                new HashSet<>(Arrays.asList(
                        new SameShardAllocationDecider(ImmutableSettings.EMPTY),
                        new DiskThresholdDecider(diskSettings))));

        ClusterInfoService cis = new ClusterInfoService() {
            @Override
            public ClusterInfo getClusterInfo() {
                logger.info("--> calling fake getClusterInfo");
                return clusterInfo;
            }
        };

        AllocationService strategy = new AllocationService(settingsBuilder()
                .put("cluster.routing.allocation.concurrent_recoveries", 10)
                .put(ClusterRebalanceAllocationDecider.CLUSTER_ROUTING_ALLOCATION_ALLOW_REBALANCE, "always")
                .put("cluster.routing.allocation.cluster_concurrent_rebalance", -1)
                .build(), deciders, new ShardsAllocators(), cis);

        MetaData metaData = MetaData.builder()
                .put(IndexMetaData.builder("test").numberOfShards(1).numberOfReplicas(0))
                .build();

        RoutingTable routingTable = RoutingTable.builder()
                .addAsNew(metaData.index("test"))
                .build();

        ClusterState clusterState = ClusterState.builder(org.elasticsearch.cluster.ClusterName.DEFAULT).metaData(metaData).routingTable(routingTable).build();
        logger.info("--> adding node1");
        clusterState = ClusterState.builder(clusterState).nodes(DiscoveryNodes.builder()
                .put(newNode("node1"))
                .put(newNode("node3")) // node3 is added because DiskThresholdDecider automatically ignore single-node clusters
        ).build();
        routingTable = strategy.reroute(clusterState).routingTable();
        clusterState = ClusterState.builder(clusterState).routingTable(routingTable).build();

        // Shard can be allocated to node1, even though it only has 25% free,
        // because it's a primary that's never been allocated before
        assertThat(clusterState.routingNodes().shardsWithState(INITIALIZING).size(), equalTo(1));

        logger.info("--> start the shards (primaries)");
        routingTable = strategy.applyStartedShards(clusterState, clusterState.routingNodes().shardsWithState(INITIALIZING)).routingTable();
        clusterState = ClusterState.builder(clusterState).routingTable(routingTable).build();
        logShardStates(clusterState);

        // A single shard is started on node1, even though it normally would not
        // be allowed, because it's a primary that hasn't been allocated, and node1
        // is still below the high watermark (unlike node3)
        assertThat(clusterState.routingNodes().shardsWithState(STARTED).size(), equalTo(1));
        assertThat(clusterState.getRoutingNodes().node("node1").size(), equalTo(1));
    }

    @Test
    public void averageUsageUnitTest() {
        RoutingNode rn = new RoutingNode("node1", newNode("node1"));
        DiskThresholdDecider decider = new DiskThresholdDecider(ImmutableSettings.EMPTY);

        Map<String, DiskUsage> usages = new HashMap<>();
        usages.put("node2", new DiskUsage("node2", 100, 50)); // 50% used
        usages.put("node3", new DiskUsage("node3", 100, 0));  // 100% used

        DiskUsage node1Usage = decider.averageUsage(rn, usages);
        assertThat(node1Usage.getTotalBytes(), equalTo(100L));
        assertThat(node1Usage.getFreeBytes(), equalTo(25L));
    }

    @Test
    public void freeDiskPercentageAfterShardAssignedUnitTest() {
        RoutingNode rn = new RoutingNode("node1", newNode("node1"));
        DiskThresholdDecider decider = new DiskThresholdDecider(ImmutableSettings.EMPTY);

        Map<String, DiskUsage> usages = new HashMap<>();
        usages.put("node2", new DiskUsage("node2", 100, 50)); // 50% used
        usages.put("node3", new DiskUsage("node3", 100, 0));  // 100% used

        Double after = decider.freeDiskPercentageAfterShardAssigned(new DiskUsage("node2", 100, 30), 11L);
        assertThat(after, equalTo(19.0));
    }

    public void logShardStates(ClusterState state) {
        RoutingNodes rn = state.routingNodes();
        logger.info("--> counts: total: {}, unassigned: {}, initializing: {}, relocating: {}, started: {}",
                rn.shards(new Predicate<MutableShardRouting>() {
                    @Override
                    public boolean apply(org.elasticsearch.cluster.routing.MutableShardRouting input) {
                        return true;
                    }
                }).size(),
                rn.shardsWithState(UNASSIGNED).size(),
                rn.shardsWithState(INITIALIZING).size(),
                rn.shardsWithState(RELOCATING).size(),
                rn.shardsWithState(STARTED).size());
        logger.info("--> unassigned: {}, initializing: {}, relocating: {}, started: {}",
                rn.shardsWithState(UNASSIGNED),
                rn.shardsWithState(INITIALIZING),
                rn.shardsWithState(RELOCATING),
                rn.shardsWithState(STARTED));
    }
}
