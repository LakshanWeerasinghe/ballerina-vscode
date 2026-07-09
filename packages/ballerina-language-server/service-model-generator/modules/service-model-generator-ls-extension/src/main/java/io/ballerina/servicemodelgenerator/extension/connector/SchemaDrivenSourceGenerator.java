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

import io.ballerina.compiler.syntax.tree.ModulePartNode;
import io.ballerina.servicemodelgenerator.extension.connector.model.TriggerModel;
import io.ballerina.servicemodelgenerator.extension.model.Codedata;
import io.ballerina.servicemodelgenerator.extension.model.PropertyType;
import io.ballerina.servicemodelgenerator.extension.model.ServiceInitModel;
import io.ballerina.servicemodelgenerator.extension.model.Value;
import io.ballerina.servicemodelgenerator.extension.util.Constants;
import io.ballerina.servicemodelgenerator.extension.util.Utils;
import org.eclipse.lsp4j.TextEdit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import static io.ballerina.servicemodelgenerator.extension.model.ServiceInitModel.KEY_EXISTING_LISTENER;
import static io.ballerina.servicemodelgenerator.extension.util.Constants.ARG_TYPE_LISTENER_PARAM_CONFIG_FIELD;
import static io.ballerina.servicemodelgenerator.extension.util.Constants.ARG_TYPE_LISTENER_PARAM_INCLUDED_DEFAULTABLE_FIELD;
import static io.ballerina.servicemodelgenerator.extension.util.Constants.ARG_TYPE_LISTENER_PARAM_INCLUDED_FIELD;
import static io.ballerina.servicemodelgenerator.extension.util.Constants.ARG_TYPE_LISTENER_PARAM_REQUIRED;
import static io.ballerina.servicemodelgenerator.extension.util.Constants.ARG_TYPE_LISTENER_VAR_NAME;
import static io.ballerina.servicemodelgenerator.extension.util.Constants.ARG_TYPE_SERVICE_TYPE_DESCRIPTOR;
import static io.ballerina.servicemodelgenerator.extension.util.Constants.CLOSE_BRACE;
import static io.ballerina.servicemodelgenerator.extension.util.Constants.COLON;
import static io.ballerina.servicemodelgenerator.extension.util.Constants.NEW_LINE;
import static io.ballerina.servicemodelgenerator.extension.util.Constants.NEW_LINE_WITH_TAB;
import static io.ballerina.servicemodelgenerator.extension.util.Constants.ON;
import static io.ballerina.servicemodelgenerator.extension.util.Constants.OPEN_BRACE;
import static io.ballerina.servicemodelgenerator.extension.util.Constants.REMOTE;
import static io.ballerina.servicemodelgenerator.extension.util.Constants.RESOURCE;
import static io.ballerina.servicemodelgenerator.extension.util.Constants.SERVICE;
import static io.ballerina.servicemodelgenerator.extension.util.Constants.SPACE;
import static io.ballerina.servicemodelgenerator.extension.util.Constants.TAB;
import static io.ballerina.servicemodelgenerator.extension.util.Constants.TWO_NEW_LINES;
import static io.ballerina.servicemodelgenerator.extension.util.Constants.TYPE_SERVICE;
import static io.ballerina.servicemodelgenerator.extension.util.ServiceModelUtils.getProtocol;

/**
 * Generates Ballerina source (text edits) for adding a connector-shipped trigger/service, driven
 * entirely by the {@code codedata} on the connector models — no per-connector branches.
 *
 * <p>The creation form is walked recursively so that the same CHOICE / GROUP_SECTION nesting the
 * hardcoded builders handle (create-new vs use-existing, grouped listener configuration) is handled
 * generically:
 * <ul>
 *   <li>a <b>CHOICE</b> ({@code configureListener}) descends into its enabled (or first) branch;</li>
 *   <li>a <b>GROUP_SECTION</b> that carries a listener {@code argType} becomes one record argument
 *       assembled from its {@code CONFIG_FIELD} children, while non-config children (e.g. a nested
 *       positional param, the listener variable name) are still collected as their own args — this
 *       is a legacy shape kept for backward compatibility with already-shipped manifests;</li>
 *   <li>leaves are placed by {@code argType} — {@code REQUIRED} positional (ordered by
 *       {@code position}), {@code INCLUDED_FIELD} as {@code name = value} (a multi-segment
 *       {@code path}, e.g. {@code auth.credentials.username} — a record- or union-typed included
 *       field modeled as a nested CHOICE/GROUP_SECTION — nests into a record literal assigned to
 *       the top-level segment: {@code auth = {credentials: {username: ...}}}), {@code CONFIG_FIELD}
 *       into a record at its {@code position} slot (flat {@code CONFIG_FIELD} siblings sharing a
 *       {@code position} — the current, GROUP_SECTION-free shape for a record-typed listener
 *       param — are merged into one record literal there), {@code valueQualifier} module-prefixes
 *       enum-like values.</li>
 * </ul>
 * The service type descriptor is resolved from the {@code SERVICE_TYPE_DESCRIPTOR} field wherever it
 * sits (not by property key). Output stays format-compatible with
 * {@code AbstractServiceBuilder.getServiceDeclarationEdits}.
 *
 * @since 1.8.0
 */
