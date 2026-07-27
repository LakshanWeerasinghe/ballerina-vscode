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

import io.ballerina.modelgenerator.commons.AuthoringAnnotation;
import io.ballerina.modelgenerator.commons.AuthoringDataBindingRule;
import io.ballerina.modelgenerator.commons.AuthoringServiceType;
import io.ballerina.modelgenerator.commons.PresenceForm;
import io.ballerina.modelgenerator.commons.TriggerAuthoringModel;
import io.ballerina.modelgenerator.commons.TriggerLibraryFacts;
import io.ballerina.modelgenerator.commons.TypeRef;
import io.ballerina.servicemodelgenerator.extension.connector.model.TriggerModel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Synthesizes a {@link TriggerModel} at request time from a connector's own hand-authored
 * {@link TriggerAuthoringModel} (its {@code resources/trigger-authoring.json} — presence rules,
 * {@code oneOf} relationships, identifier semantics, non-concrete handler shapes) plus
 * {@link TriggerLibraryFacts} introspected from its compiled {@code SemanticModel} (real listener
 * init params, real declared service-type methods, real annotation declarations).
 *
 * <p>The output is deliberately the <b>same</b> {@link TriggerModel} class the hand-authored and
 * {@code generate-trigger-model}-produced connectors already use, so it flows through
 * {@code SchemaDrivenServiceBuilder}, {@code SchemaDrivenFunctionBuilder}, {@code TriggerServiceAdapter},
 * {@code TriggerSourceMerger}, and {@link SchemaDrivenSourceGenerator} completely unmodified — every
 * existing init/create/view/add-handler code path (and the whole TypeScript/extension layer, which is
 * generic over {@code TriggerModel} already) works on a synthesized model exactly as it does on a
 * hand-curated one.
 *
 * <h2>What this class does NOT attempt</h2>
 * <ul>
 *   <li><b>Copy-quality labels/descriptions.</b> A hand-authored model's field labels
 *       ("Bootstrap Servers") and prose descriptions are human copywriting that exists in neither
 *       input document. This synthesizer humanizes identifiers for labels
 *       ({@link #humanize(String)}) and reuses a symbol's own doc comment (via
 *       {@link TriggerLibraryFacts}, which already carries it) for descriptions where introspection
 *       found one — functionally correct, not copy-edited.</li>
 *   <li><b>Granular per-field annotation composition.</b> A hand-authored model renders a service
 *       annotation as a field-by-field {@code MAPPING_CONSTRUCTOR} tree (see the
 *       {@code generate-trigger-model} skill). This synthesizer renders the whole annotation as one
 *       {@code RECORD_MAP_EXPRESSION} field the user fills as a single expression — the same
 *       fallback shape {@code ServiceModelUtils#getAnnotationAttachmentProperty} already uses for the
 *       non-schema-driven default builders, so it is a recognized fidelity tier in this codebase, not
 *       a new one.</li>
 *   <li><b>The general {@code oneOf} choice UX.</b> Per the agreed v1 rule, a
 *       {@code serviceTypes[].rules[]} entry of type {@code oneOf} is resolved by rendering only its
 *       {@code preferred} member (or the first member if none is marked preferred) and silently
 *       dropping the alternative(s) — e.g. RabbitMQ's queue-name-via-annotation-or-via-identifier
 *       renders the annotation field only. Revisit if a real connector needs the actual either/or
 *       surfaced.</li>
 * </ul>
 *
 * @since 1.10.0
 */
public final class TriggerModelSynthesizer {

    private static final String SCHEMA_VERSION = "1.0";
    private static final String LISTENER_KEY = "listener";
    private static final String LISTENER_VAR_NAME_KEY = "listenerVarName";
    private static final String SERVICE_TYPE_KEY = "serviceType";
    private static final String IDENTIFIER_KEY = "identifier";

    private TriggerModelSynthesizer() {
    }

    /**
     * Synthesizes a {@link TriggerModel} for one connector.
     *
     * @param authoring   the connector's own {@code resources/trigger-authoring.json}
     * @param facts       the facts introspected from the connector's compiled {@code SemanticModel}
     * @param id          the catalog identifier to stamp on the result (caller's choice; this class
     *                    has no catalog of its own)
     * @param displayName the connector's display name (e.g. from {@code TriggerMetadataResolver})
     * @param icon        the connector's icon URL (e.g. from {@code TriggerMetadataResolver})
     * @param kind         the entry-point kind bucket (e.g. {@code event}/{@code file}/{@code http})
     * @param orgName     the connector's organization
     * @param packageName the connector's package name
     * @param moduleName  the connector's module name
     * @param version     the connector's version
     * @return the synthesized model, or {@link Optional#empty()} if the authoring model declares no
     *     listeners or no service types (a malformed/empty document nothing can be built from)
     */
    public static Optional<TriggerModel> synthesize(TriggerAuthoringModel authoring, TriggerLibraryFacts facts,
                                                     String id, String displayName, String icon, String kind,
                                                     String orgName, String packageName, String moduleName,
                                                     String version) {
        if (authoring == null || facts == null
                || authoring.listeners() == null || authoring.listeners().isEmpty()
                || authoring.serviceTypes() == null || authoring.serviceTypes().isEmpty()) {
            return Optional.empty();
        }

        List<AuthoringServiceType> serviceTypes = authoring.serviceTypes();
        boolean multiType = serviceTypes.size() > 1;
        AuthoringServiceType primary = serviceTypes.get(0);

        Map<String, TriggerModel.Property> initProperties = new LinkedHashMap<>();
        buildListenerChoice(authoring.listeners().get(0), facts, moduleName, initProperties);
        buildIdentifierField(primary, initProperties);
        if (multiType) {
            initProperties.put(SERVICE_TYPE_KEY, buildServiceTypeSelector(serviceTypes));
        }

        List<TriggerModel.ServiceTypeModel> serviceTypeModels = new ArrayList<>();
        for (int i = 0; i < serviceTypes.size(); i++) {
            AuthoringServiceType st = serviceTypes.get(i);
            serviceTypeModels.add(buildServiceType(st, facts, authoring, moduleName, i == 0, multiType));
        }

        String listenerKind = primary.multipleListenersAllowed()
                ? "MULTIPLE_SELECT_LISTENER" : "SINGLE_SELECT_LISTENER";

        return Optional.of(new TriggerModel(
                SCHEMA_VERSION, id, displayName, "", orgName, packageName, moduleName, version,
                kind, icon, kind, listenerKind, initProperties, serviceTypeModels, List.of(), List.of(), null));
    }

    // ---- Codedata helpers (25-field record; centralized here so every call site is counted once) ----

    private static TriggerModel.Codedata cd() {
        return new TriggerModel.Codedata(null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static TriggerModel.Codedata cdType(String type) {
        return new TriggerModel.Codedata(type, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static TriggerModel.Codedata cdListenerParam(String argType, Integer position, String path) {
        return new TriggerModel.Codedata(null, argType, null, null, null, null, position, path, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static TriggerModel.Codedata cdFunction(String originalName, String moduleName) {
        return new TriggerModel.Codedata("FUNCTION", null, originalName, moduleName, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null);
    }

    private static TriggerModel.Codedata cdServiceType(String originalName, String moduleName) {
        return new TriggerModel.Codedata("SERVICE_TYPE_DESCRIPTOR", null, originalName, moduleName, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null);
    }

    private static TriggerModel.Codedata cdAnnotationAttachment(String originalName, String moduleName,
                                                                 String packageName, boolean optional) {
        return new TriggerModel.Codedata("ANNOTATION_ATTACHMENT", null, originalName, moduleName, null,
                packageName, null, null, null, null, null, null, null, null, null, null, null, null, null,
                optional, null, null, null, null, null);
    }

    private static TriggerModel.Codedata cdPayload(String type, String defaultType, String template, String field,
                                                    String typeConstraint) {
        return new TriggerModel.Codedata(type, null, null, null, null, null, null, null, defaultType, "", true,
                "USER_SELECTED", typeConstraint, template, null, null, null, null, field, null, null, null,
                null, null, true);
    }

    // ---- listener init form --------------------------------------------------

    /**
     * Builds the {@code listener} CHOICE (create-new / use-existing) per the {@code generate-trigger-model}
     * skill's default: a connector-shipped model always offers both branches unless a developer directive
     * says otherwise -- this synthesizer has no such directive channel, so it always includes the choice.
     */
    private static void buildListenerChoice(TriggerAuthoringModel.Listener listener, TriggerLibraryFacts facts,
                                            String moduleName, Map<String, TriggerModel.Property> initProperties) {
        TriggerLibraryFacts.Listener listenerFacts = findListener(listener, facts);

        Map<String, TriggerModel.Property> createNewProps = new LinkedHashMap<>();
        createNewProps.put(LISTENER_VAR_NAME_KEY, listenerVarNameProperty(moduleName));
        if (listenerFacts != null) {
            int position = 1;
            for (TriggerLibraryFacts.Param param : listenerFacts.initParams()) {
                position = addListenerParam(param, position, createNewProps);
            }
        }
        TriggerModel.Property createNew = new TriggerModel.Property(
                new TriggerModel.Metadata("Create New Listener", "Create a new listener", null, null, null, null,
                        null, null),
                true, true, false, false, null, null, null, null, null, createNewProps, cd(), null);

        Map<String, TriggerModel.Property> useExistingProps = new LinkedHashMap<>();
        useExistingProps.put(LISTENER_KEY, existingListenerSelector());
        TriggerModel.Property useExisting = new TriggerModel.Property(
                new TriggerModel.Metadata("Use Existing Listener", "Attach to an already-declared listener",
                        null, null, null, null, null, null),
                false, false, false, false, null, null, null, null, null, useExistingProps, cd(), null);

        TriggerModel.PropertyType choiceType = new TriggerModel.PropertyType(
                "CHOICE", true, null, null, null, null, null, null);
        TriggerModel.Property choice = new TriggerModel.Property(
                new TriggerModel.Metadata("Listener", "The listener this service attaches to", null, null, null,
                        null, null, null),
                true, true, false, false, null, null, List.of(choiceType), null,
                List.of(createNew, useExisting), null, cdType("LISTENER_CONFIG"), null);
        initProperties.put(LISTENER_KEY, choice);
    }

    private static TriggerModel.Property listenerVarNameProperty(String moduleName) {
        TriggerModel.PropertyType type = new TriggerModel.PropertyType(
                "IDENTIFIER", true, moduleName + ":Listener", null, null, null, null, null);
        return new TriggerModel.Property(
                new TriggerModel.Metadata("Listener Name", "A name for the listener being created", null, null,
                        null, null, null, null),
                true, true, false, true, null, moduleName + "Listener", List.of(type), null, null, null,
                cdType("LISTENER_VAR_NAME"), null);
    }

    /** The "use existing" branch's selector; the LS injects the project's existing listeners at request time. */
    private static TriggerModel.Property existingListenerSelector() {
        TriggerModel.PropertyType type = new TriggerModel.PropertyType(
                "SINGLE_SELECT_LISTENER", true, null, null, null, null, null, null);
        return new TriggerModel.Property(
                new TriggerModel.Metadata("Listener", "The existing listener to attach to", null, null, null, null,
                        null, null),
                true, true, false, false, null, null, List.of(type), null, null, null,
                cdType("KEY_EXISTING_LISTENER"), null);
    }

    /**
     * Adds one listener init parameter to {@code createNewProps}, per the mapping table: REQUIRED and
     * DEFAULTABLE scalars are positional {@code LISTENER_PARAM_REQUIRED} fields; an INCLUDED_RECORD
     * param is not itself rendered -- its fields are flattened into named
     * {@code LISTENER_PARAM_INCLUDED_FIELD}/{@code LISTENER_PARAM_INCLUDED_DEFAULTABLE_FIELD} fields
     * sharing the record param's own position. Returns the next position to assign.
     */
    private static int addListenerParam(TriggerLibraryFacts.Param param, int position,
                                        Map<String, TriggerModel.Property> createNewProps) {
        if ("INCLUDED_RECORD".equals(param.kind())) {
            for (TriggerLibraryFacts.Param field : param.fields()) {
                String argType = field.optional()
                        ? "LISTENER_PARAM_INCLUDED_DEFAULTABLE_FIELD" : "LISTENER_PARAM_INCLUDED_FIELD";
                createNewProps.put(field.name(), listenerParamProperty(field, argType, null, field.name()));
            }
            return position + 1;
        }
        createNewProps.put(param.name(), listenerParamProperty(param, "LISTENER_PARAM_REQUIRED", position, null));
        return position + 1;
    }

    private static TriggerModel.Property listenerParamProperty(TriggerLibraryFacts.Param param, String argType,
                                                                Integer position, String path) {
        boolean optional = param.optional();
        TriggerModel.PropertyType type = new TriggerModel.PropertyType(
                "string".equals(param.type()) ? "TEXT" : "EXPRESSION", true, param.type(), null, null, null, null,
                null);
        String label = humanize(param.name());
        String description = param.doc() == null || param.doc().isBlank()
                ? "The " + param.name() + " parameter." : param.doc();
        return new TriggerModel.Property(
                new TriggerModel.Metadata(label, description, null, null, null, null, null, null),
                true, true, optional, optional, null, null, List.of(type), null, null, null,
                cdListenerParam(argType, position, path), null);
    }

    private static TriggerLibraryFacts.Listener findListener(TriggerAuthoringModel.Listener listener,
                                                              TriggerLibraryFacts facts) {
        if (facts.listeners() == null || facts.listeners().isEmpty()) {
            return null;
        }
        String name = simpleName(listener.type());
        for (TriggerLibraryFacts.Listener candidate : facts.listeners()) {
            if (simpleNameOfQualified(candidate.type()).equals(name)) {
                return candidate;
            }
        }
        return facts.listeners().get(0);
    }

    // ---- identifier / base path -----------------------------------------------

    /**
     * Adds an {@code identifier}/base-path field when the primary service type declares one and it is
     * not already resolved (per the v1 {@code oneOf} rule) by a preferred annotation-field alternative.
     */
    private static void buildIdentifierField(AuthoringServiceType serviceType,
                                             Map<String, TriggerModel.Property> initProperties) {
        PresenceForm identifier = serviceType.identifier();
        if (identifier == null) {
            return;
        }
        if (isSupersededByPreferredAnnotation(serviceType)) {
            return;
        }
        boolean isBasePath = identifier.form() != null && identifier.form().contains(PresenceForm.FORM_BASE_PATH);
        String fieldType = isBasePath ? "SERVICE_PATH" : "IDENTIFIER";
        boolean optional = PresenceForm.PRESENCE_OPTIONAL.equals(identifier.presence());
        TriggerModel.PropertyType type = new TriggerModel.PropertyType(
                fieldType, true, "string", null, null, null, null, null);
        TriggerModel.Property property = new TriggerModel.Property(
                new TriggerModel.Metadata(isBasePath ? "Service Path" : "Identifier",
                        isBasePath ? "The base path this service is exposed on"
                                : "The identifier for this service", null, null, null, null, null, null),
                true, true, optional, false, isBasePath ? "/" : null, null, List.of(type), null, null, null,
                cdType("SERVICE_ID"), null);
        initProperties.put(IDENTIFIER_KEY, property);
    }

    /** True when a {@code oneOf} rule on this service type prefers an annotation field over the identifier. */
    private static boolean isSupersededByPreferredAnnotation(AuthoringServiceType serviceType) {
        if (serviceType.rules() == null) {
            return false;
        }
        for (AuthoringServiceType.Rule rule : serviceType.rules()) {
            if (!AuthoringServiceType.Rule.TYPE_ONE_OF.equals(rule.type())) {
                continue;
            }
            boolean hasIdentifierMember = rule.members().stream()
                    .anyMatch(m -> AuthoringServiceType.Rule.RuleMember.PART_IDENTIFIER.equals(m.part()));
            if (!hasIdentifierMember) {
                continue;
            }
            AuthoringServiceType.Rule.RuleMember preferred = preferredMember(rule);
            if (preferred.annotation() != null) {
                return true;
            }
        }
        return false;
    }

    /** The {@code preferred:true} member of a {@code oneOf} rule, or its first member if none is marked. */
    private static AuthoringServiceType.Rule.RuleMember preferredMember(AuthoringServiceType.Rule rule) {
        return rule.members().stream()
                .filter(m -> Boolean.TRUE.equals(m.preferred()))
                .findFirst()
                .orElse(rule.members().get(0));
    }

    // ---- service type selector (multi-type connectors) -------------------------

    private static TriggerModel.Property buildServiceTypeSelector(List<AuthoringServiceType> serviceTypes) {
        List<TriggerModel.Option> options = new ArrayList<>();
        for (AuthoringServiceType st : serviceTypes) {
            options.add(new TriggerModel.Option(humanize(st.id()), st.id(), null));
        }
        TriggerModel.PropertyType type = new TriggerModel.PropertyType(
                "SINGLE_SELECT", true, null, options, null, null, null, null);
        return new TriggerModel.Property(
                new TriggerModel.Metadata("Service Type", "The kind of service to create", null, null, null, null,
                        null, null),
                true, true, false, false, null, serviceTypes.get(0).id(), List.of(type), null, null, null,
                cdType("SERVICE_TYPE_DESCRIPTOR"), null);
    }

    // ---- service types & handlers ------------------------------------------------

    private static TriggerModel.ServiceTypeModel buildServiceType(AuthoringServiceType serviceType,
                                                                  TriggerLibraryFacts facts,
                                                                  TriggerAuthoringModel authoring,
                                                                  String moduleName, boolean isFirst,
                                                                  boolean multiType) {
        String typeName = serviceType.type().name();
        Map<String, TriggerModel.Property> properties = buildServiceAnnotations(serviceType, authoring, moduleName);

        List<TriggerModel.FunctionModel> functions = new ArrayList<>();
        List<TriggerModel.FunctionModel> schemaFunctions = new ArrayList<>();
        AuthoringServiceType.Handlers handlers = serviceType.handlers();
        if (handlers != null && handlers.backedByConcreteType()) {
            TriggerLibraryFacts.ServiceType stFacts = findServiceType(typeName, facts);
            if (stFacts != null) {
                for (TriggerLibraryFacts.Function fn : stFacts.functions()) {
                    functions.add(buildFunctionFromFacts(fn, moduleName));
                }
            }
        } else if (handlers != null && handlers.options() != null) {
            for (AuthoringServiceType.HandlerOption option : handlers.options()) {
                schemaFunctions.add(buildFunctionFromAuthoring(option, handlers.addMode(), authoring, moduleName));
            }
        }

        return new TriggerModel.ServiceTypeModel(
                new TriggerModel.Metadata(humanize(serviceType.id()), null, null, null, null, null, null, null),
                typeName, null, isFirst, multiType, properties, functions, schemaFunctions,
                cdServiceType(typeName, moduleName));
    }

    private static TriggerLibraryFacts.ServiceType findServiceType(String name, TriggerLibraryFacts facts) {
        for (TriggerLibraryFacts.ServiceType st : facts.serviceTypes()) {
            if (st.name().equals(name)) {
                return st;
            }
        }
        return null;
    }

    /** A locked handler for a {@code backedByConcreteType} service type -- entirely from introspection. */
    private static TriggerModel.FunctionModel buildFunctionFromFacts(TriggerLibraryFacts.Function fn,
                                                                     String moduleName) {
        List<TriggerModel.Parameter> parameters = new ArrayList<>();
        for (TriggerLibraryFacts.Param param : fn.parameters()) {
            parameters.add(buildParameterFromFacts(param));
        }
        TriggerModel.ReturnType returnType = buildReturnType(fn.returnType(), fn.returnsError());
        String description = fn.doc() == null || fn.doc().isBlank() ? "The `" + fn.name() + "` handler." : fn.doc();
        return new TriggerModel.FunctionModel(
                new TriggerModel.Metadata(fn.name(), description, null, null, null, null, null, null),
                fn.name(), false, null, fn.kind(), null, fn.qualifiers(), null, null, true, false, false, false,
                null, null, null, parameters, null, Map.of(), returnType, cdFunction(fn.name(), moduleName), null);
    }

    private static TriggerModel.Parameter buildParameterFromFacts(TriggerLibraryFacts.Param param) {
        TriggerModel.Property typeProperty = plainTypeProperty(param.type());
        TriggerModel.Property nameProperty = identifierProperty(param.name());
        return new TriggerModel.Parameter(
                new TriggerModel.Metadata(humanize(param.name()),
                        param.doc() == null || param.doc().isBlank() ? null : param.doc(), null, null, null, null,
                        null, null),
                "REQUIRED", typeProperty, nameProperty, null, null, null, null, true, false, param.optional(),
                false, false, cdType("FUNCTION_PARAM"), null);
    }

    private static TriggerModel.Property identifierProperty(String name) {
        TriggerModel.PropertyType type = new TriggerModel.PropertyType(
                "IDENTIFIER", true, null, null, null, null, null, null);
        return new TriggerModel.Property(
                new TriggerModel.Metadata(name, null, null, null, null, null, null, null),
                true, true, false, false, name, name, List.of(type), null, null, null, null, null);
    }

    /**
     * An addable/locked handler built entirely from the authoring schema's own {@code HandlerOption}
     * (never from introspection -- a non-concrete service type declares no methods of its own, so
     * there is nothing to introspect; the option's {@code params}/{@code returns} are already fully
     * resolved {@link TypeRef}s).
     */
    private static TriggerModel.FunctionModel buildFunctionFromAuthoring(AuthoringServiceType.HandlerOption option,
                                                                         String addMode,
                                                                         TriggerAuthoringModel authoring,
                                                                         String moduleName) {
        boolean many = AuthoringServiceType.Handlers.ADD_MODE_MANY.equals(addMode);
        boolean required = "required".equals(option.presence());

        List<TriggerModel.Parameter> parameters = new ArrayList<>();
        if (option.params() != null) {
            for (AuthoringServiceType.Param param : option.params()) {
                parameters.add(buildParameterFromAuthoring(param, authoring));
            }
        }
        TriggerModel.ReturnType returnType = buildReturnTypeFromRefs(option.returns());

        String name = many ? "" : option.name();
        String label = many ? "Handler" : option.name();
        return new TriggerModel.FunctionModel(
                new TriggerModel.Metadata(label, "The `" + option.name() + "` handler.", null, null, null,
                        many ? "Add Handler" : null, null, null),
                name, many, null, option.kind() == null ? null : option.kind().toUpperCase(Locale.ROOT),
                null, option.kind() == null ? null : List.of(option.kind()), null, null, false, true, !required,
                false, null, null, null, parameters, null, Map.of(), returnType,
                cdFunction(option.name(), moduleName), null);
    }

    private static TriggerModel.Parameter buildParameterFromAuthoring(AuthoringServiceType.Param param,
                                                                      TriggerAuthoringModel authoring) {
        String typeName = typeRefName(param.type());
        AuthoringDataBindingRule bindingRule = param.dataBinding() == null ? null
                : findDataBindingRule(param.dataBinding(), authoring);

        TriggerModel.Property typeProperty = bindingRule == null
                ? plainTypeProperty(typeName)
                : dataBindingTypeProperty(bindingRule, typeName);

        String name = param.name() == null ? "" : param.name();
        TriggerModel.Property nameProperty = identifierProperty(name);
        String kind = bindingRule != null ? "DATA_BINDING"
                : ("optional".equals(param.presence()) ? "OPTIONAL" : "REQUIRED");
        return new TriggerModel.Parameter(
                new TriggerModel.Metadata(humanize(name.isEmpty() ? "value" : name), null, null, null, null, null,
                        null, null),
                kind, typeProperty, nameProperty, null, null, null, null, true, true,
                "optional".equals(param.presence()), false, false, cdType("FUNCTION_PARAM"), null);
    }

    private static TriggerModel.Property plainTypeProperty(String typeName) {
        TriggerModel.PropertyType type = new TriggerModel.PropertyType(
                "TYPE", true, typeName, null, null, null, null, null);
        return new TriggerModel.Property(
                new TriggerModel.Metadata("Parameter Type", "The type of the parameter", null, null, null, null,
                        null, null),
                true, false, false, false, null, typeName, List.of(type), null, null, null, cd(), null);
    }

    /**
     * The {@code PAYLOAD_TYPE}/{@code PAYLOAD_TYPE_INCLUDED_RECORD} composition for a data-bound
     * parameter, per {@code AuthoringDataBindingRule}'s {@code direct}/{@code includedRecord} modes --
     * only these two are attempted; {@code streamable} falls back to a plain type (a v1 simplification).
     */
    private static TriggerModel.Property dataBindingTypeProperty(AuthoringDataBindingRule rule, String typeName) {
        Optional<AuthoringDataBindingRule.SupportedMode> includedRecord = rule.supportedModes().stream()
                .filter(m -> AuthoringDataBindingRule.SupportedMode.MODE_INCLUDED_RECORD.equals(m.mode()))
                .findFirst();
        Optional<AuthoringDataBindingRule.SupportedMode> direct = rule.supportedModes().stream()
                .filter(m -> AuthoringDataBindingRule.SupportedMode.MODE_DIRECT.equals(m.mode()))
                .findFirst();

        String cdType = includedRecord.isPresent() ? "PAYLOAD_TYPE_INCLUDED_RECORD" : "PAYLOAD_TYPE";
        String defaultType;
        String template = rule.cardinality() != null
                && AuthoringDataBindingRule.CARDINALITY_ARRAY.equals(rule.cardinality()) ? "{{type}}[]" : "{{type}}";
        String field = null;
        String typeConstraint = null;
        if (includedRecord.isPresent()) {
            AuthoringDataBindingRule.SupportedMode mode = includedRecord.get();
            defaultType = mode.includes() == null ? typeName : mode.includes().name();
            field = mode.bindableFields() == null || mode.bindableFields().isEmpty()
                    ? null : mode.bindableFields().get(0);
        } else if (direct.isPresent() && !direct.get().typeConstraint().isEmpty()) {
            defaultType = direct.get().typeConstraint().get(0).name();
            typeConstraint = defaultType;
        } else {
            defaultType = typeName;
        }

        TriggerModel.PropertyType propertyType = new TriggerModel.PropertyType(
                "PAYLOAD_TYPE", true, null, null, null, null,
                List.of(new TriggerModel.PayloadFormat(List.of("schema", "browse", "json", "xml"), "json")), null);
        return new TriggerModel.Property(
                new TriggerModel.Metadata("Payload", "The shape of the received payload", null, null, null, null,
                        null, null),
                true, true, false, false, null, "", List.of(propertyType), null, null, null,
                cdPayload(cdType, defaultType, template, field, typeConstraint), null);
    }

    private static AuthoringDataBindingRule findDataBindingRule(String id, TriggerAuthoringModel authoring) {
        if (authoring.dataBindingRules() == null) {
            return null;
        }
        for (AuthoringDataBindingRule rule : authoring.dataBindingRules()) {
            if (rule.id().equals(id)) {
                return rule;
            }
        }
        return null;
    }

    private static TriggerModel.ReturnType buildReturnType(String type, boolean hasError) {
        boolean enabled = type != null && !"()".equals(type);
        return new TriggerModel.ReturnType(
                new TriggerModel.Metadata("Return Type", "The return type of the function.", null, null, null,
                        null, null, null),
                type, false, null, enabled, false, enabled, hasError, "", cd(), null);
    }

    private static TriggerModel.ReturnType buildReturnTypeFromRefs(List<TypeRef> refs) {
        if (refs == null || refs.isEmpty()) {
            return buildReturnType(null, false);
        }
        String joined = String.join("|", refs.stream().map(TypeRef::name).toList());
        boolean hasError = joined.contains("error");
        return buildReturnType(joined, hasError);
    }

    // ---- service-level annotations ------------------------------------------------

    /**
     * Renders every {@code service}-attached annotation applicable to {@code serviceType} as a single
     * {@code RECORD_MAP_EXPRESSION} field the user fills as one expression -- the same fidelity tier
     * {@code ServiceModelUtils#getAnnotationAttachmentProperty} already uses for the non-schema-driven
     * default builders (not a granular per-field {@code MAPPING_CONSTRUCTOR} tree; see the class javadoc).
     */
    private static Map<String, TriggerModel.Property> buildServiceAnnotations(AuthoringServiceType serviceType,
                                                                              TriggerAuthoringModel authoring,
                                                                              String moduleName) {
        Map<String, TriggerModel.Property> properties = new LinkedHashMap<>();
        if (authoring.annotations() == null) {
            return properties;
        }
        for (AuthoringAnnotation annotation : authoring.annotations()) {
            if (!AuthoringAnnotation.ATTACH_POINT_SERVICE.equals(annotation.attachPoint())) {
                continue;
            }
            if (annotation.appliesTo() != null && !annotation.appliesTo().contains(serviceType.id())) {
                continue;
            }
            properties.put(annotation.id(), buildAnnotationAttachment(annotation, moduleName));
        }
        return properties;
    }

    private static TriggerModel.Property buildAnnotationAttachment(AuthoringAnnotation annotation,
                                                                    String moduleName) {
        String typeName = annotation.type().name();
        String pkgName = annotation.type().packageInfo() == null ? moduleName
                : annotation.type().packageInfo().packageName();
        TriggerModel.TypeMember member = new TriggerModel.TypeMember(typeName, null, pkgName, "RECORD_TYPE", true);
        TriggerModel.PropertyType propertyType = new TriggerModel.PropertyType(
                "RECORD_MAP_EXPRESSION", true, typeName, null, List.of(member), null, null, null);
        boolean optional = AuthoringAnnotation.PRESENCE_OPTIONAL.equals(annotation.presence());
        return new TriggerModel.Property(
                new TriggerModel.Metadata(humanize(annotation.id()), "Configuration for this service", null,
                        null, null, null, null, null),
                true, true, optional, !optional, "{}", "", List.of(propertyType), null, null, null,
                cdAnnotationAttachment(annotation.id(), moduleName, pkgName, optional), null);
    }

    // ---- shared helpers ------------------------------------------------------------

    private static String typeRefName(List<TypeRef> refs) {
        if (refs == null || refs.isEmpty()) {
            return "anydata";
        }
        return String.join("|", refs.stream().map(TypeRef::name).toList());
    }

    /** The unqualified name of a same-/cross-module {@link TypeRef}, e.g. {@code "http:Listener" -> "Listener"}. */
    private static String simpleName(TypeRef ref) {
        return simpleNameOfQualified(ref.name());
    }

    /** The unqualified suffix of a module-qualified name, e.g. {@code "kafka:Listener" -> "Listener"}. */
    private static String simpleNameOfQualified(String name) {
        int colon = name.lastIndexOf(':');
        return colon < 0 ? name : name.substring(colon + 1);
    }

    /** {@code "bootstrapServers" -> "Bootstrap Servers"}; also splits on {@code _}/{@code -}. */
    static String humanize(String identifier) {
        if (identifier == null || identifier.isEmpty()) {
            return identifier;
        }
        StringBuilder result = new StringBuilder();
        char[] chars = identifier.replace('_', ' ').replace('-', ' ').toCharArray();
        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];
            if (i > 0 && Character.isUpperCase(c) && Character.isLowerCase(chars[i - 1])) {
                result.append(' ');
            }
            if (i == 0) {
                result.append(Character.toUpperCase(c));
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }
}
