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

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import io.ballerina.compiler.api.SemanticModel;
import io.ballerina.modelgenerator.commons.CommonUtils;
import io.ballerina.modelgenerator.commons.ModuleInfo;
import io.ballerina.modelgenerator.commons.PackageUtil;
import io.ballerina.modelgenerator.commons.trigger.LibraryMetadataReader;
import io.ballerina.modelgenerator.commons.trigger.models.TriggerLibraryFacts;
import io.ballerina.modelgenerator.commons.trigger.models.TriggerMetadataModel;
import io.ballerina.modelgenerator.commons.trigger.models.TriggerUIMetadataModel;
import io.ballerina.modelgenerator.commons.trigger.models.TriggerUISchemaModel;
import io.ballerina.modelgenerator.commons.trigger.models.TypeRef;
import io.ballerina.modelgenerator.commons.trigger.utils.TriggerLibraryIntrospector;
import io.ballerina.projects.Package;
import io.ballerina.projects.PackageDescriptor;
import io.ballerina.servicemodelgenerator.extension.model.Codedata;
import io.ballerina.servicemodelgenerator.extension.model.Listener;
import io.ballerina.servicemodelgenerator.extension.model.ServiceInitModel;
import io.ballerina.servicemodelgenerator.extension.util.ListenerUtil;

import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import static io.ballerina.servicemodelgenerator.extension.util.Constants.PROP_KEY_LISTENER;

/**
 * Reads the unified {@code trigger-ui-schema.json} for a connector, generated, shipped, or synthesized.
 */
public class TriggerModelReader {

    private static final Logger LOGGER = Logger.getLogger(TriggerModelReader.class.getName());

    private static final TriggerModelReader INSTANCE = new TriggerModelReader();

    private static final List<String> INIT_IDENTITY_KEYS = List.of(
            "id", "displayName", "description", "orgName", "packageName", "moduleName", "version", "type", "icon");

    private static final int MAX_CACHE_SIZE = 2;
    /** Sized for the generated tier once it's the default resolution path: every one of the ~30
     * packaged connectors may be resolved in one designer session, not just one at a time. */
    private static final int GENERATED_CACHE_SIZE = 32;

    private static final String GENERATION_MODE_PROPERTY = "ballerina.trigger.models";
    private static final String GENERATION_MODE_GENERATED = "generated";

    /**
     * Whether the generated (L1 + semantic facts + L2) tier is tried ahead of the shipped/synthesized
     * tiers. Generation is opt-in until it has been fully validated against real connector packages;
     * use {@code -Dballerina.trigger.models=generated} to exercise the new tier. The default keeps the
     * established shipped/synthesized resolution path intact while the generated models are being
     * onboarded.
     */
    private static boolean generationEnabled(String moduleName) {
        return GENERATION_MODE_GENERATED.equalsIgnoreCase(System.getProperty(GENERATION_MODE_PROPERTY))
                && !GENERATION_NOT_YET_ONBOARDED.contains(moduleName);
    }

    /**
     * Modules whose packaged L1 + L2 exist (so {@link #getGeneratedTriggerModel} would happily
     * synthesize a model for them) but which are deliberately kept off the generated tier for now:
     * {@code http}/{@code graphql}/{@code grpc}/{@code tcp}/{@code websocket}/{@code websub}/
     * {@code trigger.google.calendar} are not schema-driven today -- {@code ServiceBuilderRouter}/
     * {@code FunctionBuilderRouter} route
     * {@code http}/{@code graphql}/{@code tcp} to their own dedicated hardcoded builders via
     * {@code hasSchemaDrivenModel}, and the rest fall through to {@code DefaultServiceBuilder}.
     * Silently making {@code hasSchemaDrivenModel} true for them would divert that routing decision as
     * an unintended side effect of packaging their L1+L2 for the parity harness -- precisely what broke
     * ~55 unrelated tests the last time a packaged metadata model was seeded for a not-yet-schema-driven
     * module (see the "Follow-ups from the Trigger Construct Spec v1.0 migration" backlog entry).
     * Onboarding any of these onto the generated tier is a deliberate follow-up, not a side effect of
     * this set shrinking.
     */
    private static final Set<String> GENERATION_NOT_YET_ONBOARDED = Set.of(
            "http", "graphql", "grpc", "tcp", "websocket", "websub", "trigger.google.calendar");

