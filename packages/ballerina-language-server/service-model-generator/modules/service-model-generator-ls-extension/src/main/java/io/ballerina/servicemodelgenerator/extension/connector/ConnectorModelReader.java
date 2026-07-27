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
import io.ballerina.compiler.api.SemanticModel;
import io.ballerina.modelgenerator.commons.CommonUtils;
import io.ballerina.modelgenerator.commons.PackageUtil;
import io.ballerina.modelgenerator.commons.TriggerAuthoringModel;
import io.ballerina.modelgenerator.commons.TriggerAuthoringResolver;
import io.ballerina.modelgenerator.commons.TriggerLibraryFacts;
import io.ballerina.modelgenerator.commons.TriggerLibraryIntrospector;
import io.ballerina.projects.Package;
import io.ballerina.projects.PackageDescriptor;
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
 * Reads the unified {@code trigger-model.json} for a connector, either from a bundled classpath
 * resource shipped in this jar, or -- on a miss -- synthesized at request time from a connector's own
 * shipped {@code resources/trigger-authoring.json} plus semantic-API introspection of its compiled
 * {@code .bala}.
 *
 * <p>The bundled path (registered in {@code bundled_trigger_models.json}) is the entry point of the
 * schema-driven trigger path for connectors curated (or {@code generate-trigger-model}-authored) into
 * this jar; {@link #getBundledTriggerModel} / {@link #hasBundledTriggerModel} consult only that
 * classpath registry, keyed by bare {@code moduleName} (a small curated set, so no org/version is
 * needed to disambiguate). {@link #getSchemaDrivenTriggerModel(String, String)} /
 * {@link #hasSchemaDrivenModel(String, String)} are the org-aware superset: they check the bundled
 * registry first (unchanged, zero regression), and on a miss resolve the module's own
 * {@code trigger-authoring.json} (via {@link TriggerAuthoringResolver}), introspect its compiled
 * {@link SemanticModel} (via {@link TriggerLibraryIntrospector}), and synthesize a {@link TriggerModel}
 * (via {@link io.ballerina.servicemodelgenerator.extension.connector.TriggerModelSynthesizer}) --
 * caching the result per {@code orgName/moduleName} so the resolve+introspect+synthesize cost is paid
 * at most once per module. A module with neither a bundled schema nor a resolvable
 * {@code trigger-authoring.json} resolves to {@link Optional#empty()}, so the routers fall back to the
 * existing hardcoded builder path exactly as before.
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
    // Keyed by "orgName/moduleName" -- the resolve+introspect+synthesize path, cached separately from
    // the bundled caches above since a synthesized model's source (a resolved .bala) differs entirely
    // from a bundled classpath resource.
    private final Map<String, Optional<TriggerModel>> schemaDrivenTriggerCache = new ConcurrentHashMap<>();

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

    // --- schema-driven trigger models (bundled-by-name, falling back to resolve+synthesize) ---

    /**
     * Cheap presence check across both tiers: the bundled classpath registry, then (on a miss, and only
     * when {@code orgName} is known) a synthesized model resolved from the module's own {@code .bala}.
     */
    public boolean hasSchemaDrivenModel(String orgName, String moduleName) {
        return getSchemaDrivenTriggerModel(orgName, moduleName).isPresent();
    }

    /**
     * The connector's {@link TriggerModel} -- bundled-by-name first (unchanged, zero regression), then
     * (on a miss) resolved from its own shipped {@code resources/trigger-authoring.json} plus semantic
     * introspection of its compiled {@code .bala}, synthesized via {@link TriggerModelSynthesizer}, and
     * cached per {@code orgName/moduleName}. {@code orgName == null} short-circuits to the bundled-only
     * result -- some call sites (e.g. a request DTO with no org field) genuinely have no org to resolve
     * a {@code .bala} with.
     */
    public Optional<TriggerModel> getSchemaDrivenTriggerModel(String orgName, String moduleName) {
        Optional<TriggerModel> bundled = getBundledTriggerModel(moduleName);
        if (bundled.isPresent() || orgName == null || moduleName == null) {
            return bundled;
        }
        return schemaDrivenTriggerCache.computeIfAbsent(orgName + "/" + moduleName,
                ignored -> synthesizeTriggerModel(orgName, moduleName));
    }

    /**
     * The connector's add-trigger init form -- the {@link #getSchemaDrivenTriggerModel} counterpart of
     * {@link #getBundledServiceInitModel}, remapping a synthesized model's {@code initProperties} the
     * same way {@link #buildServiceInitModelFromJson} does for a bundled one.
     */
    public Optional<ServiceInitModel> getSchemaDrivenServiceInitModel(String orgName, String moduleName) {
        Optional<ServiceInitModel> bundled = getBundledServiceInitModel(moduleName);
        if (bundled.isPresent() || orgName == null || moduleName == null) {
            return bundled;
        }
        return getSchemaDrivenTriggerModel(orgName, moduleName)
                .flatMap(model -> buildServiceInitModelFromJson(gson.toJsonTree(model)));
    }

    /**
     * Resolves and synthesizes a {@link TriggerModel} for a non-bundled module: reads the module's
     * package once (so the authoring-rules file and the semantic model agree on the exact same
     * resolved version), reads its {@code trigger-authoring.json} from that package root, introspects
     * its {@link SemanticModel}, and combines both via {@link TriggerModelSynthesizer}. Identity fields
     * a bundled model would carry (display name, icon) have no non-bundled source yet -- see
     * {@code TriggerMetadataResolver}'s own note that a connector's shipped {@code trigger-metadata.json}
     * is not yet consulted either -- so a humanized module name and the derived Central icon URL stand
     * in.
     *
     * <p>Wrapped in a blanket {@code catch (Throwable)}: {@code PackageUtil.getModulePackage}'s
     * version-less overload falls through to a live Central version lookup on an
     * offline-metadata miss, which <b>throws</b> (rather than returning empty) for an org/module that
     * doesn't exist there or when offline -- this method runs on the hot {@code useSchemaDrivenPath}
     * path for every unrecognized module, so any such failure must degrade to "not schema-driven," not
     * propagate and break routing for every service/function request.
     */
    private Optional<TriggerModel> synthesizeTriggerModel(String orgName, String moduleName) {
        try {
            return doSynthesizeTriggerModel(orgName, moduleName);
        } catch (Throwable e) {
            return Optional.empty();
        }
    }

    private Optional<TriggerModel> doSynthesizeTriggerModel(String orgName, String moduleName) {
        Optional<Package> pkg = PackageUtil.getModulePackage(PackageUtil.getSampleProject(), orgName, moduleName);
        if (pkg.isEmpty()) {
            return Optional.empty();
        }
        Optional<TriggerAuthoringModel> authoring = TriggerAuthoringResolver.readResource(
                pkg.get().project().sourceRoot());
        if (authoring.isEmpty()) {
            return Optional.empty();
        }
        SemanticModel semanticModel = PackageUtil.getCompilation(pkg.get())
                .getSemanticModel(pkg.get().getDefaultModule().moduleId());
        TriggerLibraryFacts facts = TriggerLibraryIntrospector.introspect(semanticModel);

        PackageDescriptor descriptor = pkg.get().descriptor();
        String resolvedOrg = descriptor.org().value();
        String resolvedPackageName = descriptor.name().value();
        String resolvedVersion = descriptor.version().value().toString();
        String displayName = TriggerModelSynthesizer.humanize(moduleName);
        String icon = CommonUtils.generateIcon(resolvedOrg, resolvedPackageName, resolvedVersion);

        return TriggerModelSynthesizer.synthesize(authoring.get(), facts, moduleName, displayName, icon, "event",
                resolvedOrg, resolvedPackageName, moduleName, resolvedVersion);
    }
}