public final class SchemaDrivenSourceGenerator {

    private static final String LISTENER = "listener";
    private static final String LISTENER_TYPE = "Listener";
    private static final String NEW = "new";
    private static final String ERROR = "error";
    private static final String LISTENER_VAR_NAME_KIND = "LISTENER_VAR_NAME";

    private SchemaDrivenSourceGenerator() {
    }

    /**
     * Builds the {@code listener <proto>:Listener <var> = new (...);} declaration from the filled
     * creation model (CHOICE/GROUP_SECTION aware).
     */
    public static String buildListenerDeclaration(ServiceInitModel creationModel) {
        return renderListenerDeclaration(getProtocol(creationModel.getModuleName()),
                collectListenerArgs(creationModel));
    }

    /** The {@code \nimport <org>/<module>;\n} statement for the connector. */
    public static String buildImport(ServiceInitModel creationModel) {
        return Utils.getImportStmt(creationModel.getOrgName(), creationModel.getModuleName());
    }

    // ==================================================================
    // Unified TriggerModel path. The listener-arg walk below is shared across service descriptor
    // resolution; the service descriptor and function block are sourced from the TriggerModel.
    // ==================================================================

    /** {@code addServiceAndListener} for the unified model: import (if missing) + listener/service block. */
    public static Map<String, List<TextEdit>> buildAddServiceEditsForTrigger(ServiceInitModel filledInitForm,
                                                                   TriggerModel triggerModel,
                                                                   ModulePartNode rootNode, String filePath) {
        List<TextEdit> edits = new ArrayList<>();
        String imports = buildImports(filledInitForm, triggerModel, rootNode);
        if (!imports.isEmpty()) {
            edits.add(new TextEdit(Utils.toRange(rootNode.lineRange().startLine()), imports));
        }
        edits.add(new TextEdit(Utils.toRange(rootNode.lineRange().endLine()),
                buildServiceBlockForTrigger(filledInitForm, triggerModel)));
        return Map.of(filePath, edits);
    }

    /**
     * The connector import plus any additional imports the model declares in {@code importStatements}
     * (each an {@code org/module} reference — e.g. a handler payload's or listener param's module such
     * as {@code ballerina/http}). Each is emitted only when not already present in the file.
     */
    private static String buildImports(ServiceInitModel filledInitForm, TriggerModel triggerModel,
                                       ModulePartNode rootNode) {
        StringBuilder imports = new StringBuilder();
        if (!Utils.importExists(rootNode, filledInitForm.getOrgName(), filledInitForm.getModuleName())) {
            imports.append(buildImport(filledInitForm));
        }
        if (triggerModel != null && triggerModel.importStatements() != null) {
            for (String moduleRef : triggerModel.importStatements()) {
                if (moduleRef == null) {
                    continue;
                }
                int slash = moduleRef.indexOf('/');
                if (slash <= 0 || slash == moduleRef.length() - 1) {
                    continue;
                }
                String org = moduleRef.substring(0, slash);
                String module = moduleRef.substring(slash + 1);
                if (!Utils.importExists(rootNode, org, module)) {
                    imports.append(Utils.getImportStmt(org, module));
                }
            }
        }
        return imports.toString();
    }

    /**
     * Full add-trigger block from the unified model: listener declaration (create-new branch only) +
     * {@code service <descriptor> on <var> { <present functions> }}. Named distinctly from the
     * two-model {@code buildServiceBlock} so a {@code null} second argument stays unambiguous.
     */
    public static String buildServiceBlockForTrigger(ServiceInitModel filledInitForm, TriggerModel triggerModel) {
        String protocol = getProtocol(filledInitForm.getModuleName());
        ListenerArgs collected = collectListenerArgs(filledInitForm);
        String descriptor = resolveServiceDescriptor(filledInitForm, triggerModel, protocol);
        List<String> functions = buildRequiredFunctionSources(filledInitForm, triggerModel);

        StringBuilder builder = new StringBuilder(NEW_LINE);
        if (collected.hasArgs()) {
            builder.append(renderListenerDeclaration(protocol, collected)).append(NEW_LINE);
        }
        for (String annotation : buildServiceAnnotations(filledInitForm)) {
            builder.append(annotation).append(NEW_LINE);
        }
        builder.append(SERVICE).append(SPACE).append(descriptor).append(SPACE).append(ON).append(SPACE)
                .append(collected.varName).append(SPACE).append(OPEN_BRACE)
                .append(NEW_LINE)
                .append(String.join(TWO_NEW_LINES, functions)).append(NEW_LINE)
                .append(CLOSE_BRACE).append(NEW_LINE);
        return builder.toString();
    }

