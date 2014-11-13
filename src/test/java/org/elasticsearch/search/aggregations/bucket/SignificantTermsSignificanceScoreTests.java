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

package org.elasticsearch.search.aggregations.bucket;

import org.elasticsearch.action.index.IndexRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.index.query.FilterBuilders;
import org.elasticsearch.index.query.QueryParsingException;
import org.elasticsearch.plugins.AbstractPlugin;
import org.elasticsearch.search.aggregations.Aggregation;
import org.elasticsearch.search.aggregations.bucket.filter.FilterAggregationBuilder;
import org.elasticsearch.search.aggregations.bucket.filter.InternalFilter;
import org.elasticsearch.search.aggregations.bucket.significant.SignificantTerms;
import org.elasticsearch.search.aggregations.bucket.significant.SignificantTermsAggregatorFactory;
import org.elasticsearch.search.aggregations.bucket.significant.SignificantTermsBuilder;
import org.elasticsearch.search.aggregations.bucket.significant.heuristics.*;
import org.elasticsearch.search.aggregations.bucket.terms.StringTerms;
import org.elasticsearch.search.aggregations.bucket.terms.Terms;
import org.elasticsearch.search.aggregations.bucket.terms.TermsBuilder;
import org.elasticsearch.test.ElasticsearchIntegrationTest;
import org.elasticsearch.test.ElasticsearchIntegrationTest.ClusterScope;
import org.elasticsearch.test.ElasticsearchIntegrationTest.Scope;
import org.junit.Test;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutionException;

import static org.elasticsearch.cluster.metadata.IndexMetaData.SETTING_NUMBER_OF_REPLICAS;
import static org.elasticsearch.cluster.metadata.IndexMetaData.SETTING_NUMBER_OF_SHARDS;
import static org.elasticsearch.common.settings.ImmutableSettings.settingsBuilder;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertSearchResponse;
import static org.hamcrest.Matchers.*;

/**
 *
 */
@ClusterScope(scope = Scope.SUITE)
public class SignificantTermsSignificanceScoreTests extends ElasticsearchIntegrationTest {

    static final String INDEX_NAME = "testidx";
    static final String DOC_TYPE = "doc";
    static final String TEXT_FIELD = "text";
    static final String CLASS_FIELD = "class";

    @Override
    protected Settings nodeSettings(int nodeOrdinal) {
        return settingsBuilder()
                .put(super.nodeSettings(nodeOrdinal))
                .put("plugin.types", CustomSignificanceHeuristicPlugin.class.getName())
                .build();
    }

    public String randomExecutionHint() {
        return randomBoolean() ? null : randomFrom(SignificantTermsAggregatorFactory.ExecutionMode.values()).toString();
    }