    private final Gson gson = new Gson();
    /** Static counterpart of {@link #gson}, for the init-form derivation that runs before binding. */
    private static final Gson DERIVATION_GSON = new Gson();
    private static final Type LISTENER_MODEL_LIST_TYPE =
            new TypeToken<List<TriggerUISchemaModel.ListenerModel>>() { }.getType();
    private final Cache<String, Optional<TriggerUISchemaModel>> schemaDrivenTriggerCache =
            Caffeine.newBuilder().maximumSize(MAX_CACHE_SIZE).build();
    /** Keyed {@code org/module:version} -- unlike {@link #schemaDrivenTriggerCache}, version is part of
     * the key here because the generated tier's output genuinely varies by version (L2 variant
     * selection, semantic facts from the resolved package). */
    private final Cache<String, Optional<TriggerUISchemaModel>> generatedTriggerCache =
            Caffeine.newBuilder().maximumSize(GENERATED_CACHE_SIZE).build();

    private TriggerModelReader() {
    }

    public static TriggerModelReader getInstance() {
        return INSTANCE;
    }

    /** Derives the add-trigger init form by remapping {@code initProperties -> properties} at the JSON level. */
    private static Optional<JsonObject> initFormJson(JsonElement parsed) {
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
        remapped.add("properties", withDerivedListenerField(root, initProperties.getAsJsonObject()));
        return Optional.of(remapped);
    }

    /**
     * The init form's properties with the listener field derived from a model's declared {@code listeners}
     * placed first. An authored listener field wins, so this can be adopted per connector.
     */
    private static JsonObject withDerivedListenerField(JsonObject root, JsonObject authored) {
        JsonElement listeners = root.get("listeners");
        if (listeners == null || !listeners.isJsonArray() || listeners.getAsJsonArray().isEmpty()) {
            return authored.deepCopy();
        }
        JsonElement derived = null;
        try {
            List<TriggerUISchemaModel.ListenerModel> declared =
                    DERIVATION_GSON.fromJson(listeners, LISTENER_MODEL_LIST_TYPE);
            JsonElement listenerKind = root.get("listenerKind");
            derived = ListenerChoiceDeriver.derive(declared,
                            listenerKind == null || listenerKind.isJsonNull()
                                    ? null : listenerKind.getAsString(),
                            DERIVATION_GSON.fromJson(root.get("listenerForm"),
                                    TriggerUISchemaModel.ListenerFormModel.class))
                    .map(DERIVATION_GSON::toJsonTree)
                    .orElse(null);
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Could not derive the listener field from `listeners`", e);
        }
        if (derived == null) {
            return authored.deepCopy();
        }
        JsonObject ordered = new JsonObject();
        ordered.add(PROP_KEY_LISTENER, derived);
        authored.entrySet().forEach(entry -> ordered.add(entry.getKey(), entry.getValue()));
        return ordered;
    }

    /** A fresh {@link ServiceInitModel} bound from {@link #initFormJson}; never a shared instance. */
    private Optional<ServiceInitModel> buildServiceInitModelFromJson(JsonElement parsed) {
        return initFormJson(parsed).map(json -> gson.fromJson(json, ServiceInitModel.class));
    }

    /** Cheap presence check across all tiers: connector-shipped or synthesized. */
    public boolean hasSchemaDrivenModel(String orgName, String moduleName) {
        return getSchemaDrivenTriggerModel(orgName, moduleName, null, false).isPresent();
    }

    /** {@code isLocalRepository} variant of {@link #hasSchemaDrivenModel(String, String)}. */
    public boolean hasSchemaDrivenModel(String orgName, String moduleName, String version,
                                        boolean isLocalRepository) {
        return getSchemaDrivenTriggerModel(orgName, moduleName, version, isLocalRepository).isPresent();
    }