    /**
     * The service-level annotation attachments (e.g. {@code @rabbitmq:ServiceConfig {...}}), built
     * entirely from {@code SERVICE_ANNOTATION} fields present in the filled {@code ServiceInitModel}
     * (the add-service init form) — the {@code TriggerModel}'s service-type properties are not
     * consulted here, since at add-time the only values available are the ones the user filled in the
     * init form (e.g. RabbitMQ's {@code queueName}). Fields are grouped by their annotation identity
     * ({@code moduleName}/{@code originalName}), so several init-form fields belonging to the same
     * annotation are merged into one {@code @module:Name {...}} attachment.
     */
    private static List<String> buildServiceAnnotations(ServiceInitModel filledInitForm) {
        Map<String, AnnotationFields> byAnnotation = new LinkedHashMap<>();
        collectAnnotationFields(filledInitForm.getProperties(), byAnnotation);
        List<String> annotations = new ArrayList<>();
        for (AnnotationFields annotation : byAnnotation.values()) {
            if (!annotation.fields.isEmpty()) {
                annotations.add(annotation.render());
            }
        }
        return annotations;
    }

    /**
     * Recursively collects {@code SERVICE_ANNOTATION} leaf fields (a {@code path} names the field)
     * from a filled form, e.g. the init form's {@code queueName}, grouping same-annotation fields
     * ({@code moduleName}/{@code originalName}) together.
     */
    private static void collectAnnotationFields(Map<String, Value> properties,
                                                Map<String, AnnotationFields> byAnnotation) {
        if (properties == null) {
            return;
        }
        for (Value field : properties.values()) {
            if (isChoice(field)) {
                Value branch = enabledOrFirstChoice(field.getChoices());
                if (branch != null) {
                    collectAnnotationFields(branch.getProperties(), byAnnotation);
                }
                continue;
            }
            Codedata codedata = field.getCodedata();
            if (codedata != null && Constants.CD_TYPE_SERVICE_ANNOTATION.equals(codedata.getType())
                    && codedata.getPath() != null && !codedata.getPath().isBlank()
                    && field.isEnabledWithValue()) {
                String rendered = qualifiedValue(field);
                if (!rendered.isEmpty()) {
                    String key = codedata.getModuleName() + COLON + codedata.getOriginalName();
                    byAnnotation.computeIfAbsent(key,
                            k -> new AnnotationFields(codedata.getModuleName(), codedata.getOriginalName()))
                            .fields.add(codedata.getPath() + ": " + rendered);
                }
            }
            if (isGroup(field)) {
                collectAnnotationFields(field.getProperties(), byAnnotation);
            }
        }
    }

    /** Accumulates the fields of one {@code @moduleName:originalName {...}} service annotation. */
    private static final class AnnotationFields {
        private final String moduleName;
        private final String originalName;
        private final List<String> fields = new ArrayList<>();

        private AnnotationFields(String moduleName, String originalName) {
            this.moduleName = moduleName;
            this.originalName = originalName;
        }

        private String render() {
            String prefix = moduleName == null || moduleName.isBlank()
                    ? "@" + originalName : "@" + moduleName + COLON + originalName;
            return prefix + " {" + String.join(", ", fields) + "}";
        }
    }

    /**
     * Resolves {@code <module>:<ServiceType>}. Prefers a SERVICE_TYPE_DESCRIPTOR field in the init
     * form (ftp/github carry an already-qualified value); otherwise reads the selected/first
     * {@code serviceTypes[]} entry (kafka carries the descriptor on the type, not the init form).
     */
    private static String resolveServiceDescriptor(ServiceInitModel filledInitForm, TriggerModel triggerModel,
                                                   String protocol) {
        String fromForm = findServiceType(filledInitForm.getProperties());
        if (fromForm != null && !fromForm.isEmpty()) {
            return qualify(fromForm, protocol);
        }
        TriggerModel.ServiceTypeModel serviceType = selectServiceType(filledInitForm, triggerModel);
        if (serviceType != null) {
            TriggerModel.Codedata cd = serviceType.codedata();
            if (cd != null && cd.originalName() != null && !cd.originalName().isBlank()) {
                String module = cd.moduleName() != null && !cd.moduleName().isBlank() ? cd.moduleName() : protocol;
                return module + COLON + cd.originalName();
            }
            if (serviceType.name() != null && !serviceType.name().isBlank()) {
                return qualify(serviceType.name(), protocol);
            }
        }
        return protocol + COLON + TYPE_SERVICE;
    }

