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

import com.google.gson.JsonParseException;
import io.ballerina.compiler.api.ModuleID;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves a connector's own shipped {@code resources/trigger-authoring.json} from its resolved
 * {@code .bala} -- the step {@link TriggerMetadataResolver}'s own doc comment marks as "not
 * supported in this phase" for the separate, much larger {@code trigger-model.json}. This resolver
 * is deliberately narrower: it only resolves and parses the small authoring-rules document (see
 * {@link TriggerAuthoringModel}). Combining it with {@link TriggerLibraryIntrospector}'s facts into
 * a full {@code TriggerModel} is a synthesizer's job, not this class's.
 *
 * <p>Same trust boundary and caching pattern as {@link TriggerMetadataResolver}'s icon resolution:
 * a package root is resolved lazily (only on first lookup) and cached by {@code org/name:version}
 * coordinates, so a repeated lookup pays bala-cache resolution at most once per module. The two
 * resolvers keep independent caches rather than sharing one -- each is a handful of lines and the
 * two documents (display metadata vs. authoring rules) are read on unrelated schedules.
 *
 * @since 1.10.0
 */
public final class TriggerAuthoringResolver {

    private static final String RESOURCE_PATH = "resources/trigger-authoring.json";

    // Package roots are resolved lazily (only on lookup) and cached by module coordinates, same
    // shape as TriggerMetadataResolver's PACKAGE_ROOT_CACHE.
    private static final Map<String, Optional<Path>> PACKAGE_ROOT_CACHE = new ConcurrentHashMap<>();

    private TriggerAuthoringResolver() {
    }

    /** The connector's {@code trigger-authoring.json}, if it ships one; {@link Optional#empty()} otherwise. */
    public static Optional<TriggerAuthoringModel> resolve(ModuleID moduleId) {
        return moduleId == null ? Optional.empty()
                : resolve(moduleId.orgName(), moduleId.packageName(), moduleId.version());
    }

    /** Coordinate-based overload of {@link #resolve(ModuleID)}. */
    public static Optional<TriggerAuthoringModel> resolve(String orgName, String packageName, String version) {
        return packageRoot(orgName, packageName, version).flatMap(TriggerAuthoringResolver::readResource);
    }

    private static Optional<Path> packageRoot(String orgName, String packageName, String version) {
        String key = orgName + "/" + packageName + ":" + version;
        return PACKAGE_ROOT_CACHE.computeIfAbsent(key, ignored -> {
            try {
                return PackageUtil.getModulePackage(PackageUtil.getSampleProject(), orgName, packageName, version)
                        .map(pkg -> pkg.project().sourceRoot());
            } catch (Throwable e) {
                return Optional.empty();
            }
        });
    }

    /**
     * Reads and parses {@code resources/trigger-authoring.json} relative to an already-resolved
     * package root. Package-visible (rather than private) so it is directly unit-testable against a
     * plain test-fixture directory, independent of live bala/Central resolution.
     */
    static Optional<TriggerAuthoringModel> readResource(Path packageRoot) {
        Path file = packageRoot.resolve(RESOURCE_PATH).normalize();
        if (!file.startsWith(packageRoot) || !Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            return Optional.ofNullable(TriggerAuthoringGson.instance().fromJson(json, TriggerAuthoringModel.class));
        } catch (IOException | JsonParseException e) {
            return Optional.empty();
        }
    }
}
