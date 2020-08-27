/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

package org.elasticsearch.xpack.runtimefields.query;

import org.elasticsearch.script.Script;
import org.elasticsearch.test.ESTestCase;

import static org.hamcrest.Matchers.equalTo;

public class DoubleScriptFieldRangeQueryTests extends AbstractDoubleScriptFieldQueryTestCase<DoubleScriptFieldRangeQuery> {
    @Override
    protected DoubleScriptFieldRangeQuery createTestInstance() {
        double lower = randomDouble();
        double upper = randomValueOtherThan(lower, ESTestCase::randomDouble);
        if (lower > upper) {
            double tmp = lower;
            lower = upper;
            upper = tmp;
        }
        return new DoubleScriptFieldRangeQuery(randomScript(), leafFactory, randomAlphaOfLength(5), lower, upper);
    }

    @Override
    protected DoubleScriptFieldRangeQuery copy(DoubleScriptFieldRangeQuery orig) {
        return new DoubleScriptFieldRangeQuery(orig.script(), leafFactory, orig.fieldName(), orig.lowerValue(), orig.upperValue());
    }

    @Override
    protected DoubleScriptFieldRangeQuery mutate(DoubleScriptFieldRangeQuery orig) {
        Script script = orig.script();
        String fieldName = orig.fieldName();
        double lower = orig.lowerValue();
        double upper = orig.upperValue();
        switch (randomInt(3)) {
            case 0:
                script = randomValueOtherThan(script, this::randomScript);
                break;
            case 1:
                fieldName += "modified";
                break;
            case 2:
                lower -= 1;
                break;
            case 3:
                upper += 1;
                break;
            default:
                fail();
        }
        return new DoubleScriptFieldRangeQuery(script, leafFactory, fieldName, lower, upper);
    }

    @Override
    public void testMatches() {
        DoubleScriptFieldRangeQuery query = new DoubleScriptFieldRangeQuery(randomScript(), leafFactory, "test", 1.2, 3.14);
        assertTrue(query.matches(values(new double[] { 1.2 })));
        assertTrue(query.matches(values(new double[] { 3.14 })));
        assertTrue(query.matches(values(new double[] { 2 })));
        assertFalse(query.matches(values(new double[] {})));
        assertFalse(query.matches(values(new double[] { 5 })));
        assertTrue(query.matches(values(new double[] { 2, 5 })));
        assertTrue(query.matches(values(new double[] { 5, 2 })));
        assertFalse(query.matches(values(new double[] { 1 })));
    }

    @Override
    protected void assertToString(DoubleScriptFieldRangeQuery query) {
        assertThat(query.toString(query.fieldName()), equalTo("[" + query.lowerValue() + " TO " + query.upperValue() + "]"));
    }
}