    /** The connector's {@link TriggerUISchemaModel}: generated, shipped, or synthesized. */
    public Optional<TriggerUISchemaModel> getSchemaDrivenTriggerModel(String orgName, String moduleName) {
        return getSchemaDrivenTriggerModel(orgName, moduleName, null);
    }

    /** Version-aware counterpart of {@link #getSchemaDrivenTriggerModel(String, String)}. */
    public Optional<TriggerUISchemaModel> getSchemaDrivenTriggerModel(String orgName, String moduleName,
                                                                       String version) {
        return getSchemaDrivenTriggerModel(orgName, moduleName, version, false);
    }

    /** {@code isLocalRepository} variant, resolving via the Ballerina local repository. Not cached. */
    public Optional<TriggerUISchemaModel> getSchemaDrivenTriggerModel(String orgName, String moduleName,
                                                                       String version, boolean isLocalRepository) {
        if (isLocalRepository) {
            return resolveSchemaDrivenTriggerModelFromLocalRepository(orgName, moduleName, version);
        }
        if (orgName != null && moduleName != null && generationEnabled(moduleName)) {
            Optional<TriggerUISchemaModel> generated = getCachedGeneratedTriggerModel(orgName, moduleName, version);
            if (generated.isPresent()) {
                return generated;
            }
            // Falls through: the packaged L1+L2 corpus doesn't (yet) cover this connector, or its
            // package isn't resolvable offline. The shipped/legacy-synthesize tier below is the same
            // fallback this method has always had for exactly that case.
        }
        if (orgName == null || moduleName == null) {
            return Optional.empty();
        }
        String key = orgName + "/" + moduleName + ":" + (version == null ? "" : version);
        Optional<TriggerUISchemaModel> cached = schemaDrivenTriggerCache.getIfPresent(key);
        if (cached != null && cached.isPresent()) {
            return cached;
        }
        Resolution resolution = resolveSchemaDrivenTriggerModel(orgName, moduleName, version);
        if (resolution.cacheable()) {
            schemaDrivenTriggerCache.put(key, resolution.model());
        }
        return resolution.model();
    }

    /**
     * The L1 + semantic facts + L2 generated model, cached by {@code org/module:version}. This is the
     * default resolution tier as of the L1+L2 cutover -- {@link #getGeneratedTriggerModel} itself stays
     * uncached and version-precise so callers needing an exact pinned version can bypass the cache,
     * which is exactly why this wrapper exists rather than caching inside it.
     */
    private Optional<TriggerUISchemaModel> getCachedGeneratedTriggerModel(String orgName, String moduleName,
                                                                          String version) {
        String key = orgName + "/" + moduleName + ":" + (version == null ? "" : version);
        Optional<TriggerUISchemaModel> cached = generatedTriggerCache.getIfPresent(key);
        if (cached != null) {
            return cached;
        }
        Optional<TriggerUISchemaModel> generated;
        try {
            generated = getGeneratedTriggerModel(orgName, moduleName, version);
        } catch (Throwable e) {
            LOGGER.log(Level.FINE, "Generated trigger model resolution failed for " + orgName + "/" + moduleName,
                    e);
            generated = Optional.empty();
        }
        generatedTriggerCache.put(key, generated);
        if (generated.isPresent()) {
            LOGGER.log(Level.FINE, () -> "Resolved " + orgName + "/" + moduleName + " from the generated tier");
        }
        return generated;
    }

    /**
     * One resolution attempt.
     *
     * @param model     the resolved model, if any
     * @param cacheable whether the outcome may be memoized; {@code false} when the connector is simply
     *                  not in the local repository yet, so a later pull is picked up instead of being
     *                  masked by a memoized miss
     */
    private record Resolution(Optional<TriggerUISchemaModel> model, boolean cacheable) {

        private static final Resolution UNRESOLVED = new Resolution(Optional.empty(), false);
        private static final Resolution ABSENT = new Resolution(Optional.empty(), true);

        static Resolution of(Optional<TriggerUISchemaModel> model) {
            return model.isEmpty() ? ABSENT : new Resolution(model, true);
        }
    }

