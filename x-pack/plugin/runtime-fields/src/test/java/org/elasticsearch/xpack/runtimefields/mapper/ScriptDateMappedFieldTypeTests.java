/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

package org.elasticsearch.xpack.runtimefields.mapper;

import org.apache.lucene.document.StoredField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.RandomIndexWriter;
import org.apache.lucene.index.SortedNumericDocValues;
import org.apache.lucene.search.Collector;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.LeafCollector;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Scorable;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TopFieldDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.Version;
import org.elasticsearch.common.lucene.search.function.ScriptScoreQuery;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.fielddata.ScriptDocValues;
import org.elasticsearch.index.mapper.MappedFieldType;
import org.elasticsearch.index.query.QueryShardContext;
import org.elasticsearch.plugins.ScriptPlugin;
import org.elasticsearch.script.ScoreScript;
import org.elasticsearch.script.Script;
import org.elasticsearch.script.ScriptContext;
import org.elasticsearch.script.ScriptEngine;
import org.elasticsearch.script.ScriptModule;
import org.elasticsearch.script.ScriptService;
import org.elasticsearch.script.ScriptType;
import org.elasticsearch.search.MultiValueMode;
import org.elasticsearch.xpack.runtimefields.DateScriptFieldScript;
import org.elasticsearch.xpack.runtimefields.RuntimeFields;
import org.elasticsearch.xpack.runtimefields.fielddata.ScriptDateFieldData;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

import static java.util.Collections.emptyMap;
import static org.hamcrest.Matchers.equalTo;

public class ScriptDateMappedFieldTypeTests extends AbstractNonTextScriptMappedFieldTypeTestCase {
    public void testFormat() throws IOException {
        assertThat(simpleMappedFieldType().docValueFormat("date", null).format(1595432181354L), equalTo("2020-07-22"));
        assertThat(
            simpleMappedFieldType().docValueFormat("strict_date_optional_time", null).format(1595432181354L),
            equalTo("2020-07-22T15:36:21.354Z")
        );
        assertThat(
            simpleMappedFieldType().docValueFormat("strict_date_optional_time", ZoneId.of("America/New_York")).format(1595432181354L),
            equalTo("2020-07-22T11:36:21.354-04:00")
        );
        assertThat(
            simpleMappedFieldType().docValueFormat(null, ZoneId.of("America/New_York")).format(1595432181354L),
            equalTo("2020-07-22T11:36:21.354-04:00")
        );
    }

    @Override
    public void testDocValues() throws IOException {
        try (Directory directory = newDirectory(); RandomIndexWriter iw = new RandomIndexWriter(random(), directory)) {
            iw.addDocument(List.of(new StoredField("_source", new BytesRef("{\"timestamp\": [1595432181354]}"))));
            iw.addDocument(List.of(new StoredField("_source", new BytesRef("{\"timestamp\": [1595432181356, 1595432181351]}"))));
            List<Long> results = new ArrayList<>();
            try (DirectoryReader reader = iw.getReader()) {
                IndexSearcher searcher = newSearcher(reader);
                ScriptDateMappedFieldType ft = build("add_days", Map.of("days", 1));
                ScriptDateFieldData ifd = ft.fielddataBuilder("test").build(null, null, null);
                ifd.setSearchLookup(mockContext().lookup());
                searcher.search(new MatchAllDocsQuery(), new Collector() {
                    @Override
                    public ScoreMode scoreMode() {
                        return ScoreMode.COMPLETE_NO_SCORES;
                    }

                    @Override
                    public LeafCollector getLeafCollector(LeafReaderContext context) throws IOException {
                        SortedNumericDocValues dv = ifd.load(context).getLongValues();
                        return new LeafCollector() {
                            @Override
                            public void setScorer(Scorable scorer) throws IOException {}

                            @Override
                            public void collect(int doc) throws IOException {
                                if (dv.advanceExact(doc)) {
                                    for (int i = 0; i < dv.docValueCount(); i++) {
                                        results.add(dv.nextValue());
                                    }
                                }
                            }
                        };
                    }
                });
                assertThat(results, equalTo(List.of(1595518581354L, 1595518581351L, 1595518581356L)));
            }
        }
    }

