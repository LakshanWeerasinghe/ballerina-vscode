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
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.ballerina.compiler.api.SemanticModel;
import io.ballerina.compiler.api.symbols.ClassSymbol;
import io.ballerina.compiler.api.symbols.ObjectTypeSymbol;
import io.ballerina.modelgenerator.commons.ModuleInfo;
import io.ballerina.modelgenerator.commons.trigger.LibraryMetadataReader;
import io.ballerina.modelgenerator.commons.trigger.models.TriggerMetadataModel;
import io.ballerina.modelgenerator.commons.trigger.models.TypeRef;
import io.ballerina.projects.Package;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.logging.Logger;

/**
 * Schema-driven Copilot service loader: builds the Copilot's per-library {@code services} JSON from
 * exactly two read-only sources instead of the SQLite service-index —
 * <ol>
 *   <li><b>{@code trigger-metadata.json}</b> (the LS-bundled authoring metadata, resolved through
 *       {@link LibraryMetadataReader#getPackagedTriggerMetadataModel}): the structural truth — which
 *       service types exist, the handler vocabulary of marker types, parameter types/optionality,
 *       and return types;</li>
 *   <li><b>the semantic model</b> of the same resolved package the manager already compiles:
 *       listener class + init parameters (docs via the init method's {@code parameterMap()},
 *       declared defaults via the syntax tree), the declared methods <i>and doc comments</i> of
 *       concrete service types, and validation that every metadata claim (listener class,
 *       service-type names, handler signature types) actually exists in the resolved package
 *       version.</li>
 * </ol>
 * The trigger UI models ({@code trigger-models/*.json}) are deliberately <b>not</b> consumed: the
 * authoring metadata plus the library itself are the single source of truth for the Copilot.
 *
 * <p><b>FLAG — documentation gaps this leaves.</b> A marker service type (kafka/rabbitmq/ftp/smb/
 * websub/cdc {@code Service}) declares no methods in the library source — its handler contract is
 * enforced by a compiler plugin at user-code compile time — so there is no symbol carrying a doc
 * comment for a handler or its parameters, and {@code trigger-metadata.json} does not model
 * descriptions. Consequently <b>handler and handler-parameter descriptions are unavailable for
 * marker service types</b> and are simply omitted (never fabricated). Concrete service types are
 * unaffected: their declared methods' doc comments are read from the semantic model. Closing this
 * gap requires either optional {@code description} fields in the authoring schema or doc comments on
 * declared handler contracts in the connectors.
 *
 * <p><b>FLAG — handler parameter names are generated.</b> A handler parameter's name is chosen by
 * whoever writes the service, so the authoring schema intentionally omits it for such slots. Where
 * the metadata does state a name it always wins; otherwise a name is synthesized by
 * {@link HandlerParamNameGenerator} (handler parameters only — never listener init params, concrete
 * methods, client methods or type fields, all of which carry declared names).
 *
 * <p>Any library is served here if a trigger metadata document resolves for it — the connector's own
 * shipped {@code resources/trigger-metadata.json} first, then the LS-bundled copy. There is no
 * allowlist: a trigger connector published after this LS is picked up by resolution alone, with no
 * LS release. A library with no document resolves to an empty array and stays on
 * {@link ServiceIndexLoader}. Output shape is exactly the Copilot service contract
 * ({@code type/name/listener/methods}), so downstream enrichers, the generic-services merge, and the
 * TS prompt renderer are untouched. Function-level {@code optional} is deliberately never emitted
 * (matching the previous output), and metadata constructs with no Copilot counterpart
 * ({@code dataBindingRules}, {@code rules}, {@code identifier}, wildcard {@code "*"} handlers,
 * repeatable {@code addMode: "many"} slots) are ignored.
 *
 * @since 1.7.0
 */
final class TriggerSchemaServiceLoader {

    private static final Logger LOGGER = Logger.getLogger(TriggerSchemaServiceLoader.class.getName());

    /** The alias a side-effect-only import is written with: {@code import org/pkg as _;}. */
    static final String SIDE_EFFECT_IMPORT_ALIAS = "_";