    private static String qualify(String typeName, String protocol) {
        return typeName.contains(COLON) ? typeName : protocol + COLON + typeName;
    }

    /** Picks the service type matching the init-form selection; else the enabled one; else the first. */
    private static TriggerModel.ServiceTypeModel selectServiceType(ServiceInitModel filledInitForm,
                                                                   TriggerModel triggerModel) {
        if (triggerModel == null || triggerModel.serviceTypes() == null
                || triggerModel.serviceTypes().isEmpty()) {
            return null;
        }
        String selected = findServiceType(filledInitForm.getProperties());
        if (selected != null && !selected.isEmpty()) {
            for (TriggerModel.ServiceTypeModel st : triggerModel.serviceTypes()) {
                if (selected.equals(st.name())) {
                    return st;
                }
            }
        }
        for (TriggerModel.ServiceTypeModel st : triggerModel.serviceTypes()) {
            if (Boolean.TRUE.equals(st.enabled())) {
                return st;
            }
        }
        return triggerModel.serviceTypes().getFirst();
    }

    /** Emits the present (enabled, non-optional) handlers of the selected service type. */
    private static List<String> buildRequiredFunctionSources(ServiceInitModel filledInitForm,
                                                             TriggerModel triggerModel) {
        List<String> functions = new ArrayList<>();
        TriggerModel.ServiceTypeModel serviceType = selectServiceType(filledInitForm, triggerModel);
        if (serviceType == null || serviceType.functions() == null) {
            return functions;
        }
        for (TriggerModel.FunctionModel function : serviceType.functions()) {
            if (function.enabled() && !Boolean.TRUE.equals(function.optional())) {
                functions.add(TAB + buildFunctionSource(function).replace(NEW_LINE, NEW_LINE_WITH_TAB));
            }
        }
        return functions;
    }

    /** Renders one handler from the unified {@code FunctionModel} (params carry type/name as Property). */
    static String buildFunctionSource(TriggerModel.FunctionModel function) {
        StringBuilder builder = new StringBuilder();
        // Function-level annotations (COMPLEX_FUNCTION_ANNOTATION) sit above the function.
        for (String annotation : AnnotationEmitter.annotationsOf(function.properties())) {
            builder.append(annotation).append(NEW_LINE);
        }
        builder.append(qualifiers(function)).append("function").append(SPACE);
        if (RESOURCE.equals(qualifierKeyword(function.kind())) && function.accessor() != null
                && !function.accessor().isBlank()) {
            builder.append(function.accessor()).append(SPACE);
        }
        builder.append(effectiveFunctionName(function)).append("(").append(buildParameterList(function)).append(")");
        String returnClause = buildReturnType(function.returnType());
        if (!returnClause.isEmpty()) {
            builder.append(SPACE).append(returnClause);
        }
        builder.append(SPACE).append(OPEN_BRACE).append(NEW_LINE).append(CLOSE_BRACE);
        return builder.toString();
    }

    /**
     * The emitted function name. A format-variant handler ({@code VARIATION_SELECTOR} param) fans out
     * to the selected variant's {@code originalName} (e.g. onFileCsv / onFileJson); otherwise the
     * declared name.
     */
    private static String effectiveFunctionName(TriggerModel.FunctionModel function) {
        if (function.parameters() != null) {
            for (TriggerModel.Parameter parameter : function.parameters()) {
                String variantName = selectedVariantOriginalName(parameter.type());
                if (variantName != null && !variantName.isBlank()) {
                    return variantName;
                }
            }
        }
        return function.name();
    }

    private static String selectedVariantOriginalName(TriggerModel.Property typeProp) {
        if (typeProp == null || !"VARIATION_SELECTOR".equals(PayloadComposer.selectedFieldType(typeProp))) {
            return null;
        }
        Map<String, TriggerModel.Property> variants = typeProp.properties();
        if (variants == null || variants.isEmpty()) {
            return null;
        }
        TriggerModel.Property selected = null;
        Object value = typeProp.value();
        if (value != null && variants.containsKey(String.valueOf(value))) {
            selected = variants.get(String.valueOf(value));
        }
        if (selected == null) {
            for (TriggerModel.Property variant : variants.values()) {
                if (variant.enabled()) {
                    selected = variant;
                    break;
                }
            }
        }
        return selected == null || selected.codedata() == null ? null : selected.codedata().originalName();
    }