    @Override
    public void testSort() throws IOException {
        try (Directory directory = newDirectory(); RandomIndexWriter iw = new RandomIndexWriter(random(), directory)) {
            iw.addDocument(List.of(new StoredField("_source", new BytesRef("{\"timestamp\": [1595432181354]}"))));
            iw.addDocument(List.of(new StoredField("_source", new BytesRef("{\"timestamp\": [1595432181351]}"))));
            iw.addDocument(List.of(new StoredField("_source", new BytesRef("{\"timestamp\": [1595432181356]}"))));
            try (DirectoryReader reader = iw.getReader()) {
                IndexSearcher searcher = newSearcher(reader);
                ScriptDateFieldData ifd = simpleMappedFieldType().fielddataBuilder("test").build(null, null, null);
                ifd.setSearchLookup(mockContext().lookup());
                SortField sf = ifd.sortField(null, MultiValueMode.MIN, null, false);
                TopFieldDocs docs = searcher.search(new MatchAllDocsQuery(), 3, new Sort(sf));
                assertThat(
                    reader.document(docs.scoreDocs[0].doc).getBinaryValue("_source").utf8ToString(),
                    equalTo("{\"timestamp\": [1595432181351]}")
                );
                assertThat(
                    reader.document(docs.scoreDocs[1].doc).getBinaryValue("_source").utf8ToString(),
                    equalTo("{\"timestamp\": [1595432181354]}")
                );
                assertThat(
                    reader.document(docs.scoreDocs[2].doc).getBinaryValue("_source").utf8ToString(),
                    equalTo("{\"timestamp\": [1595432181356]}")
                );
            }
        }
    }

    @Override
    public void testUsedInScript() throws IOException {
        try (Directory directory = newDirectory(); RandomIndexWriter iw = new RandomIndexWriter(random(), directory)) {
            iw.addDocument(List.of(new StoredField("_source", new BytesRef("{\"timestamp\": [1595432181354]}"))));
            iw.addDocument(List.of(new StoredField("_source", new BytesRef("{\"timestamp\": [1595432181351]}"))));
            iw.addDocument(List.of(new StoredField("_source", new BytesRef("{\"timestamp\": [1595432181356]}"))));
            try (DirectoryReader reader = iw.getReader()) {
                IndexSearcher searcher = newSearcher(reader);
                QueryShardContext qsc = mockContext(true, simpleMappedFieldType());
                assertThat(searcher.count(new ScriptScoreQuery(new MatchAllDocsQuery(), new Script("test"), new ScoreScript.LeafFactory() {
                    @Override
                    public boolean needs_score() {
                        return false;
                    }

                    @Override
                    public ScoreScript newInstance(LeafReaderContext ctx) throws IOException {
                        return new ScoreScript(Map.of(), qsc.lookup(), ctx) {
                            @Override
                            public double execute(ExplanationHolder explanation) {
                                ScriptDocValues.Dates dates = (ScriptDocValues.Dates) getDoc().get("test");
                                return dates.get(0).toInstant().toEpochMilli() % 1000;
                            }
                        };
                    }
                }, 354.5f, "test", 0, Version.CURRENT)), equalTo(1));
            }
        }
    }

    @Override
    public void testExistsQuery() throws IOException {
        try (Directory directory = newDirectory(); RandomIndexWriter iw = new RandomIndexWriter(random(), directory)) {
            iw.addDocument(List.of(new StoredField("_source", new BytesRef("{\"timestamp\": [1595432181356]}"))));
            iw.addDocument(List.of(new StoredField("_source", new BytesRef("{\"timestamp\": []}"))));
            try (DirectoryReader reader = iw.getReader()) {
                IndexSearcher searcher = newSearcher(reader);
                assertThat(searcher.count(simpleMappedFieldType().existsQuery(mockContext())), equalTo(1));
            }
        }
    }

    @Override
    public void testExistsQueryIsExpensive() throws IOException {
        checkExpensiveQuery(ScriptDateMappedFieldType::existsQuery);
    }

