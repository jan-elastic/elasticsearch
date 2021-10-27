/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */
package org.elasticsearch.client.ml;

import org.elasticsearch.test.AbstractXContentTestCase;
import org.elasticsearch.xcontent.XContentParser;

import java.io.IOException;

public class DeleteExpiredDataResponseTests extends AbstractXContentTestCase<DeleteExpiredDataResponse> {

    @Override
    protected DeleteExpiredDataResponse createTestInstance() {
        return new DeleteExpiredDataResponse(randomBoolean());
    }

    @Override
    protected DeleteExpiredDataResponse doParseInstance(XContentParser parser) throws IOException {
        return DeleteExpiredDataResponse.PARSER.apply(parser, null);
    }

    @Override
    protected boolean supportsUnknownFields() {
        return true;
    }
}
