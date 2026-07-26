/*
 *  Copyright (c) 2026, WSO2 LLC. (http://www.wso2.com)
 *
 *  WSO2 LLC. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package io.ballerina.servicemodelgenerator.extension.connector;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import io.ballerina.servicemodelgenerator.extension.connector.model.TriggerModel;
import io.ballerina.servicemodelgenerator.extension.model.ServiceInitModel;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads the unified {@code trigger-model.json} for a connector from a bundled classpath resource
 * shipped in this jar.
 *
 * <p>This is the entry point of the schema-driven trigger path: a connector's schema is bundled here
 * (registered in {@code bundled_trigger_models.json}) rather than resolved from the connector's own
 * {@code .bala} -- there is no support for a connector shipping its own {@code trigger-model.json}. A
 * module with no bundled schema resolves to {@link Optional#empty()}, so the routers fall back to the
 * existing hardcoded builder path.
 *
 * @since 1.8.0
 */
public class ConnectorModelReader {

    private static final ConnectorModelReader INSTANCE = new ConnectorModelReader();

    private static final List<String> INIT_IDENTITY_KEYS = List.of(
            "id", "displayName", "description", "orgName", "packageName", "moduleName", "version", "type", "icon");

    private static final String BUNDLED_TRIGGER_MODEL_REGISTRY_RESOURCE = "bundled_trigger_models.json";
    private static final Type BUNDLED_REGISTRY_TYPE = new TypeToken<Map<String, String>>() { }.getType();

    /**
     * Modules for which a {@code trigger-model.json} is bundled as a classpath resource in this jar.
     * This lets a connector with a hardcoded Java builder (e.g. RabbitMQ, Kafka, Solace) migrate onto
     * the schema-driven path without needing a Central release. Keyed by moduleName to line up with the
     * routers' {@code CONSTRUCTOR_MAP}s.
     *
     * <p>Loaded from {@code bundled_trigger_models.json} (a resource sibling of
     * {@code trigger_properties.json}) rather than hardcoded, so onboarding a new bundled trigger model
     * is a data edit, not a Java edit. Falls back to an empty registry (no bundled models resolve) if
     * the resource is missing or malformed, so a broken/absent file degrades to the legacy-index
     * fallback rather than failing the class to load.
     *
     * <p>The registry's resource paths are rooted at {@code trigger-models/}.
     */
    private static final Map<String, String> BUNDLED_TRIGGER_MODEL_RESOURCES = loadBundledTriggerModelRegistry();

    private static Map<String, String> loadBundledTriggerModelRegistry() {
        try (InputStream is = ConnectorModelReader.class.getClassLoader()
                .getResourceAsStream(BUNDLED_TRIGGER_MODEL_REGISTRY_RESOURCE)) {
            if (is == null) {
                return Map.of();
            }
            try (JsonReader reader = new JsonReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                Map<String, String> loaded = new Gson().fromJson(reader, BUNDLED_REGISTRY_TYPE);
                return loaded == null ? Map.of() : Map.copyOf(loaded);
            }
        } catch (IOException | JsonParseException e) {
            return Map.of();
        }
    }

    private final Gson gson = new Gson();
    private final Map<String, Optional<TriggerModel>> bundledTriggerCache = new ConcurrentHashMap<>();
    private final Map<String, Optional<ServiceInitModel>> bundledInitCache = new ConcurrentHashMap<>();

    private ConnectorModelReader() {
    }

    public static ConnectorModelReader getInstance() {
        return INSTANCE;
    }

    /**
     * Derives the add-trigger init form from a parsed {@code trigger-model.json} document by remapping
     * {@code initProperties -> properties} at the JSON level. The wire model expects identity fields +
     * a top-level {@code properties} map, so this remaps at the JSON level and lets Gson deserialize the
     * (already {@code Value}-shaped) init-form nodes.
     */
    private Optional<ServiceInitModel> buildServiceInitModelFromJson(JsonElement parsed) {
        if (!parsed.isJsonObject()) {
            return Optional.empty();
        }
        JsonObject root = parsed.getAsJsonObject();
        JsonElement initProperties = root.get("initProperties");
        if (initProperties == null || !initProperties.isJsonObject()) {
            return Optional.empty();
        }
        JsonObject remapped = new JsonObject();
        for (String key : INIT_IDENTITY_KEYS) {
            if (root.has(key)) {
                remapped.add(key, root.get(key));
            }
        }
        remapped.add("properties", initProperties);
        return Optional.ofNullable(gson.fromJson(remapped, ServiceInitModel.class));
    }

    // --- bundled trigger models (classpath resources shipped in this jar) ---
    // Lets a connector with a hardcoded Java builder migrate onto the schema-driven path without a
    // Central release: the schema is bundled here instead of being resolved from a .bala.

    /** Cheap presence check for a bundled schema, used by the routers at dispatch time. */
    public boolean hasBundledTriggerModel(String moduleName) {
        return getBundledTriggerModel(moduleName).isPresent();
    }

    /** Reads and caches the bundled {@code trigger-model.json} for {@code moduleName}, if any. */
    public Optional<TriggerModel> getBundledTriggerModel(String moduleName) {
        if (moduleName == null) {
            return Optional.empty();
        }
        return bundledTriggerCache.computeIfAbsent(moduleName, m ->
                parseBundledResource(m).map(json -> gson.fromJson(json, TriggerModel.class)));
    }

    /** Reads and caches the bundled model's init form for {@code moduleName}, if any. */
    public Optional<ServiceInitModel> getBundledServiceInitModel(String moduleName) {
        if (moduleName == null) {
            return Optional.empty();
        }
        return bundledInitCache.computeIfAbsent(moduleName, m ->
                parseBundledResource(m).flatMap(this::buildServiceInitModelFromJson));
    }

    private Optional<JsonElement> parseBundledResource(String moduleName) {
        String resourcePath = BUNDLED_TRIGGER_MODEL_RESOURCES.get(moduleName);
        if (resourcePath == null) {
            return Optional.empty();
        }
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                return Optional.empty();
            }
            String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            return Optional.of(JsonParser.parseString(json));
        } catch (IOException | JsonParseException e) {
            return Optional.empty();
        }
    }
}