    @Override
    public void testRangeQuery() throws IOException {
        try (Directory directory = newDirectory(); RandomIndexWriter iw = new RandomIndexWriter(random(), directory)) {
            iw.addDocument(List.of(new StoredField("_source", new BytesRef("{\"timestamp\": [1595432181354]}"))));
            iw.addDocument(List.of(new StoredField("_source", new BytesRef("{\"timestamp\": [1595432181351]}"))));
            iw.addDocument(List.of(new StoredField("_source", new BytesRef("{\"timestamp\": [1595432181356]}"))));
            try (DirectoryReader reader = iw.getReader()) {
                IndexSearcher searcher = newSearcher(reader);
                MappedFieldType ft = simpleMappedFieldType();
                assertThat(
                    searcher.count(
                        ft.rangeQuery("2020-07-22T15:36:21.356Z", "2020-07-23T00:00:00.000Z", true, true, null, null, null, mockContext())
                    ),
                    equalTo(1)
                );
                assertThat(
                    searcher.count(
                        ft.rangeQuery("2020-07-22T00:00:00.00Z", "2020-07-22T15:36:21.354Z", true, true, null, null, null, mockContext())
                    ),
                    equalTo(2)
                );
                assertThat(
                    searcher.count(ft.rangeQuery(1595432181351L, 1595432181356L, true, true, null, null, null, mockContext())),
                    equalTo(3)
                );
                assertThat(
                    searcher.count(
                        ft.rangeQuery("2020-07-22T15:36:21.356Z", "2020-07-23T00:00:00.000Z", true, false, null, null, null, mockContext())
                    ),
                    equalTo(1)
                );
                assertThat(
                    searcher.count(
                        ft.rangeQuery("2020-07-22T15:36:21.356Z", "2020-07-23T00:00:00.000Z", false, false, null, null, null, mockContext())
                    ),
                    equalTo(0)
                );
            }
        }
    }

    @Override
    public void testRangeQueryIsExpensive() throws IOException {
        checkExpensiveQuery(
            (ft, ctx) -> ft.rangeQuery(randomLong(), randomLong(), randomBoolean(), randomBoolean(), null, null, null, ctx)
        );
    }

    @Override
    public void testTermQuery() throws IOException {
        try (Directory directory = newDirectory(); RandomIndexWriter iw = new RandomIndexWriter(random(), directory)) {
            iw.addDocument(List.of(new StoredField("_source", new BytesRef("{\"timestamp\": [1595432181354]}"))));
            iw.addDocument(List.of(new StoredField("_source", new BytesRef("{\"timestamp\": [1595432181355]}"))));
            try (DirectoryReader reader = iw.getReader()) {
                IndexSearcher searcher = newSearcher(reader);
                assertThat(searcher.count(simpleMappedFieldType().termQuery("2020-07-22T15:36:21.354Z", mockContext())), equalTo(1));
                assertThat(searcher.count(simpleMappedFieldType().termQuery("1595432181355", mockContext())), equalTo(1));
                assertThat(searcher.count(simpleMappedFieldType().termQuery(1595432181354L, mockContext())), equalTo(1));
                assertThat(searcher.count(simpleMappedFieldType().termQuery(2595432181354L, mockContext())), equalTo(0));
                assertThat(
                    searcher.count(build("add_days", Map.of("days", 1)).termQuery("2020-07-23T15:36:21.354Z", mockContext())),
                    equalTo(1)
                );
            }
        }
    }

    @Override
    public void testTermQueryIsExpensive() throws IOException {
        checkExpensiveQuery((ft, ctx) -> ft.termQuery(0, ctx));
    }

    @Override
    public void testTermsQuery() throws IOException {
        try (Directory directory = newDirectory(); RandomIndexWriter iw = new RandomIndexWriter(random(), directory)) {
            iw.addDocument(List.of(new StoredField("_source", new BytesRef("{\"timestamp\": [1595432181354]}"))));
            iw.addDocument(List.of(new StoredField("_source", new BytesRef("{\"timestamp\": [1595432181355]}"))));
            try (DirectoryReader reader = iw.getReader()) {
                MappedFieldType ft = simpleMappedFieldType();
                IndexSearcher searcher = newSearcher(reader);
                assertThat(searcher.count(ft.termsQuery(List.of("2020-07-22T15:36:21.354Z"), mockContext())), equalTo(1));
                assertThat(searcher.count(ft.termsQuery(List.of("1595432181354"), mockContext())), equalTo(1));
                assertThat(searcher.count(ft.termsQuery(List.of(1595432181354L), mockContext())), equalTo(1));
                assertThat(searcher.count(ft.termsQuery(List.of(2595432181354L), mockContext())), equalTo(0));
                assertThat(searcher.count(ft.termsQuery(List.of(1595432181354L, 2595432181354L), mockContext())), equalTo(1));
                assertThat(searcher.count(ft.termsQuery(List.of(2595432181354L, 1595432181354L), mockContext())), equalTo(1));
                assertThat(searcher.count(ft.termsQuery(List.of(1595432181355L, 1595432181354L), mockContext())), equalTo(2));
            }
        }
    }

