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

package org.elasticsearch.search.fetch.innerhits;

import com.google.common.collect.ImmutableMap;
import org.apache.lucene.index.AtomicReader;
import org.apache.lucene.index.AtomicReaderContext;
import org.apache.lucene.index.Term;
import org.apache.lucene.queries.TermFilter;
import org.apache.lucene.search.*;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.FixedBitSet;
import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.cache.recycler.CacheRecycler;
import org.elasticsearch.common.lucene.search.AndFilter;
import org.elasticsearch.common.lucene.search.XFilteredQuery;
import org.elasticsearch.index.cache.docset.DocSetCache;
import org.elasticsearch.index.cache.fixedbitset.FixedBitSetFilter;
import org.elasticsearch.index.mapper.DocumentMapper;
import org.elasticsearch.index.mapper.Uid;
import org.elasticsearch.index.mapper.internal.ParentFieldMapper;
import org.elasticsearch.index.mapper.internal.UidFieldMapper;
import org.elasticsearch.index.mapper.object.ObjectMapper;
import org.elasticsearch.index.query.ParsedQuery;
import org.elasticsearch.index.search.nested.NonNestedDocsFilter;
import org.elasticsearch.search.facet.SearchContextFacets;
import org.elasticsearch.search.fetch.FetchSubPhase;
import org.elasticsearch.search.internal.FilteredSearchContext;
import org.elasticsearch.search.internal.SearchContext;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;

/**
 */
public final class InnerHitsContext {

    private Map<String, BaseInnerHits> innerHits;

    public InnerHitsContext(Map<String, BaseInnerHits> innerHits) {
        this.innerHits = innerHits;
    }

    public Map<String, BaseInnerHits> getInnerHits() {
        return innerHits;
    }

    public static abstract class BaseInnerHits extends FilteredSearchContext {

        protected final Query query;
        private final InnerHitsContext childInnerHits;

        protected BaseInnerHits(SearchContext context, Query query, Map<String, BaseInnerHits> childInnerHits) {
            super(context);
            this.query = query;
            if (childInnerHits != null && !childInnerHits.isEmpty()) {
                this.childInnerHits = new InnerHitsContext(childInnerHits);
            } else {
                this.childInnerHits = null;
            }
        }

        @Override
        public Query query() {
            return query;
        }

        @Override
        public ParsedQuery parsedQuery() {
            return new ParsedQuery(query, ImmutableMap.<String, Filter>of());
        }

        public abstract TopDocs topDocs(SearchContext context, FetchSubPhase.HitContext hitContext);

        @Override
        public InnerHitsContext innerHits() {
            return childInnerHits;
        }

    }

    public static final class NestedInnerHits extends BaseInnerHits {

        private final ObjectMapper parentObjectMapper;
        private final ObjectMapper childObjectMapper;

        public NestedInnerHits(SearchContext context, Query query, Map<String, BaseInnerHits> childInnerHits, ObjectMapper parentObjectMapper, ObjectMapper childObjectMapper) {
            super(context, query, childInnerHits);
            this.parentObjectMapper = parentObjectMapper;
            this.childObjectMapper = childObjectMapper;
        }

        @Override
        public TopDocs topDocs(SearchContext context, FetchSubPhase.HitContext hitContext) {
            TopDocsCollector topDocsCollector;
            int topN = from() + size();
            if (sort() != null) {
                try {
                    topDocsCollector = TopFieldCollector.create(sort(), topN, true, trackScores(), trackScores(), false);
                } catch (IOException e) {
                    throw ExceptionsHelper.convertToElastic(e);
                }
            } else {
                topDocsCollector = TopScoreDocCollector.create(topN, false);
            }

            Filter rawParentFiter;
            if (parentObjectMapper == null) {
                rawParentFiter = NonNestedDocsFilter.INSTANCE;
            } else {
                rawParentFiter = parentObjectMapper.nestedTypeFilter();
            }
            FixedBitSetFilter parentFilter = context.fixedBitSetFilterCache().getFixedBitSetFilter(rawParentFiter);
            FixedBitSetFilter childFilter = context.fixedBitSetFilterCache().getFixedBitSetFilter(childObjectMapper.nestedTypeFilter());
            try {
                Query q = new XFilteredQuery(query, new NestedChildrenFilter(parentFilter, childFilter, hitContext));
                context.searcher().search(q, topDocsCollector);
            } catch (IOException e) {
                throw ExceptionsHelper.convertToElastic(e);
            }
            return topDocsCollector.topDocs(from(), size());
        }

