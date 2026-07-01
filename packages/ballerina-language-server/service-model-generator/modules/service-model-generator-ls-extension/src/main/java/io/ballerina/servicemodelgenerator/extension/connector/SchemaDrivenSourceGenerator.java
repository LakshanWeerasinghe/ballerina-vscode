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
import io.ballerina.servicemodelgenerator.extension.connector.model.LibraryArtifact;
import io.ballerina.servicemodelgenerator.extension.model.Codedata;
import io.ballerina.servicemodelgenerator.extension.model.ServiceInitModel;
import io.ballerina.servicemodelgenerator.extension.model.Value;
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
 *       positional param, the listener variable name) are still collected as their own args;</li>
 *   <li>leaves are placed by {@code argType} — {@code REQUIRED} positional (ordered by
 *       {@code position}), {@code INCLUDED_FIELD} as {@code name = value}, {@code CONFIG_FIELD}
 *       into a record, {@code valueQualifier} module-prefixes enum-like values.</li>
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
    private static final String TYPE_PLACEHOLDER = "{{type}}";
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

    /**
     * Builds the full add-trigger source block: {@code \n<listener>;\nservice <descriptor> on <var>
     * {\n<funcs>\n}\n}. The listener declaration is emitted only when the selected branch actually
     * configures a new listener (create-new); a use-existing branch attaches to the resolved name.
     */
    public static String buildServiceBlock(ServiceInitModel creationModel, LibraryArtifact metadataModel) {
        String protocol = getProtocol(creationModel.getModuleName());
        ListenerArgs collected = collectListenerArgs(creationModel);
        String descriptor = resolveServiceDescriptor(creationModel, protocol);
        List<String> functions = buildRequiredFunctionSources(creationModel, metadataModel);

        StringBuilder builder = new StringBuilder(NEW_LINE);
        if (collected.hasArgs()) {
            builder.append(renderListenerDeclaration(protocol, collected)).append(NEW_LINE);
        }
        builder.append(SERVICE).append(SPACE).append(descriptor).append(SPACE).append(ON).append(SPACE)
                .append(collected.varName).append(SPACE).append(OPEN_BRACE)
                .append(NEW_LINE)
                .append(String.join(TWO_NEW_LINES, functions)).append(NEW_LINE)
                .append(CLOSE_BRACE).append(NEW_LINE);
        return builder.toString();
    }

    /** The {@code \nimport <org>/<module>;\n} statement for the connector. */
    public static String buildImport(ServiceInitModel creationModel) {
        return Utils.getImportStmt(creationModel.getOrgName(), creationModel.getModuleName());
    }

    /**
     * Assembles the text edits for {@code addServiceAndListener}: the import (if missing) at the top
     * of the file and the listener+service block at the end.
     */
    public static Map<String, List<TextEdit>> buildAddServiceEdits(ServiceInitModel creationModel,
                                                                   LibraryArtifact metadataModel,
                                                                   ModulePartNode rootNode, String filePath) {
        List<TextEdit> edits = new ArrayList<>();
        if (!Utils.importExists(rootNode, creationModel.getOrgName(), creationModel.getModuleName())) {
            edits.add(new TextEdit(Utils.toRange(rootNode.lineRange().startLine()), buildImport(creationModel)));
        }
        edits.add(new TextEdit(Utils.toRange(rootNode.lineRange().endLine()),
                buildServiceBlock(creationModel, metadataModel)));
        return Map.of(filePath, edits);
    }

    private static String renderListenerDeclaration(String protocol, ListenerArgs args) {
        return String.format("%s %s%s%s %s = %s (%s);", LISTENER, protocol, COLON, LISTENER_TYPE,
                args.varName, NEW, args.render());
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
            if (KEY_EXISTING_LISTENER.equals(entry.getKey())) {
                // "Use existing" branch: attach to the selected listener, no new declaration.
                String existing = value(field);
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
     * A GROUP_SECTION is a UI grouping. Its {@code CONFIG_FIELD} children form one record argument
     * (placed by the group's own {@code argType}/{@code position}); its other children (nested
     * positional params, the listener variable name, nested groups) are collected as their own args.
     */
    private static void collectGroup(String key, Value group, ListenerArgs args) {
        List<String> recordFields = new ArrayList<>();
        Map<String, Value> rest = new LinkedHashMap<>();
        if (group.getProperties() != null) {
            for (Map.Entry<String, Value> child : group.getProperties().entrySet()) {
                Codedata childCodedata = child.getValue().getCodedata();
                if (childCodedata != null
                        && ARG_TYPE_LISTENER_PARAM_CONFIG_FIELD.equals(childCodedata.getArgType())) {
                    String rendered = qualifiedValue(child.getValue());
                    if (!rendered.isEmpty()) {
                        recordFields.add(fieldName(childCodedata, child.getKey()) + ": " + rendered);
                    }
                } else {
                    rest.put(child.getKey(), child.getValue());
                }
            }
        }
        if (!recordFields.isEmpty()) {
            String record = "{" + String.join(", ", recordFields) + "}";
            placeArg(group.getCodedata(), key, record, args);
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
            // A config field with no enclosing group -> a loose record argument.
            args.looseConfig.add(fieldName(codedata, key) + ": " + rendered);
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
            args.included.add(argName(codedata, key) + " = " + rendered);
        } else if (ARG_TYPE_LISTENER_PARAM_CONFIG_FIELD.equals(argType)) {
            args.looseConfig.add(fieldName(codedata, key) + ": " + rendered);
        }
        // SERVICE_TYPE_DESCRIPTOR / unknown -> not a listener argument.
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
            if (codedata != null && ARG_TYPE_SERVICE_TYPE_DESCRIPTOR.equals(codedata.getArgType())
                    && field.isEnabledWithValue()) {
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
    // Functions (locked handlers from the metadata model)
    // ------------------------------------------------------------------

    private static List<String> buildRequiredFunctionSources(ServiceInitModel creationModel,
                                                             LibraryArtifact metadataModel) {
        List<String> functions = new ArrayList<>();
        if (metadataModel == null || metadataModel.serviceTypes() == null) {
            return functions;
        }
        LibraryArtifact.ServiceType serviceType = selectServiceType(creationModel, metadataModel);
        if (serviceType == null || serviceType.functions() == null) {
            return functions;
        }
        for (LibraryArtifact.FunctionModel function : serviceType.functions()) {
            if (function.enabled() && !Boolean.TRUE.equals(function.optional())) {
                functions.add(TAB + buildFunctionSource(function).replace(NEW_LINE, NEW_LINE_WITH_TAB));
            }
        }
        return functions;
    }

    private static LibraryArtifact.ServiceType selectServiceType(ServiceInitModel creationModel,
                                                                LibraryArtifact metadataModel) {
        String serviceTypeName = findServiceType(creationModel.getProperties());
        if (serviceTypeName != null && metadataModel.serviceTypes().containsKey(serviceTypeName)) {
            return metadataModel.serviceTypes().get(serviceTypeName);
        }
        if (metadataModel.serviceTypes().size() == 1) {
            return metadataModel.serviceTypes().values().iterator().next();
        }
        return metadataModel.serviceTypes().get(serviceTypeName);
    }

    /**
     * Renders a single function/handler. Driven by the function model's kind/qualifiers, parameters
     * (required, enabled), return type ({@code typeTemplate}/optional/hasError) — no per-connector code.
     */
    static String buildFunctionSource(LibraryArtifact.FunctionModel function) {
        StringBuilder builder = new StringBuilder();
        builder.append(qualifiers(function)).append("function").append(SPACE);
        if (RESOURCE.equals(qualifierKeyword(function)) && function.accessor() != null
                && !function.accessor().isBlank()) {
            builder.append(function.accessor()).append(SPACE);
        }
        builder.append(function.name()).append("(").append(buildParameterList(function)).append(")");
        String returnClause = buildReturnType(function.returnType());
        if (!returnClause.isEmpty()) {
            builder.append(SPACE).append(returnClause);
        }
        builder.append(SPACE).append(OPEN_BRACE).append(NEW_LINE).append(CLOSE_BRACE);
        return builder.toString();
    }

    private static String qualifiers(LibraryArtifact.FunctionModel function) {
        if (function.qualifiers() != null && !function.qualifiers().isEmpty()) {
            return String.join(SPACE, function.qualifiers()) + SPACE;
        }
        String keyword = qualifierKeyword(function);
        return keyword.isEmpty() ? "" : keyword + SPACE;
    }

    private static String qualifierKeyword(LibraryArtifact.FunctionModel function) {
        String kind = function.kind() == null ? "" : function.kind().toUpperCase(Locale.US);
        return switch (kind) {
            case "REMOTE" -> REMOTE;
            case "RESOURCE" -> RESOURCE;
            default -> "";
        };
    }

    private static String buildParameterList(LibraryArtifact.FunctionModel function) {
        if (function.parameters() == null) {
            return "";
        }
        List<String> params = new ArrayList<>();
        for (LibraryArtifact.Parameter parameter : function.parameters()) {
            boolean enabled = parameter.enabled() == null || parameter.enabled();
            boolean optional = Boolean.TRUE.equals(parameter.optional());
            if (enabled && !optional) {
                params.add(applyTypeTemplate(parameter.typeTemplate(), parameter.type()) + SPACE + parameter.name());
            }
        }
        return String.join(", ", params);
    }

    private static String buildReturnType(LibraryArtifact.ReturnType returnType) {
        if (returnType == null || !returnType.enabled() || returnType.type() == null
                || returnType.type().isBlank()) {
            return "";
        }
        String type = applyTypeTemplate(returnType.typeTemplate(), returnType.type());
        if (Boolean.TRUE.equals(returnType.hasError()) && !type.contains(ERROR)) {
            type = type + "|" + ERROR;
        }
        if (Boolean.TRUE.equals(returnType.optional()) && !type.endsWith("?")) {
            type = type + "?";
        }
        return "returns" + SPACE + type;
    }

    private static String applyTypeTemplate(String template, String type) {
        String safeType = type == null ? "" : type;
        if (template != null && template.contains(TYPE_PLACEHOLDER)) {
            return template.replace(TYPE_PLACEHOLDER, safeType);
        }
        return safeType;
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
        return rendered == null ? "" : rendered;
    }

    /** Accumulates listener arguments: positional (by position, then unordered), included, loose config. */
    private static final class ListenerArgs {
        private final TreeMap<Integer, String> byPosition = new TreeMap<>();
        private final List<String> noPosition = new ArrayList<>();
        private final List<String> included = new ArrayList<>();
        private final List<String> looseConfig = new ArrayList<>();
        private String varName = "";

        private void addPositional(Integer position, String rendered) {
            if (position != null) {
                byPosition.put(position, rendered);
            } else {
                noPosition.add(rendered);
            }
        }

        private boolean hasArgs() {
            return !byPosition.isEmpty() || !noPosition.isEmpty() || !included.isEmpty() || !looseConfig.isEmpty();
        }

        private String render() {
            List<String> args = new ArrayList<>(byPosition.values());
            args.addAll(noPosition);
            if (!looseConfig.isEmpty()) {
                args.add("{" + String.join(", ", looseConfig) + "}");
            }
            args.addAll(included);
            return String.join(", ", args);
        }
    }
}
