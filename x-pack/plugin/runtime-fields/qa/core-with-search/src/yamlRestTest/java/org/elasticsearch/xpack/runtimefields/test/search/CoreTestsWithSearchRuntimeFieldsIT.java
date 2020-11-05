/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

package org.elasticsearch.xpack.runtimefields.test.search;

import com.carrotsearch.randomizedtesting.annotations.Name;
import com.carrotsearch.randomizedtesting.annotations.ParametersFactory;

import org.apache.logging.log4j.LogManager;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.common.regex.Regex;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.index.mapper.DateFieldMapper;
import org.elasticsearch.test.rest.yaml.ClientYamlTestCandidate;
import org.elasticsearch.test.rest.yaml.ESClientYamlSuiteTestCase;
import org.elasticsearch.test.rest.yaml.section.ApiCallSection;
import org.elasticsearch.test.rest.yaml.section.ExecutableSection;
import org.elasticsearch.xpack.runtimefields.test.CoreTestTranslater;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.Collections.unmodifiableMap;

/**
 * Runs elasticsearch's core rest tests disabling all mappings and replacing them
 * with runtime fields defined on the search request that load from {@code _source}. Tests
 * that configure the field in a way that are not supported by runtime fields are skipped.
 */
public class CoreTestsWithSearchRuntimeFieldsIT extends ESClientYamlSuiteTestCase {
    public CoreTestsWithSearchRuntimeFieldsIT(@Name("yaml") ClientYamlTestCandidate testCandidate) {
        super(testCandidate);
    }

    @Override
    protected boolean randomizeContentType() { // NOCOMMIT remove me
        return false;
    }

    @ParametersFactory
    public static Iterable<Object[]> parameters() throws Exception {
        return new SearchRequestRuntimeFieldTranslater().parameters();
    }

    /**
     * Translating the tests is fairly difficult here because instead of ES
     * tracking the mappings we have to track them. We don't have to do it as
     * well as ES, just well enough that we can decorate the search requests
     * with types that make most tests "just work".
     */
    private static class SearchRequestRuntimeFieldTranslater extends CoreTestTranslater {
        @Override
        protected Map<String, Object> dynamicTemplateFor(String type) {
            return dynamicTemplateToDisableRuntimeCompatibleFields(type);
        }