    @Test
    public void testPlugin() throws Exception {
        String type = randomBoolean() ? "string" : "long";
        String settings = "{\"index.number_of_shards\": 1, \"index.number_of_replicas\": 0}";
        index01Docs(type, settings);
        SearchResponse response = client().prepareSearch(INDEX_NAME).setTypes(DOC_TYPE)
                .addAggregation(new TermsBuilder("class")
                        .field(CLASS_FIELD)
                        .subAggregation((new SignificantTermsBuilder("sig_terms"))
                                .field(TEXT_FIELD)
                                .significanceHeuristic(new SimpleHeuristic.SimpleHeuristicBuilder())
                                .minDocCount(1)
                        )
                )
                .execute()
                .actionGet();
        assertSearchResponse(response);
        StringTerms classes = (StringTerms) response.getAggregations().get("class");
        assertThat(classes.getBuckets().size(), equalTo(2));
        for (Terms.Bucket classBucket : classes.getBuckets()) {
            Map<String, Aggregation> aggs = classBucket.getAggregations().asMap();
            assertTrue(aggs.containsKey("sig_terms"));
            SignificantTerms agg = (SignificantTerms) aggs.get("sig_terms");
            assertThat(agg.getBuckets().size(), equalTo(2));
            Iterator<SignificantTerms.Bucket> bucketIterator = agg.iterator();
            SignificantTerms.Bucket sigBucket = bucketIterator.next();
            String term = sigBucket.getKey();
            String classTerm = classBucket.getKey();
            assertTrue(term.equals(classTerm));
            assertThat(sigBucket.getSignificanceScore(), closeTo(2.0, 1.e-8));
            sigBucket = bucketIterator.next();
            assertThat(sigBucket.getSignificanceScore(), closeTo(1.0, 1.e-8));
        }

        // we run the same test again but this time we do not call assertSearchResponse() before the assertions
        // the reason is that this would trigger toXContent and we would like to check that this has no potential side effects

        response = client().prepareSearch(INDEX_NAME).setTypes(DOC_TYPE)
                .addAggregation(new TermsBuilder("class")
                        .field(CLASS_FIELD)
                        .subAggregation((new SignificantTermsBuilder("sig_terms"))
                                .field(TEXT_FIELD)
                                .significanceHeuristic(new SimpleHeuristic.SimpleHeuristicBuilder())
                                .minDocCount(1)
                        )
                )
                .execute()
                .actionGet();

        classes = (StringTerms) response.getAggregations().get("class");
        assertThat(classes.getBuckets().size(), equalTo(2));
        for (Terms.Bucket classBucket : classes.getBuckets()) {
            Map<String, Aggregation> aggs = classBucket.getAggregations().asMap();
            assertTrue(aggs.containsKey("sig_terms"));
            SignificantTerms agg = (SignificantTerms) aggs.get("sig_terms");
            assertThat(agg.getBuckets().size(), equalTo(2));
            Iterator<SignificantTerms.Bucket> bucketIterator = agg.iterator();
            SignificantTerms.Bucket sigBucket = bucketIterator.next();
            String term = sigBucket.getKey();
            String classTerm = classBucket.getKey();
            assertTrue(term.equals(classTerm));
            assertThat(sigBucket.getSignificanceScore(), closeTo(2.0, 1.e-8));
            sigBucket = bucketIterator.next();
            assertThat(sigBucket.getSignificanceScore(), closeTo(1.0, 1.e-8));
        }
    }

    public static class CustomSignificanceHeuristicPlugin extends AbstractPlugin {

        @Override
        public String name() {
            return "test-plugin-significance-heuristic";
        }

        @Override
        public String description() {
            return "Significance heuristic plugin";
        }

        public void onModule(SignificantTermsHeuristicModule significanceModule) {
            significanceModule.registerParser(SimpleHeuristic.SimpleHeuristicParser.class);
        }

        public void onModule(TransportSignificantTermsHeuristicModule significanceModule) {
            significanceModule.registerStream(SimpleHeuristic.STREAM);
        }
    }

    public static class SimpleHeuristic extends SignificanceHeuristic {

        protected static final String[] NAMES = {"simple"};

        public static final SignificanceHeuristicStreams.Stream STREAM = new SignificanceHeuristicStreams.Stream() {
            @Override
            public SignificanceHeuristic readResult(StreamInput in) throws IOException {
                return readFrom(in);
            }

            @Override
            public String getName() {
                return NAMES[0];
            }
        };

        public static SignificanceHeuristic readFrom(StreamInput in) throws IOException {
            return new SimpleHeuristic();
        }

        /**
         * @param subsetFreq   The frequency of the term in the selected sample
         * @param subsetSize   The size of the selected sample (typically number of docs)
         * @param supersetFreq The frequency of the term in the superset from which the sample was taken
         * @param supersetSize The size of the superset from which the sample was taken  (typically number of docs)
         * @return a "significance" score
         */
        @Override
        public double getScore(long subsetFreq, long subsetSize, long supersetFreq, long supersetSize) {
            return subsetFreq / subsetSize > supersetFreq / supersetSize ? 2.0 : 1.0;
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            out.writeString(STREAM.getName());
        }

        public static class SimpleHeuristicParser implements SignificanceHeuristicParser {

            @Override
            public SignificanceHeuristic parse(XContentParser parser) throws IOException, QueryParsingException {
                parser.nextToken();
                return new SimpleHeuristic();
            }

            @Override
            public String[] getNames() {
                return NAMES;
            }
        }

        public static class SimpleHeuristicBuilder implements SignificanceHeuristicBuilder {