    /**
     * Module keys for the LS-bundled {@code trigger-metadata-models/<key>/trigger-metadata.json}
     * documents, keyed by library name. This is <em>not</em> an allowlist — any library whose package
     * ships its own {@code resources/trigger-metadata.json} is served without appearing here. It maps
     * only the libraries whose bundled document is filed under a name the library itself does not
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
     * Resolves the trigger metadata document for a library, preferring the one the connector ships
     * itself over the LS-bundled copy.
     *
     * <p>The connector's own document is versioned with the connector, so it can never describe a
     * release the resolved package predates — the bundled mcp document declaring
     * {@code StreamableHttpListener} before mcp 1.2.0 shipped it is exactly that failure mode. It
     * also means a connector published after this LS is served without an LS release. The bundled
     * tier then covers the libraries that do not ship a document yet.
     *
     * <p>Reading the shipped document costs a single {@code stat} against the already-resolved
     * package, so consulting it for every library (rather than a curated few) is cheap.
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

    /**
     * Loads services for a trigger library. Returns an empty array when inputs are missing, no
     * metadata document resolves for the library, or anything throws — the caller then falls back to
     * the SQLite path, so a failure here can never lose a library. Most libraries are not trigger
     * libraries and land here legitimately, so an unresolved document is not logged.
     */
    static JsonArray loadServices(String libraryName, Package pkg, SemanticModel semanticModel) {
        if (pkg == null || semanticModel == null) {
            return new JsonArray();
        }

        String packageName = ServiceIndexLoader.stripOrg(libraryName);
        String org = libraryName.contains("/")
                ? libraryName.substring(0, libraryName.indexOf('/'))
                : "ballerinax";

        try {
            Optional<TriggerMetadataModel> metadataOpt = resolveMetadata(libraryName, org, packageName, pkg);
            if (metadataOpt.isEmpty()) {
                return new JsonArray();
            }
            TriggerMetadataModel metadata = metadataOpt.get();
            if (metadata.listeners() == null || metadata.listeners().isEmpty()
                    || metadata.serviceTypes() == null || metadata.serviceTypes().isEmpty()) {
                return new JsonArray();
            }

            TriggerSemanticFacts facts = new TriggerSemanticFacts(semanticModel, pkg);

            // An unresolvable listener class means the resolved package no longer matches the
            // metadata's world view — hard-fail so the caller falls back to the SQLite path instead
            // of emitting a listener the generated code could not instantiate.
            String declaredListenerName = metadata.listeners().get(0).type() != null
                    ? metadata.listeners().get(0).type().name() : null;
            Optional<ClassSymbol> listenerClass = facts.resolveListenerClass(declaredListenerName);
            if (listenerClass.isEmpty()) {
                LOGGER.warning("No listener class resolvable for " + libraryName
                        + " (metadata declared: " + declaredListenerName + ")");
                return new JsonArray();
            }

            JsonObject listenerJson = buildListener(listenerClass.get(), facts, packageName);
            // Spec §1: everything cross-module is judged against the home module, which the listener
            // defines. Spec §2's imports are declared on the listener too.
            String homeModule = homeModule(metadata.listeners().get(0), packageName);
            JsonArray listenerImports = requiredImports(metadata.listeners().get(0));

            JsonArray services = new JsonArray();
            for (TriggerMetadataModel.ServiceType serviceType : metadata.serviceTypes()) {
                String typeName = serviceType.type() != null ? serviceType.type().name() : null;
                if (typeName == null) {
                    continue;
                }
                // A same-module service type must exist in the resolved package version (guards
                // against metadata authored for a future release); a cross-module type (e.g. mssql's
                // cdc:Service) cannot be checked against this module's symbols and is trusted.
                boolean foreignType = isForeignServiceType(serviceType, homeModule);
                if (!foreignType && !facts.declaresType(typeName)) {
                    LOGGER.warning("Skipping service type " + typeName + " for " + libraryName
                            + ": not declared by the resolved package version");
                    continue;
                }

                // A concrete service type whose object type cannot be introspected would emit a
                // phantom method-less service — skip it (a fully skipped library then falls back).
                TriggerMetadataModel.ServiceType.Handlers handlers = serviceType.handlers();
                boolean concrete = serviceType.concrete() || handlers == null
                        || handlers.backedByConcreteType();
                if (concrete && facts.serviceObjectType(typeName).isEmpty()) {
                    LOGGER.warning("Skipping concrete service type " + typeName + " for " + libraryName
                            + ": no introspectable service object type");
                    continue;
                }

                JsonObject svc = new JsonObject();
                svc.addProperty("type", "fixed");
                // Note: for a cross-module service type (mssql's cdc:Service) this is the bare type
                // name; CopilotDeprecationEnricher's lookup against this module's symbols is then a
                // deliberate no-op unless the module declares the same name itself.
                svc.addProperty("name", typeName);
                // Spec §1: a foreign service type is written with its own module's alias, not the
                // listener's. Absent for a home-module type, which the renderer prefixes with the
                // listener alias exactly as before.
                serviceTypeModule(serviceType, homeModule)
                        .ifPresent(module -> svc.addProperty("serviceTypeModule", module));
                // Spec §2's side-effect imports. Carried on the service rather than the library: the
                // spec declares them on the listener, so they are only needed by code that uses it.
                if (!listenerImports.isEmpty()) {
                    svc.add("requiredImports", listenerImports.deepCopy());
                }
                svc.add("listener", listenerJson);

                JsonArray methods = concrete
                        ? buildConcreteMethods(typeName, facts, packageName)
                        : buildOptionMethods(handlers.options(), typeName, facts::declaresType,
                                packageName);
                if (!methods.isEmpty()) {
                    svc.add("methods", methods);
                }
                services.add(svc);
            }
            return services;
        } catch (RuntimeException e) {
            LOGGER.warning("Failed to load schema-driven services for " + libraryName + ": " + e.getMessage());
            return new JsonArray();
        }
    }