    @Override
    public void testTermsQueryIsExpensive() throws IOException {
        checkExpensiveQuery((ft, ctx) -> ft.termsQuery(List.of(0), ctx));
    }

    @Override
    protected ScriptDateMappedFieldType simpleMappedFieldType() throws IOException {
        return build("read_timestamp");
    }

    @Override
    protected String runtimeType() {
        return "date";
    }

    private static ScriptDateMappedFieldType build(String code) throws IOException {
        return build(code, Map.of());
    }

    private static ScriptDateMappedFieldType build(String code, Map<String, Object> params) throws IOException {
        return build(new Script(ScriptType.INLINE, "test", code, params));
    }

    private static ScriptDateMappedFieldType build(Script script) throws IOException {
        ScriptPlugin scriptPlugin = new ScriptPlugin() {
            @Override
            public ScriptEngine getScriptEngine(Settings settings, Collection<ScriptContext<?>> contexts) {
                return new ScriptEngine() {
                    @Override
                    public String getType() {
                        return "test";
                    }

                    @Override
                    public Set<ScriptContext<?>> getSupportedContexts() {
                        return Set.of(DateScriptFieldScript.CONTEXT);
                    }

                    @Override
                    public <FactoryType> FactoryType compile(
                        String name,
                        String code,
                        ScriptContext<FactoryType> context,
                        Map<String, String> params
                    ) {
                        @SuppressWarnings("unchecked")
                        FactoryType factory = (FactoryType) factory(code);
                        return factory;
                    }

                    private DateScriptFieldScript.Factory factory(String code) {
                        switch (code) {
                            case "read_timestamp":
                                return (params, lookup) -> (ctx) -> new DateScriptFieldScript(params, lookup, ctx) {
                                    @Override
                                    public void execute() {
                                        for (Object timestamp : (List<?>) getSource().get("timestamp")) {
                                            new DateScriptFieldScript.Millis(this).millis((Long) timestamp);
                                        }
                                    }
                                };
                            case "add_days":
                                return (params, lookup) -> (ctx) -> new DateScriptFieldScript(params, lookup, ctx) {
                                    @Override
                                    public void execute() {
                                        for (Object timestamp : (List<?>) getSource().get("timestamp")) {
                                            long epoch = (Long) timestamp;
                                            ZonedDateTime dt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(epoch), ZoneId.of("UTC"));
                                            dt = dt.plus(((Number) params.get("days")).longValue(), ChronoUnit.DAYS);
                                            new DateScriptFieldScript.Date(this).date(dt);
                                        }
                                    }
                                };
                            default:
                                throw new IllegalArgumentException("unsupported script [" + code + "]");
                        }
                    }
                };
            }
        };
        ScriptModule scriptModule = new ScriptModule(Settings.EMPTY, List.of(scriptPlugin, new RuntimeFields()));
        try (ScriptService scriptService = new ScriptService(Settings.EMPTY, scriptModule.engines, scriptModule.contexts)) {
            DateScriptFieldScript.Factory factory = scriptService.compile(script, DateScriptFieldScript.CONTEXT);
            return new ScriptDateMappedFieldType("test", script, factory, emptyMap());
        }
    }

    private void checkExpensiveQuery(BiConsumer<ScriptDateMappedFieldType, QueryShardContext> queryBuilder) throws IOException {
        ScriptDateMappedFieldType ft = simpleMappedFieldType();
        Exception e = expectThrows(ElasticsearchException.class, () -> queryBuilder.accept(ft, mockContext(false)));
        assertThat(
            e.getMessage(),
            equalTo("queries cannot be executed against [runtime_script] fields while [search.allow_expensive_queries] is set to [false].")
        );
    }
}
