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

package org.elasticsearch.index.store.fs;

import org.elasticsearch.ElasticsearchIllegalStateException;
import org.elasticsearch.common.io.FileSystemUtils;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.NodeEnvironment;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.service.IndexService;
import org.elasticsearch.index.settings.IndexSettings;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.index.store.support.AbstractIndexStore;
import org.elasticsearch.indices.store.IndicesStore;

import java.io.File;
import java.io.IOException;

/**
 *
 */
public abstract class FsIndexStore extends AbstractIndexStore {

    private final NodeEnvironment nodeEnv;

    private final File[] locations;

    public FsIndexStore(Index index, @IndexSettings Settings indexSettings, IndexService indexService, IndicesStore indicesStore, NodeEnvironment nodeEnv) {
        super(index, indexSettings, indexService, indicesStore);
        this.nodeEnv = nodeEnv;
        if (nodeEnv.hasNodeFile()) {
            this.locations = nodeEnv.indexLocations(index);
        } else {
            this.locations = null;
        }
    }

    @Override
    public boolean persistent() {
        return true;
    }

    @Override
    public boolean canDeleteUnallocated(ShardId shardId) {
        if (locations == null) {
            return false;
        }
        if (indexService.hasShard(shardId.id())) {
            return false;
        }
        for (File location : shardLocations(shardId)) {
            if (location.exists()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void deleteUnallocated(ShardId shardId) throws IOException {
        if (locations == null) {
            return;
        }
        if (indexService.hasShard(shardId.id())) {
            throw new ElasticsearchIllegalStateException(shardId + " allocated, can't be deleted");
        }
        FileSystemUtils.deleteRecursively(shardLocations(shardId));
    }

    public File[] shardLocations(ShardId shardId) {
        return nodeEnv.shardLocations(shardId);
    }

    public File[] shardIndexLocations(ShardId shardId) {
        File[] shardLocations = shardLocations(shardId);
        File[] shardIndexLocations = new File[shardLocations.length];
        for (int i = 0; i < shardLocations.length; i++) {
            shardIndexLocations[i] = new File(shardLocations[i], "index");
        }
        return shardIndexLocations;
    }

    // not used currently, but here to state that this store also defined a file based translog location

    public File[] shardTranslogLocations(ShardId shardId) {
        File[] shardLocations = shardLocations(shardId);
        File[] shardTranslogLocations = new File[shardLocations.length];
        for (int i = 0; i < shardLocations.length; i++) {
            shardTranslogLocations[i] = new File(shardLocations[i], "translog");
        }
        return shardTranslogLocations;
    }
}
