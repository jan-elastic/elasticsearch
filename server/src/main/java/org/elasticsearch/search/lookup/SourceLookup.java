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
package org.elasticsearch.search.lookup;

import org.apache.lucene.index.LeafReaderContext;
import org.elasticsearch.ElasticsearchParseException;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.common.xcontent.support.XContentMapValues;
import org.elasticsearch.index.fielddata.IndexFieldData;
import org.elasticsearch.index.fieldvisitor.FieldsVisitor;
import org.elasticsearch.index.fieldvisitor.SourceLoader;
import org.elasticsearch.index.mapper.DocumentMapper;
import org.elasticsearch.index.mapper.MappedFieldType;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.index.mapper.FieldMapper;
import org.elasticsearch.search.fetch.subphase.FetchSourceContext;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static java.util.Collections.emptyMap;

public class SourceLookup implements Map {
    private final MapperService mapperService;
    private final Function<MappedFieldType, IndexFieldData<?>> fieldDataLookup;

    private LeafReaderContext context;

    private int docId = -1;

    private BytesReference sourceAsBytes;
    private Map<String, Object> source;
    private XContentType sourceContentType;

    SourceLookup(MapperService mapperService, Function<MappedFieldType, IndexFieldData<?>> fieldDataLookup) {
        this.mapperService = mapperService;
        this.fieldDataLookup = fieldDataLookup;
    }

    public Map<String, Object> source() {
        return source;
    }

    public XContentType sourceContentType() {
        return sourceContentType;
    }

    private Map<String, Object> loadSourceIfNeeded() {
        if (source != null) {
            return source;
        }
        if (sourceAsBytes != null) {
            Tuple<XContentType, Map<String, Object>> tuple = sourceAsMapAndType(sourceAsBytes);
            sourceContentType = tuple.v1();
            source = tuple.v2();
            return source;
        }
        try {
            DocumentMapper docMapper = mapperService.documentMapper();
            Map<String, FieldMapper.SourceRelocationHandler> relocationHandlers =
                    docMapper == null ? emptyMap() : docMapper.sourceRelocationHandlers();
            SourceLoader sourceLoader = SourceLoader.forReadingFromIndex(relocationHandlers, fieldDataLookup);
            FieldsVisitor sourceFieldVisitor = new FieldsVisitor(sourceLoader);
            context.reader().document(docId, sourceFieldVisitor);
            sourceFieldVisitor.postProcess(mapperService);
            sourceLoader.load(context, docId);

            if (sourceLoader.source() == null) {
                this.source = emptyMap();
                this.sourceContentType = null;
            } else {
                Tuple<XContentType, Map<String, Object>> tuple = sourceAsMapAndType(sourceLoader.source());
                this.sourceContentType = tuple.v1();
                this.source = tuple.v2();
            }
        } catch (Exception e) {
            throw new ElasticsearchParseException("failed to parse / load source", e);
        }
        return this.source;
    }

    public static Tuple<XContentType, Map<String, Object>> sourceAsMapAndType(BytesReference source) throws ElasticsearchParseException {
        return XContentHelper.convertToMap(source, false);
    }

    public static Map<String, Object> sourceAsMap(BytesReference source) throws ElasticsearchParseException {
        return sourceAsMapAndType(source).v2();
    }

    public void setSegmentAndDocument(LeafReaderContext context, int docId) {
        if (this.context != null && this.context.reader() == context.reader() && this.docId == docId) {
            // if we are called with the same document, don't invalidate source
            return;
        }
        // NOCOMMIT jpountz review: we should probably check more things for equality here. there is more than reader
        this.context = context;
        this.source = null;
        this.sourceAsBytes = null;
        this.docId = docId;
    }

    public void setSource(BytesReference source) {
        this.sourceAsBytes = source;
    }

    public void setSourceContentType(XContentType sourceContentType) {
        this.sourceContentType = sourceContentType;
    }

    public void setSource(Map<String, Object> source) {
        this.source = source;
    }

    /**
     * Internal source representation, might be compressed....
     */
    public BytesReference internalSourceRef() {
        return sourceAsBytes;
    }

    /**
     * Returns the values associated with the path. Those are "low" level values, and it can
     * handle path expression where an array/list is navigated within.
     */
    public List<Object> extractRawValues(String path) {
        return XContentMapValues.extractRawValues(path, loadSourceIfNeeded());
    }

    public Object filter(FetchSourceContext context) {
        return context.getFilter().apply(loadSourceIfNeeded());
    }

    public Object extractValue(String path) {
        return XContentMapValues.extractValue(path, loadSourceIfNeeded());
    }

    @Override
    public Object get(Object key) {
        return loadSourceIfNeeded().get(key);
    }

    @Override
    public int size() {
        return loadSourceIfNeeded().size();
    }

    @Override
    public boolean isEmpty() {
        return loadSourceIfNeeded().isEmpty();
    }

    @Override
    public boolean containsKey(Object key) {
        return loadSourceIfNeeded().containsKey(key);
    }

    @Override
    public boolean containsValue(Object value) {
        return loadSourceIfNeeded().containsValue(value);
    }

    @Override
    public Set keySet() {
        return loadSourceIfNeeded().keySet();
    }

    @Override
    public Collection values() {
        return loadSourceIfNeeded().values();
    }

    @Override
    public Set entrySet() {
        return loadSourceIfNeeded().entrySet();
    }

    @Override
    public Object put(Object key, Object value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Object remove(Object key) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void putAll(Map m) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void clear() {
        throw new UnsupportedOperationException();
    }
}
