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

package org.elasticsearch.rest;

import com.google.common.collect.Lists;
import org.elasticsearch.common.inject.AbstractModule;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.rest.action.RestActionModule;
import org.elasticsearch.rest.action.cat.AbstractCatAction;

import java.util.ArrayList;
import java.util.List;

/**
 *
 */
public class RestModule extends AbstractModule {
    private final Settings settings;
    private final List<Class<? extends BaseRestHandler>> restPluginsActions = new ArrayList<>();
    private final List<Class<? extends AbstractCatAction>> catActions = new ArrayList<>();

    public RestModule(Settings settings) {
        this.settings = settings;
    }

    /**
     * Register a REST handler.
     */
    public void addRestAction(Class<? extends BaseRestHandler> handler) {
        restPluginsActions.add(handler);
    }

    /**
     * Register a cat action.
     */
    public void addCatAction(Class<? extends AbstractCatAction> action) {
        catActions.add(action);
    }

    @Override
    protected void configure() {
        bind(RestController.class).asEagerSingleton();
        new RestActionModule(restPluginsActions, catActions).configure(binder());
    }
}
