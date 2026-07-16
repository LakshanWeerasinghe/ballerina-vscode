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

package io.ballerina.modelgenerator.commons;

import com.google.gson.Gson;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads a connector's small {@code trigger-metadata.json} — either bundled as a classpath resource
 * for the LS's currently-hardcoded entry-point modules, or shipped by the connector itself under
 * {@code resources/trigger-metadata.json}.
 *
 * <p>The bundled lookup ({@link #getBundledMetadata}) is a pure classpath read keyed by module name —
 * no bala-cache resolution or network access — so it is safe to call from hot paths such as
 * project-tree / artifact-tree generation, which runs on every rebuild of the component tree. This is
 * the same reason the reader targets {@code trigger-metadata.json} rather than the much larger
 * {@code trigger-model.json}: that document additionally carries the full init-form and
 * service-type/handler schema, which the tree never needs.
 *
 * @since 1.9.0
 */
public final class TriggerMetadataReader {

    private static final String TRIGGER_METADATA_FILE = "trigger-metadata.json";
    private static final String RESOURCES_DIR = "resources";
    private static final String BUNDLED_RESOURCE_ROOT = "trigger-metadata/";

    // The module names that ship a bundled trigger-metadata/<name>.json. Keep this in sync when adding a
    // resource under src/main/resources/trigger-metadata/. A name absent here is treated as non-bundled,
    // so callers skip the (far more expensive) package resolution only for the modules that truly need it.
    private static final Set<String> BUNDLED_MODULES = Set.of(
            "ai", "asb", "file", "ftp", "github", "graphql", "http", "kafka", "mqtt", "mssql", "mysql",
            "postgresql", "rabbitmq", "salesforce", "shopify", "solace", "tcp", "twilio");

    private static final TriggerMetadataReader INSTANCE = new TriggerMetadataReader();

    private final Gson gson = new Gson();
    private final Map<String, Optional<TriggerMetadata>> bundledCache = new ConcurrentHashMap<>();
    private final Map<Path, Optional<TriggerMetadata>> packageRootCache = new ConcurrentHashMap<>();

    private TriggerMetadataReader() {
    }

    public static TriggerMetadataReader getInstance() {
        return INSTANCE;
    }

    /** Whether {@code trigger-metadata/<moduleName>.json} is bundled in the LS. A cheap set lookup. */
    public boolean isBundled(String moduleName) {
        return moduleName != null && BUNDLED_MODULES.contains(moduleName);
    }

    /**
     * Reads the bundled {@code trigger-metadata/<moduleName>.json} classpath resource, if any.
     * Pure classpath access — no I/O beyond the jar's own resources.
     */
    public Optional<TriggerMetadata> getBundledMetadata(String moduleName) {
        if (!isBundled(moduleName)) {
            return Optional.empty();
        }
        return bundledCache.computeIfAbsent(moduleName, this::readBundledResource);
    }

    private Optional<TriggerMetadata> readBundledResource(String moduleName) {
        String resourcePath = BUNDLED_RESOURCE_ROOT + moduleName + ".json";
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                return Optional.empty();
            }
            String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            return Optional.ofNullable(gson.fromJson(JsonParser.parseString(json), TriggerMetadata.class));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Reads {@code <packageRoot>/resources/trigger-metadata.json}, if a connector ships one. Intended
     * for callers that already have a resolved package root in hand (e.g. alongside a
     * {@code trigger-model.json} read) — it does not itself perform bala-cache/Central resolution.
     */
    public Optional<TriggerMetadata> readMetadataFromPackageRoot(Path packageRoot) {
        if (packageRoot == null) {
            return Optional.empty();
        }
        return packageRootCache.computeIfAbsent(packageRoot, this::parsePackageRoot);
    }

    private Optional<TriggerMetadata> parsePackageRoot(Path packageRoot) {
        Path modelFile = packageRoot.resolve(RESOURCES_DIR).resolve(TRIGGER_METADATA_FILE);
        if (!Files.isRegularFile(modelFile)) {
            return Optional.empty();
        }
        try {
            String json = Files.readString(modelFile, StandardCharsets.UTF_8);
            return Optional.ofNullable(gson.fromJson(JsonParser.parseString(json), TriggerMetadata.class));
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