        @Override
        protected Suite suite(ClientYamlTestCandidate candidate) {
            return new Suite(candidate) {
                private Map<String, Map<String, Map<String, Object>>> runtimeMappingsAfterSetup;
                private Map<String, Set<String>> mappedFieldsAfterSetup;
                private Map<String, Map<String, Map<String, Object>>> runtimeMappings;
                private Map<String, Set<String>> mappedFields;

                @Override
                public boolean modifySections(List<ExecutableSection> executables) {
                    if (runtimeMappingsAfterSetup == null) {
                        // We're modifying the setup section
                        runtimeMappings = new HashMap<>();
                        mappedFields = new HashMap<>();
                        if (false == super.modifySections(executables)) {
                            return false;
                        }
                        runtimeMappingsAfterSetup = unmodifiableMap(runtimeMappings);
                        runtimeMappings = null;
                        mappedFieldsAfterSetup = unmodifiableMap(mappedFields);
                        mappedFields = null;
                        return true;
                    }
                    runtimeMappings = new HashMap<>(runtimeMappingsAfterSetup);
                    mappedFields = new HashMap<>(mappedFieldsAfterSetup);
                    return super.modifySections(executables);
                }

                @Override
                protected boolean modifyMappingProperties(String index, Map<String, Object> properties) {
                    Map<String, Object> untouchedMapping = new HashMap<>();
                    Map<String, Map<String, Object>> runtimeMapping = new HashMap<>();
                    if (false == runtimeifyMappingProperties(properties, untouchedMapping, runtimeMapping)) {
                        return false;
                    }
                    properties.clear();
                    properties.putAll(untouchedMapping);
                    mappedFields.put(index, untouchedMapping.keySet());
                    runtimeMappings.put(index, runtimeMapping);
                    return true;
                }

                @Override
                protected boolean modifySearch(ApiCallSection search) {
                    if (search.getBodies().isEmpty()) {
                        search.addBody(new HashMap<>());
                    }
                    for (Map<String, Object> body : search.getBodies()) {
                        Map<?, ?> runtimeMapping = runtimeMappings(search.getParams().get("index"));
                        if (runtimeMapping == null) {
                            return false;
                        }
                        body.put("runtime_mappings", runtimeMapping);
                    }
                    return true;
                }

                private Map<?, ?> runtimeMappings(String index) {
                    if (index == null) {
                        return mergeMappings(index, new String[] { "*" });
                    }
                    String[] patterns = Arrays.stream(index.split(",")).map(m -> m.equals("_all") ? "*" : m).toArray(String[]::new);
                    if (patterns.length == 0 && Regex.isSimpleMatchPattern(patterns[0])) {
                        return runtimeMappings.get(patterns[0]);
                    }
                    return mergeMappings(index, patterns);
                }

                private Map<?, ?> mergeMappings(String index, String[] patterns) {
                    Map<String, Map<String, Object>> merged = new HashMap<>();
                    for (Map.Entry<String, Map<String, Map<String, Object>>> indexEntry : runtimeMappings.entrySet()) {
                        if (index != null && index.equals("date*")) {
                            LogManager.getLogger().error("ADSFASFDDAF {} {} {}", patterns, indexEntry.getKey(), Regex.simpleMatch(patterns, indexEntry.getKey()));
                        }
                        if (false == Regex.simpleMatch(patterns, indexEntry.getKey())) {
                            continue;
                        }
                        for (Map.Entry<String, Map<String, Object>> field : indexEntry.getValue().entrySet()) {
                            Map<String, Object> mergedConfig = merged.get(field.getKey());
                            if (mergedConfig == null) {
                                merged.put(field.getKey(), field.getValue());
                            } else if (false == mergedConfig.equals(field.getValue())) {
                                // The two indices have different runtime mappings for a field so we have to give up on running the test.
                                return null;
                            }
                        }
                    }
                    for (Map.Entry<String, Set<String>> indexEntry : mappedFields.entrySet()) {
                        if (index != null && index.equals("date*")) {
                            LogManager.getLogger().error("ADSFASFDDAF {} {} {}", patterns, indexEntry.getKey(), Regex.simpleMatch(patterns, indexEntry.getKey()));
                        }
                        if (false == Regex.simpleMatch(patterns, indexEntry.getKey())) {
                            continue;
                        }
                        for (String mappedField :indexEntry.getValue()) {
                            if (merged.containsKey(mappedField)) {
                                // We have a runtime mappings for a field *and* regular mapping. We can't make this test work so skip it. 
                                return null;
                            }
                        }
                    }
                    return merged;
                }

                @Override
                protected boolean handleIndex(IndexRequest index) {
                    /*
                     * Ok! Let's reverse engineer dynamic mapping. Sort of. We're
                     * really just looking to figure out which of the runtime fields
                     * is "close enough" to what dynamic mapping would do.
                     */
                    if (index.getPipeline() != null) {
                        // We can't attempt local dynamic mappings with pipelines
                        return false;
                    }
                    Map<String, Object> map = XContentHelper.convertToMap(index.source(), false, index.getContentType()).v2();
                    Map<String, Map<String, Object>> indexRuntimeMappings = runtimeMappings.computeIfAbsent(
                        index.index(),
                        i -> new HashMap<>()
                    );
                    Set<String> indexMappedfields = mappedFields.computeIfAbsent(index.index(), i -> Set.of());
                    for (Map.Entry<String, Object> e : map.entrySet()) {
                        String name = e.getKey();
                        if (indexRuntimeMappings.containsKey(name) || indexMappedfields.contains(name)) {
                            continue;
                        }
                        Object value = e.getValue();
                        if (value == null) {
                            continue;
                        }
                        if (value instanceof Boolean) {
                            indexRuntimeMappings.put(name, runtimeFieldLoadingFromSource(name, "boolean"));
                            continue;
                        }
                        if (value instanceof Long || value instanceof Integer) {
                            indexRuntimeMappings.put(name, runtimeFieldLoadingFromSource(name, "long"));
                            continue;
                        }
                        if (value instanceof Double) {
                            indexRuntimeMappings.put(name, runtimeFieldLoadingFromSource(name, "double"));
                            continue;
                        }
                        if (false == value instanceof String) {
                            continue;
                        }
                        try {
                            Long.parseLong(value.toString());
                            indexRuntimeMappings.put(name, runtimeFieldLoadingFromSource(name, "long"));
                            continue;
                        } catch (IllegalArgumentException iae) {
                            // Try the next one
                        }
                        try {
                            Double.parseDouble(value.toString());
                            indexRuntimeMappings.put(name, runtimeFieldLoadingFromSource(name, "double"));
                            continue;
                        } catch (IllegalArgumentException iae) {
                            // Try the next one
                        }
                        try {
                            DateFieldMapper.DEFAULT_DATE_TIME_FORMATTER.parse(value.toString());
                            indexRuntimeMappings.put(name, runtimeFieldLoadingFromSource(name, "date"));
                            continue;
                        } catch (IllegalArgumentException iae) {
                            // Try the next one
                        }
                        // Strings are funny, the regular dynamic mapping puts them in "name.keyword" so we follow along.
                        indexRuntimeMappings.put(name + ".keyword", runtimeFieldLoadingFromSource(name, "keyword"));
                    }
                    return true;
                }
            };
        }
    }
}
