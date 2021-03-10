/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.index.mapper;

import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.compress.CompressorFactory;
import org.elasticsearch.common.io.stream.BytesStreamOutput;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.search.DocValueFormat;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.function.Supplier;

import static org.hamcrest.Matchers.instanceOf;

public class BinaryFieldMapperTests extends MapperTestCase {

    @Override
    protected Object getSampleValueForDocument() {
        final byte[] binaryValue = new byte[100];
        binaryValue[56] = 1;
        return binaryValue;
    }

    @Override
    protected void minimalMapping(XContentBuilder b) throws IOException {
        b.field("type", "binary");
    }

    @Override
    protected void registerParameters(ParameterChecker checker) throws IOException {
        checker.registerConflictCheck("doc_values", b -> b.field("doc_values", true));
        checker.registerConflictCheck("store", b -> b.field("store", true));
    }

    public void testExistsQueryDocValuesEnabled() throws IOException {
        MapperService mapperService = createMapperService(fieldMapping(b -> {
            minimalMapping(b);
            b.field("doc_values", true);
            if (randomBoolean()) {
                b.field("store", randomBoolean());
            }
        }));
        assertExistsQuery(mapperService);
        assertParseMinimalWarnings();
    }

    public void testExistsQueryStoreEnabled() throws IOException {
        MapperService mapperService = createMapperService(fieldMapping(b -> {
            minimalMapping(b);
            b.field("store", true);
            if (randomBoolean()) {
                b.field("doc_values", false);
            }
        }));
        assertExistsQuery(mapperService);
    }

    public void testExistsQueryStoreAndDocValuesDiabled() throws IOException {
        MapperService mapperService = createMapperService(fieldMapping(b -> {
            minimalMapping(b);
            b.field("store", false);
            b.field("doc_values", false);
        }));
        assertExistsQuery(mapperService);
    }

    public void testDefaultMapping() throws Exception {
        MapperService mapperService = createMapperService(fieldMapping(this::minimalMapping));
        FieldMapper mapper = (FieldMapper) mapperService.documentMapper().mappers().getMapper("field");

        assertThat(mapper, instanceOf(BinaryFieldMapper.class));

        byte[] value = new byte[100];
        ParsedDocument doc = mapperService.documentMapper().parse(source(b -> b.field("field", value)));
        assertEquals(0, doc.rootDoc().getFields("field").length);
    }

    public void testStoredValue() throws IOException {

        MapperService mapperService = createMapperService(fieldMapping(b -> {
            minimalMapping(b);
            b.field("store", "true");
        }));

        // case 1: a simple binary value
        final byte[] binaryValue1 = new byte[100];
        binaryValue1[56] = 1;

        // case 2: a value that looks compressed: this used to fail in 1.x
        BytesStreamOutput out = new BytesStreamOutput();
        try (OutputStream compressed = CompressorFactory.COMPRESSOR.threadLocalOutputStream(out)) {
            new BytesArray(binaryValue1).writeTo(compressed);
        }
        final byte[] binaryValue2 = BytesReference.toBytes(out.bytes());
        assertTrue(CompressorFactory.isCompressed(new BytesArray(binaryValue2)));

        for (byte[] value : Arrays.asList(binaryValue1, binaryValue2)) {
            ParsedDocument doc = mapperService.documentMapper().parse(source(b -> b.field("field", value)));
            BytesRef indexedValue = doc.rootDoc().getBinaryValue("field");
            assertEquals(new BytesRef(value), indexedValue);
            IndexableField field = doc.rootDoc().getField("field");
            assertTrue(field.fieldType().stored());
            assertEquals(IndexOptions.NONE, field.fieldType().indexOptions());

            MappedFieldType fieldType = mapperService.fieldType("field");
            Object originalValue = fieldType.valueForDisplay(indexedValue);
            assertEquals(new BytesArray(value), originalValue);
        }
    }

    @Override
    protected Supplier<? extends Object> randomFetchTestValueVendor(MappedFieldType ft) {
        assumeFalse("We can't parse the binary doc values we send", true);
        // AwaitsFix https://github.com/elastic/elasticsearch/issues/70244
        // One of these two should work:
//        return () -> Base64.getEncoder().encode(randomByteArrayOfLength(between(1, 30)));
        return () -> DocValueFormat.BINARY.format(new BytesRef(randomByteArrayOfLength(between(1, 30))));
        /*
         * but they don't produce the same results. DocValueFormat.BINARY doesn't
         * include trailing padding (looks like `=`) while Base64.getEncoder does.
         * The parser needs the padding.
         */
    }

    @Override
    protected void randomFetchTestFieldConfig(XContentBuilder b) throws IOException {
        b.field("type", "binary").field("doc_values", true); // enable doc_values so the test is happy
    }
}