            @Override
            public void toXContent(XContentBuilder builder) throws IOException {
                builder.startObject(STREAM.getName()).endObject();
            }
        }
    }


    @Test
    public void testXContentResponse() throws Exception {

        String type = randomBoolean() ? "string" : "long";
        String settings = "{\"index.number_of_shards\": 1, \"index.number_of_replicas\": 0}";
        index01Docs(type, settings);
        SearchResponse response = client().prepareSearch(INDEX_NAME).setTypes(DOC_TYPE)
                .addAggregation(new TermsBuilder("class").field(CLASS_FIELD).subAggregation(new SignificantTermsBuilder("sig_terms").field(TEXT_FIELD)))
                .execute()
                .actionGet();
        assertSearchResponse(response);
        StringTerms classes = (StringTerms) response.getAggregations().get("class");
        assertThat(classes.getBuckets().size(), equalTo(2));
        for (Terms.Bucket classBucket : classes.getBuckets()) {
            Map<String, Aggregation> aggs = classBucket.getAggregations().asMap();
            assertTrue(aggs.containsKey("sig_terms"));
            SignificantTerms agg = (SignificantTerms) aggs.get("sig_terms");
            assertThat(agg.getBuckets().size(), equalTo(1));
            String term = agg.iterator().next().getKey();
            String classTerm = classBucket.getKey();
            assertTrue(term.equals(classTerm));
        }

        XContentBuilder responseBuilder = XContentFactory.jsonBuilder();
        classes.toXContent(responseBuilder, null);
        String result = null;
        if (type.equals("long")) {
            result = "\"class\"{\"doc_count_error_upper_bound\":0,\"buckets\":[{\"key\":\"0\",\"doc_count\":4,\"sig_terms\":{\"doc_count\":4,\"buckets\":[{\"key\":0,\"key_as_string\":\"0\",\"doc_count\":4,\"score\":0.39999999999999997,\"bg_count\":5}]}},{\"key\":\"1\",\"doc_count\":3,\"sig_terms\":{\"doc_count\":3,\"buckets\":[{\"key\":1,\"key_as_string\":\"1\",\"doc_count\":3,\"score\":0.75,\"bg_count\":4}]}}]}";
        } else {
            result = "\"class\"{\"doc_count_error_upper_bound\":0,\"buckets\":[{\"key\":\"0\",\"doc_count\":4,\"sig_terms\":{\"doc_count\":4,\"buckets\":[{\"key\":\"0\",\"doc_count\":4,\"score\":0.39999999999999997,\"bg_count\":5}]}},{\"key\":\"1\",\"doc_count\":3,\"sig_terms\":{\"doc_count\":3,\"buckets\":[{\"key\":\"1\",\"doc_count\":3,\"score\":0.75,\"bg_count\":4}]}}]}";
        }
        assertThat(responseBuilder.string(), equalTo(result));

    }

    @Test
    public void testBackgroundVsSeparateSet() throws Exception {
        String type = randomBoolean() ? "string" : "long";
        String settings = "{\"index.number_of_shards\": 1, \"index.number_of_replicas\": 0}";
        index01Docs(type, settings);
        testBackgroundVsSeparateSet(new MutualInformation.MutualInformationBuilder(true, true), new MutualInformation.MutualInformationBuilder(true, false));
        testBackgroundVsSeparateSet(new ChiSquare.ChiSquareBuilder(true, true), new ChiSquare.ChiSquareBuilder(true, false));
        testBackgroundVsSeparateSet(new GND.GNDBuilder(true), new GND.GNDBuilder(false));
    }

