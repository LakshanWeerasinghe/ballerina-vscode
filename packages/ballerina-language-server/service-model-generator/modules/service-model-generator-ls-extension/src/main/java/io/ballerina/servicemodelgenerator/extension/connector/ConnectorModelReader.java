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
import io.ballerina.projects.SemanticVersion;
import io.ballerina.servicemodelgenerator.extension.connector.model.TriggerModel;
import io.ballerina.servicemodelgenerator.extension.model.ServiceInitModel;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
    private static final Type BUNDLED_REGISTRY_TYPE = new TypeToken<Map<String, JsonElement>>() { }.getType();
    private static final String KEY_MIN_VERSION = "minVersion";
    private static final String KEY_RESOURCE = "resource";

    /**
     * One version-gated variant of a connector's bundled schema.
     *
     * @param minVersion the lowest connector version this document describes; a variant without one
     *                   matches any version and so acts as the floor
     * @param resource   the classpath resource path of the schema document
     */
    private record ModelVariant(String minVersion, String resource) {

        boolean matches(String version) {
            if (minVersion == null || minVersion.isBlank()) {
                return true;
            }
            try {
                return SemanticVersion.from(version).greaterThanOrEqualTo(SemanticVersion.from(minVersion));
            } catch (RuntimeException e) {
                // An unparsable version can't be gated on -> treat the variant as a match, which
                // (given declaration order) resolves to the newest document.
                return true;
            }
        }
    }

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
     * <p>An entry is either a single resource path
     * <pre>{@code "kafka": "trigger-models/kafka.json"}</pre>
     * or, for a connector whose UI surface changed across releases, an array of variants <b>ordered
     * newest first</b>, each gated by the lowest connector version it describes:
     * <pre>{@code
     * "mcp": [
     *   { "minVersion": "1.2.0", "resource": "trigger-models/mcp.json" },
     *   { "resource": "trigger-models/mcp_1.0.3.json" }
     * ]}</pre>
     * The first variant whose {@code minVersion} the resolved connector version satisfies wins; the
     * unconstrained trailing entry is the floor. Callers with no version in hand get the first
     * (newest) variant — see {@link #getBundledTriggerModel(String)}.
     *
     * <p>The registry's resource paths are rooted at {@code trigger-models/}.
     */
    private static final Map<String, List<ModelVariant>> BUNDLED_TRIGGER_MODEL_RESOURCES =
            loadBundledTriggerModelRegistry();

    private static Map<String, List<ModelVariant>> loadBundledTriggerModelRegistry() {
        try (InputStream is = ConnectorModelReader.class.getClassLoader()
                .getResourceAsStream(BUNDLED_TRIGGER_MODEL_REGISTRY_RESOURCE)) {
            if (is == null) {
                return Map.of();
            }
            try (JsonReader reader = new JsonReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                Map<String, JsonElement> loaded = new Gson().fromJson(reader, BUNDLED_REGISTRY_TYPE);
                if (loaded == null) {
                    return Map.of();
                }
                Map<String, List<ModelVariant>> registry = new LinkedHashMap<>();
                loaded.forEach((moduleName, entry) -> {
                    List<ModelVariant> variants = parseVariants(entry);
                    if (!variants.isEmpty()) {
                        registry.put(moduleName, variants);
                    }
                });
                return Map.copyOf(registry);
            }
        } catch (IOException | JsonParseException e) {
            return Map.of();
        }
    }

    /** Normalizes both registry entry forms (a bare resource path, or an ordered variant array). */
    private static List<ModelVariant> parseVariants(JsonElement entry) {
        if (entry == null || entry.isJsonNull()) {
            return List.of();
        }
        if (entry.isJsonPrimitive()) {
            return List.of(new ModelVariant(null, entry.getAsString()));
        }
        if (!entry.isJsonArray()) {
            return List.of();
        }
        List<ModelVariant> variants = new ArrayList<>();
        for (JsonElement element : entry.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject variant = element.getAsJsonObject();
            JsonElement resource = variant.get(KEY_RESOURCE);
            if (resource == null || !resource.isJsonPrimitive()) {
                continue;
            }
            JsonElement minVersion = variant.get(KEY_MIN_VERSION);
            variants.add(new ModelVariant(
                    minVersion != null && minVersion.isJsonPrimitive() ? minVersion.getAsString() : null,
                    resource.getAsString()));
        }
        return List.copyOf(variants);
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

    /**
     * Cheap presence check for a bundled schema, used by the routers at dispatch time. Deliberately
     * version-free: it only decides <i>which builder</i> handles the module, and every variant of a
     * connector's schema is served by the same schema-driven builder.
     */
    public boolean hasBundledTriggerModel(String moduleName) {
        return getBundledTriggerModel(moduleName).isPresent();
    }

    /**
     * Reads and caches the bundled {@code trigger-model.json} for {@code moduleName}, if any, choosing
     * the newest variant. For a connector whose schema is version-gated, prefer
     * {@link #getBundledTriggerModel(String, String)} wherever the version the project actually
     * resolves is known — the newest variant may describe types the project's version does not have.
     */
    public Optional<TriggerModel> getBundledTriggerModel(String moduleName) {
        return getBundledTriggerModel(moduleName, null);
    }

    /**
     * Reads and caches the bundled {@code trigger-model.json} variant that describes
     * {@code moduleName} at {@code version}. A {@code null}/blank version selects the newest variant.
     */
    public Optional<TriggerModel> getBundledTriggerModel(String moduleName, String version) {
        return resolveResource(moduleName, version).flatMap(resource ->
                bundledTriggerCache.computeIfAbsent(resource, r ->
                        parseBundledResource(r).map(json -> gson.fromJson(json, TriggerModel.class))));
    }

    /** Reads and caches the newest bundled model's init form for {@code moduleName}, if any. */
    public Optional<ServiceInitModel> getBundledServiceInitModel(String moduleName) {
        return getBundledServiceInitModel(moduleName, null);
    }

    /**
     * Reads and caches the init form of the bundled model variant that describes {@code moduleName} at
     * {@code version}. A {@code null}/blank version selects the newest variant.
     */
    public Optional<ServiceInitModel> getBundledServiceInitModel(String moduleName, String version) {
        return resolveResource(moduleName, version).flatMap(resource ->
                bundledInitCache.computeIfAbsent(resource, r ->
                        parseBundledResource(r).flatMap(this::buildServiceInitModelFromJson)));
    }

    /**
     * The resource path of the variant describing {@code moduleName} at {@code version}: the first
     * declared variant the version satisfies. When no version is supplied the newest (first) variant
     * wins; when the version is below every declared floor the oldest (last) variant is the closest
     * fit — a model authored without an unconstrained trailing entry still resolves to something.
     */
    private static Optional<String> resolveResource(String moduleName, String version) {
        if (moduleName == null) {
            return Optional.empty();
        }
        List<ModelVariant> variants = BUNDLED_TRIGGER_MODEL_RESOURCES.get(moduleName);
        if (variants == null || variants.isEmpty()) {
            return Optional.empty();
        }
        if (version == null || version.isBlank()) {
            return Optional.of(variants.getFirst().resource());
        }
        return Optional.of(variants.stream()
                .filter(variant -> variant.matches(version))
                .findFirst()
                .orElseGet(variants::getLast)
                .resource());
    }

    private Optional<JsonElement> parseBundledResource(String resourcePath) {
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
