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
import com.google.gson.JsonParser;
import io.ballerina.modelgenerator.commons.PackageUtil;
import io.ballerina.projects.Package;
import io.ballerina.servicemodelgenerator.extension.connector.model.TriggerModel;
import io.ballerina.servicemodelgenerator.extension.model.ServiceInitModel;
import io.ballerina.servicemodelgenerator.extension.util.Constants;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads the unified {@code trigger-model.json} out of a connector's {@code .bala} (or a bundled
 * classpath resource).
 *
 * <p>This is the entry point of the schema-driven trigger path: it locates the package in the
 * Bala Cache (pulling from Ballerina Central on a cache miss via {@link PackageUtil}), reads
 * {@code resources/trigger-model.json} from the resolved package root, deserializes it, and
 * caches the result per {@code org:name:version}. A connector that does not ship the model
 * resolves to {@link Optional#empty()}, so the routers fall back to the existing hardcoded path.
 *
 * @since 1.8.0
 */
public class ConnectorModelReader {

    public static final String TRIGGER_MODEL_FILE = "trigger-model.json";
    private static final String RESOURCES_DIR = "resources";

    private static final ConnectorModelReader INSTANCE = new ConnectorModelReader();

    private static final List<String> INIT_IDENTITY_KEYS = List.of(
            "id", "displayName", "description", "orgName", "packageName", "moduleName", "version", "type", "icon");

    /**
     * Modules for which a {@code trigger-model.json} is bundled as a classpath resource in this jar,
     * instead of being resolved from the connector's {@code .bala}. This lets a connector with a
     * hardcoded Java builder (e.g. RabbitMQ, Kafka, Solace) migrate onto the schema-driven path
     * without needing a Central release that ships the model. Keyed by moduleName to line up with the
     * routers' {@code CONSTRUCTOR_MAP}s.
     *
     * <p>Deliberately rooted at {@code bundled_trigger_models/} rather than {@code trigger_models/}:
     * the latter is already used under {@code src/test/resources} for unrelated read-path test
     * fixtures, and the test classpath merges main + test resources, so reusing that root would let a
     * test fixture masquerade as a bundled production schema.
     */
    private static final Map<String, String> BUNDLED_TRIGGER_MODEL_RESOURCES = Map.of(
            Constants.FTP, "trigger-models/ftp.json",
            Constants.KAFKA, "trigger-models/kafka.json",
            Constants.RABBITMQ, "trigger-models/rabbitmq.json",
            Constants.TRIGGER_GITHUB, "trigger-models/trigger.github.json"
    );

    private final Gson gson = new Gson();
    private final Map<String, Optional<TriggerModel>> triggerCache = new ConcurrentHashMap<>();
    private final Map<String, Optional<ServiceInitModel>> initCache = new ConcurrentHashMap<>();
    private final Map<String, Optional<TriggerModel>> bundledTriggerCache = new ConcurrentHashMap<>();
    private final Map<String, Optional<ServiceInitModel>> bundledInitCache = new ConcurrentHashMap<>();

    private ConnectorModelReader() {
    }

    public static ConnectorModelReader getInstance() {
        return INSTANCE;
    }

    /**
     * Resolves the on-disk root of a package from the Bala Cache, pulling from Ballerina Central on a
     * cache miss. Returns empty on any resolution failure (so callers fall back to the hardcoded path).
     */
    private Optional<Path> resolvePackageRoot(String org, String name, String version) {
        try {
            Optional<Package> resolved = (version == null || version.isBlank())
                    ? PackageUtil.getModulePackage(PackageUtil.getSampleProject(), org, name)
                    : PackageUtil.getModulePackage(PackageUtil.getSampleProject(), org, name, version);
            return resolved.map(pkg -> pkg.project().sourceRoot());
        } catch (Throwable e) {
            return Optional.empty();
        }
    }

    // --- single unified TriggerModel (resources/trigger-model.json) ---
    // One document unifying the init form with the service type(s) and their handler functions.

    /** Cheap presence check for the unified model, used by the routers for the schema-driven path. */
    public boolean hasTriggerModel(String org, String name, String version) {
        return readTriggerModel(org, name, version).isPresent();
    }

    /** Resolves and reads the connector's single {@code trigger-model.json}, caching the result. */
    public Optional<TriggerModel> readTriggerModel(String org, String name, String version) {
        String key = org + ":" + name + ":" + (version == null || version.isBlank() ? "latest" : version);
        return triggerCache.computeIfAbsent(key,
                k -> resolvePackageRoot(org, name, version).flatMap(this::readTriggerModelFromPackageRoot));
    }

    /**
     * Reads the unified model from a resolved package/bala root
     * ({@code <root>/resources/trigger-model.json}). Tolerant of the leading {@code $comment} key
     * (Gson ignores unknown fields). Package-visible for unit testing without the LS.
     *
     * @param packageRoot the on-disk root of the resolved package (a bala directory)
     * @return the model, or empty if the file is missing or unparseable
     */
    Optional<TriggerModel> readTriggerModelFromPackageRoot(Path packageRoot) {
        if (packageRoot == null) {
            return Optional.empty();
        }
        Path modelFile = packageRoot.resolve(RESOURCES_DIR).resolve(TRIGGER_MODEL_FILE);
        if (!Files.isRegularFile(modelFile)) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(gson.fromJson(parse(modelFile), TriggerModel.class));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Builds the add-trigger init form ({@link ServiceInitModel}) from the unified model's
     * {@code initProperties} subtree, caching the result. The wire model expects identity fields +
     * a top-level {@code properties} map, so this remaps {@code initProperties -> properties} at the
     * JSON level and lets Gson deserialize the (already {@code Value}-shaped) init-form nodes.
     */
    public Optional<ServiceInitModel> readServiceInitModel(String org, String name, String version) {
        String key = org + ":" + name + ":" + (version == null || version.isBlank() ? "latest" : version);
        return initCache.computeIfAbsent(key,
                k -> resolvePackageRoot(org, name, version).flatMap(this::readServiceInitModelFromPackageRoot));
    }

    /** Package-visible for unit testing without the LS. See {@link #readServiceInitModel}. */
    Optional<ServiceInitModel> readServiceInitModelFromPackageRoot(Path packageRoot) {
        if (packageRoot == null) {
            return Optional.empty();
        }
        Path modelFile = packageRoot.resolve(RESOURCES_DIR).resolve(TRIGGER_MODEL_FILE);
        if (!Files.isRegularFile(modelFile)) {
            return Optional.empty();
        }
        try {
            return buildServiceInitModelFromJson(parse(modelFile));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Derives the add-trigger init form from a parsed {@code trigger-model.json} document by remapping
     * {@code initProperties -> properties} at the JSON level. Shared by the bala-root reader and the
     * bundled-resource reader below.
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
    // Central release: the schema is bundled here instead of being resolved from a .bala. Checked by
    // the routers before the bala-based lookup above, so it never triggers a network/bala-cache hit.

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
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private JsonElement parse(Path file) throws IOException {
        return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
    }
}