    /** The connector's add-trigger init form. */
    public Optional<ServiceInitModel> getSchemaDrivenServiceInitModel(String orgName, String moduleName) {
        return getSchemaDrivenServiceInitModel(orgName, moduleName, null);
    }

    /** Version-aware counterpart of {@link #getSchemaDrivenServiceInitModel(String, String)}. */
    public Optional<ServiceInitModel> getSchemaDrivenServiceInitModel(String orgName, String moduleName,
                                                                       String version) {
        return getSchemaDrivenServiceInitModel(orgName, moduleName, version, false);
    }

    /** {@code isLocalRepository} variant; the returned model has {@link ServiceInitModel#setLocalRepository} set. */
    public Optional<ServiceInitModel> getSchemaDrivenServiceInitModel(String orgName, String moduleName,
                                                                       String version, boolean isLocalRepository) {
        if (isLocalRepository) {
            return getSchemaDrivenTriggerModel(orgName, moduleName, version, true)
                    .flatMap(model -> buildServiceInitModelFromJson(gson.toJsonTree(model)))
                    .map(initModel -> {
                        initModel.setLocalRepository(true);
                        return initModel;
                    });
        }
        if (orgName != null && moduleName != null && generationEnabled(moduleName)) {
            Optional<TriggerUISchemaModel> generated = getCachedGeneratedTriggerModel(orgName, moduleName, version);
            if (generated.isPresent()) {
                return generated.flatMap(model -> buildServiceInitModelFromJson(gson.toJsonTree(model)));
            }
        }
        if (orgName == null || moduleName == null) {
            return Optional.empty();
        }
        return getSchemaDrivenTriggerModel(orgName, moduleName, version)
                .flatMap(model -> buildServiceInitModelFromJson(gson.toJsonTree(model)));
    }

    /** Resolves a {@link TriggerUISchemaModel} for a connector via the Ballerina local repository. */
    private Optional<TriggerUISchemaModel> resolveSchemaDrivenTriggerModelFromLocalRepository(
            String orgName, String moduleName, String version) {
        try {
            ModuleInfo moduleInfo = new ModuleInfo(orgName, moduleName, moduleName, version);
            LibraryMetadataReader metadataReader = LibraryMetadataReader.getInstance();

            Optional<TriggerMetadataModel> metadata = metadataReader
                    .getTriggerMetadataModelFromLocalRepository(moduleInfo);
            if (metadata.isEmpty()) {
                return Optional.empty();
            }
            Optional<Package> pkg = metadataReader.getCompiledPackageFromLocalRepository(moduleInfo);
            if (pkg.isEmpty()) {
                return Optional.empty();
            }
            TriggerUIMetadataModel uiMetadata = metadataReader
                    .getTriggerUIMetadataModelFromLocalRepository(moduleInfo)
                    .orElse(null);
            return synthesizeTriggerModel(metadata.get(), uiMetadata, pkg.get(), moduleName);
        } catch (Throwable e) {
            LOGGER.log(Level.FINE, "Local-repository trigger model resolution failed for "
                    + orgName + "/" + moduleName, e);
            return Optional.empty();
        }
    }

    /** Resolves a {@link TriggerUISchemaModel} via {@link LibraryMetadataReader}. */
    private Resolution resolveSchemaDrivenTriggerModel(String orgName, String moduleName, String version) {
        try {
            return doResolveSchemaDrivenTriggerModel(orgName, moduleName, version);
        } catch (Throwable e) {
            return Resolution.UNRESOLVED;
        }
    }

