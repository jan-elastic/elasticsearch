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
package org.elasticsearch.test;

import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.NodeMocksPlugin;
import org.elasticsearch.index.MockEngineFactoryPlugin;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.search.MockSearchService;
import org.elasticsearch.test.store.MockFSIndexStore;
import org.elasticsearch.test.transport.AssertingLocalTransport;
import org.elasticsearch.test.transport.MockTransportService;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public abstract class NodeConfigurationSource {

    public static final NodeConfigurationSource EMPTY = new NodeConfigurationSource() {
        @Override
        public Settings nodeSettings(int nodeOrdinal) {
            return null;
        }

        @Override
        public Settings transportClientSettings() {
            return null;
        }
    };

    /**
     * @return the settings for the node represented by the given ordinal, or {@code null} if there are no settings defined
     */
    public abstract Settings nodeSettings(int nodeOrdinal);

    /** Plugins that will be randomly added to the node */
    public Collection<Class<? extends Plugin>> mockPlugins() {
        List<Class<? extends Plugin>> plugins = new ArrayList<>();
        plugins.add(MockTransportService.TestPlugin.class);
        plugins.add(MockFSIndexStore.TestPlugin.class);
        plugins.add(NodeMocksPlugin.class);
        plugins.add(MockEngineFactoryPlugin.class);
        plugins.add(MockSearchService.TestPlugin.class);
        plugins.add(AssertingLocalTransport.TestPlugin.class);
        return plugins;
    }

    /** Returns plugins that should be loaded on the node */
    public Collection<Class<? extends Plugin>> nodePlugins() {
        return Collections.emptyList();
    }

    public abstract Settings transportClientSettings();

    /** Returns plugins that should be loaded in the transport client */
    public Collection<Class<? extends Plugin>> transportClientPlugins() {
        return Collections.emptyList();
    }

}
