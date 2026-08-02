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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.ballerina.compiler.api.SemanticModel;
import io.ballerina.modelgenerator.commons.ModuleInfo;
import io.ballerina.modelgenerator.commons.trigger.LibraryMetadataReader;
import io.ballerina.modelgenerator.commons.trigger.models.TriggerMetadataModel;
import io.ballerina.modelgenerator.commons.trigger.models.TypeRef;
import io.ballerina.modelgenerator.commons.trigger.utils.TypeRefResolver;
import io.ballerina.projects.Package;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.logging.Logger;

/**
 * Schema-driven Copilot service loader: builds a library's {@code services} JSON from exactly two
 * read-only sources instead of the SQLite service-index —
 * <ol>
 *   <li><b>{@code trigger-metadata.json}</b>, the authoring metadata: which service types exist, the
 *       handler vocabulary of marker types, parameter types/optionality, and return types;</li>
 *   <li><b>the semantic model</b> of the same resolved package the manager already compiles: listener
 *       class and init parameters, the declared methods and doc comments of concrete service types, and
 *       validation that every metadata claim actually exists in the resolved package version.</li>
 * </ol>
 *
 * <p><b>Structure.</b> This class is only an orchestrator. It resolves the document, builds the facts
 * once, pairs service types with listeners, and then runs an ordered list of components over each pair.
 * Every spec construct is owned by exactly one pure resolver, plugged in by a thin aspect — so a spec
 * change edits one resolver and touches nothing else, and no component can grow a dependency on another
 * without going through {@link TriggerScope}. See {@link AspectRegistry} for the ordered list.
 *
 * <p><b>Failure model.</b> Two distinct outcomes, deliberately not merged:
 * <ul>
 *   <li><b>Library-level abort</b> — no document, a malformed document, or no resolvable listener
 *       returns an empty array, and the caller falls back to the SQLite service index. Most libraries are
 *       not trigger libraries and land here legitimately, so an unresolved document is not logged.</li>
 *   <li><b>Entry-level veto</b> — one service type or one handler is dropped with an attributable
 *       {@link Veto} while the rest of the library is served normally.</li>
 * </ul>
 *
 * <p><b>Documentation gaps this leaves.</b> A marker service type declares no methods in the library
 * source — its handler contract is enforced by a compiler plugin at user-code compile time — so no symbol
 * carries a doc comment for a handler or its parameters, and the metadata document does not model
 * descriptions. Handler and handler-parameter descriptions are therefore unavailable for marker service
 * types and are omitted, never fabricated. Concrete service types are unaffected.
 *
 * @since 1.7.0
 */
final class TriggerSchemaServiceLoader {

    private static final Logger LOGGER = Logger.getLogger(TriggerSchemaServiceLoader.class.getName());

    private static final String DEFAULT_ORG = "ballerinax";

    /** The alias a side-effect-only import is written with: {@code import org/pkg as _;}. */
    static final String SIDE_EFFECT_IMPORT_ALIAS = RequiredImportResolver.SIDE_EFFECT_IMPORT_ALIAS;

    /**
     * Module keys for the LS-bundled {@code trigger-metadata-models/<key>/trigger-metadata.json}
     * documents, keyed by library name. This is <em>not</em> an allowlist — any library whose package
     * ships its own {@code resources/trigger-metadata.json} is served without appearing here, and a
     * library absent from this map still resolves a bundled document filed under its bare package name.
     * It maps only the libraries whose bundled document is filed under a name the library itself does not
     * carry: {@code ballerinax/mssql} maps to the {@code mssql.cdc} document — the same CDC trigger
     * published under the new module layout; its listener ({@code CdcListener}) and handler set are
     * validated against the actually resolved {@code mssql} package before use.
     */
    static final Map<String, String> BUNDLED_METADATA_KEYS = Map.of(
            "ballerinax/kafka", "kafka",
            "ballerinax/rabbitmq", "rabbitmq",
            "ballerina/ftp", "ftp",
            "ballerina/mcp", "mcp",
            "ballerinax/mssql", "mssql.cdc",
            "ballerinax/trigger.github", "trigger.github",
            // Net-new to the Copilot: these were never in the SQLite service-index.
            "ballerina/smb", "smb",
            "ballerina/websub", "websub",
            "ballerinax/trigger.google.calendar", "trigger.google.calendar");

    private TriggerSchemaServiceLoader() {
        // Prevent instantiation
    }

    /**
     * The outcome of one library load: the emitted services, and every reason an entry was dropped.
     *
     * <p>Vetoes never reach the emitted JSON — they are diagnostics about the document. They are returned
     * rather than only logged so a caller (or a test) can assert exactly what a library dropped and why,
     * which is what makes a silently missing handler impossible to reintroduce unnoticed.
     *
     * @param services the emitted service entries, in document order
     * @param vetoes   every reason an entry was dropped, whether a service type or a single handler
     */
    record LoadResult(JsonArray services, List<Veto> vetoes) {
    }