        @Override
        public SearchContextFacets facets() {
            return null;
        }

        @Override
        public SearchContext facets(SearchContextFacets facets) {
            return null;
        }

        @Override
        public CacheRecycler cacheRecycler() {
            return null;
        }

        @Override
        public DocSetCache docSetCache() {
            return null;
        }

        // A filter that only emits the nested children docs of a specific nested parent doc
        private static class NestedChildrenFilter extends Filter {

            private final FixedBitSetFilter parentFilter;
            private final FixedBitSetFilter childFilter;
            private final int docId;
            private final AtomicReader atomicReader;

            private NestedChildrenFilter(FixedBitSetFilter parentFilter, FixedBitSetFilter childFilter, FetchSubPhase.HitContext hitContext) {
                this.parentFilter = parentFilter;
                this.childFilter = childFilter;
                this.docId = hitContext.docId();
//                System.out.println("docId=" + docId);
                this.atomicReader = hitContext.readerContext().reader();
            }

            @Override
            public DocIdSet getDocIdSet(AtomicReaderContext context, final Bits acceptDocs) throws IOException {
                // Nested docs only reside in a single segment, so no need to evaluate all segments
                if (!context.reader().getCoreCacheKey().equals(this.atomicReader.getCoreCacheKey())) {
                    return null;
                }

//                System.out.println("maxDoc=" + context.reader().maxDoc());
                FixedBitSet parents = parentFilter.getDocIdSet(context, acceptDocs);
                final int firstChildDocId = parents.prevSetBit(docId - 1) + 1;
//                System.out.println("firstChildDocId=" + firstChildDocId);
                final FixedBitSet children = childFilter.getDocIdSet(context, acceptDocs);
                return new DocIdSet() {
                    @Override
                    public DocIdSetIterator iterator() throws IOException {
                        return new DocIdSetIterator() {

                            int currentDocId = -1;

                            @Override
                            public int docID() {
                                return currentDocId;
                            }

                            @Override
                            public int nextDoc() throws IOException {
                                if (currentDocId == -1) {
                                    return advance(firstChildDocId);
                                } else {
                                    return advance(currentDocId + 1);
                                }
                            }

                            @Override
                            public int advance(int target) throws IOException {
//                                System.out.println("target=" + target);
                                if (target >= docId) {
                                    // We're outside the child nested scope, so it is done
                                    return currentDocId = NO_MORE_DOCS;
                                }
                                int advanced = children.nextSetBit(target);
//                                System.out.println("advanced=" + advanced);
                                if (advanced == -1|| advanced >= docId) {
                                    return currentDocId = NO_MORE_DOCS;
                                } else {
                                    return currentDocId = advanced;
                                }
                            }

                            @Override
                            public long cost() {
                                return 1;
                            }
                        };
                    }
                };
            }
        }

        private static class NestedValidatingQuery extends Query {

            private final Query query;
            private final FixedBitSetFilter validSpace;

            private NestedValidatingQuery(Query query, FixedBitSetFilter validSpace) {
                this.query = query;
                this.validSpace = validSpace;
            }

            @Override
            public Weight createWeight(IndexSearcher searcher) throws IOException {
                return new W(query.createWeight(searcher), validSpace);
            }

            @Override
            public String toString(String field) {
                return "validating(" + query.toString(field) + ")";
            }

            private static class W extends Weight {

                private final Weight weight;
                private final FixedBitSetFilter validSpace;

                private W(Weight weight, FixedBitSetFilter validSpace) {
                    this.weight = weight;
                    this.validSpace = validSpace;
                }

