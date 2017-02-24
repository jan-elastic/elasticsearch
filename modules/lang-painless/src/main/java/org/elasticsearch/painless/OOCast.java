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

package org.elasticsearch.painless;

import org.elasticsearch.painless.Definition.Sort;
import org.elasticsearch.painless.Definition.Type;

/**
 * Casting strategies. Many, but not all, casting strategies support a "next" strategy to allow building compound strategies like "unbox and
 * then convert from char to int". These are always read from "outside inwards", meaning that a strategy is first executed and then the
 * "next" strategy is executed.
 */
public abstract class OOCast { // NOCOMMIT rename
    OOCast() {} // Subclasses should all be inner classes.

    public abstract boolean castRequired();
    public abstract void write(MethodWriter writer);
    public abstract Object castConstant(Location location, Object constant);
    public abstract String toString();

    /**
     * Cast that doesn't do anything. Used when you don't need to cast at all.
     */
    static final OOCast NOOP = new OOCast() {
        @Override
        public void write(MethodWriter writer) {
        }

        @Override
        public boolean castRequired() {
            return false;
        }

        @Override
        public Object castConstant(Location location, Object constant) {
            return constant;
        }

        @Override
        public String toString() {
            return "noop";
        }
    };

    /**
     * Promote or demote a numeric.
     */
    static class Numeric extends OOCast {
        private final Type from;
        private final Type to;
        private final OOCast next;

        Numeric(Type from, Type to, OOCast next) {
            if (from.equals(to)) {
                throw new IllegalArgumentException("From and to must not be equal but were [" + from + "].");
            }
            if (to.clazz.isAssignableFrom(from.clazz)) {
                throw new IllegalArgumentException("Promote isn't needed for to [" + to + "] is assignable to from [" + from + "]");
            }
            if (false == from.sort.numeric && from.sort.primitive) {
                throw new IllegalArgumentException("From [" + from + "] must be primitive and numeric.");
            }
            if (false == to.sort.numeric && to.sort.primitive) {
                throw new IllegalArgumentException("To [" + to + "] must be primitive and numeric.");
            }
            if (next == null) {
                throw new IllegalArgumentException("Next must not be null.");
            }
            this.from = from;
            this.to = to;
            this.next = next;
        }

        Numeric(Type from, Type to) {
            this(from, to, OOCast.NOOP); // NOCOMMIT use this more in analyzerCaster
        }

        @Override
        public boolean castRequired() {
            return true;
        }

        @Override
        public void write(MethodWriter writer) {
            writer.cast(from.type, to.type);
            next.write(writer);
        }

        @Override
        public Object castConstant(Location location, Object constant) {
            return next.castConstant(location, internalCastConstant(location, constant));
        }

        private Object internalCastConstant(Location location, Object constant) {
            Number number = from.sort == Sort.CHAR ? (int)(char)constant : (Number)constant;
            switch (to.sort) {
            case BYTE:   return number.byteValue();
            case SHORT:  return number.shortValue();
            case CHAR:   return (char)number.intValue();
            case INT:    return number.intValue();
            case LONG:   return number.longValue();
            case FLOAT:  return number.floatValue();
            case DOUBLE: return number.doubleValue();
            default:
                throw location.createError(new IllegalStateException("Cannot cast from " +
                    "[" + from.clazz.getCanonicalName() + "] to [" + to.clazz.getCanonicalName() + "]."));
            }
        }

        @Override
        public String toString() {
            return "(Numeric " + from + " " + to + " " + next + ")";
        }
    }

    /**
     * Box some boxable type.
     */
    static class Box extends OOCast {
        private final Type from;

        Box(Type from) {
            if (from.sort.boxed == null) {
                throw new IllegalArgumentException("From must be a boxable type but was [" + from + "]");
            }
            this.from = from;
        }

        @Override
        public boolean castRequired() {
            return true;
        }

        @Override
        public void write(MethodWriter writer) {
            writer.box(from.type);
        }

        @Override
        public Object castConstant(Location location, Object constant) {
            throw new UnsupportedOperationException("Boxed values can't be written as constants");
        }

        @Override
        public String toString() {
            return "(Box " + from + ")";
        }
    }

    /**
     * Unbox some boxable type.
     */
    static class Unbox extends OOCast {
        private final Type to;
        private final OOCast next;

        Unbox(Type to, OOCast next) {
            if (to.sort.boxed == null) {
                throw new IllegalArgumentException("To must be a boxable type but was [" + to + "]");
            }
            if (next == null) {
                throw new IllegalArgumentException("Next must not be null");
            }
            this.to = to;
            this.next = next;
        }

        Unbox(Type to) {
            this(to, OOCast.NOOP);
        }

        @Override
        public boolean castRequired() {
            return true;
        }

        @Override
        public void write(MethodWriter writer) {
            writer.unbox(to.type);
            next.write(writer);
        }

        @Override
        public Object castConstant(Location location, Object constant) {
            // Constants are always boxed inside the compiler. We unbox them when writing instead.
            return next.castConstant(location, constant);
        }

        @Override
        public String toString() {
            return "(Unbox " + to + " " + next + ")";
        }
    }

    /**
     * Performs a checked cast to narrow from a wider type to a more specific one. For example
     * {@code Number n = Integer.valueOf(5); Integer i = (Integer) n}.
     */
    static class CheckedCast extends OOCast {
        private final Type to;

        CheckedCast(Type to) {
            this.to = to;
        }

        @Override
        public boolean castRequired() {
            return true;
        }

        @Override
        public void write(MethodWriter writer) {
            writer.checkCast(to.type);
        }

        @Override
        public Object castConstant(Location location, Object constant) {
            return to.clazz.cast(constant);
        }

        @Override
        public String toString() {
            return "(CheckedCast " + to + ")";
        }
    }
}