    /**
     * Loads services for a trigger library.
     *
     * <p>Returns an empty array when inputs are missing, no metadata document resolves for the library, or
     * anything throws — the caller then falls back to the SQLite path, so a failure here can never lose a
     * library.
     *
     * @param libraryName   the library name, e.g. {@code "ballerinax/kafka"}
     * @param pkg           the resolved package the caller already compiled; may be {@code null}
     * @param semanticModel the package's semantic model; may be {@code null}
     * @return the services JSON, empty when this library is not served from metadata
     */
    static JsonArray loadServices(String libraryName, Package pkg, SemanticModel semanticModel) {
        return load(libraryName, pkg, semanticModel).services();
    }

    /**
     * {@link #loadServices} plus the veto report. Same work; this overload simply does not discard the
     * diagnostics.
     */
    static LoadResult load(String libraryName, Package pkg, SemanticModel semanticModel) {
        if (pkg == null || semanticModel == null) {
            return empty();
        }

        String packageName = ServiceIndexLoader.stripOrg(libraryName);
        String org = libraryName.contains("/")
                ? libraryName.substring(0, libraryName.indexOf('/'))
                : DEFAULT_ORG;

        try {
            Optional<TriggerMetadataModel> metadataOpt = resolveMetadata(libraryName, org, packageName, pkg);
            if (metadataOpt.isEmpty()) {
                return empty();
            }
            TriggerMetadataModel metadata = metadataOpt.get();
            if (metadata.listeners() == null || metadata.listeners().isEmpty()
                    || metadata.serviceTypes() == null || metadata.serviceTypes().isEmpty()) {
                return empty();
            }

            TriggerSemanticFacts facts = new TriggerSemanticFacts(semanticModel, pkg);
            List<ListenerPairingResolver.ListenerPairing> pairings = ListenerPairingResolver.resolve(
                    metadata.listeners(), metadata.serviceTypes(), facts);
            if (pairings.isEmpty()) {
                // An unresolvable listener means the resolved package no longer matches the metadata's
                // world view — abort the library so the caller falls back, rather than emitting a
                // listener the generated code could not instantiate.
                TypeRef declared = metadata.listeners().get(0).type();
                LOGGER.warning("No listener class resolvable for " + libraryName
                        + " (metadata declared: " + (declared == null ? null : declared.name()) + ")");
                return empty();
            }

            AspectRegistry registry = AspectRegistry.forVersion(AspectRegistry.VERSION_V1);
            JsonArray services = new JsonArray();
            List<Veto> vetoes = new ArrayList<>();

            for (ListenerPairingResolver.ListenerPairing pairing : pairings) {
                ServiceDraft draft = buildService(libraryName, org, packageName, metadata, pairing, facts,
                        registry);
                vetoes.addAll(draft.vetoes());
                if (draft.isVetoed()) {
                    continue;
                }
                services.add(draft.toJson());
            }

            for (Veto veto : vetoes) {
                LOGGER.warning("Dropped for " + libraryName + ": " + veto);
            }
            return new LoadResult(services, vetoes);
        } catch (RuntimeException e) {
            LOGGER.warning("Failed to load schema-driven services for " + libraryName + ": " + e.getMessage());
            return empty();
        }
    }

    /** Runs the ordered service components over one (service type × listener) pair. */
    private static ServiceDraft buildService(String libraryName, String org, String packageName,
                                             TriggerMetadataModel metadata,
                                             ListenerPairingResolver.ListenerPairing pairing,
                                             TriggerSemanticFacts facts, AspectRegistry registry) {
        TriggerScope scope = new TriggerScope(
                libraryName,
                org,
                packageName,
                ServiceIdentityResolver.homeModule(pairing.listener(), packageName),
                metadata,
                pairing.serviceType(),
                pairing.listener(),
                pairing.listenerClass(),
                facts,
                facts::declaresType);

        ServiceDraft draft = new ServiceDraft();
        for (ServiceAspect aspect : registry.serviceAspects()) {
            aspect.contribute(scope, draft);
            if (draft.isVetoed()) {
                // A vetoed entry is dropped whole; running the remaining components would build output
                // nothing will read.
                break;
            }
        }
        return draft;
    }

    private static LoadResult empty() {
        return new LoadResult(new JsonArray(), List.of());
    }