    private static String qualifiers(TriggerModel.FunctionModel function) {
        if (function.qualifiers() != null && !function.qualifiers().isEmpty()) {
            return String.join(SPACE, function.qualifiers()) + SPACE;
        }
        String keyword = qualifierKeyword(function.kind());
        return keyword.isEmpty() ? "" : keyword + SPACE;
    }

    private static String qualifierKeyword(String kind) {
        String normalized = kind == null ? "" : kind.toUpperCase(Locale.US);
        return switch (normalized) {
            case "REMOTE", "COMPLEX_REMOTE_FUNCTION" -> REMOTE;
            case "RESOURCE", "QUERY", "MUTATION", "SUBSCRIPTION" -> RESOURCE;
            default -> "";
        };
    }

    private static String buildParameterList(TriggerModel.FunctionModel function) {
        if (function.parameters() == null) {
            return "";
        }
        List<String> params = new ArrayList<>();
        for (TriggerModel.Parameter parameter : function.parameters()) {
            if ("FLAG".equals(PayloadComposer.selectedFieldType(parameter.type()))) {
                // Framework param (caller/context): emitted only when the checkbox is ticked.
                if (!isFlagOn(parameter)) {
                    continue;
                }
            } else if (Boolean.TRUE.equals(parameter.optional())) {
                // Core params (REQUIRED / DATA_BINDING / VARIANT) always emit unless explicitly
                // optional. (The `enabled` marker is a UI-presence flag on the template, not an
                // emission gate — a schemaFunction template ships its core param as enabled:false.)
                continue;
            }
            String type = PayloadComposer.effectiveType(parameter.type());
            String name = paramName(parameter);
            if (!type.isEmpty() && !name.isEmpty()) {
                params.add(type + SPACE + name);
            }
        }
        return String.join(", ", params);
    }

    private static boolean isFlagOn(TriggerModel.Parameter parameter) {
        Object value = parameter.type() == null ? null : parameter.type().value();
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
    }

    private static String paramName(TriggerModel.Parameter parameter) {
        TriggerModel.Property nameProp = parameter.name();
        if (nameProp == null || nameProp.value() == null) {
            return "";
        }
        return String.valueOf(nameProp.value());
    }

    private static String buildReturnType(TriggerModel.ReturnType returnType) {
        if (returnType == null || !returnType.enabled() || returnType.type() == null
                || returnType.type().isBlank()) {
            return "";
        }
        String type = returnType.type();
        if (Boolean.TRUE.equals(returnType.hasError()) && !type.contains(ERROR)) {
            type = type + "|" + ERROR;
        }
        if (Boolean.TRUE.equals(returnType.optional()) && !type.endsWith("?")) {
            type = type + "?";
        }
        return "returns" + SPACE + type;
    }

    private static String renderListenerDeclaration(String protocol, ListenerArgs args) {
        String listenerType = args.listenerType != null && !args.listenerType.isBlank()
                ? args.listenerType : protocol + COLON + LISTENER_TYPE;
        return String.format("%s %s %s = %s (%s);", LISTENER, listenerType, args.varName, NEW, args.render());
    }

    // ------------------------------------------------------------------
    // Listener argument collection (CHOICE + GROUP_SECTION aware)
    // ------------------------------------------------------------------

    private static ListenerArgs collectListenerArgs(ServiceInitModel creationModel) {
        ListenerArgs args = new ListenerArgs();
        collect(creationModel.getProperties(), args);
        return args;
    }

    private static void collect(Map<String, Value> properties, ListenerArgs args) {
        if (properties == null) {
            return;
        }
        for (Map.Entry<String, Value> entry : properties.entrySet()) {
            Value field = entry.getValue();
            if (isChoice(field)) {
                Value branch = enabledOrFirstChoice(field.getChoices());
                if (branch != null) {
                    collect(branch.getProperties(), args);
                }
                continue;
            }
            if (isExistingListener(entry.getKey(), field)) {
                // "Use existing" branch: attach to the selected listener(s), no new declaration.
                // A MULTIPLE_SELECT_LISTENER yields several names -> `service ... on l1, l2`.
                String existing = existingListenerAttach(field);
                if (!existing.isEmpty()) {
                    args.varName = existing;
                }
                continue;
            }
            Codedata codedata = field.getCodedata();
            if (isVarName(codedata)) {
                String varName = value(field);
                if (!varName.isEmpty()) {
                    args.varName = varName;
                }
                String listenerType = listenerTypeOf(field);
                if (listenerType != null) {
                    args.listenerType = listenerType;
                }
                continue;
            }
            if (isGroup(field)) {
                collectGroup(entry.getKey(), field, args);
                continue;
            }
            placeLeaf(entry.getKey(), field, codedata, args);
        }
    }

