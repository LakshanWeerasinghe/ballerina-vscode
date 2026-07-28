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
import io.ballerina.modelgenerator.commons.AuthoringAnnotation;
import io.ballerina.modelgenerator.commons.AuthoringDataBindingRule;
import io.ballerina.modelgenerator.commons.AuthoringServiceType;
import io.ballerina.modelgenerator.commons.PresenceForm;
import io.ballerina.modelgenerator.commons.TriggerAuthoringModel;
import io.ballerina.modelgenerator.commons.TriggerLibraryFacts;
import io.ballerina.modelgenerator.commons.TypeRef;
import io.ballerina.servicemodelgenerator.extension.connector.model.TriggerModel;
import io.ballerina.servicemodelgenerator.extension.model.Listener;
import io.ballerina.servicemodelgenerator.extension.model.Value;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Synthesizes a {@link TriggerModel} at request time from a connector's own hand-authored
 * {@link TriggerAuthoringModel} (its {@code resources/trigger-authoring.json} — presence rules,
 * {@code oneOf} relationships, identifier semantics, non-concrete handler shapes),
 * {@link TriggerLibraryFacts} introspected from its compiled {@code SemanticModel} (real declared
 * service-type methods, real annotation declarations), and a listener init-form template already
 * resolved by {@code ListenerUtil#getListenerModelByName} (real init params, already correctly
 * widget-typed — records, unions, numbers — for the connector's declared listener class).
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
 *   <li><b>Listener init-param widget selection.</b> Deliberately not reimplemented here: a listener's
 *       record-typed/union-typed/etc. init parameters are already correctly resolved by
 *       {@code ListenerUtil#getListenerModelByName} (the same utility the non-schema-driven "add
 *       listener" flow uses) -- this class only enriches that result with the schema-specific
 *       {@code argType}/{@code position} codedata {@link SchemaDrivenSourceGenerator} needs, per
 *       {@link #enrichListenerParam}.</li>
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
    private static final Gson GSON = new Gson();

    private TriggerModelSynthesizer() {
    }

    /**
     * Synthesizes a {@link TriggerModel} for one connector.
     *
     * @param authoring     the connector's own {@code resources/trigger-authoring.json}
     * @param facts         the service-type/annotation facts introspected from the connector's
     *                      compiled {@code SemanticModel} (see {@link TriggerLibraryFacts})
     * @param listenerModel the listener init-form template resolved via
     *                      {@code ListenerUtil#getListenerModelByName} for the connector's declared
     *                      listener class; {@code null} if that resolution failed (the listener
     *                      choice still renders, just with no init params beyond its name)
     * @param id            the catalog identifier to stamp on the result (caller's choice; this class
     *                      has no catalog of its own)
     * @param displayName   the connector's display name (e.g. from {@code TriggerMetadataResolver})
     * @param icon          the connector's icon URL (e.g. from {@code TriggerMetadataResolver})
     * @param kind          the entry-point kind bucket (e.g. {@code event}/{@code file}/{@code http})
     * @param orgName       the connector's organization
     * @param packageName   the connector's package name
     * @param moduleName    the connector's module name
     * @param version       the connector's version
     * @return the synthesized model, or {@link Optional#empty()} if the authoring model declares no
     *     listeners or no service types (a malformed/empty document nothing can be built from)
     */
    public static Optional<TriggerModel> synthesize(TriggerAuthoringModel authoring, TriggerLibraryFacts facts,
                                                     Listener listenerModel,
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
        ConnectorIdentity identity = new ConnectorIdentity(orgName, packageName, moduleName, version);

        TriggerLibraryFacts.Listener listenerFacts = findListener(authoring.listeners().get(0), facts);
        Map<String, TriggerModel.Property> initProperties = new LinkedHashMap<>();
        buildListenerChoice(listenerFacts, listenerModel, moduleName, initProperties);
        buildInitServiceAnnotations(primary, authoring, facts, identity, initProperties);
        buildIdentifierField(primary, initProperties);
        if (multiType) {
            initProperties.put(SERVICE_TYPE_KEY, buildServiceTypeSelector(serviceTypes));
        }

        List<TriggerModel.ServiceTypeModel> serviceTypeModels = new ArrayList<>();
        for (int i = 0; i < serviceTypes.size(); i++) {
            AuthoringServiceType st = serviceTypes.get(i);
            serviceTypeModels.add(buildServiceType(st, facts, authoring, identity, i == 0, multiType));
        }

        String listenerKind = primary.multipleListenersAllowed()
                ? "MULTIPLE_SELECT_LISTENER" : "SINGLE_SELECT_LISTENER";

        return Optional.of(new TriggerModel(
                SCHEMA_VERSION, id, displayName, "", orgName, packageName, moduleName, version,
                kind, icon, kind, listenerKind, initProperties, serviceTypeModels, List.of(), List.of(), null));
    }

    /** The connector's own coordinates, threaded to wherever a same-module type/annotation needs qualifying. */
    private record ConnectorIdentity(String orgName, String packageName, String moduleName, String version) {
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

    private static TriggerModel.Codedata cdAnnotation(String codedataType, String originalName, String moduleName,
                                                       String orgName, String packageName, boolean optional) {
        return new TriggerModel.Codedata(codedataType, null, originalName, moduleName, orgName,
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

    private static final String LISTENER_CONFIG_GROUP_KEY = "listenerConfig";

    /**
     * Builds the {@code listener} CHOICE (create-new / use-existing) per the {@code generate-trigger-model}
     * skill's default: a connector-shipped model always offers both branches unless a developer directive
     * says otherwise -- this synthesizer has no such directive channel, so it always includes the choice.
     *
     * <p>Every create-new field -- the listener name plus each init param, one property each -- is
     * nested inside one {@code listenerConfig} {@code GROUP_SECTION} (a single flat level, not
     * recursively nested groups), matching how a connector with many init params (e.g. SMB's dozen
     * listener config fields) should render: one titled box, not a long unlabelled list of fields
     * bleeding directly into the "Create New Listener" choice branch. Each field's actual widget
     * (record editor, number, text, ...) is never rebuilt here -- it is looked up by name from
     * {@code listenerModel}, already correctly resolved by {@code ListenerUtil#getListenerModelByName}
     * (see {@link #enrichListenerParam}). {@code listenerFacts} supplies only the piece that utility's
     * generic result cannot: the <b>structure</b> needed to assign correct {@code argType}/position
     * codedata -- see {@link #walkListenerParams}.
     */
    private static void buildListenerChoice(TriggerLibraryFacts.Listener listenerFacts, Listener listenerModel,
                                            String moduleName, Map<String, TriggerModel.Property> initProperties) {
        Map<String, TriggerModel.Property> groupProps = new LinkedHashMap<>();
        groupProps.put(LISTENER_VAR_NAME_KEY, listenerVarNameProperty(moduleName));
        if (listenerFacts != null && listenerModel != null && listenerModel.getProperties() != null) {
            walkListenerParams(listenerFacts.initParams(), listenerModel, 1, groupProps);
        }
        TriggerModel.Property configGroup = groupSectionProperty("Listener Configuration",
                "Configure the listener.", groupProps);

        Map<String, TriggerModel.Property> createNewProps = new LinkedHashMap<>();
        createNewProps.put(LISTENER_CONFIG_GROUP_KEY, configGroup);
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

    private static TriggerModel.Property groupSectionProperty(String label, String description,
                                                              Map<String, TriggerModel.Property> properties) {
        TriggerModel.PropertyType type = new TriggerModel.PropertyType(
                "GROUP_SECTION", true, null, null, null, null, null, null);
        return new TriggerModel.Property(
                new TriggerModel.Metadata(label, description, null, null, null, null, null, null),
                true, true, false, false, null, null, List.of(type), null, null, properties, null, null);
    }

    private static TriggerModel.Property listenerVarNameProperty(String moduleName) {
        TriggerModel.PropertyType type = new TriggerModel.PropertyType(
                "IDENTIFIER", true, moduleName + ":Listener", null, null, null, null, null);
        return new TriggerModel.Property(
                new TriggerModel.Metadata("Listener Name", "Provide a name for the listener being created", null,
                        null, null, null, null, null),
                true, true, false, false, null, moduleName + "Listener", List.of(type), null, null, null,
                cdType("LISTENER_VAR_NAME"), null);
    }

    /**
     * Walks the listener's init params in declaration order, assigning each the {@code argType}/
     * position codedata {@link SchemaDrivenSourceGenerator} needs to place it as a constructor
     * argument -- while sourcing every field's actual widget from {@code listenerModel} (see
     * {@link #enrichListenerParam}), never rebuilding one:
     *
     * <ul>
     *   <li>An {@code INCLUDED_RECORD} {@code *Type} spread consumes <b>no</b> positional slot of its
     *       own -- {@code ListenerUtil} already flattens its fields into independently-named
     *       top-level entries in {@code listenerModel}, each looked up by field name and given
     *       {@code LISTENER_PARAM_INCLUDED_FIELD}/{@code _INCLUDED_DEFAULTABLE_FIELD} with no
     *       position (they are named args, not one record literal).</li>
     *   <li>Any other param (scalar, union, or a plain non-spread record type like Google Calendar's
     *       {@code ListenerConfig listenerConfig}) occupies exactly one positional/named slot --
     *       looked up by its own name and given {@code LISTENER_PARAM_REQUIRED} at the current
     *       position, which is then incremented.</li>
     * </ul>
     *
     * A param whose name has no corresponding entry in {@code listenerModel} (should not happen in
     * practice -- {@code ListenerUtil} derives its properties from the same compiled listener class)
     * is skipped defensively rather than emitting a broken field.
     */
    private static void walkListenerParams(List<TriggerLibraryFacts.Param> initParams, Listener listenerModel,
                                           int startPosition, Map<String, TriggerModel.Property> createNewProps) {
        int position = startPosition;
        for (TriggerLibraryFacts.Param param : initParams) {
            if ("INCLUDED_RECORD".equals(param.kind())) {
                for (TriggerLibraryFacts.Param field : param.fields()) {
                    Value fieldValue = listenerModel.getProperty(field.name());
                    if (fieldValue == null) {
                        continue;
                    }
                    String argType = field.optional()
                            ? "LISTENER_PARAM_INCLUDED_DEFAULTABLE_FIELD" : "LISTENER_PARAM_INCLUDED_FIELD";
                    createNewProps.put(field.name(), enrichListenerParam(fieldValue, argType, null));
                }
                // An included-record spread contributes no positional slot of its own; position is
                // only ever consumed by a genuine top-level parameter (see the other branch below).
                continue;
            }
            Value paramValue = listenerModel.getProperty(param.name());
            if (paramValue == null) {
                continue;
            }
            createNewProps.put(param.name(), enrichListenerParam(paramValue, "LISTENER_PARAM_REQUIRED", position));
            position++;
        }
    }

    /**
     * Converts one listener init-param {@link Value} (from {@code ListenerUtil.getListenerModelByName})
     * into a {@link TriggerModel.Property} via a JSON round-trip -- the two classes are designed as
     * JSON-shape-compatible siblings throughout this codebase (the same pattern
     * {@code ConnectorModelReader#buildServiceInitModelFromJson} already relies on) -- keeping its
     * already-correct {@code metadata}/{@code types}/{@code placeholder}/{@code value}/{@code optional}
     * exactly as resolved (never rebuilt), and only replacing {@code codedata} and {@code advanced}:
     * the generic {@code LISTENER_INIT_PARAM}/{@code originalName} pair that utility stamps (meaningful
     * for reading an already-declared listener back) is swapped for the {@code argType}/{@code position}
     * pair {@link SchemaDrivenSourceGenerator} actually reads to place this value as a listener
     * constructor argument; {@code advanced} is forced to {@code false} since this codebase's real
     * precedent (e.g. HubSpot's {@code listenOn}) keeps every listener init param visible by default,
     * never tucked behind an "Advanced" toggle.
     */
    private static TriggerModel.Property enrichListenerParam(Value value, String argType, Integer position) {
        TriggerModel.Property property = GSON.fromJson(GSON.toJsonTree(value), TriggerModel.Property.class);
        return new TriggerModel.Property(property.metadata(), property.enabled(), property.editable(),
                property.optional(), false, property.placeholder(), property.value(),
                property.types(), property.items(), property.choices(), property.properties(),
                cdListenerParam(argType, position, null), property.validations());
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
                                                                  ConnectorIdentity identity, boolean isFirst,
                                                                  boolean multiType) {
        String moduleName = identity.moduleName();
        String typeName = serviceType.type().name();
        Map<String, TriggerModel.Property> properties = buildServiceAnnotations(serviceType, authoring, facts,
                identity);

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
                schemaFunctions.add(buildFunctionFromAuthoring(option, handlers.addMode(), authoring, moduleName,
                        facts, identity));
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

    /**
     * The introspected listener whose simple name matches the authoring schema's declared listener
     * type -- e.g. {@code {"type": {"name": "Listener"}}} matches an introspected {@code "Listener"}
     * class. Falls back to the first introspected listener when there is no exact name match (a
     * single-listener connector's own name may differ slightly in casing/spelling from the schema).
     */
    private static TriggerLibraryFacts.Listener findListener(TriggerAuthoringModel.Listener listener,
                                                              TriggerLibraryFacts facts) {
        if (facts.listeners() == null || facts.listeners().isEmpty()) {
            return null;
        }
        String name = listener.type().name();
        int colon = name.lastIndexOf(':');
        String simpleName = colon < 0 ? name : name.substring(colon + 1);
        for (TriggerLibraryFacts.Listener candidate : facts.listeners()) {
            if (candidate.type().equals(simpleName)) {
                return candidate;
            }
        }
        return facts.listeners().get(0);
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
        TriggerModel.Property nameProperty = identifierProperty(param.name(), true);
        return new TriggerModel.Parameter(
                new TriggerModel.Metadata(humanize(param.name()),
                        param.doc() == null || param.doc().isBlank() ? null : param.doc(), null, null, null, null,
                        null, null),
                "REQUIRED", typeProperty, nameProperty, null, null, null, null, true, false, param.optional(),
                false, false, cdType("FUNCTION_PARAM"), null);
    }

    private static TriggerModel.Property identifierProperty(String name, boolean editable) {
        TriggerModel.PropertyType type = new TriggerModel.PropertyType(
                "IDENTIFIER", true, null, null, null, null, null, null);
        return new TriggerModel.Property(
                new TriggerModel.Metadata(name, null, null, null, null, null, null, null),
                true, editable, false, false, name, name, List.of(type), null, null, null, null, null);
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
                                                                         String moduleName,
                                                                         TriggerLibraryFacts facts,
                                                                         ConnectorIdentity identity) {
        boolean many = AuthoringServiceType.Handlers.ADD_MODE_MANY.equals(addMode);
        boolean required = "required".equals(option.presence());

        List<TriggerModel.Parameter> parameters = new ArrayList<>();
        if (option.params() != null) {
            for (AuthoringServiceType.Param param : option.params()) {
                parameters.add(buildParameterFromAuthoring(param, authoring, moduleName));
            }
        }
        TriggerModel.ReturnType returnType = buildReturnTypeFromRefs(option.returns(), moduleName);
        Map<String, TriggerModel.Property> properties = buildFunctionAnnotations(option.annotations(), authoring,
                facts, identity);

        String name = many ? "" : option.name();
        String label = many ? "Handler" : option.name();
        return new TriggerModel.FunctionModel(
                new TriggerModel.Metadata(label, "The `" + option.name() + "` handler.", null, null, null,
                        many ? "Add Handler" : null, null, null),
                name, many, null, option.kind() == null ? null : option.kind().toUpperCase(Locale.ROOT),
                null, option.kind() == null ? null : List.of(option.kind()), null, null, false, true, !required,
                false, null, null, null, parameters, null, properties, returnType,
                cdFunction(option.name(), moduleName), null);
    }

    /**
     * Renders each of a handler's {@code attachPoint: "function"} annotations (referenced by id from
     * {@code HandlerOption#annotations()}) the same way a service-level one renders (see
     * {@link #buildAnnotationProperty}) -- one whole-value {@code RECORD_MAP_EXPRESSION} field per
     * annotation, keyed by its schema id, added to the handler's own {@code properties} -- so a
     * connector-declared handler annotation (e.g. a per-handler config record) is editable in the add/
     * update-handler form and emitted above the function by {@code AnnotationEmitter#annotationsOf}.
     */
    private static Map<String, TriggerModel.Property> buildFunctionAnnotations(List<String> annotationIds,
                                                                                TriggerAuthoringModel authoring,
                                                                                TriggerLibraryFacts facts,
                                                                                ConnectorIdentity identity) {
        Map<String, TriggerModel.Property> properties = new LinkedHashMap<>();
        if (annotationIds == null || annotationIds.isEmpty() || authoring.annotations() == null) {
            return properties;
        }
        for (String id : annotationIds) {
            findAnnotationDeclaration(id, authoring)
                    .ifPresent(annotation -> properties.put(id,
                            buildAnnotationProperty(annotation, facts, identity, "ANNOTATION_ATTACHMENT")));
        }
        return properties;
    }

    private static Optional<AuthoringAnnotation> findAnnotationDeclaration(String id, TriggerAuthoringModel authoring) {
        return authoring.annotations().stream().filter(a -> id.equals(a.id())).findFirst();
    }

    /**
     * Builds one handler parameter. A non-data-bound, <b>optional</b>, <b>named</b> parameter (e.g.
     * FTP's {@code caller}/{@code fileInfo}, Kafka's {@code caller}) is a framework-injected object the
     * handler may opt into, not a value the user supplies -- rendered as a {@code FLAG} checkbox with a
     * fixed (non-editable) identifier, matching the real hand-authored convention for this exact shape
     * (see the {@code generate-trigger-model} skill's "Framework param (caller/context)" rule). Every
     * other parameter (required, data-bound, or unnamed/positional) renders as a normal typed field.
     */
    private static TriggerModel.Parameter buildParameterFromAuthoring(AuthoringServiceType.Param param,
                                                                      TriggerAuthoringModel authoring,
                                                                      String moduleName) {
        boolean optional = "optional".equals(param.presence());
        String name = param.name() == null ? "" : param.name();
        AuthoringDataBindingRule bindingRule = param.dataBinding() == null ? null
                : findDataBindingRule(param.dataBinding(), authoring);

        if (bindingRule == null && optional && !name.isEmpty()) {
            return buildFlagParameter(name, typeRefName(param.type(), moduleName));
        }

        String typeName = typeRefName(param.type(), moduleName);
        TriggerModel.Property typeProperty = bindingRule == null
                ? plainTypeProperty(typeName)
                : dataBindingTypeProperty(bindingRule, typeName, moduleName, name.isEmpty() ? "value" : name);
        TriggerModel.Property nameProperty = identifierProperty(name.isEmpty() ? "value" : name, true);
        String kind = bindingRule != null ? "DATA_BINDING" : (optional ? "OPTIONAL" : "REQUIRED");
        return new TriggerModel.Parameter(
                new TriggerModel.Metadata(humanize(name.isEmpty() ? "value" : name), null, null, null, null, null,
                        null, null),
                kind, typeProperty, nameProperty, null, null, null, null, true, true,
                optional, false, false, cdType("FUNCTION_PARAM"), null);
    }

    /**
     * A framework-injected opt-in parameter (e.g. {@code Caller}, {@code FileInfo}): a checkbox to
     * include it (type {@code FLAG}, unchecked/{@code false} by default) plus a fixed, non-editable
     * identifier. Not included by default and tucked behind "advanced" -- matching Kafka's real
     * {@code caller} parameter, the corpus's only concrete precedent for this shape.
     */
    private static TriggerModel.Parameter buildFlagParameter(String name, String qualifiedType) {
        String label = humanize(name);
        TriggerModel.PropertyType flagType = new TriggerModel.PropertyType(
                "FLAG", true, qualifiedType, null, null, null, null, null);
        TriggerModel.Property typeProperty = new TriggerModel.Property(
                new TriggerModel.Metadata("Include " + label,
                        "Tick to include the " + label.toLowerCase(Locale.ROOT) + " parameter in the handler "
                                + "signature.", null, null, null, null, null, null),
                true, true, true, false, null, false, List.of(flagType), null, null, null, cd(), null);
        TriggerModel.Property nameProperty = identifierProperty(name, false);
        return new TriggerModel.Parameter(
                new TriggerModel.Metadata(label, "The " + label.toLowerCase(Locale.ROOT) + " object.", null, null,
                        null, null, null, null),
                "OPTIONAL", typeProperty, nameProperty, null, null, null, null, false, true, true, true, false,
                cdType("FUNCTION_PARAM"), null);
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
     * parameter, per {@code AuthoringDataBindingRule}'s {@code direct}/{@code includedRecord} modes.
     * When the rule ALSO declares a {@code streamable} mode (e.g. a CSV/array binding that may be read
     * either as {@code T[]} or {@code stream<T, error?>}), the payload is nested one level under a
     * {@code COMPLEX_PAYLOAD} container alongside a {@code stream} {@code PAYLOAD_MODIFIER} toggle --
     * the same composed shape FTP's real {@code onFileCsv} uses (see {@link PayloadComposer}) -- so a
     * connector whose data-binding rule models both cardinalities gets the identical large-file
     * streaming UX for free, generically, without per-connector code.
     */
    private static TriggerModel.Property dataBindingTypeProperty(AuthoringDataBindingRule rule, String typeName,
                                                                  String moduleName, String paramName) {
        Optional<AuthoringDataBindingRule.SupportedMode> includedRecord = rule.supportedModes().stream()
                .filter(m -> AuthoringDataBindingRule.SupportedMode.MODE_INCLUDED_RECORD.equals(m.mode()))
                .findFirst();
        Optional<AuthoringDataBindingRule.SupportedMode> direct = rule.supportedModes().stream()
                .filter(m -> AuthoringDataBindingRule.SupportedMode.MODE_DIRECT.equals(m.mode()))
                .findFirst();
        Optional<AuthoringDataBindingRule.SupportedMode> streamable = rule.supportedModes().stream()
                .filter(m -> AuthoringDataBindingRule.SupportedMode.MODE_STREAMABLE.equals(m.mode()))
                .findFirst();

        String cdType = includedRecord.isPresent() ? "PAYLOAD_TYPE_INCLUDED_RECORD" : "PAYLOAD_TYPE";
        String defaultType;
        String template = rule.cardinality() != null
                && AuthoringDataBindingRule.CARDINALITY_ARRAY.equals(rule.cardinality()) ? "{{type}}[]" : "{{type}}";
        String field = null;
        String typeConstraint = null;
        if (includedRecord.isPresent()) {
            AuthoringDataBindingRule.SupportedMode mode = includedRecord.get();
            defaultType = mode.includes() == null ? typeName : qualifyTypeRef(mode.includes(), moduleName);
            field = mode.bindableFields() == null || mode.bindableFields().isEmpty()
                    ? null : mode.bindableFields().get(0);
        } else if (direct.isPresent() && !direct.get().typeConstraint().isEmpty()) {
            defaultType = qualifyTypeRef(direct.get().typeConstraint().get(0), moduleName);
            typeConstraint = defaultType;
        } else {
            defaultType = typeName;
        }

        TriggerModel.PropertyType propertyType = new TriggerModel.PropertyType(
                "PAYLOAD_TYPE", true, null, null, null, null,
                List.of(new TriggerModel.PayloadFormat(List.of("schema", "browse", "json", "xml"), "json")), null);
        TriggerModel.Property payload = new TriggerModel.Property(
                new TriggerModel.Metadata("Payload", "The shape of the received payload", null, null, null, null,
                        null, null),
                true, true, false, false, null, "", List.of(propertyType), null, null, null,
                cdPayload(cdType, defaultType, template, field, typeConstraint), null);

        if (streamable.isEmpty()) {
            return payload;
        }
        Map<String, TriggerModel.Property> children = new LinkedHashMap<>();
        children.put("payload", payload);
        children.put("stream", buildStreamModifierProperty(paramName));
        TriggerModel.PropertyType complexType = new TriggerModel.PropertyType(
                "COMPLEX_PAYLOAD", true, null, null, null, null, null, null);
        return new TriggerModel.Property(payload.metadata(), true, true, false, false, null, "",
                List.of(complexType), null, null, children, cd(), null);
    }

    /**
     * The {@code stream} toggle FTP's {@code onFileCsv} uses to switch a bound payload's wrap from the
     * base array template ({@code T[]}) to {@code stream<T, error?>} for large files -- unchecked by
     * default, so the array form remains the default composition.
     */
    private static TriggerModel.Property buildStreamModifierProperty(String targetParam) {
        String template = "stream<{{type}}, error?>";
        TriggerModel.PropertyType flagType = new TriggerModel.PropertyType(
                "FLAG", true, null, null, null, template, null, null);
        TriggerModel.Codedata modifierCodedata = new TriggerModel.Codedata(
                "PAYLOAD_MODIFIER", null, null, null, null, null, null, null, null, null, null, null, null,
                template, "stream", List.of("base"), targetParam, null, null, null, null, null, null, null, null);
        return new TriggerModel.Property(
                new TriggerModel.Metadata("Stream (Large Files)", "Process the file content in chunks", null,
                        null, null, null, null, null),
                true, true, false, false, null, false, List.of(flagType), null, null, null, modifierCodedata, null);
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

    private static TriggerModel.ReturnType buildReturnTypeFromRefs(List<TypeRef> refs, String moduleName) {
        if (refs == null || refs.isEmpty()) {
            return buildReturnType(null, false);
        }
        String joined = refs.stream().map(r -> qualifyTypeRef(r, moduleName)).collect(Collectors.joining("|"));
        boolean hasError = joined.contains("error");
        return buildReturnType(joined, hasError);
    }

    // ---- service-level annotations ------------------------------------------------

    /**
     * Every {@code service}-attached annotation applicable to {@code serviceType} -- shared by the
     * init-form field ({@link #buildInitServiceAnnotations}) and the service-type's own view/update
     * properties ({@link #buildServiceAnnotations}), so the two stay in lockstep by construction.
     */
    private static List<AuthoringAnnotation> applicableServiceAnnotations(AuthoringServiceType serviceType,
                                                                          TriggerAuthoringModel authoring) {
        List<AuthoringAnnotation> applicable = new ArrayList<>();
        if (authoring.annotations() == null) {
            return applicable;
        }
        for (AuthoringAnnotation annotation : authoring.annotations()) {
            if (!AuthoringAnnotation.ATTACH_POINT_SERVICE.equals(annotation.attachPoint())) {
                continue;
            }
            if (annotation.appliesTo() != null && !annotation.appliesTo().contains(serviceType.id())) {
                continue;
            }
            applicable.add(annotation);
        }
        return applicable;
    }

    /**
     * Renders every {@code service}-attached annotation applicable to {@code serviceType} as a single
     * {@code RECORD_MAP_EXPRESSION} field the user fills as one expression -- the same fidelity tier
     * {@code ServiceModelUtils#getAnnotationAttachmentProperty} already uses for the non-schema-driven
     * default builders (not a granular per-field {@code MAPPING_CONSTRUCTOR} tree; see the class javadoc).
     * These live on the service type's own {@code properties} -- consulted by the view/update-service
     * path (e.g. {@code Utils#getAnnotationEdits(Service, ModulePartNode)}), distinct from the add-time
     * copy {@link #buildInitServiceAnnotations} places directly in the init form.
     */
    private static Map<String, TriggerModel.Property> buildServiceAnnotations(AuthoringServiceType serviceType,
                                                                              TriggerAuthoringModel authoring,
                                                                              TriggerLibraryFacts facts,
                                                                              ConnectorIdentity identity) {
        Map<String, TriggerModel.Property> properties = new LinkedHashMap<>();
        for (AuthoringAnnotation annotation : applicableServiceAnnotations(serviceType, authoring)) {
            properties.put(annotation.id(), buildAnnotationProperty(annotation, facts, identity,
                    "ANNOTATION_ATTACHMENT"));
        }
        return properties;
    }

    /**
     * Places a copy of every applicable service-level annotation directly in the add-trigger init form
     * (right after the listener choice), keyed by its schema id -- e.g. SMB's {@code serviceConfig} --
     * so it is visible and fillable at creation time, not only once a service already exists. Uses the
     * {@code SERVICE_ANNOTATION} codedata role (rather than {@code ANNOTATION_ATTACHMENT}), the role
     * {@code SchemaDrivenSourceGenerator#buildServiceAnnotations} actually scans the filled init form
     * for at add-time.
     */
    private static void buildInitServiceAnnotations(AuthoringServiceType serviceType, TriggerAuthoringModel authoring,
                                                    TriggerLibraryFacts facts, ConnectorIdentity identity,
                                                    Map<String, TriggerModel.Property> initProperties) {
        for (AuthoringAnnotation annotation : applicableServiceAnnotations(serviceType, authoring)) {
            initProperties.put(annotation.id(), buildAnnotationProperty(annotation, facts, identity,
                    "SERVICE_ANNOTATION"));
        }
    }

    /**
     * The declared {@code type.name} in {@code trigger-authoring.json} references the annotation's own
     * introspected name (e.g. {@code "ServiceConfig"} -- {@link TriggerLibraryFacts.Annotation#name()}
     * is literally {@code AnnotationSymbol.getName()}), NOT its backing record type's name (which can
     * legitimately differ, e.g. SMB's {@code annotation SmbServiceConfig ServiceConfig on service;}).
     * Looking the fact up by that name resolves the record's real field list (for the value skeleton)
     * and its real package coordinates (for {@code typeMembers}) -- {@code buildAnnotationProperty}
     * degrades to an empty skeleton and same-module coordinates when introspection has nothing (should
     * not happen for a genuinely declared annotation).
     */
    private static TriggerLibraryFacts.Annotation findAnnotationFacts(String name, TriggerLibraryFacts facts) {
        if (facts.annotations() == null) {
            return null;
        }
        for (TriggerLibraryFacts.Annotation candidate : facts.annotations()) {
            if (candidate.name().equals(name)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Builds one annotation attachment field -- a single {@code RECORD_MAP_EXPRESSION} the user fills
     * as one expression -- shared by every attachment point (service-level init/view, function-level
     * handler); only the emitted {@code codedata.type} differs per caller, since each consumer scans for
     * its own role ({@code SERVICE_ANNOTATION} for the init form, {@code ANNOTATION_ATTACHMENT}
     * elsewhere).
     */
    private static TriggerModel.Property buildAnnotationProperty(AuthoringAnnotation annotation,
                                                                  TriggerLibraryFacts facts,
                                                                  ConnectorIdentity identity,
                                                                  String codedataType) {
        String annotationName = annotation.type().name();
        boolean crossModule = annotation.type().packageInfo() != null;
        String pkgOrg = crossModule ? annotation.type().packageInfo().org() : identity.orgName();
        String pkgName = crossModule ? annotation.type().packageInfo().packageName() : identity.packageName();
        String pkgModule = crossModule ? annotation.type().packageInfo().moduleName() : identity.moduleName();
        String pkgVersion = crossModule ? annotation.type().packageInfo().version() : identity.version();
        String packageInfoStr = pkgOrg + ":" + pkgName + ":" + pkgVersion;

        TriggerLibraryFacts.Annotation facted = findAnnotationFacts(annotationName, facts);
        String recordTypeName = facted != null && facted.typeConstraint() != null
                ? simpleName(facted.typeConstraint()) : annotationName;

        TriggerModel.TypeMember member = new TriggerModel.TypeMember(
                recordTypeName, packageInfoStr, pkgName, "RECORD_TYPE", false);
        TriggerModel.PropertyType propertyType = new TriggerModel.PropertyType(
                "RECORD_MAP_EXPRESSION", true, aliasOf(pkgModule) + ":" + recordTypeName, null, List.of(member),
                null, null, null);
        boolean optional = AuthoringAnnotation.PRESENCE_OPTIONAL.equals(annotation.presence());
        // Deliberately no per-field skeleton (e.g. "{topic: \"\"}") -- the LS does not need to guess
        // field defaults; an empty "{}" record is enough for the user to fill in via the record editor.
        return new TriggerModel.Property(
                new TriggerModel.Metadata(humanize(annotation.id()), "Configuration for this service", null,
                        null, null, null, null, null),
                true, true, optional, false, "{}", "{}", List.of(propertyType), null, null, null,
                cdAnnotation(codedataType, annotationName, pkgModule, pkgOrg, pkgName, optional), null);
    }

    // ---- shared helpers ------------------------------------------------------------

    /**
     * Joins a union of {@link TypeRef}s into one type-signature string, qualifying each member (see
     * {@link #qualifyTypeRef}) before joining -- so the result is always ready to emit as-is.
     */
    private static String typeRefName(List<TypeRef> refs, String moduleName) {
        if (refs == null || refs.isEmpty()) {
            return "anydata";
        }
        return refs.stream().map(r -> qualifyTypeRef(r, moduleName))
                .collect(Collectors.joining("|"));
    }

    /**
     * Qualifies a {@link TypeRef} for emission into the <b>user's</b> file: {@code trigger-authoring.json}
     * never restates a same-module reference's prefix ({@code packageInfo: null} means "same module as
     * this connector's own types" -- see {@link TypeRef}'s own javadoc), but the generated source lands
     * in a different file where the connector is only an imported dependency, so even the connector's
     * own types need a module prefix to be valid Ballerina (matching how {@code TriggerLibraryIntrospector}
     * already renders introspected facts with a prefix, never bare). A builtin/composite type signature
     * (lowercase-leading, e.g. {@code string}, {@code error}, {@code ()}, {@code record {}}) is never
     * qualified; heuristically, a Ballerina user-defined type name always starts with an uppercase
     * letter by convention, which is what distinguishes the two cases here.
     */
    private static String qualifyTypeRef(TypeRef ref, String moduleName) {
        String name = ref.name();
        if (name == null || name.isEmpty() || name.indexOf(':') >= 0 || !Character.isUpperCase(name.charAt(0))) {
            return name;
        }
        String prefixModule = ref.packageInfo() != null ? ref.packageInfo().moduleName() : moduleName;
        return aliasOf(prefixModule) + ":" + name;
    }

    /** The import alias of a (possibly dotted) module name -- its last {@code .}-separated segment. */
    private static String aliasOf(String moduleName) {
        int lastDot = moduleName.lastIndexOf('.');
        return lastDot < 0 ? moduleName : moduleName.substring(lastDot + 1);
    }

    /** The unqualified suffix of a module-qualified name, e.g. {@code "smb:SmbServiceConfig" -> "SmbServiceConfig"}. */
    private static String simpleName(String qualified) {
        int colon = qualified.lastIndexOf(':');
        return colon < 0 ? qualified : qualified.substring(colon + 1);
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