    /**
     * Resolves the trigger metadata document for a library, preferring the one the connector ships itself
     * over the LS-bundled copy.
     *
     * <p>The connector's own document is versioned with the connector, so it can never describe a release
     * the resolved package predates — the bundled mcp document declaring {@code StreamableHttpListener}
     * before mcp 1.2.0 shipped it is exactly that failure mode. It also means a connector published after
     * this LS is served without an LS release. The bundled tier then covers the libraries that do not ship
     * a document yet.
     *
     * <p>Reading the shipped document costs a single {@code stat} against the already-resolved package, so
     * consulting it for every library (rather than a curated few) is cheap.
     */
    private static Optional<TriggerMetadataModel> resolveMetadata(String libraryName, String org,
                                                                  String packageName, Package pkg) {
        LibraryMetadataReader reader = LibraryMetadataReader.getInstance();
        Optional<TriggerMetadataModel> shipped = reader.getShippedTriggerMetadataModel(pkg);
        if (shipped.isPresent()) {
            return shipped;
        }
        String metadataKey = BUNDLED_METADATA_KEYS.getOrDefault(libraryName, packageName);
        return reader.getPackagedTriggerMetadataModel(new ModuleInfo(org, packageName, metadataKey, null));
    }

    // ---- spec §1 delegates -------------------------------------------------------------
    // Spec §1 has one implementation, in commons. These forward to it so the loader stays the single
    // entry point its tests address, without owning a second copy of the rule.

    /** Spec §1: the import prefix Ballerina binds for a module — its last dot-segment. */
    static String getAlias(String moduleName) {
        return TypeRefResolver.moduleAlias(moduleName);
    }

    /** Spec §1: the leading identifier of a type name, or {@code null} for a non-identifier shape. */
    static String baseIdentifier(String typeName) {
        return TypeRefResolver.baseIdentifier(typeName);
    }

    /** Spec §1: the codegen default of a union, which is its first element. */
    static TypeRef firstTypeRef(List<TypeRef> refs) {
        return TypeRefResolver.first(refs);
    }

    /** Spec §1: a {@code TypeRef} rendered as module-prefixed signature text. */
    static String renderTypeRef(TypeRef ref, String packageName, Predicate<String> declaresType) {
        return TypeRefResolver.render(ref, packageName, declaresType);
    }

    /** Spec §5: a return union joined with {@code |}, ready for canonicalization. */
    static String renderReturns(List<TypeRef> returns, String packageName, Predicate<String> declaresType) {
        return TypeRefResolver.renderUnion(returns, packageName, declaresType);
    }

    // ---- component delegates -----------------------------------------------------------

    /** Spec §1: the document's home module — the module its listener belongs to. */
    static String homeModule(TriggerMetadataModel.Listener listener, String packageName) {
        return ServiceIdentityResolver.homeModule(listener, packageName);
    }

    /** Spec §1: whether a service type belongs to a module other than home. */
    static boolean isForeignServiceType(TriggerMetadataModel.ServiceType serviceType, String homeModule) {
        return ServiceIdentityResolver.isForeign(serviceType, homeModule);
    }

    /** Spec §1: the {@code org/module} a cross-module service type belongs to. */
    static Optional<String> serviceTypeModule(TriggerMetadataModel.ServiceType serviceType,
                                              String homeModule) {
        return ServiceIdentityResolver.serviceTypeModule(serviceType, homeModule);
    }

    /** Spec §2: a listener's side-effect-only imports, as wire-shaped entries. */
    static JsonArray requiredImports(TriggerMetadataModel.Listener listener) {
        JsonArray imports = new JsonArray();
        for (RequiredImportResolver.ImportDirective directive : RequiredImportResolver.resolve(listener)) {
            JsonObject entry = new JsonObject();
            entry.addProperty("module", directive.module());
            entry.addProperty("alias", directive.alias());
            imports.add(entry);
        }
        return imports;
    }

    /**
     * Spec §4/§5: the handlers of a marker service type, built through the real component pipeline.
     *
     * <p>Takes only what the metadata path needs — the options, the type name, a type-existence predicate
     * and the package name — so a caller can exercise the handler and parameter tiers without a compiled
     * package behind them.
     */
    static JsonArray buildOptionMethods(List<TriggerMetadataModel.ServiceType.HandlerOption> options,
                                        String typeName, Predicate<String> declaresType,
                                        String packageName) {
        TriggerMetadataModel.ServiceType serviceType = new TriggerMetadataModel.ServiceType(
                null, new TypeRef(typeName, null), false, false, false, null,
                new TriggerMetadataModel.ServiceType.Handlers(
                        false, TriggerMetadataModel.ServiceType.Handlers.ADD_MODE_SUBSET, options),
                null);
        TriggerScope scope = new TriggerScope(packageName, null, packageName, packageName, null,
                serviceType, null, null, null, declaresType);

        ServiceDraft draft = new ServiceDraft();
        new HandlerCatalogAspect(AspectRegistry.forVersion(AspectRegistry.VERSION_V1))
                .contribute(scope, draft);

        JsonObject json = draft.toJson();
        return json.has("methods") ? json.getAsJsonArray("methods") : new JsonArray();
    }
}
