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

package org.elasticsearch.discovery.zen.ping;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.Version;
import org.elasticsearch.cluster.ClusterName;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.component.LifecycleComponent;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Streamable;
import org.elasticsearch.common.unit.TimeValue;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.elasticsearch.cluster.ClusterName.readClusterName;
import static org.elasticsearch.cluster.node.DiscoveryNode.readNode;

/**
 *
 */
public interface ZenPing extends LifecycleComponent<ZenPing> {

    void setPingContextProvider(PingContextProvider contextProvider);

    void ping(PingListener listener, TimeValue timeout) throws ElasticsearchException;

    public interface PingListener {

        void onPing(PingResponse[] pings);
    }

    public static class PingResponse implements Streamable {

        public static final PingResponse[] EMPTY = new PingResponse[0];

        private static final AtomicLong idGenerator = new AtomicLong();


        // backwards compatibility value for the id field
        private static final long UNKNOWN_ID = -1;

        // an always increasing unique identifier for this ping response.
        // lower values means older pings.
        private long id;

        private ClusterName clusterName;

        private DiscoveryNode node;

        private DiscoveryNode master;

        @Nullable
        private Boolean hasJoinedOnce;

        private PingResponse() {
        }

        /**
         * @param node          the node which this ping describes
         * @param master        the current master of the node
         * @param clusterName   the cluster name of the node
         * @param hasJoinedOnce true if the joined has successfully joined the cluster before
         */
        public PingResponse(DiscoveryNode node, DiscoveryNode master, ClusterName clusterName, boolean hasJoinedOnce) {
            this.id = idGenerator.incrementAndGet();
            this.node = node;
            this.master = master;
            this.clusterName = clusterName;
            this.hasJoinedOnce = hasJoinedOnce;
        }

        /**
         * an always increasing unique identifier for this ping response.
         * lower values means older pings.
         *
         * May be set to {@link #UNKNOWN_ID} if the ping comes from nodes with version <1.4.0.Beta1
         */
        public long id() {
            return this.id;
        }

        public ClusterName clusterName() {
            return this.clusterName;
        }

        /** the node which this ping describes */
        public DiscoveryNode node() {
            return node;
        }

        /** the current master of the node */
        public DiscoveryNode master() {
            return master;
        }

        /** true if the node has successfully joined the cluster before, null for nodes with a <1.4.0 version */
        @Nullable
        public Boolean hasJoinedOnce() {
            return hasJoinedOnce;
        }

        public static PingResponse readPingResponse(StreamInput in) throws IOException {
            PingResponse response = new PingResponse();
            response.readFrom(in);
            return response;
        }

        @Override
        public void readFrom(StreamInput in) throws IOException {
            clusterName = readClusterName(in);
            node = readNode(in);
            if (in.readBoolean()) {
                master = readNode(in);
            }
            if (in.getVersion().onOrAfter(Version.V_1_4_0_Beta1)) {
                if (in.readBoolean()) {
                    this.hasJoinedOnce = in.readBoolean();
                }
                this.id = in.readLong();
            } else {
                this.hasJoinedOnce = null;
                this.id = UNKNOWN_ID;
            }
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            clusterName.writeTo(out);
            node.writeTo(out);
            if (master == null) {
                out.writeBoolean(false);
            } else {
                out.writeBoolean(true);
                master.writeTo(out);
            }
            if (out.getVersion().onOrAfter(Version.V_1_4_0_Beta1)) {
                if (hasJoinedOnce != null) {
                    out.writeBoolean(true);
                    out.writeBoolean(hasJoinedOnce);
                } else {
                    out.writeBoolean(false);
                }
                out.writeLong(id);
            }
        }

        @Override
        public String toString() {
            return "ping_response{node [" + node + "], id[" + id + "], master [" + master + "], hasJoinedOnce [" + hasJoinedOnce + "], cluster_name[" + clusterName.value() + "]}";
        }
    }


    /**
     * a utility collection of pings where only the most recent ping is stored per node
     */
    public static class PingCollection {

        Map<DiscoveryNode, PingResponse> pings;

        public PingCollection() {
            pings = new HashMap<>();
        }

        /**
         * adds a ping if newer than previous pings from the same node
         *
         * @return true if added, false o.w.
         */
        public synchronized boolean addPing(PingResponse ping) {
            PingResponse existingResponse = pings.get(ping.node());
            // in case both existing and new ping have the same id (probably because they come
            // from nodes from version <1.4.0) we prefer to use the last added one.
            if (existingResponse == null || existingResponse.id() <= ping.id()) {
                pings.put(ping.node(), ping);
                return true;
            }
            return false;
        }

        /** adds multiple pings if newer than previous pings from the same node */
        public synchronized void addPings(PingResponse[] pings) {
            for (PingResponse ping : pings) {
                addPing(ping);
            }
        }

        /** serialize current pings to an array */
        public synchronized PingResponse[] toArray() {
            return pings.values().toArray(new PingResponse[pings.size()]);
        }

    }
}