    /**
     * A GROUP_SECTION is a UI grouping — it may be a plain UI-only container boxing the whole
     * "create new listener" branch (no {@code argType}/{@code position} of its own), or it may
     * itself occupy a positional slot because a record-typed listener param's fields were flattened
     * directly into it. When the group has a slot, its {@code CONFIG_FIELD} children form that one
     * record argument; when it is UI-only, each child keeps its <b>own</b> {@code position} — fields
     * sharing a position merge into a record at that slot (e.g. HubSpot's {@code {clientSecret,
     * callbackURL}} at slot 1, ahead of {@code listenOn} at slot 2), and position-less fields fall
     * back to a trailing loose record. Other children (nested positional params, the listener
     * variable name, nested groups) are collected as their own args.
     */
    private static void collectGroup(String key, Value group, ListenerArgs args) {
        Codedata groupCodedata = group.getCodedata();
        boolean groupHasSlot = groupCodedata != null
                && ARG_TYPE_LISTENER_PARAM_REQUIRED.equals(groupCodedata.getArgType());
        List<String> recordFields = new ArrayList<>();
        Map<String, Value> rest = new LinkedHashMap<>();
        if (group.getProperties() != null) {
            for (Map.Entry<String, Value> child : group.getProperties().entrySet()) {
                Codedata childCodedata = child.getValue().getCodedata();
                if (childCodedata != null
                        && ARG_TYPE_LISTENER_PARAM_CONFIG_FIELD.equals(childCodedata.getArgType())) {
                    String rendered = qualifiedValue(child.getValue());
                    if (rendered.isEmpty()) {
                        continue;
                    }
                    String assignment = fieldName(childCodedata, child.getKey()) + ": " + rendered;
                    if (groupHasSlot) {
                        recordFields.add(assignment);
                    } else {
                        args.addConfigField(childCodedata.getPosition(), assignment);
                    }
                } else {
                    rest.put(child.getKey(), child.getValue());
                }
            }
        }
        if (groupHasSlot && !recordFields.isEmpty()) {
            args.addPositional(groupCodedata.getPosition(), "{" + String.join(", ", recordFields) + "}");
        }
        // Non-config children: nested positional params (e.g. listenOn), the var name, nested groups.
        collect(rest, args);
    }

    private static void placeLeaf(String key, Value field, Codedata codedata, ListenerArgs args) {
        if (codedata == null) {
            return;
        }
        String rendered = qualifiedValue(field);
        if (rendered.isEmpty()) {
            return;
        }
        String argType = codedata.getArgType();
        if (ARG_TYPE_LISTENER_PARAM_CONFIG_FIELD.equals(argType)) {
            // A config field with no enclosing GROUP_SECTION: fields sharing the same `position`
            // (the record param's own positional slot) are merged into one record-literal argument
            // at that slot; a field with no `position` falls back to a trailing loose record.
            args.addConfigField(codedata.getPosition(), fieldName(codedata, key) + ": " + rendered);
            return;
        }
        placeArg(codedata, key, rendered, args);
    }

    /** Places a rendered value (leaf or group record) as positional / included per its argType. */
    private static void placeArg(Codedata codedata, String key, String rendered, ListenerArgs args) {
        String argType = codedata == null ? null : codedata.getArgType();
        if (ARG_TYPE_LISTENER_PARAM_REQUIRED.equals(argType)) {
            args.addPositional(codedata.getPosition(), rendered);
        } else if (ARG_TYPE_LISTENER_PARAM_INCLUDED_FIELD.equals(argType)
                || ARG_TYPE_LISTENER_PARAM_INCLUDED_DEFAULTABLE_FIELD.equals(argType)) {
            List<String> pathSegments = dottedPathSegments(codedata);
            if (pathSegments.size() > 1) {
                // path crosses into a nested record field (e.g. a CHOICE/GROUP_SECTION modeling a
                // record- or union-typed included field, such as `auth.credentials.username`) ->
                // nest it into a record literal assigned to the top-level segment, instead of a
                // bogus flat named arg using only the leaf's own key.
                args.addIncludedPath(pathSegments, rendered);
            } else {
                args.included.add(argName(codedata, key) + " = " + rendered);
            }
        } else if (ARG_TYPE_LISTENER_PARAM_CONFIG_FIELD.equals(argType)) {
            args.addConfigField(codedata.getPosition(), fieldName(codedata, key) + ": " + rendered);
        }
        // SERVICE_TYPE_DESCRIPTOR / unknown -> not a listener argument.
    }