    // ---- listener --------------------------------------------------------------

    private static JsonObject buildListener(ClassSymbol listenerClass, TriggerSemanticFacts facts,
                                            String packageName) {
        String className = listenerClass.getName().orElse("Listener");

        JsonObject listenerObj = new JsonObject();
        listenerObj.addProperty("name", getAlias(packageName) + ":" + className);

        JsonArray parameters = new JsonArray();
        for (TriggerSemanticFacts.InitParam param : facts.listenerInitParams(listenerClass)) {
            JsonObject paramObj = new JsonObject();
            paramObj.addProperty("name", param.name());
            paramObj.addProperty("description", param.description() != null ? param.description() : "");
            paramObj.add("type", TypeResolver.resolveTypeWithLinks(
                    param.typeSignature() != null ? param.typeSignature() : "", packageName));
            if (param.optional()) {
                paramObj.addProperty("optional", true);
            }
            if (param.defaultValue() != null && !param.defaultValue().isEmpty()) {
                paramObj.addProperty("default", param.defaultValue());
            }
            parameters.add(paramObj);
        }
        listenerObj.add("parameters", parameters);
        return listenerObj;
    }

    // ---- methods ---------------------------------------------------------------

    /**
     * Concrete service types: the type declares its own methods, so everything — names, parameter
     * names, types, and doc comments — comes from the semantic model. Nothing is generated here.
     *
     * <p>FLAG: when the library ships no doc comments on a declared handler (e.g.
     * {@code trigger.github}'s event methods), the {@code description} key is simply omitted; no
     * text is invented.
     */
    private static JsonArray buildConcreteMethods(String typeName, TriggerSemanticFacts facts,
                                                  String packageName) {
        JsonArray methods = new JsonArray();
        Optional<ObjectTypeSymbol> objectType = facts.serviceObjectType(typeName);
        if (objectType.isEmpty()) {
            return methods;
        }
        for (TriggerSemanticFacts.DeclaredMethod declared : facts.declaredMethods(objectType.get())) {
            JsonObject method = new JsonObject();
            method.addProperty("name", declared.name());
            method.addProperty("type", declared.kind());

            if (declared.description() != null && !declared.description().isEmpty()) {
                method.addProperty("description", declared.description());
            }

            if (!declared.params().isEmpty()) {
                JsonArray params = new JsonArray();
                for (TriggerSemanticFacts.DeclaredParam param : declared.params()) {
                    params.add(buildParam(param.name(), param.description(), param.typeSignature(),
                            param.optional(), packageName));
                }
                method.add("parameters", params);
            }

            addReturn(method, declared.returnTypeSignature(), packageName);
            methods.add(method);
        }
        return methods;
    }

