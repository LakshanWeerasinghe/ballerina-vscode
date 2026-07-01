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
import io.ballerina.servicemodelgenerator.extension.connector.model.LibraryArtifact;
import io.ballerina.servicemodelgenerator.extension.model.ServiceInitModel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads the two connector-shipped JSON models out of a connector's {@code .bala}.
 *
 * <p>This is the entry point of the schema-driven trigger path: it locates the package in the
 * Bala Cache (pulling from Ballerina Central on a cache miss via {@link PackageUtil}), reads
 * {@code resources/service-creation.json} and {@code resources/service-metadata.json} from the
 * resolved package root, deserializes them, and caches the result per {@code org:name:version}.
 * A connector that does not ship both models resolves to {@link Optional#empty()}, so the routers
 * fall back to the existing (hardcoded / DB-backed) path.
 *
 * @since 1.8.0
 */
public class ConnectorModelReader {

    public static final String CREATION_MODEL_FILE = "service-creation.json";
    public static final String METADATA_MODEL_FILE = "service-metadata.json";
    private static final String RESOURCES_DIR = "resources";
    private static final String WRAPPER_CREATION = "serviceInitModel";
    private static final String WRAPPER_METADATA = "libraryArtifact";

    private static final ConnectorModelReader INSTANCE = new ConnectorModelReader();

    private final Gson gson = new Gson();
    private final Map<String, Optional<ConnectorModels>> cache = new ConcurrentHashMap<>();

    private ConnectorModelReader() {
    }

    public static ConnectorModelReader getInstance() {
        return INSTANCE;
    }

    /**
     * The pair of connector-shipped models. {@code creationModel} drives the add-trigger init form;
     * {@code metadataModel} drives source generation and the designer/function flows.
     *
     * @param creationModel the Service Creation Model (deserialized into the wire {@link ServiceInitModel})
     * @param metadataModel the Service Metadata Model (the connector-shaped {@link LibraryArtifact})
     */
    public record ConnectorModels(ServiceInitModel creationModel, LibraryArtifact metadataModel) {
    }

    /**
     * Cheap presence check used by the routers to decide whether to take the schema-driven path.
     * Backed by the same cache as {@link #read}.
     */
    public boolean hasConnectorModels(String org, String name, String version) {
        return read(org, name, version).isPresent();
    }

    /**
     * Resolves and reads both connector models, caching the (possibly empty) result.
     */
    public Optional<ConnectorModels> read(String org, String name, String version) {
        String key = org + ":" + name + ":" + (version == null || version.isBlank() ? "latest" : version);
        return cache.computeIfAbsent(key, k -> locateAndRead(org, name, version));
    }

    private Optional<ConnectorModels> locateAndRead(String org, String name, String version) {
        try {
            Optional<Package> resolved = (version == null || version.isBlank())
                    ? PackageUtil.getModulePackage(PackageUtil.getSampleProject(), org, name)
                    : PackageUtil.getModulePackage(PackageUtil.getSampleProject(), org, name, version);
            if (resolved.isEmpty()) {
                return Optional.empty();
            }
            return readFromPackageRoot(resolved.get().project().sourceRoot());
        } catch (Throwable e) {
            // Resolution / IO failure -> behave as "no connector models" so the caller falls back.
            return Optional.empty();
        }
    }

    /**
     * Reads both models from a resolved package/bala root ({@code <root>/resources/*.json}).
     * Tolerant of the example wrapper keys ({@code serviceInitModel} / {@code libraryArtifact}) and
     * of an unrelated {@code $comment} key. Package-visible for unit testing without the LS.
     *
     * @param packageRoot the on-disk root of the resolved package (a bala directory)
     * @return both models, or empty if either file is missing or unparseable
     */
    Optional<ConnectorModels> readFromPackageRoot(Path packageRoot) {
        if (packageRoot == null) {
            return Optional.empty();
        }
        Path resourcesDir = packageRoot.resolve(RESOURCES_DIR);
        Path creationFile = resourcesDir.resolve(CREATION_MODEL_FILE);
        Path metadataFile = resourcesDir.resolve(METADATA_MODEL_FILE);
        if (!Files.isRegularFile(creationFile) || !Files.isRegularFile(metadataFile)) {
            return Optional.empty();
        }
        try {
            ServiceInitModel creation = gson.fromJson(
                    unwrap(parse(creationFile), WRAPPER_CREATION), ServiceInitModel.class);
            LibraryArtifact metadata = gson.fromJson(
                    unwrap(parse(metadataFile), WRAPPER_METADATA), LibraryArtifact.class);
            if (creation == null || metadata == null) {
                return Optional.empty();
            }
            return Optional.of(new ConnectorModels(creation, metadata));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private JsonElement parse(Path file) throws IOException {
        return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
    }

    private static JsonElement unwrap(JsonElement element, String wrapperKey) {
        if (element.isJsonObject()) {
            JsonObject obj = element.getAsJsonObject();
            if (obj.has(wrapperKey) && obj.get(wrapperKey).isJsonObject()) {
                return obj.get(wrapperKey);
            }
        }
        return element;
    }
}
