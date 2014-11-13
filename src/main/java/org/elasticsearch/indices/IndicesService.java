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

package org.elasticsearch.indices;

import com.google.common.collect.ImmutableMap;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.admin.indices.stats.CommonStatsFlags;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.component.LifecycleComponent;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.service.IndexService;

/**
 *
 */
public interface IndicesService extends Iterable<IndexService>, LifecycleComponent<IndicesService> {

    /**
     * Returns <tt>true</tt> if changes (adding / removing) indices, shards and so on are allowed.
     */
    public boolean changesAllowed();

    /**
     * Returns the node stats indices stats. The <tt>includePrevious</tt> flag controls
     * if old shards stats will be aggregated as well (only for relevant stats, such as
     * refresh and indexing, not for docs/store).
     */
    NodeIndicesStats stats(boolean includePrevious);

    NodeIndicesStats stats(boolean includePrevious, CommonStatsFlags flags);

    boolean hasIndex(String index);

    IndicesLifecycle indicesLifecycle();

    /**
     * Returns a snapshot of the started indices and the associated {@link IndexService} instances.
     *
     * The map being returned is not a live view and subsequent calls can return a different view.
     */
    ImmutableMap<String, IndexService> indices();

    /**
     * Returns an IndexService for the specified index if exists otherwise returns <code>null</code>.
     *
     * Even if the index name appeared in {@link #indices()} <code>null</code> can still be returned as an
     * index maybe removed in the meantime, so preferable use the associated {@link IndexService} in order to prevent NPE.
     */
    @Nullable
    IndexService indexService(String index);

    /**
     * Returns an IndexService for the specified index if exists otherwise a {@link IndexMissingException} is thrown.
     */
    IndexService indexServiceSafe(String index) throws IndexMissingException;

    IndexService createIndex(String index, Settings settings, String localNodeId) throws ElasticsearchException;

    void removeIndex(String index, String reason) throws ElasticsearchException;
}