    /**
     * Marker service types: the type declares no methods, so the handler vocabulary, parameter types,
     * optionality and returns all come from the metadata document.
     *
     * <p>FLAG — two things the metadata document cannot supply here, by design:
     * <ul>
     *   <li><b>descriptions</b> — neither the document (no {@code description} field) nor the library
     *       (no declared method to document) has them, so handler and parameter {@code description}
     *       keys are omitted rather than fabricated;</li>
     *   <li><b>parameter names</b> — a handler parameter's name is the service author's choice, so
     *       the document states it only where a conventional name exists. Where it does, it wins;
     *       otherwise {@link HandlerParamNameGenerator} synthesizes a deterministic, idiomatic one.</li>
     * </ul>
     */
    static JsonArray buildOptionMethods(List<TriggerMetadataModel.ServiceType.HandlerOption> options,
                                        String typeName, Predicate<String> declaresType,
                                        String packageName) {
        JsonArray methods = new JsonArray();
        if (options == null) {
            return methods;
        }
        for (TriggerMetadataModel.ServiceType.HandlerOption option : options) {
            if (option == null || option.name() == null
                    || TriggerMetadataModel.ServiceType.HandlerOption.WILDCARD_NAME.equals(option.name())) {
                continue;
            }
            // Same validation philosophy as service types: a handler whose signature references a
            // same-module type the resolved package does not declare (metadata authored against a
            // different/future release) would render an uncompilable prompt — skip it.
            if (referencesUndeclaredModuleType(option, declaresType)) {
                LOGGER.warning("Skipping handler " + option.name() + " of " + typeName
                        + ": its signature references a type the resolved package does not declare");
                continue;
            }
            JsonObject method = new JsonObject();
            method.addProperty("name", option.name());
            method.addProperty("type",
                    TriggerMetadataModel.ServiceType.HandlerOption.KIND_RESOURCE.equals(option.kind())
                            ? "resource" : "remote");
            // No description key: see the FLAG in this method's javadoc.

            List<TriggerMetadataModel.ServiceType.Param> optionParams = option.params();
            if (optionParams != null && !optionParams.isEmpty()) {
                JsonArray params = new JsonArray();
                Set<String> usedNames = new HashSet<>();
                for (TriggerMetadataModel.ServiceType.Param p : optionParams) {
                    if (p != null && p.name() != null) {
                        usedNames.add(p.name());
                    }
                }
                for (int i = 0; i < optionParams.size(); i++) {
                    TriggerMetadataModel.ServiceType.Param param = optionParams.get(i);
                    // A repeatable (addMode: "many") slot is an open-ended, user-named group — a
                    // low-code authoring concept with no fixed-signature counterpart; skip it.
                    if (TriggerMetadataModel.ServiceType.Handlers.ADD_MODE_MANY.equals(param.addMode())) {
                        continue;
                    }
                    // The authored name wins; otherwise generate one (handler params only).
                    String name = param.name() != null ? param.name()
                            : HandlerParamNameGenerator.generate(firstTypeRef(param.type()),
                                    param.dataBinding() != null, getAlias(packageName), i, usedNames);
                    usedNames.add(name);
                    String typeSignature = renderTypeRef(firstTypeRef(param.type()), packageName,
                            declaresType);
                    boolean optional = "optional".equals(param.presence());

                    // No description argument: see the FLAG in this method's javadoc.
                    params.add(buildParam(name, null, typeSignature, optional, packageName));
                }
                // Guard against an all-repeatable option emitting an empty array (every slot skipped
                // above), matching how the concrete path omits the key when there are no parameters.
                if (!params.isEmpty()) {
                    method.add("parameters", params);
                }
            }

            addReturn(method, renderReturns(option.returns(), packageName, declaresType), packageName);
            methods.add(method);
        }
        return methods;
    }

    // ---- shared building blocks --------------------------------------------------

    private static JsonObject buildParam(String name, String description, String typeSignature,
                                         boolean optional, String packageName) {
        JsonObject paramObj = new JsonObject();
        paramObj.addProperty("name", name);
        if (description != null && !description.isEmpty()) {
            paramObj.addProperty("description", description);
        }
        paramObj.add("type", TypeResolver.resolveTypeWithLinks(
                typeSignature != null ? typeSignature : "", packageName));
        if (optional) {
            paramObj.addProperty("optional", true);
        }
        return paramObj;
    }

    /**
     * Adds the {@code return} object unless the (canonicalized) signature is empty or plain
     * {@code ()} — a nil return carries no information and today's output omits it.
     */
    private static void addReturn(JsonObject method, String returnSignature, String packageName) {
        if (returnSignature == null || returnSignature.isEmpty()) {
            return;
        }
        String canonical = ServiceIndexLoader.canonicalizeReturnType(returnSignature);
        if (canonical.isEmpty() || "()".equals(canonical)) {
            return;
        }
        JsonObject returnObj = new JsonObject();
        returnObj.add("type", TypeResolver.resolveTypeWithLinks(canonical, packageName));
        method.add("return", returnObj);
    }

    /** The codegen-default member: the first element of a scalar-or-union {@code TypeRef} slot. */
    static TypeRef firstTypeRef(List<TypeRef> refs) {
        return refs == null || refs.isEmpty() ? null : refs.get(0);
    }

