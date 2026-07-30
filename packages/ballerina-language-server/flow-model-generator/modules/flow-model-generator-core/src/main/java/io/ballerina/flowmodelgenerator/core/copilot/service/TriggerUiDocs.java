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

package io.ballerina.flowmodelgenerator.core.copilot.service;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.ballerina.modelgenerator.commons.trigger.models.TriggerUISchemaModel;
import io.ballerina.projects.SemanticVersion;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Read-only view over the LS-bundled trigger UI models ({@code bundled_trigger_models.json} +
 * {@code trigger-models/<module>.json}) used purely as a <b>documentation source</b> for the
 * schema-driven Copilot service loader: handler descriptions and parameter names/descriptions that
 * marker service types cannot supply through the semantic model (their methods are never declared
 * in the library — the contracts are enforced by compiler plugins). Types, optionality, and method
 * sets are never taken from here; those come from {@code trigger-metadata.json} and the semantic
 * model.
 *
 * <p>Registry entries are either a plain resource-path string or an array of
 * {@code {minVersion?, resource}} variants; variants are matched against the resolved package
 * version exactly like the service model generator's {@code ConnectorModelReader} does, so the
 * Copilot and the low-code editor always describe the same model of a module version.
 *
 * @since 1.7.0
 */
final class TriggerUiDocs {

    private static final Logger LOGGER = Logger.getLogger(TriggerUiDocs.class.getName());
    private static final String REGISTRY_RESOURCE = "bundled_trigger_models.json";
    private static final Gson GSON = new Gson();

    /** serviceTypeName (module prefix stripped) → functionName → docs. */
    private final Map<String, Map<String, FunctionDocs>> byServiceType;

    /**
     * Documentation of one handler function.
     *
     * @param description the function's UI-model description
     * @param params      per-parameter docs, in the UI model's parameter order
     */
    record FunctionDocs(String description, List<ParamDocs> params) {

        Optional<ParamDocs> paramAt(int index) {
            return index >= 0 && index < params.size() ? Optional.of(params.get(index)) : Optional.empty();
        }

        Optional<ParamDocs> paramNamed(String name) {
            if (name == null) {
                return Optional.empty();
            }
            return params.stream().filter(p -> name.equals(p.name())).findFirst();
        }
    }

    /**
     * Documentation of one handler parameter.
     *
     * @param name        the UI model's parameter name (may be null when the model carries none)
     * @param description the parameter description
     */
    record ParamDocs(String name, String description) {
    }

    private TriggerUiDocs(Map<String, Map<String, FunctionDocs>> byServiceType) {
        this.byServiceType = byServiceType;
    }

    static TriggerUiDocs empty() {
        return new TriggerUiDocs(Map.of());
    }