    /**
     * {@code version} is threaded all the way through -- to the {@link ModuleInfo} used for L1/L2
     * resolution and to the package resolver -- rather than always resolving "whatever the offline
     * cache holds as newest". Without a pin, a module with more than one version cached offline (e.g. a
     * connector pulled at both an older and a newer release) resolves arbitrarily, and an unversioned
     * {@link PackageUtil#getModulePackageOffline(io.ballerina.projects.BuildProject, String, String)}
     * lookup can fail to resolve at all in an environment whose local index doesn't already know which
     * version is "newest" -- silently dropping this tier's model instead of resolving the version the
     * caller actually meant.
     */
    private Resolution doResolveSchemaDrivenTriggerModel(String orgName, String moduleName, String version) {
        ModuleInfo moduleInfo = new ModuleInfo(orgName, moduleName, moduleName, version);
        LibraryMetadataReader metadataReader = LibraryMetadataReader.getInstance();

        Optional<TriggerMetadataModel> metadata = metadataReader.getTriggerMetadataModel(moduleInfo);
        if (metadata.isEmpty()) {
            return metadataReader.isLocallyResolvable(moduleInfo) ? Resolution.ABSENT : Resolution.UNRESOLVED;
        }
        Optional<Package> pkg = PackageUtil.getModulePackageOffline(PackageUtil.getSampleProject(), orgName,
                moduleName, version);
        if (pkg.isEmpty()) {
            return Resolution.UNRESOLVED;
        }
        TriggerUIMetadataModel uiMetadata = metadataReader.getTriggerUIMetadataModel(moduleInfo)
                .orElse(null);
        return Resolution.of(synthesizeTriggerModel(metadata.get(), uiMetadata, pkg.get(), moduleName));
    }

    /**
     * Builds the L1 + semantic + L2 model for one connector: uncached and version-precise, so callers
     * needing an exact pinned version can bypass the cache. Production resolution goes through
     * {@link #getCachedGeneratedTriggerModel}, which adds caching on top of this.
     */
    Optional<TriggerUISchemaModel> getGeneratedTriggerModel(String orgName, String moduleName, String version) {
        if (orgName == null || moduleName == null) {
            return Optional.empty();
        }
        ModuleInfo moduleInfo = new ModuleInfo(orgName, moduleName, moduleName, version);
        Optional<Package> pkg = PackageUtil.getModulePackageOffline(PackageUtil.getSampleProject(), orgName,
                moduleName, version);
        return pkg.flatMap(value -> getGeneratedTriggerModel(moduleInfo, value));
    }

    /** {@link #getGeneratedTriggerModel(String, String, String)}'s init-form counterpart. */
    Optional<ServiceInitModel> getGeneratedServiceInitModel(String orgName, String moduleName, String version) {
        return getGeneratedTriggerModel(orgName, moduleName, version)
                .flatMap(model -> buildServiceInitModelFromJson(gson.toJsonTree(model)));
    }

    private Optional<TriggerUISchemaModel> getGeneratedTriggerModel(ModuleInfo moduleInfo, Package pkg) {
        LibraryMetadataReader reader = LibraryMetadataReader.getInstance();
        Optional<TriggerMetadataModel> metadata = reader.getTriggerMetadataModel(moduleInfo);
        Optional<TriggerUIMetadataModel> uiMetadata = reader.getTriggerUIMetadataModel(moduleInfo);
        if (metadata.isEmpty() || uiMetadata.isEmpty()) {
            return Optional.empty();
        }
        return synthesizeTriggerModel(metadata.get(), uiMetadata.get(), pkg, moduleInfo.moduleName());
    }

    /** Synthesizes a {@link TriggerUISchemaModel} from a connector's metadata plus semantic introspection. */
    private Optional<TriggerUISchemaModel> synthesizeTriggerModel(TriggerMetadataModel metadata,
                                                                  TriggerUIMetadataModel uiMetadata, Package pkg,
                                                                  String moduleName) {
        SemanticModel semanticModel = PackageUtil.getCompilation(pkg)
                .getSemanticModel(pkg.getDefaultModule().moduleId());

        PackageDescriptor descriptor = pkg.descriptor();
        String resolvedOrg = descriptor.org().value();
        String resolvedPackageName = descriptor.name().value();
        String resolvedVersion = descriptor.version().value().toString();
        TriggerLibraryFacts facts = TriggerLibraryIntrospector.introspect(semanticModel, null);
        Map<String, TriggerLibraryFacts> crossModuleFacts =
                resolveCrossModuleFacts(metadata, resolvedOrg, resolvedPackageName);

        Map<String, Listener> listenerModels = resolveListenerModels(metadata, semanticModel, resolvedOrg,
                resolvedPackageName, moduleName, resolvedVersion);

        String displayName = TriggerModelSynthesizer.humanize(moduleName);
        String icon = CommonUtils.generateIcon(resolvedOrg, resolvedPackageName, resolvedVersion);

        return TriggerModelSynthesizer.synthesize(metadata, facts, crossModuleFacts, listenerModels, moduleName,
                displayName, icon, "event", resolvedOrg, resolvedPackageName, moduleName, resolvedVersion,
                uiMetadata, semanticModel);
    }