    private static List<String> dottedPathSegments(Codedata codedata) {
        if (codedata == null || codedata.getPath() == null || codedata.getPath().isBlank()) {
            return List.of();
        }
        return List.of(codedata.getPath().split("\\."));
    }

    /** Resolves {@code <protocol>:<ServiceType>} from the SERVICE_TYPE_DESCRIPTOR field, anywhere in the tree. */
    private static String resolveServiceDescriptor(ServiceInitModel creationModel, String protocol) {
        String serviceType = findServiceType(creationModel.getProperties());
        return protocol + COLON + (serviceType == null || serviceType.isEmpty() ? TYPE_SERVICE : serviceType);
    }

    private static String findServiceType(Map<String, Value> properties) {
        if (properties == null) {
            return null;
        }
        for (Value field : properties.values()) {
            if (isChoice(field)) {
                Value branch = enabledOrFirstChoice(field.getChoices());
                String nested = branch == null ? null : findServiceType(branch.getProperties());
                if (nested != null) {
                    return nested;
                }
                continue;
            }
            Codedata codedata = field.getCodedata();
            // v1 tags the descriptor field with argType; the unified model uses codedata.type.
            if (codedata != null && field.isEnabledWithValue()
                    && (ARG_TYPE_SERVICE_TYPE_DESCRIPTOR.equals(codedata.getArgType())
                        || ARG_TYPE_SERVICE_TYPE_DESCRIPTOR.equals(codedata.getType()))) {
                return value(field);
            }
            if (isGroup(field)) {
                String nested = findServiceType(field.getProperties());
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

    private static boolean isChoice(Value field) {
        return hasFieldType(field, Value.FieldType.CHOICE);
    }

    private static boolean isGroup(Value field) {
        return hasFieldType(field, Value.FieldType.GROUP_SECTION);
    }

    private static boolean hasFieldType(Value field, Value.FieldType fieldType) {
        return field.getTypes() != null
                && field.getTypes().stream().anyMatch(type -> type.fieldType() == fieldType);
    }

    private static boolean isVarName(Codedata codedata) {
        if (codedata == null) {
            return false;
        }
        return LISTENER_VAR_NAME_KIND.equals(codedata.getType())
                || ARG_TYPE_LISTENER_VAR_NAME.equals(codedata.getArgType());
    }

    /**
     * The listener's actual Ballerina type (e.g. {@code mssql:CdcListener}), read off the
     * {@code listenerVarName} field's {@code ballerinaType} — the connector's declared listener type
     * name is not always {@code Listener} (MSSQL CDC's is {@code CdcListener}). Falls back to
     * {@code null} (so the caller defaults to {@code <protocol>:Listener}) when unset, for manifests
     * authored before this hint existed.
     */
    private static String listenerTypeOf(Value field) {
        if (field.getTypes() == null) {
            return null;
        }
        for (PropertyType type : field.getTypes()) {
            if (type.ballerinaType() != null && !type.ballerinaType().isBlank()) {
                return type.ballerinaType();
            }
        }
        return null;
    }

    private static Value enabledOrFirstChoice(List<Value> choices) {
        if (choices == null || choices.isEmpty()) {
            return null;
        }
        return choices.stream().filter(Value::isEnabled).findFirst().orElse(choices.getFirst());
    }

    private static String fieldName(Codedata codedata, String key) {
        if (codedata != null && codedata.getPath() != null && !codedata.getPath().isBlank()) {
            return codedata.getPath();
        }
        if (codedata != null && codedata.getOriginalName() != null && !codedata.getOriginalName().isBlank()) {
            return codedata.getOriginalName();
        }
        return key;
    }

    private static String argName(Codedata codedata, String key) {
        if (codedata != null && codedata.getOriginalName() != null && !codedata.getOriginalName().isBlank()) {
            return codedata.getOriginalName();
        }
        return key;
    }

    private static String qualifiedValue(Value field) {
        String rendered = value(field);
        if (rendered.isEmpty()) {
            return "";
        }
        Codedata codedata = field.getCodedata();
        if (codedata != null && codedata.getValueQualifier() != null && !codedata.getValueQualifier().isBlank()) {
            return codedata.getValueQualifier() + COLON + rendered;
        }
        return rendered;
    }

    private static String value(Value field) {
        if (field == null) {
            return "";
        }
        String rendered = field.getValue();
        if (rendered != null && !rendered.isEmpty()) {
            return rendered;
        }
        // Multi-valued fields (TEXT_SET / EXPRESSION_SET / MULTIPLE_SELECT) carry their entries in
        // `values`, not `value` (e.g. MSSQL CDC's `databaseNames`) -> render as an array literal.
        List<String> values = field.getValues();
        if (values != null && !values.isEmpty()) {
            return "[" + String.join(", ", values) + "]";
        }
        return "";
    }

    /** The "use existing" selector — by key or by {@code codedata.type == KEY_EXISTING_LISTENER}. */
    private static boolean isExistingListener(String key, Value field) {
        if (KEY_EXISTING_LISTENER.equals(key)) {
            return true;
        }
        Codedata codedata = field == null ? null : field.getCodedata();
        return codedata != null && "KEY_EXISTING_LISTENER".equals(codedata.getType());
    }

    /**
     * The listener name(s) to attach to. A single-select yields one name; a
     * {@code MULTIPLE_SELECT_LISTENER} yields several, joined so the service attaches to all
     * (`service ... on l1, l2`).
     */
    private static String existingListenerAttach(Value field) {
        if (field == null) {
            return "";
        }
        List<String> values = field.getValues();
        if (values != null && !values.isEmpty()) {
            return String.join(", ", values);
        }
        return value(field);
    }

    /** Accumulates listener arguments: positional (by position, then unordered), included, loose config. */
    private static final class ListenerArgs {
        private final TreeMap<Integer, String> byPosition = new TreeMap<>();
        private final TreeMap<Integer, List<String>> configFieldsByPosition = new TreeMap<>();
        private final List<String> noPosition = new ArrayList<>();
        private final List<String> included = new ArrayList<>();
        private final List<String> looseConfig = new ArrayList<>();
        private final Map<String, Object> includedTree = new LinkedHashMap<>();
        private String varName = "";
        private String listenerType;

        private void addPositional(Integer position, String rendered) {
            if (position != null) {
                byPosition.put(position, rendered);
            } else {
                noPosition.add(rendered);
            }
        }

        /**
         * Adds a flat {@code LISTENER_PARAM_CONFIG_FIELD} (no enclosing GROUP_SECTION). Fields
         * sharing the same {@code position} are merged into one record literal at that positional
         * slot — this is how a record-typed listener param's fields are laid out (see
         * {@link #collect}). A field with no {@code position} falls back to a trailing loose record
         * for backward compatibility with older manifests.
         */
        private void addConfigField(Integer position, String fieldAssignment) {
            if (position != null) {
                configFieldsByPosition.computeIfAbsent(position, ignored -> new ArrayList<>()).add(fieldAssignment);
            } else {
                looseConfig.add(fieldAssignment);
            }
        }

        /**
         * Merges a rendered value into the nested-record tree at a dotted path (e.g.
         * {@code auth.credentials.username}) — intermediate segments become nested record literals,
         * so the top-level segment ({@code auth}) renders as one named arg: {@code auth = {credentials:
         * {username: "...", password: "..."}}}.
         */
        @SuppressWarnings("unchecked")
        private void addIncludedPath(List<String> segments, String renderedValue) {
            Map<String, Object> node = includedTree;
            for (int i = 0; i < segments.size() - 1; i++) {
                node = (Map<String, Object>) node.computeIfAbsent(segments.get(i), ignored -> new LinkedHashMap<>());
            }
            node.put(segments.getLast(), renderedValue);
        }

        @SuppressWarnings("unchecked")
        private static String renderIncludedValue(Object value) {
            if (value instanceof String rendered) {
                return rendered;
            }
            Map<String, Object> nested = (Map<String, Object>) value;
            List<String> fields = new ArrayList<>();
            for (Map.Entry<String, Object> entry : nested.entrySet()) {
                fields.add(entry.getKey() + ": " + renderIncludedValue(entry.getValue()));
            }
            return "{" + String.join(", ", fields) + "}";
        }

        private boolean hasArgs() {
            return !byPosition.isEmpty() || !configFieldsByPosition.isEmpty() || !noPosition.isEmpty()
                    || !included.isEmpty() || !looseConfig.isEmpty() || !includedTree.isEmpty();
        }

        private String render() {
            TreeMap<Integer, String> positional = new TreeMap<>(byPosition);
            for (Map.Entry<Integer, List<String>> entry : configFieldsByPosition.entrySet()) {
                positional.put(entry.getKey(), "{" + String.join(", ", entry.getValue()) + "}");
            }
            List<String> args = new ArrayList<>(positional.values());
            args.addAll(noPosition);
            if (!looseConfig.isEmpty()) {
                args.add("{" + String.join(", ", looseConfig) + "}");
            }
            args.addAll(included);
            for (Map.Entry<String, Object> entry : includedTree.entrySet()) {
                args.add(entry.getKey() + " = " + renderIncludedValue(entry.getValue()));
            }
            return String.join(", ", args);
        }
    }
}