    // compute significance score by
    // 1. terms agg on class and significant terms
    // 2. filter buckets and set the background to the other class and set is_background false
    // both should yield exact same result
    public void testBackgroundVsSeparateSet(SignificanceHeuristicBuilder significanceHeuristicExpectingSuperset, SignificanceHeuristicBuilder significanceHeuristicExpectingSeparateSets) throws Exception {

        SearchResponse response1 = client().prepareSearch(INDEX_NAME).setTypes(DOC_TYPE)
                .addAggregation(new TermsBuilder("class")
                        .field(CLASS_FIELD)
                        .subAggregation(
                                new SignificantTermsBuilder("sig_terms")
                                        .field(TEXT_FIELD)
                                        .minDocCount(1)
                                        .significanceHeuristic(
                                                significanceHeuristicExpectingSuperset)))
                .execute()
                .actionGet();
        assertSearchResponse(response1);
        SearchResponse response2 = client().prepareSearch(INDEX_NAME).setTypes(DOC_TYPE)
                .addAggregation((new FilterAggregationBuilder("0"))
                        .filter(FilterBuilders.termFilter(CLASS_FIELD, "0"))
                        .subAggregation(new SignificantTermsBuilder("sig_terms")
                                .field(TEXT_FIELD)
                                .minDocCount(1)
                                .backgroundFilter(FilterBuilders.termFilter(CLASS_FIELD, "1"))
                                .significanceHeuristic(significanceHeuristicExpectingSeparateSets)))
                .addAggregation((new FilterAggregationBuilder("1"))
                        .filter(FilterBuilders.termFilter(CLASS_FIELD, "1"))
                        .subAggregation(new SignificantTermsBuilder("sig_terms")
                                .field(TEXT_FIELD)
                                .minDocCount(1)
                                .backgroundFilter(FilterBuilders.termFilter(CLASS_FIELD, "0"))
                                .significanceHeuristic(significanceHeuristicExpectingSeparateSets)))
                .execute()
                .actionGet();

        SignificantTerms sigTerms0 = ((SignificantTerms) (((StringTerms) response1.getAggregations().get("class")).getBucketByKey("0").getAggregations().asMap().get("sig_terms")));
        assertThat(sigTerms0.getBuckets().size(), equalTo(2));
        double score00Background = sigTerms0.getBucketByKey("0").getSignificanceScore();
        double score01Background = sigTerms0.getBucketByKey("1").getSignificanceScore();
        SignificantTerms sigTerms1 = ((SignificantTerms) (((StringTerms) response1.getAggregations().get("class")).getBucketByKey("1").getAggregations().asMap().get("sig_terms")));
        double score10Background = sigTerms1.getBucketByKey("0").getSignificanceScore();
        double score11Background = sigTerms1.getBucketByKey("1").getSignificanceScore();

        double score00SeparateSets = ((SignificantTerms) ((InternalFilter) response2.getAggregations().get("0")).getAggregations().getAsMap().get("sig_terms")).getBucketByKey("0").getSignificanceScore();
        double score01SeparateSets = ((SignificantTerms) ((InternalFilter) response2.getAggregations().get("0")).getAggregations().getAsMap().get("sig_terms")).getBucketByKey("1").getSignificanceScore();
        double score10SeparateSets = ((SignificantTerms) ((InternalFilter) response2.getAggregations().get("1")).getAggregations().getAsMap().get("sig_terms")).getBucketByKey("0").getSignificanceScore();
        double score11SeparateSets = ((SignificantTerms) ((InternalFilter) response2.getAggregations().get("1")).getAggregations().getAsMap().get("sig_terms")).getBucketByKey("1").getSignificanceScore();

        assertThat(score00Background, equalTo(score00SeparateSets));
        assertThat(score01Background, equalTo(score01SeparateSets));
        assertThat(score10Background, equalTo(score10SeparateSets));
        assertThat(score11Background, equalTo(score11SeparateSets));
    }

