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

package org.elasticsearch.index.shard.service;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.cluster.routing.ShardRouting;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.index.VersionType;
import org.elasticsearch.index.cache.filter.FilterCacheStats;
import org.elasticsearch.index.cache.filter.ShardFilterCache;
import org.elasticsearch.index.cache.id.IdCacheStats;
import org.elasticsearch.index.cache.query.ShardQueryCache;
import org.elasticsearch.index.deletionpolicy.SnapshotIndexCommit;
import org.elasticsearch.index.engine.Engine;
import org.elasticsearch.index.engine.EngineException;
import org.elasticsearch.index.engine.SegmentsStats;
import org.elasticsearch.index.fielddata.FieldDataStats;
import org.elasticsearch.index.fielddata.IndexFieldDataService;
import org.elasticsearch.index.fielddata.ShardFieldData;
import org.elasticsearch.index.flush.FlushStats;
import org.elasticsearch.index.get.GetStats;
import org.elasticsearch.index.get.ShardGetService;
import org.elasticsearch.index.indexing.IndexingStats;
import org.elasticsearch.index.indexing.ShardIndexingService;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.index.mapper.ParsedDocument;
import org.elasticsearch.index.mapper.SourceToParse;
import org.elasticsearch.index.merge.MergeStats;
import org.elasticsearch.index.percolator.PercolatorQueriesRegistry;
import org.elasticsearch.index.percolator.stats.ShardPercolateService;
import org.elasticsearch.index.cache.fixedbitset.ShardFixedBitSetFilterCache;
import org.elasticsearch.index.refresh.RefreshStats;
import org.elasticsearch.index.search.stats.SearchStats;
import org.elasticsearch.index.search.stats.ShardSearchService;
import org.elasticsearch.index.service.IndexService;
import org.elasticsearch.index.shard.DocsStats;
import org.elasticsearch.index.shard.IllegalIndexShardStateException;
import org.elasticsearch.index.shard.IndexShardComponent;
import org.elasticsearch.index.shard.IndexShardState;
import org.elasticsearch.index.store.StoreStats;
import org.elasticsearch.index.suggest.stats.ShardSuggestService;
import org.elasticsearch.index.suggest.stats.SuggestStats;
import org.elasticsearch.index.termvectors.ShardTermVectorService;
import org.elasticsearch.index.translog.TranslogStats;
import org.elasticsearch.index.warmer.ShardIndexWarmerService;
import org.elasticsearch.index.warmer.WarmerStats;
import org.elasticsearch.search.suggest.completion.CompletionStats;

/**
 *
 */
public interface IndexShard extends IndexShardComponent {

    ShardIndexingService indexingService();

    ShardGetService getService();

    ShardSearchService searchService();

    ShardIndexWarmerService warmerService();

    ShardFilterCache filterCache();

    ShardQueryCache queryCache();

    ShardFieldData fieldData();

    /**
     * Returns the latest cluster routing entry received with this shard. Might be null if the
     * shard was just created.
     */
    @Nullable
    ShardRouting routingEntry();

    DocsStats docStats();

    StoreStats storeStats();

    IndexingStats indexingStats(String... types);

    SearchStats searchStats(String... groups);

    GetStats getStats();

    MergeStats mergeStats();

    SegmentsStats segmentStats();

    RefreshStats refreshStats();

    FlushStats flushStats();

    WarmerStats warmerStats();

    FilterCacheStats filterCacheStats();

    IdCacheStats idCacheStats();

    FieldDataStats fieldDataStats(String... fields);

    CompletionStats completionStats(String... fields);

    TranslogStats translogStats();

    SuggestStats suggestStats();

    PercolatorQueriesRegistry percolateRegistry();

    ShardPercolateService shardPercolateService();

    ShardTermVectorService termVectorService();

    ShardSuggestService shardSuggestService();

    ShardFixedBitSetFilterCache shardFixedBitSetFilterCache();

    MapperService mapperService();

    IndexFieldDataService indexFieldDataService();

    IndexService indexService();

    IndexShardState state();

    Engine.Create prepareCreate(SourceToParse source, long version, VersionType versionType, Engine.Operation.Origin origin, boolean canHaveDuplicates, boolean autoGeneratedId) throws ElasticsearchException;

    ParsedDocument create(Engine.Create create) throws ElasticsearchException;

    Engine.Index prepareIndex(SourceToParse source, long version, VersionType versionType, Engine.Operation.Origin origin, boolean canHaveDuplicates) throws ElasticsearchException;

    ParsedDocument index(Engine.Index index) throws ElasticsearchException;

    Engine.Delete prepareDelete(String type, String id, long version, VersionType versionType, Engine.Operation.Origin origin) throws ElasticsearchException;

    void delete(Engine.Delete delete) throws ElasticsearchException;

    Engine.DeleteByQuery prepareDeleteByQuery(BytesReference source, @Nullable String[] filteringAliases, Engine.Operation.Origin origin, String... types) throws ElasticsearchException;

    void deleteByQuery(Engine.DeleteByQuery deleteByQuery) throws ElasticsearchException;

    Engine.GetResult get(Engine.Get get) throws ElasticsearchException;

    void refresh(Engine.Refresh refresh) throws ElasticsearchException;

    void flush(Engine.Flush flush) throws ElasticsearchException;

    void optimize(Engine.Optimize optimize) throws ElasticsearchException;

    SnapshotIndexCommit snapshotIndex() throws EngineException;

    void recover(Engine.RecoveryHandler recoveryHandler) throws EngineException;

    void failShard(String reason, @Nullable Throwable e);

    Engine.Searcher acquireSearcher(String source);

    Engine.Searcher acquireSearcher(String source, Mode mode);

    /**
     * Returns <tt>true</tt> if this shard can ignore a recovery attempt made to it (since the already doing/done it)
     */
    public boolean ignoreRecoveryAttempt();

    void readAllowed() throws IllegalIndexShardStateException;

    void readAllowed(Mode mode) throws IllegalIndexShardStateException;

    public enum Mode {
        READ,
        WRITE
    }
}