                @Override
                public Explanation explain(AtomicReaderContext context, int doc) throws IOException {
                    return weight.explain(context, doc);
                }

                @Override
                public Query getQuery() {
                    return weight.getQuery();
                }

                @Override
                public float getValueForNormalization() throws IOException {
                    return weight.getValueForNormalization();
                }

                @Override
                public void normalize(float norm, float topLevelBoost) {
                    weight.normalize(norm, topLevelBoost);
                }

                @Override
                public Scorer scorer(AtomicReaderContext context, Bits acceptDocs) throws IOException {
                    return new S(weight, weight.scorer(context, acceptDocs), validSpace.getDocIdSet(context, acceptDocs));
                }

                private static class S extends Scorer {

                    private final Scorer scorer;
                    private final FixedBitSet validSpace;

                    private S(Weight weight, Scorer scorer, FixedBitSet validSpace) {
                        super(weight);
                        this.scorer = scorer;
                        this.validSpace = validSpace;
                    }

                    @Override
                    public float score() throws IOException {
                        return scorer.score();
                    }

                    @Override
                    public int freq() throws IOException {
                        return scorer.freq();
                    }

                    @Override
                    public int docID() {
                        return scorer.docID();
                    }

                    @Override
                    public int nextDoc() throws IOException {
                        int docId = scorer.nextDoc();
                        assert validSpace.get(docId) : "docId=" + docId + " is not valid";
                        return docId;
                    }

                    @Override
                    public int advance(int target) throws IOException {
                        int docId = scorer.advance(target);
                        assert validSpace.get(docId) : "docId=" + docId + " is not valid";
                        return docId;
                    }

                    @Override
                    public long cost() {
                        return scorer.cost();
                    }
                }
            }
        }

    }

    public static final class ParentChildInnerHits extends BaseInnerHits {

        private final DocumentMapper documentMapper;

        public ParentChildInnerHits(SearchContext context, Query query, Map<String, BaseInnerHits> childInnerHits, DocumentMapper documentMapper) {
            super(context, query, childInnerHits);
            this.documentMapper = documentMapper;
        }

        @Override
        public TopDocs topDocs(SearchContext context, FetchSubPhase.HitContext hitContext) {
            TopDocsCollector topDocsCollector;
            int topN = from() + size();
            if (sort() != null) {
                try {
                    topDocsCollector = TopFieldCollector.create(sort(), topN, true, trackScores(), trackScores(), false);
                } catch (IOException e) {
                    throw ExceptionsHelper.convertToElastic(e);
                }
            } else {
                topDocsCollector = TopScoreDocCollector.create(topN, false);
            }

            String field;
            ParentFieldMapper hitParentFieldMapper = documentMapper.parentFieldMapper();
            if (hitParentFieldMapper.active()) {
                // Hit has a active _parent field and it is a child doc, so we want a parent doc as inner hits.
                field = ParentFieldMapper.NAME;
            } else {
                // Hit has no active _parent field and it is a parent doc, so we want children docs as inner hits.
                field = UidFieldMapper.NAME;
            }
            String term = Uid.createUid(hitContext.hit().type(), hitContext.hit().id());
            Filter filter = new TermFilter(new Term(field, term)); // Only include docs that have the current hit as parent
            Filter typeFilter = documentMapper.typeFilter(); // Only include docs that have this inner hits type.
            try {
                context.searcher().search(
                        new XFilteredQuery(query, new AndFilter(Arrays.asList(filter, typeFilter))),
                        topDocsCollector
                );
            } catch (IOException e) {
                throw ExceptionsHelper.convertToElastic(e);
            }
            return topDocsCollector.topDocs(from(), size());
        }

        @Override
        public SearchContextFacets facets() {
            return null;
        }

        @Override
        public SearchContext facets(SearchContextFacets facets) {
            return null;
        }

        @Override
        public CacheRecycler cacheRecycler() {
            return null;
        }

        @Override
        public DocSetCache docSetCache() {
            return null;
        }
    }
}