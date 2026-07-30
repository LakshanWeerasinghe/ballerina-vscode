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
import io.ballerina.compiler.api.symbols.ClassSymbol;
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
 * three read-only sources instead of the SQLite service-index —
 * <ol>
 *   <li><b>{@code trigger-metadata.json}</b> (the LS-bundled authoring metadata, resolved through
 *       {@link LibraryMetadataReader#getPackagedTriggerMetadataModel}): the structural truth — which
 *       service types exist, the handler vocabulary of marker types, parameter types/optionality,
 *       and return types;</li>
 *   <li><b>the semantic model</b> of the same resolved package the manager already compiles:
 *       listener class + init parameters (docs via the init method's {@code parameterMap()},
 *       declared defaults via the syntax tree), the declared methods of concrete service types, and
 *       validation that every metadata claim (listener class, service-type names) actually exists in
 *       the resolved package version;</li>
 *   <li><b>the bundled trigger UI models</b> ({@link TriggerUiDocs}): handler and parameter
 *       descriptions/names for marker service types, whose methods are never declared in the library
 *       source (compiler-plugin contracts) and therefore have no semantic-model documentation.</li>
 * </ol>
 *
 * <p>Only libraries in {@link #SCHEMA_DRIVEN_LIBRARIES} are served here; everything else stays on
 * {@link ServiceIndexLoader}. Output shape is exactly the Copilot service contract
 * ({@code type/name/listener/methods}), so downstream enrichers, the generic-services merge, and the
 * TS prompt renderer are untouched. Function-level {@code optional} is deliberately never emitted
 * (matching today's output), and metadata constructs with no Copilot counterpart
 * ({@code dataBindingRules}, {@code rules}, {@code identifier}, wildcard {@code "*"} handlers) are
 * ignored.
 *
 * @since 1.7.0
 */
final class TriggerSchemaServiceLoader {

    private static final Logger LOGGER = Logger.getLogger(TriggerSchemaServiceLoader.class.getName());

    /**
     * Copilot libraries served from trigger metadata + semantic model, mapped to the LS-bundled
     * {@code trigger-metadata-models/<key>/trigger-metadata.json} module key. {@code ballerinax/mssql}
     * maps to the {@code mssql.cdc} document — the same CDC trigger published under the new module
     * layout; its listener ({@code CdcListener}) and handler set are validated against the actually
     * resolved {@code mssql} package before use.
     */
    static final Map<String, String> SCHEMA_DRIVEN_LIBRARIES = Map.of(
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

    static boolean isSchemaDriven(String libraryName) {
        return SCHEMA_DRIVEN_LIBRARIES.containsKey(libraryName);
    }

    /**
     * Loads services for a schema-driven library. Returns an empty array when the library is not
     * schema-driven, inputs are missing, the metadata document cannot be resolved, or anything
     * throws — the caller then falls back to the SQLite path, so a failure here can never lose a
     * library.
     */
    static JsonArray loadServices(String libraryName, Package pkg, SemanticModel semanticModel) {
        String metadataKey = SCHEMA_DRIVEN_LIBRARIES.get(libraryName);
        if (metadataKey == null || pkg == null || semanticModel == null) {
            return new JsonArray();
        }

        String packageName = ServiceIndexLoader.stripOrg(libraryName);
        String org = libraryName.contains("/")
                ? libraryName.substring(0, libraryName.indexOf('/'))
                : "ballerinax";

        try {
            Optional<TriggerMetadataModel> metadataOpt = LibraryMetadataReader.getInstance()
                    .getPackagedTriggerMetadataModel(new ModuleInfo(org, packageName, metadataKey, null));
            if (metadataOpt.isEmpty()) {
                LOGGER.warning("No bundled trigger metadata for " + libraryName + " (key: " + metadataKey + ")");
                return new JsonArray();
            }
            TriggerMetadataModel metadata = metadataOpt.get();
            if (metadata.listeners() == null || metadata.listeners().isEmpty()
                    || metadata.serviceTypes() == null || metadata.serviceTypes().isEmpty()) {
                return new JsonArray();
            }

            TriggerSemanticFacts facts = new TriggerSemanticFacts(semanticModel, pkg);
            TriggerUiDocs uiDocs = TriggerUiDocs.load(packageName,
                    pkg.packageVersion() != null ? pkg.packageVersion().value().toString() : null);

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

            JsonArray services = new JsonArray();
            for (TriggerMetadataModel.ServiceType serviceType : metadata.serviceTypes()) {
                String typeName = serviceType.type() != null ? serviceType.type().name() : null;
                if (typeName == null) {
                    continue;
                }
                // A same-module service type must exist in the resolved package version (guards
                // against metadata authored for a future release); a cross-module type (e.g. mssql's
                // cdc:Service) cannot be checked against this module's symbols and is trusted.
                boolean foreignType = serviceType.type().packageInfo() != null
                        && serviceType.type().packageInfo().packageName() != null
                        && !serviceType.type().packageInfo().packageName().equals(packageName);
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
                svc.add("listener", listenerJson);

                JsonArray methods = concrete
                        ? buildConcreteMethods(typeName, facts, uiDocs, packageName)
                        : buildOptionMethods(handlers.options(), typeName, facts::declaresType,
                                uiDocs, packageName);
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

    /** Concrete service types: methods and docs from the semantic model; UI docs fill blanks. */
    private static JsonArray buildConcreteMethods(String typeName, TriggerSemanticFacts facts,
                                                  TriggerUiDocs uiDocs, String packageName) {
        JsonArray methods = new JsonArray();
        Optional<io.ballerina.compiler.api.symbols.ObjectTypeSymbol> objectType =
                facts.serviceObjectType(typeName);
        if (objectType.isEmpty()) {
            return methods;
        }
        for (TriggerSemanticFacts.DeclaredMethod declared : facts.declaredMethods(objectType.get())) {
            Optional<TriggerUiDocs.FunctionDocs> functionDocs = uiDocs.functionDocs(typeName, declared.name());

            JsonObject method = new JsonObject();
            method.addProperty("name", declared.name());
            method.addProperty("type", declared.kind());

            String description = declared.description();
            if ((description == null || description.isEmpty()) && functionDocs.isPresent()) {
                description = functionDocs.get().description();
            }
            if (description != null && !description.isEmpty()) {
                method.addProperty("description", description);
            }

            if (!declared.params().isEmpty()) {
                JsonArray params = new JsonArray();
                for (int i = 0; i < declared.params().size(); i++) {
                    TriggerSemanticFacts.DeclaredParam param = declared.params().get(i);
                    String paramDescription = param.description();
                    if (paramDescription == null || paramDescription.isEmpty()) {
                        int index = i;
                        paramDescription = functionDocs
                                .flatMap(docs -> docs.paramNamed(param.name())
                                        .or(() -> docs.paramAt(index)))
                                .map(TriggerUiDocs.ParamDocs::description)
                                .orElse("");
                    }
                    params.add(buildParam(param.name(), paramDescription, param.typeSignature(),
                            param.optional(), packageName));
                }
                method.add("parameters", params);
            }

            addReturn(method, declared.returnTypeSignature(), packageName);
            methods.add(method);
        }
        return methods;
    }

    /** Marker service types: structure from metadata handler options; docs/names from UI models. */
    static JsonArray buildOptionMethods(List<TriggerMetadataModel.ServiceType.HandlerOption> options,
                                        String typeName, Predicate<String> declaresType,
                                        TriggerUiDocs uiDocs, String packageName) {
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
            Optional<TriggerUiDocs.FunctionDocs> functionDocs = uiDocs.functionDocs(typeName, option.name());

            JsonObject method = new JsonObject();
            method.addProperty("name", option.name());
            method.addProperty("type",
                    TriggerMetadataModel.ServiceType.HandlerOption.KIND_RESOURCE.equals(option.kind())
                            ? "resource" : "remote");

            String description = functionDocs.map(TriggerUiDocs.FunctionDocs::description).orElse("");
            if (!description.isEmpty()) {
                method.addProperty("description", description);
            }

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
                    int index = i;
                    Optional<TriggerUiDocs.ParamDocs> paramDocs = functionDocs
                            .flatMap(docs -> param.name() != null
                                    ? docs.paramNamed(param.name()).or(() -> docs.paramAt(index))
                                    : docs.paramAt(index));

                    String name = param.name() != null ? param.name()
                            : paramDocs.map(TriggerUiDocs.ParamDocs::name).orElse(null);
                    if (name == null) {
                        name = deriveParamName(firstTypeRef(param.type()), declaresType, i, usedNames);
                    }
                    usedNames.add(name);
                    String paramDescription = paramDocs.map(TriggerUiDocs.ParamDocs::description).orElse("");
                    String typeSignature = renderTypeRef(firstTypeRef(param.type()), packageName,
                            declaresType);
                    boolean optional = "optional".equals(param.presence());

                    params.add(buildParam(name, paramDescription, typeSignature, optional, packageName));
                }
                method.add("parameters", params);
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
     * Ballerina reserved/contextual words a derived parameter name must never be — the rendered
     * prompt would not be valid source (e.g. a type named {@code Error} deriving {@code error}).
     */
    private static final Set<String> RESERVED_WORDS = Set.of(
            "error", "service", "client", "listener", "type", "function", "record", "object", "table",
            "map", "stream", "string", "int", "float", "decimal", "boolean", "byte", "json", "xml",
            "anydata", "any", "never", "readonly", "distinct", "worker", "fork", "transaction", "retry",
            "new", "isolated", "final", "const", "var", "if", "else", "while", "foreach", "in", "return",
            "returns", "break", "continue", "fail", "panic", "trap", "from", "where", "select", "let",
            "on", "do", "is", "null", "true", "false", "import", "public", "private", "remote",
            "resource", "abstract", "class", "enum", "annotation", "external", "check", "checkpanic",
            "future", "typedesc", "handle", "match", "source", "field", "start", "wait", "flush",
            "lock", "commit", "rollback", "version", "key", "limit", "order", "group", "join");

    /**
     * Fallback name for a parameter slot the metadata deliberately leaves unnamed and no UI model
     * documents (e.g. websub's handlers): the lower-camel-cased declared type name
     * ({@code SubscriptionVerification} → {@code subscriptionVerification}) — idiomatic, compilable
     * Ballerina — or a positional {@code paramN} when the type is not a module-declared named type,
     * the derived name is a reserved word, or it would collide with a sibling parameter.
     */
    static String deriveParamName(TypeRef ref, Predicate<String> declaresType, int index,
                                  Set<String> usedNames) {
        if (ref != null && ref.name() != null && ref.packageInfo() == null) {
            String base = baseIdentifier(ref.name());
            if (base != null && base.equals(ref.name()) && declaresType.test(base)) {
                String derived = Character.toLowerCase(base.charAt(0)) + base.substring(1);
                if (!RESERVED_WORDS.contains(derived) && !usedNames.contains(derived)) {
                    return derived;
                }
            }
        }
        return "param" + (index + 1);
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
}