    /**
     * Renders a metadata {@code TypeRef} into the module-prefixed signature form the service-index
     * stored, so the shared {@link TypeResolver} produces identical {@code {name, links}} output:
     * a cross-module ref gets its own module's alias prefix (and, since the prefix won't match the
     * current package, no link — e.g. {@code cdc:Error}); a same-module declared type gets the
     * current alias prefix (stripped back off with a link); built-ins and anonymous shapes stay bare.
     */
    static String renderTypeRef(TypeRef ref, String packageName, Predicate<String> declaresType) {
        if (ref == null || ref.name() == null) {
            return "";
        }
        String name = ref.name();
        if (ref.packageInfo() != null) {
            String refPackage = ref.packageInfo().packageName();
            String refModule = ref.packageInfo().moduleName() != null
                    ? ref.packageInfo().moduleName() : refPackage;
            if (refPackage != null && !refPackage.equals(packageName)) {
                return getAlias(refModule) + ":" + name;
            }
            return getAlias(packageName) + ":" + name;
        }
        String base = baseIdentifier(name);
        if (base != null && declaresType.test(base)) {
            return getAlias(packageName) + ":" + name;
        }
        return name;
    }

    /**
     * Joins a metadata {@code returns} union into a single signature ({@code error|()}) ready for
     * {@link ServiceIndexLoader#canonicalizeReturnType}.
     */
    static String renderReturns(List<TypeRef> returns, String packageName, Predicate<String> declaresType) {
        if (returns == null || returns.isEmpty()) {
            return "";
        }
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < returns.size(); i++) {
            if (i > 0) {
                joined.append("|");
            }
            joined.append(renderTypeRef(returns.get(i), packageName, declaresType));
        }
        return joined.toString();
    }

    /**
     * Whether the handler's emitted signature (first type member of each parameter, every return
     * member) references a bare, capitalized — i.e. user-defined-looking — same-module type the
     * resolved package does not declare.
     */
    static boolean referencesUndeclaredModuleType(TriggerMetadataModel.ServiceType.HandlerOption option,
                                                  Predicate<String> declaresType) {
        if (option.params() != null) {
            for (TriggerMetadataModel.ServiceType.Param param : option.params()) {
                if (param != null && isUndeclaredBareUserType(firstTypeRef(param.type()), declaresType)) {
                    return true;
                }
            }
        }
        if (option.returns() != null) {
            for (TypeRef ref : option.returns()) {
                if (isUndeclaredBareUserType(ref, declaresType)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isUndeclaredBareUserType(TypeRef ref, Predicate<String> declaresType) {
        if (ref == null || ref.name() == null || ref.packageInfo() != null) {
            return false;
        }
        String base = baseIdentifier(ref.name());
        return base != null && !base.isEmpty() && Character.isUpperCase(base.charAt(0))
                && !declaresType.test(base);
    }

    /** Leading identifier of a type name: {@code "AnydataConsumerRecord[]"} → {@code "AnydataConsumerRecord"}. */
    static String baseIdentifier(String typeName) {
        if (typeName == null || typeName.isEmpty()) {
            return null;
        }
        int end = 0;
        while (end < typeName.length()
                && (Character.isLetterOrDigit(typeName.charAt(end)) || typeName.charAt(end) == '_')) {
            end++;
        }
        return end == 0 ? null : typeName.substring(0, end);
    }

    static String getAlias(String moduleName) {
        if (moduleName != null && moduleName.contains(".")) {
            return moduleName.substring(moduleName.lastIndexOf('.') + 1);
        }
        return moduleName;
    }

    /**
     * Spec &sect;1 — the document's <b>home module</b>: "whichever module the file's primary construct
     * (its listener, usually) belongs to". Taken from the listener's own {@code packageInfo} when it
     * declares one; otherwise the resolved library's default module, which is its package name.
     *
     * <p>Everything else in &sect;1 is relative to this: a {@code TypeRef} is cross-module exactly
     * when its module differs from the value returned here.
     *
     * @param listener    the document's first listener (may be {@code null})
     * @param packageName the resolved library's package name, the default home
     * @return the home module name, never {@code null} unless {@code packageName} is
     */
    static String homeModule(TriggerMetadataModel.Listener listener, String packageName) {
        String declared = moduleOf(listener == null ? null : listener.type());
        return declared != null ? declared : packageName;
    }

    /**
     * The module a {@link TypeRef} belongs to, or {@code null} for a bare reference (spec &sect;1: "A
     * bare {@code {"name": ...}} always means same module as this connector's own types"). Prefers
     * {@code moduleName} over {@code packageName}: a submodule such as {@code mssql.cdc} shares its
     * parent's package name but is a distinct module, and it is the module that determines both the
     * import path and the alias.
     */
    private static String moduleOf(TypeRef ref) {
        if (ref == null || ref.packageInfo() == null) {
            return null;
        }
        TypeRef.PackageInfo info = ref.packageInfo();
        if (info.moduleName() != null && !info.moduleName().isEmpty()) {
            return info.moduleName();
        }
        return info.packageName() == null || info.packageName().isEmpty() ? null : info.packageName();
    }

    /**
     * Spec &sect;1 — whether a service type is <b>cross-module</b>, i.e. belongs to a module other than
     * the document's home module. Compared at <i>module</i> granularity, not package: {@code mssql.cdc}
     * is foreign to {@code mssql} even though both share a package name.
     *
     * <p>Kept separate from {@link #serviceTypeAlias} because the two answer different questions. This
     * one also decides whether the type may be validated against the resolved package's symbols: a
     * foreign type cannot be, so it is trusted rather than vetoed.
     */
    static boolean isForeignServiceType(TriggerMetadataModel.ServiceType serviceType, String homeModule) {
        String module = serviceType == null ? null : moduleOf(serviceType.type());
        return module != null && !module.equals(homeModule);
    }

    /**
     * Spec &sect;1 — the {@code org/module} a cross-module service type belongs to, e.g.
     * {@code ballerinax/cdc} for a document whose home module is {@code mssql}.
     *
     * <p>The <i>module</i> is emitted rather than the alias it renders with: the module is the fact the
     * document states, and deriving a prefix from it is a syntax decision that belongs to the renderer
     * (which already derives module prefixes the same way for every other foreign type reference).
     * Emitting the full coordinate also lets the renderer name the owning package in the provenance
     * note it attaches, exactly as it does for foreign types in fields, parameters and returns.
     *
     * <p>Empty for a home-module type — the renderer then falls back to the listener's alias — and for
     * a reference carrying no usable coordinate.
     *
     * @param serviceType the service type being emitted
     * @param homeModule  the document's home module, from {@link #homeModule}
     * @return the foreign {@code org/module}, or empty
     */
    static Optional<String> serviceTypeModule(TriggerMetadataModel.ServiceType serviceType,
                                              String homeModule) {
        if (!isForeignServiceType(serviceType, homeModule)) {
            return Optional.empty();
        }
        String org = serviceType.type().packageInfo().org();
        String module = moduleOf(serviceType.type());
        if (org == null || org.isEmpty() || getAlias(module) == null || getAlias(module).isEmpty()) {
            // No usable prefix could be derived; qualifying with a blank alias would erase the type.
            return Optional.empty();
        }
        return Optional.of(org + "/" + module);
    }

    /**
     * Spec &sect;2 — {@code listeners[].requiredImports}: packages that must be imported for their side
     * effect even though nothing in the generated code references them by name (a CDC driver
     * registering itself at runtime). Emitted as {@code {module, alias}} so the renderer can write
     * {@code import ballerinax/mssql.cdc.driver as _;}.
     *
     * <p>{@code importType} is deliberately <b>not</b> filtered on: the spec's &sect;10 vocabulary
     * lists {@code driver} as the only value today, but an unrecognised kind still needs its import to
     * be emitted for the generated code to work, so it degrades rather than disappearing.
     *
     * <p>The import path is the <i>module</i> ({@link #moduleOf}), not the package: they differ for a
     * submodule, and it is the module that is imported.
     *
     * @param listener the document's first listener (may be {@code null})
     * @return one entry per declared import that carries usable coordinates; empty when there are none
     */
    static JsonArray requiredImports(TriggerMetadataModel.Listener listener) {
        JsonArray imports = new JsonArray();
        if (listener == null || listener.requiredImports() == null) {
            return imports;
        }
        for (TriggerMetadataModel.RequiredImport required : listener.requiredImports()) {
            if (required == null || required.packageInfo() == null) {
                continue;
            }
            String org = required.packageInfo().org();
            String module = moduleOf(new TypeRef(null, required.packageInfo()));
            if (org == null || org.isEmpty() || module == null) {
                continue;
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("module", org + "/" + module);
            entry.addProperty("alias", SIDE_EFFECT_IMPORT_ALIAS);
            imports.add(entry);
        }
        return imports;
    }
}