    /**
     * Introspects every distinct package a cross-module annotation is declared in (e.g. CDC's shared
     * {@code ballerinax/cdc}), so its real backing record type can be resolved. Best-effort: a package
     * that fails to resolve/compile is silently skipped.
     */
    private Map<String, TriggerLibraryFacts> resolveCrossModuleFacts(TriggerMetadataModel metadata, String ownOrg,
                                                                     String ownPackageName) {
        if (metadata.annotations() == null) {
            return Map.of();
        }
        Map<String, TriggerLibraryFacts> crossModuleFacts = new LinkedHashMap<>();
        for (TriggerMetadataModel.Annotation annotation : metadata.annotations()) {
            TypeRef.PackageInfo packageInfo = annotation.type() == null ? null : annotation.type().packageInfo();
            if (packageInfo == null || packageInfo.org() == null || packageInfo.packageName() == null
                    || (packageInfo.org().equals(ownOrg) && packageInfo.packageName().equals(ownPackageName))) {
                continue;
            }
            String key = TriggerModelSynthesizer.crossModuleFactsKey(packageInfo);
            if (crossModuleFacts.containsKey(key)) {
                continue;
            }
            introspectCrossModulePackage(packageInfo).ifPresent(facts -> crossModuleFacts.put(key, facts));
        }
        return crossModuleFacts;
    }

    private Optional<TriggerLibraryFacts> introspectCrossModulePackage(TypeRef.PackageInfo packageInfo) {
        try {
            String targetModule = packageInfo.moduleName() != null && !packageInfo.moduleName().isBlank()
                    ? packageInfo.moduleName() : packageInfo.packageName();
            Optional<Package> pkg = PackageUtil.getModulePackageOffline(packageInfo.org(), targetModule);
            if (pkg.isEmpty()) {
                return Optional.empty();
            }
            SemanticModel semanticModel = PackageUtil.getCompilation(pkg.get())
                    .getSemanticModel(pkg.get().getDefaultModule().moduleId());
            return Optional.of(TriggerLibraryIntrospector.introspect(semanticModel, null));
        } catch (Throwable e) {
            LOGGER.log(Level.FINE, "Cross-module introspection failed for "
                    + packageInfo.org() + "/" + packageInfo.packageName(), e);
            return Optional.empty();
        }
    }

    /**
     * One listener init-form template per declared listener, keyed by its type's simple name. A type that
     * cannot be introspected is left out, costing only that listener its parameter widgets.
     */
    private static Map<String, Listener> resolveListenerModels(TriggerMetadataModel metadata,
                                                               SemanticModel semanticModel, String orgName,
                                                               String packageName, String moduleName,
                                                               String version) {
        Map<String, Listener> models = new LinkedHashMap<>();
        if (metadata.listeners() == null) {
            return models;
        }
        for (TriggerMetadataModel.Listener listener : metadata.listeners()) {
            if (listener.type() == null || listener.type().name() == null) {
                continue;
            }
            String listenerType = listener.type().name();
            try {
                Codedata codedata = new Codedata.Builder()
                        .setType(listenerType)
                        .setOrgName(orgName)
                        .setPackageName(packageName)
                        .setModuleName(moduleName)
                        .setVersion(version)
                        .build();
                ListenerUtil.getListenerModelFromConnectorPackage(codedata, semanticModel, null)
                        .ifPresent(model -> models.put(listenerType, model));
            } catch (Throwable e) {
                LOGGER.log(Level.FINE, "Could not resolve the listener model for " + listenerType, e);
            }
        }
        return models;
    }
}