    private void index01Docs(String type, String settings) throws ExecutionException, InterruptedException {
        String mappings = "{\"doc\": {\"properties\":{\"text\": {\"type\":\"" + type + "\"}}}}";
        assertAcked(prepareCreate(INDEX_NAME).setSettings(settings).addMapping("doc", mappings));
        String[] gb = {"0", "1"};
        List<IndexRequestBuilder> indexRequestBuilderList = new ArrayList<>();
        indexRequestBuilderList.add(client().prepareIndex(INDEX_NAME, DOC_TYPE, "1")
                .setSource(TEXT_FIELD, "1", CLASS_FIELD, "1"));
        indexRequestBuilderList.add(client().prepareIndex(INDEX_NAME, DOC_TYPE, "2")
                .setSource(TEXT_FIELD, "1", CLASS_FIELD, "1"));
        indexRequestBuilderList.add(client().prepareIndex(INDEX_NAME, DOC_TYPE, "3")
                .setSource(TEXT_FIELD, "0", CLASS_FIELD, "0"));
        indexRequestBuilderList.add(client().prepareIndex(INDEX_NAME, DOC_TYPE, "4")
                .setSource(TEXT_FIELD, "0", CLASS_FIELD, "0"));
        indexRequestBuilderList.add(client().prepareIndex(INDEX_NAME, DOC_TYPE, "5")
                .setSource(TEXT_FIELD, gb, CLASS_FIELD, "1"));
        indexRequestBuilderList.add(client().prepareIndex(INDEX_NAME, DOC_TYPE, "6")
                .setSource(TEXT_FIELD, gb, CLASS_FIELD, "0"));
        indexRequestBuilderList.add(client().prepareIndex(INDEX_NAME, DOC_TYPE, "7")
                .setSource(TEXT_FIELD, "0", CLASS_FIELD, "0"));
        indexRandom(true, indexRequestBuilderList);
    }

    @Test
    public void testScoresEqualForPositiveAndNegative() throws Exception {
        indexEqualTestData();
        testScoresEqualForPositiveAndNegative(new MutualInformation.MutualInformationBuilder(true, true));
        testScoresEqualForPositiveAndNegative(new ChiSquare.ChiSquareBuilder(true, true));
    }

    public void testScoresEqualForPositiveAndNegative(SignificanceHeuristicBuilder heuristic) throws Exception {

        //check that results for both classes are the same with exclude negatives = false and classes are routing ids
        SearchResponse response = client().prepareSearch("test")
                .addAggregation(new TermsBuilder("class").field("class").subAggregation(new SignificantTermsBuilder("mySignificantTerms")
                        .field("text")
                        .executionHint(randomExecutionHint())
                        .significanceHeuristic(heuristic)
                        .minDocCount(1).shardSize(1000).size(1000)))
                .execute()
                .actionGet();
        assertSearchResponse(response);
        StringTerms classes = (StringTerms) response.getAggregations().get("class");
        assertThat(classes.getBuckets().size(), equalTo(2));
        Iterator<Terms.Bucket> classBuckets = classes.getBuckets().iterator();
        Collection<SignificantTerms.Bucket> classA = ((SignificantTerms) classBuckets.next().getAggregations().get("mySignificantTerms")).getBuckets();
        Iterator<SignificantTerms.Bucket> classBBucketIterator = ((SignificantTerms) classBuckets.next().getAggregations().get("mySignificantTerms")).getBuckets().iterator();
        assertThat(classA.size(), greaterThan(0));
        for (SignificantTerms.Bucket classABucket : classA) {
            SignificantTerms.Bucket classBBucket = classBBucketIterator.next();
            assertThat(classABucket.getKey(), equalTo(classBBucket.getKey()));
            assertThat(classABucket.getSignificanceScore(), closeTo(classBBucket.getSignificanceScore(), 1.e-5));
        }
    }

    private void indexEqualTestData() throws ExecutionException, InterruptedException {
        assertAcked(prepareCreate("test").setSettings(SETTING_NUMBER_OF_SHARDS, 1, SETTING_NUMBER_OF_REPLICAS, 0).addMapping("doc",
                "text", "type=string", "class", "type=string"));
        createIndex("idx_unmapped");

        ensureGreen();
        String data[] = {
                "A\ta",
                "A\ta",
                "A\tb",
                "A\tb",
                "A\tb",
                "B\tc",
                "B\tc",
                "B\tc",
                "B\tc",
                "B\td",
                "B\td",
                "B\td",
                "B\td",
                "B\td",
                "A\tc d",
                "B\ta b"
        };

        List<IndexRequestBuilder> indexRequestBuilders = new ArrayList<>();
        for (int i = 0; i < data.length; i++) {
            String[] parts = data[i].split("\t");
            indexRequestBuilders.add(client().prepareIndex("test", "doc", "" + i)
                    .setSource("class", parts[0], "text", parts[1]));
        }
        indexRandom(true, indexRequestBuilders);
    }
}