    Optional<FunctionDocs> functionDocs(String serviceTypeName, String functionName) {
        Map<String, FunctionDocs> functions = byServiceType.get(stripModulePrefix(serviceTypeName));
        if (functions == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(functions.get(functionName));
    }

    boolean isEmpty() {
        return byServiceType.isEmpty();
    }

    /**
     * Loads the version-appropriate bundled UI model for a module and indexes its documentation.
     * Returns {@link #empty()} when the module has no bundled model or on any read failure — the
     * loader then simply emits without UI-sourced docs.
     */
    static TriggerUiDocs load(String moduleName, String resolvedVersion) {
        try (InputStream registryStream = TriggerUiDocs.class.getClassLoader()
                .getResourceAsStream(REGISTRY_RESOURCE)) {
            if (registryStream == null) {
                return empty();
            }
            JsonObject registry;
            try (InputStreamReader reader = new InputStreamReader(registryStream, StandardCharsets.UTF_8)) {
                registry = JsonParser.parseReader(reader).getAsJsonObject();
            }
            String resource = resolveResource(registry.get(moduleName), resolvedVersion);
            if (resource == null) {
                return empty();
            }
            try (InputStream modelStream = TriggerUiDocs.class.getClassLoader().getResourceAsStream(resource)) {
                if (modelStream == null) {
                    return empty();
                }
                try (InputStreamReader reader = new InputStreamReader(modelStream, StandardCharsets.UTF_8)) {
                    TriggerUISchemaModel model = GSON.fromJson(reader, TriggerUISchemaModel.class);
                    return index(model);
                }
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warning("Failed to load bundled trigger UI model for " + moduleName + ": " + e.getMessage());
            return empty();
        }
    }

    /**
     * Resolves the registry entry to a resource path: a plain string entry is unconditional; an
     * array entry picks the first variant whose {@code minVersion} the resolved version satisfies,
     * with a version-less variant acting as the fallback.
     */
    static String resolveResource(JsonElement entry, String resolvedVersion) {
        if (entry == null || entry.isJsonNull()) {
            return null;
        }
        if (entry.isJsonPrimitive()) {
            return entry.getAsString();
        }
        if (!entry.isJsonArray()) {
            return null;
        }
        String fallback = null;
        for (JsonElement variantElement : entry.getAsJsonArray()) {
            JsonObject variant = variantElement.getAsJsonObject();
            String resource = variant.has("resource") ? variant.get("resource").getAsString() : null;
            if (resource == null) {
                continue;
            }
            if (!variant.has("minVersion")) {
                if (fallback == null) {
                    fallback = resource;
                }
                continue;
            }
            if (satisfiesMinVersion(resolvedVersion, variant.get("minVersion").getAsString())) {
                return resource;
            }
        }
        return fallback;
    }

    /**
     * Mirrors {@code ConnectorModelReader}'s variant matching: an absent or unparsable resolved
     * version resolves to the newest (gated) document rather than the oldest fallback, so the
     * Copilot and the low-code editor always describe the same model of a module.
     */
    private static boolean satisfiesMinVersion(String version, String minVersion) {
        if (version == null) {
            return true;
        }
        try {
            return SemanticVersion.from(version).greaterThanOrEqualTo(SemanticVersion.from(minVersion));
        } catch (RuntimeException e) {
            return true;
        }
    }

    static TriggerUiDocs index(TriggerUISchemaModel model) {
        if (model == null || model.serviceTypes() == null) {
            return empty();
        }
        Map<String, Map<String, FunctionDocs>> byServiceType = new LinkedHashMap<>();
        for (TriggerUISchemaModel.ServiceTypeModel serviceType : model.serviceTypes()) {
            if (serviceType == null || serviceType.name() == null) {
                continue;
            }
            Map<String, FunctionDocs> functions = byServiceType.computeIfAbsent(
                    stripModulePrefix(serviceType.name()), k -> new LinkedHashMap<>());
            indexFunctions(serviceType.functions(), functions);
            indexFunctions(serviceType.schemaFunctions(), functions);
        }
        return new TriggerUiDocs(byServiceType);
    }

    private static void indexFunctions(List<TriggerUISchemaModel.FunctionModel> models,
                                       Map<String, FunctionDocs> out) {
        if (models == null) {
            return;
        }
        for (TriggerUISchemaModel.FunctionModel function : models) {
            if (function == null || function.name() == null) {
                continue;
            }
            String description = function.metadata() != null && function.metadata().description() != null
                    ? function.metadata().description() : "";
            List<ParamDocs> params = new ArrayList<>();
            if (function.parameters() != null) {
                for (TriggerUISchemaModel.Parameter parameter : function.parameters()) {
                    params.add(new ParamDocs(propertyStringValue(parameter == null ? null : parameter.name()),
                            parameterDescription(parameter)));
                }
            }
            out.putIfAbsent(function.name(), new FunctionDocs(description, params));
        }
    }

    private static String parameterDescription(TriggerUISchemaModel.Parameter parameter) {
        if (parameter == null) {
            return "";
        }
        if (parameter.metadata() != null && parameter.metadata().description() != null
                && !parameter.metadata().description().isEmpty()) {
            return parameter.metadata().description();
        }
        TriggerUISchemaModel.Property name = parameter.name();
        if (name != null && name.metadata() != null && name.metadata().description() != null) {
            return name.metadata().description();
        }
        return "";
    }

    private static String propertyStringValue(TriggerUISchemaModel.Property property) {
        if (property == null || !(property.value() instanceof String stringValue) || stringValue.isEmpty()) {
            return null;
        }
        return stringValue;
    }

    /** {@code "github:IssuesService"} → {@code "IssuesService"}; unprefixed names pass through. */
    static String stripModulePrefix(String serviceTypeName) {
        if (serviceTypeName == null) {
            return null;
        }
        int idx = serviceTypeName.lastIndexOf(':');
        return idx >= 0 ? serviceTypeName.substring(idx + 1) : serviceTypeName;
    }
}
