/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.shield.authz.accesscontrol;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.NoMergePolicy;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.LeafCollector;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.Weight;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.RAMDirectory;
import org.apache.lucene.util.Accountable;
import org.apache.lucene.util.SparseFixedBitSet;
import org.elasticsearch.common.compress.CompressedXContent;
import org.elasticsearch.common.lucene.index.ElasticsearchDirectoryReader;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.analysis.AnalysisService;
import org.elasticsearch.index.cache.bitset.BitsetFilterCache;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.index.mapper.internal.ParentFieldMapper;
import org.elasticsearch.index.shard.IndexShard;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.index.similarity.SimilarityService;
import org.elasticsearch.indices.IndicesModule;
import org.elasticsearch.search.aggregations.LeafBucketCollector;
import org.elasticsearch.shield.license.ShieldLicenseState;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.IndexSettingsModule;
import org.junit.After;
import org.junit.Before;

import java.io.IOException;
import java.util.Collections;

import static java.util.Collections.emptySet;
import static java.util.Collections.singleton;
import static java.util.Collections.singletonMap;
import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.elasticsearch.shield.authz.accesscontrol.ShieldIndexSearcherWrapper.intersectScorerAndRoleBits;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ShieldIndexSearcherWrapperUnitTests extends ESTestCase {

    private ThreadContext threadContext;
    private MapperService mapperService;
    private ShieldIndexSearcherWrapper shieldIndexSearcherWrapper;
    private ElasticsearchDirectoryReader esIn;
    private ShieldLicenseState licenseState;
    private IndexSettings indexSettings;

    @Before
    public void before() throws Exception {
        Index index = new Index("_index", "testUUID");
        indexSettings = IndexSettingsModule.newIndexSettings(index, Settings.EMPTY);
        AnalysisService analysisService = new AnalysisService(indexSettings, Collections.emptyMap(), Collections.emptyMap(),
                Collections.emptyMap(), Collections.emptyMap());
        SimilarityService similarityService = new SimilarityService(indexSettings, Collections.emptyMap());
        mapperService = new MapperService(indexSettings, analysisService, similarityService,
                new IndicesModule().getMapperRegistry(), () -> null);

        ShardId shardId = new ShardId(index, 0);
        licenseState = mock(ShieldLicenseState.class);
        when(licenseState.documentAndFieldLevelSecurityEnabled()).thenReturn(true);
        threadContext = new ThreadContext(Settings.EMPTY);
        IndexShard indexShard = mock(IndexShard.class);
        when(indexShard.shardId()).thenReturn(shardId);

        Directory directory = new RAMDirectory();
        IndexWriter writer = new IndexWriter(directory, newIndexWriterConfig());
        writer.close();

        DirectoryReader in = DirectoryReader.open(directory); // unfortunately DirectoryReader isn't mock friendly
        esIn = ElasticsearchDirectoryReader.wrap(in, shardId);
    }

    @After
    public void after() throws Exception {
        esIn.close();
    }

    public void testDefaultMetaFields() throws Exception {
        XContentBuilder mappingSource = jsonBuilder().startObject().startObject("type")
                .startObject("properties")
                .endObject()
                .endObject().endObject();
        mapperService.merge("type", new CompressedXContent(mappingSource.string()), MapperService.MergeReason.MAPPING_UPDATE, false);

        shieldIndexSearcherWrapper = new ShieldIndexSearcherWrapper(indexSettings, null, mapperService, null, threadContext, licenseState) {
            @Override
            protected IndicesAccessControl getIndicesAccessControl() {
                IndicesAccessControl.IndexAccessControl indexAccessControl = new IndicesAccessControl.IndexAccessControl(true,
                        emptySet(), null);
                return new IndicesAccessControl(true, singletonMap("_index", indexAccessControl));
            }
        };

        FieldSubsetReader.FieldSubsetDirectoryReader result =
                (FieldSubsetReader.FieldSubsetDirectoryReader) shieldIndexSearcherWrapper.wrap(esIn);
        assertThat(result.getFieldNames().size(), equalTo(11));
        assertThat(result.getFieldNames().contains("_uid"), is(true));
        assertThat(result.getFieldNames().contains("_id"), is(true));
        assertThat(result.getFieldNames().contains("_version"), is(true));
        assertThat(result.getFieldNames().contains("_type"), is(true));
        assertThat(result.getFieldNames().contains("_source"), is(true));
        assertThat(result.getFieldNames().contains("_routing"), is(true));
        assertThat(result.getFieldNames().contains("_parent"), is(true));
        assertThat(result.getFieldNames().contains("_timestamp"), is(true));
        assertThat(result.getFieldNames().contains("_ttl"), is(true));
        assertThat(result.getFieldNames().contains("_size"), is(true));
        assertThat(result.getFieldNames().contains("_index"), is(true));
        // _all contains actual user data and therefor can't be included by default
        assertThat(result.getFieldNames().contains("_all"), is(false));
    }

    public void testWrapReaderWhenFeatureDisabled() throws Exception {
        when(licenseState.documentAndFieldLevelSecurityEnabled()).thenReturn(false);
        shieldIndexSearcherWrapper = new ShieldIndexSearcherWrapper(indexSettings, null, mapperService, null, threadContext, licenseState);
        DirectoryReader reader = shieldIndexSearcherWrapper.wrap(esIn);
        assertThat(reader, sameInstance(esIn));
    }

    public void testWrapSearcherWhenFeatureDisabled() throws Exception {
        shieldIndexSearcherWrapper = new ShieldIndexSearcherWrapper(indexSettings, null, mapperService, null, threadContext, licenseState);
        IndexSearcher indexSearcher = new IndexSearcher(esIn);
        IndexSearcher result = shieldIndexSearcherWrapper.wrap(indexSearcher);
        assertThat(result, sameInstance(indexSearcher));
    }

    public void testWildcards() throws Exception {
        XContentBuilder mappingSource = jsonBuilder().startObject().startObject("type").startObject("properties")
                    .startObject("field1_a").field("type", "text").endObject()
                    .startObject("field1_b").field("type", "text").endObject()
                    .startObject("field1_c").field("type", "text").endObject()
                    .startObject("field2_a").field("type", "text").endObject()
                    .startObject("field2_b").field("type", "text").endObject()
                    .startObject("field2_c").field("type", "text").endObject()
                .endObject().endObject().endObject();
        mapperService.merge("type", new CompressedXContent(mappingSource.string()), MapperService.MergeReason.MAPPING_UPDATE, false);

        assertResolvedFields("field1*", "field1_a", "field1_b", "field1_c");
        assertResolvedFields("field2*", "field2_a", "field2_b", "field2_c");
    }

    public void testDotNotion() throws Exception {
        XContentBuilder mappingSource = jsonBuilder().startObject().startObject("type").startObject("properties")
                .startObject("foo")
                    .field("type", "object")
                    .startObject("properties")
                        .startObject("bar").field("type", "text").endObject()
                        .startObject("baz").field("type", "text").endObject()
                    .endObject()
                .endObject()
                .startObject("bar")
                    .field("type", "object")
                    .startObject("properties")
                        .startObject("foo").field("type", "text").endObject()
                        .startObject("baz").field("type", "text").endObject()
                    .endObject()
                .endObject()
                .startObject("baz")
                    .field("type", "object")
                    .startObject("properties")
                        .startObject("bar").field("type", "text").endObject()
                        .startObject("foo").field("type", "text").endObject()
                    .endObject()
                .endObject()
                .endObject().endObject().endObject();
        mapperService.merge("type", new CompressedXContent(mappingSource.string()), MapperService.MergeReason.MAPPING_UPDATE, false);

        assertResolvedFields("foo.bar", "foo.bar");
        assertResolvedFields("bar.baz", "bar.baz");
        assertResolvedFields("foo.*", "foo.bar", "foo.baz");
        assertResolvedFields("baz.*", "baz.bar", "baz.foo");
    }

    public void testParentChild() throws Exception {
        XContentBuilder mappingSource = jsonBuilder().startObject().startObject("parent1")
                .startObject("properties")
                    .startObject("field").field("type", "text").endObject()
                .endObject()
                .endObject().endObject();
        mapperService.merge("parent1", new CompressedXContent(mappingSource.string()), MapperService.MergeReason.MAPPING_UPDATE, false);
        mappingSource = jsonBuilder().startObject().startObject("child1")
                .startObject("properties")
                    .startObject("field").field("type", "text").endObject()
                .endObject()
                .startObject("_parent")
                    .field("type", "parent1")
                .endObject()
                .endObject().endObject();
        mapperService.merge("child1", new CompressedXContent(mappingSource.string()), MapperService.MergeReason.MAPPING_UPDATE, false);
        mappingSource = jsonBuilder().startObject().startObject("child2")
                .startObject("properties")
                    .startObject("field").field("type", "text").endObject()
                .endObject()
                .startObject("_parent")
                    .field("type", "parent1")
                .endObject()
                .endObject().endObject();
        mapperService.merge("child2", new CompressedXContent(mappingSource.string()), MapperService.MergeReason.MAPPING_UPDATE, false);
        mappingSource = jsonBuilder().startObject().startObject("parent2")
                .startObject("properties")
                .startObject("field").field("type", "text").endObject()
                .endObject()
                .endObject().endObject();
        mapperService.merge("parent2", new CompressedXContent(mappingSource.string()), MapperService.MergeReason.MAPPING_UPDATE, false);
        mappingSource = jsonBuilder().startObject().startObject("child3")
                .startObject("properties")
                    .startObject("field").field("type", "text").endObject()
                .endObject()
                .startObject("_parent")
                    .field("type", "parent2")
                .endObject()
                .endObject().endObject();
        mapperService.merge("child3", new CompressedXContent(mappingSource.string()), MapperService.MergeReason.MAPPING_UPDATE, false);

        assertResolvedFields("field1", "field1", ParentFieldMapper.joinField("parent1"), ParentFieldMapper.joinField("parent2"));
    }

    public void testDelegateSimilarity() throws Exception {
        IndexSettings settings = IndexSettingsModule.newIndexSettings("_index", Settings.EMPTY);
        BitsetFilterCache bitsetFilterCache = new BitsetFilterCache(settings, new BitsetFilterCache.Listener() {
            @Override
            public void onCache(ShardId shardId, Accountable accountable) {
            }

            @Override
            public void onRemoval(ShardId shardId, Accountable accountable) {

            }
        });
        DirectoryReader directoryReader = DocumentSubsetReader.wrap(esIn, bitsetFilterCache, new MatchAllDocsQuery());
        IndexSearcher indexSearcher = new IndexSearcher(directoryReader);
        shieldIndexSearcherWrapper = new ShieldIndexSearcherWrapper(indexSettings, null, mapperService, null, threadContext, licenseState);
        IndexSearcher result = shieldIndexSearcherWrapper.wrap(indexSearcher);
        assertThat(result, not(sameInstance(indexSearcher)));
        assertThat(result.getSimilarity(true), sameInstance(indexSearcher.getSimilarity(true)));
        bitsetFilterCache.close();
    }

    public void testIntersectScorerAndRoleBits() throws Exception {
        shieldIndexSearcherWrapper = new ShieldIndexSearcherWrapper(indexSettings, null, mapperService, null, threadContext, licenseState);
        final Directory directory = newDirectory();
        IndexWriter iw = new IndexWriter(
                directory,
                new IndexWriterConfig(new StandardAnalyzer()).setMergePolicy(NoMergePolicy.INSTANCE)
        );

        Document document = new Document();
        document.add(new StringField("field1", "value1", Field.Store.NO));
        document.add(new StringField("field2", "value1", Field.Store.NO));
        iw.addDocument(document);

        document = new Document();
        document.add(new StringField("field1", "value2", Field.Store.NO));
        document.add(new StringField("field2", "value1", Field.Store.NO));
        iw.addDocument(document);

        document = new Document();
        document.add(new StringField("field1", "value3", Field.Store.NO));
        document.add(new StringField("field2", "value1", Field.Store.NO));
        iw.addDocument(document);

        document = new Document();
        document.add(new StringField("field1", "value4", Field.Store.NO));
        document.add(new StringField("field2", "value1", Field.Store.NO));
        iw.addDocument(document);

        iw.commit();
        iw.deleteDocuments(new Term("field1", "value3"));
        iw.close();
        DirectoryReader directoryReader = DirectoryReader.open(directory);
        IndexSearcher searcher = new IndexSearcher(directoryReader);
        Weight weight = searcher.createNormalizedWeight(new TermQuery(new Term("field2", "value1")), false);

        LeafReaderContext leaf = directoryReader.leaves().get(0);
        Scorer scorer = weight.scorer(leaf);

        SparseFixedBitSet sparseFixedBitSet = query(leaf, "field1", "value1");
        LeafCollector leafCollector = new LeafBucketCollector() {
            @Override
            public void collect(int doc, long bucket) throws IOException {
                assertThat(doc, equalTo(0));
            }
        };
        intersectScorerAndRoleBits(scorer, sparseFixedBitSet, leafCollector, leaf.reader().getLiveDocs());

        sparseFixedBitSet = query(leaf, "field1", "value2");
        leafCollector = new LeafBucketCollector() {
            @Override
            public void collect(int doc, long bucket) throws IOException {
                assertThat(doc, equalTo(1));
            }
        };
        intersectScorerAndRoleBits(scorer, sparseFixedBitSet, leafCollector, leaf.reader().getLiveDocs());


        sparseFixedBitSet = query(leaf, "field1", "value3");
        leafCollector = new LeafBucketCollector() {
            @Override
            public void collect(int doc, long bucket) throws IOException {
                fail("docId [" + doc + "] should have been deleted");
            }
        };
        intersectScorerAndRoleBits(scorer, sparseFixedBitSet, leafCollector, leaf.reader().getLiveDocs());

        sparseFixedBitSet = query(leaf, "field1", "value4");
        leafCollector = new LeafBucketCollector() {
            @Override
            public void collect(int doc, long bucket) throws IOException {
                assertThat(doc, equalTo(3));
            }
        };
        intersectScorerAndRoleBits(scorer, sparseFixedBitSet, leafCollector, leaf.reader().getLiveDocs());

        directoryReader.close();
        directory.close();
    }

    private SparseFixedBitSet query(LeafReaderContext leaf, String field, String value) throws IOException {
        SparseFixedBitSet sparseFixedBitSet = new SparseFixedBitSet(leaf.reader().maxDoc());
        TermsEnum tenum = leaf.reader().terms(field).iterator();
        while (tenum.next().utf8ToString().equals(value) == false) {}
        PostingsEnum penum = tenum.postings(null);
        sparseFixedBitSet.or(penum);
        return sparseFixedBitSet;
    }

    private void assertResolvedFields(String expression, String... expectedFields) {
        shieldIndexSearcherWrapper = new ShieldIndexSearcherWrapper(indexSettings, null, mapperService, null, threadContext, licenseState) {
            @Override
            protected IndicesAccessControl getIndicesAccessControl() {
                IndicesAccessControl.IndexAccessControl indexAccessControl = new IndicesAccessControl.IndexAccessControl(true,
                        singleton(expression), null);
                return new IndicesAccessControl(true, singletonMap("_index", indexAccessControl));
            }
        };
        FieldSubsetReader.FieldSubsetDirectoryReader result =
                (FieldSubsetReader.FieldSubsetDirectoryReader) shieldIndexSearcherWrapper.wrap(esIn);

        assertThat(result.getFieldNames().size() - shieldIndexSearcherWrapper.getAllowedMetaFields().size(),
                equalTo(expectedFields.length));
        for (String expectedField : expectedFields) {
            assertThat(result.getFieldNames().contains(expectedField), is(true));
        }
    }

}
