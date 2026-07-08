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

import io.ballerina.compiler.api.SemanticModel;
import io.ballerina.compiler.api.symbols.Qualifier;
import io.ballerina.compiler.api.symbols.Symbol;
import io.ballerina.compiler.api.symbols.VariableSymbol;
import io.ballerina.compiler.syntax.tree.CheckExpressionNode;
import io.ballerina.compiler.syntax.tree.FunctionArgumentNode;
import io.ballerina.compiler.syntax.tree.ListenerDeclarationNode;
import io.ballerina.compiler.syntax.tree.MappingConstructorExpressionNode;
import io.ballerina.compiler.syntax.tree.MappingFieldNode;
import io.ballerina.compiler.syntax.tree.ModulePartNode;
import io.ballerina.compiler.syntax.tree.NamedArgumentNode;
import io.ballerina.compiler.syntax.tree.NewExpressionNode;
import io.ballerina.compiler.syntax.tree.Node;
import io.ballerina.compiler.syntax.tree.NonTerminalNode;
import io.ballerina.compiler.syntax.tree.PositionalArgumentNode;
import io.ballerina.compiler.syntax.tree.SeparatedNodeList;
import io.ballerina.compiler.syntax.tree.SpecificFieldNode;
import io.ballerina.projects.Document;
import io.ballerina.projects.DocumentId;
import io.ballerina.projects.Project;
import io.ballerina.servicemodelgenerator.extension.model.Codedata;
import io.ballerina.servicemodelgenerator.extension.model.PropertyType;
import io.ballerina.servicemodelgenerator.extension.model.Value;
import io.ballerina.servicemodelgenerator.extension.util.ListenerUtil;
import io.ballerina.tools.diagnostics.Location;
import io.ballerina.tools.text.TextRange;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.ballerina.servicemodelgenerator.extension.util.Constants.ARG_TYPE_LISTENER_PARAM_CONFIG_FIELD;
import static io.ballerina.servicemodelgenerator.extension.util.Constants.ARG_TYPE_LISTENER_PARAM_INCLUDED_DEFAULTABLE_FIELD;
import static io.ballerina.servicemodelgenerator.extension.util.Constants.ARG_TYPE_LISTENER_PARAM_INCLUDED_FIELD;
import static io.ballerina.servicemodelgenerator.extension.util.Constants.ARG_TYPE_LISTENER_PARAM_REQUIRED;

/**
 * Builds the "use existing" listener selector for the schema-driven path, resolving each existing
 * listener's configuration from <b>both the model and the source</b> — the generic equivalent of the
 * per-connector extraction in {@code RabbitMQServiceBuilder}/{@code FTPServiceBuilder}.
 *
 * <p>The create-new branch's listener params define the field <i>template</i> (labels, types, and the
 * {@code codedata} position/path that says where each value sits in {@code new(...)}). For each listener
 * variable found in the project, its {@code new(...)} arguments are parsed and mapped back onto that
 * template as <b>read-only</b> fields, so selecting a listener shows its host/port/config etc.
 *
 * @since 1.8.0
 */
public final class ExistingListenerResolver {

    private ExistingListenerResolver() {
    }

    /**
     * Builds a {@code SINGLE_SELECT} of the given listeners; selecting one reveals its config
     * (read-only) resolved from source, using {@code createNewBranch} as the field template.
     */
    public static Value buildSelector(Value createNewBranch, List<String> listenerNames,
                                      SemanticModel semanticModel, Project project, String protocol) {
        ListenerTemplate template = collectTemplate(createNewBranch);
        Map<String, Value> perListenerConfigs = new LinkedHashMap<>();
        for (String name : listenerNames) {
            Map<String, Value> fields = new LinkedHashMap<>();
            parseListener(name, semanticModel, project)
                    .ifPresent(parsed -> fields.putAll(buildFieldsFromParsed(parsed, template)));
            Value configGroup = new Value.ValueBuilder()
                    .metadata(name, protocol + " listener: " + name)
                    .value(name)
                    .types(List.of(PropertyType.types(Value.FieldType.FORM)))
                    .enabled(true)
                    .editable(false)
                    .setProperties(fields)
                    .build();
            perListenerConfigs.put(name, configGroup);
        }
        return assembleSelector(listenerNames, perListenerConfigs, protocol);
    }

    /**
     * Assembles the {@code existingListener} dropdown (pure; unit-testable). It is a plain
     * {@code SINGLE_SELECT} whose choices come from {@code items} and whose per-listener config comes
     * from {@code properties}.
     *
     * <p><b>Important:</b> the type must NOT carry {@code options}. The front end renders the nested
     * per-listener config ({@code DropdownChoiceForm}) only for a SINGLE_SELECT with no {@code options};
     * adding options makes {@code isDropDownType} true, which routes it to the expression/enum editor and
     * hides the resolved fields. FTP/RabbitMQ likewise use {@code items} only.
     */
    static Value assembleSelector(List<String> listenerNames, Map<String, Value> perListenerConfigs,
                                  String protocol) {
        return new Value.ValueBuilder()
                .metadata("Select Listener", String.format("Select from the existing %s listeners", protocol))
                .value(listenerNames.getFirst())
                .types(List.of(PropertyType.types(Value.FieldType.SINGLE_SELECT)))
                .enabled(true)
                .editable(true)
                .setItems(new ArrayList<>(listenerNames))
                .setProperties(perListenerConfigs)
                .build();
    }

    // ------------------------------------------------------------------
    // Model side — derive the field template from the create-new params
    // ------------------------------------------------------------------

    /** The listener-parameter field template derived from the create-new branch. */
    static final class ListenerTemplate {
        // position -> a scalar positional param (key + template value)
        final Map<Integer, Field> positionalScalars = new LinkedHashMap<>();
        // position -> a record-arg group: field name -> config-field template value
        final Map<Integer, LinkedHashMap<String, Value>> recordGroups = new LinkedHashMap<>();
        // named (included/config) params: name -> template value
        final LinkedHashMap<String, Value> named = new LinkedHashMap<>();
    }

    record Field(String key, Value template) {
    }

    static ListenerTemplate collectTemplate(Value createNewBranch) {
        ListenerTemplate template = new ListenerTemplate();
        collectTemplate(createNewBranch == null ? null : createNewBranch.getProperties(), template);
        return template;
    }

    private static void collectTemplate(Map<String, Value> properties, ListenerTemplate template) {
        if (properties == null) {
            return;
        }
        for (Map.Entry<String, Value> entry : properties.entrySet()) {
            Value field = entry.getValue();
            if (isGroup(field)) {
                // A group with its own positional slot collects its config fields into that one
                // record group; a UI-only group's config fields keep their OWN position (fields
                // sharing a position form the record at that slot, e.g. HubSpot's config at slot 1)
                // and position-less ones resolve as named fields.
                Codedata groupCodedata = field.getCodedata();
                boolean groupHasSlot = groupCodedata != null
                        && ARG_TYPE_LISTENER_PARAM_REQUIRED.equals(groupCodedata.getArgType())
                        && groupCodedata.getPosition() != null;
                LinkedHashMap<String, Value> configChildren = new LinkedHashMap<>();
                Map<String, Value> rest = new LinkedHashMap<>();
                if (field.getProperties() != null) {
                    for (Map.Entry<String, Value> child : field.getProperties().entrySet()) {
                        Codedata childCodedata = child.getValue().getCodedata();
                        if (childCodedata != null
                                && ARG_TYPE_LISTENER_PARAM_CONFIG_FIELD.equals(childCodedata.getArgType())) {
                            String name = fieldName(childCodedata, child.getKey());
                            if (groupHasSlot) {
                                configChildren.put(name, child.getValue());
                            } else if (childCodedata.getPosition() != null) {
                                template.recordGroups
                                        .computeIfAbsent(childCodedata.getPosition(),
                                                ignored -> new LinkedHashMap<>())
                                        .put(name, child.getValue());
                            } else {
                                template.named.put(name, child.getValue());
                            }
                        } else {
                            rest.put(child.getKey(), child.getValue());
                        }
                    }
                }
                if (groupHasSlot && !configChildren.isEmpty()) {
                    template.recordGroups.put(groupCodedata.getPosition(), configChildren);
                }
                collectTemplate(rest, template);
                continue;
            }
            Codedata codedata = field.getCodedata();
            if (codedata == null) {
                continue;
            }
            String argType = codedata.getArgType();
            if (ARG_TYPE_LISTENER_PARAM_REQUIRED.equals(argType) && codedata.getPosition() != null) {
                template.positionalScalars.put(codedata.getPosition(), new Field(entry.getKey(), field));
            } else if (ARG_TYPE_LISTENER_PARAM_INCLUDED_FIELD.equals(argType)
                    || ARG_TYPE_LISTENER_PARAM_INCLUDED_DEFAULTABLE_FIELD.equals(argType)) {
                template.named.put(argName(codedata, entry.getKey()), field);
            } else if (ARG_TYPE_LISTENER_PARAM_CONFIG_FIELD.equals(argType)) {
                template.named.put(fieldName(codedata, entry.getKey()), field);
            }
        }
    }

    // ------------------------------------------------------------------
    // Mapping — parsed source args onto the template as read-only fields
    // ------------------------------------------------------------------

    /** A parsed {@code new(...)}: positional args (scalar or record) and named args. */
    record ParsedListener(List<ParsedArg> positional, LinkedHashMap<String, String> named) {
    }

    /** One argument: exactly one of {@code scalar} / {@code recordFields} is set. */
    record ParsedArg(String scalar, LinkedHashMap<String, String> recordFields) {
        static ParsedArg scalar(String value) {
            return new ParsedArg(value, null);
        }

        static ParsedArg record(LinkedHashMap<String, String> fields) {
            return new ParsedArg(null, fields);
        }
    }

    static Map<String, Value> buildFieldsFromParsed(ParsedListener parsed, ListenerTemplate template) {
        Map<String, Value> fields = new LinkedHashMap<>();
        List<ParsedArg> positional = parsed.positional();
        for (int i = 0; i < positional.size(); i++) {
            int position = i + 1;
            ParsedArg arg = positional.get(i);
            if (arg.recordFields() != null && template.recordGroups.containsKey(position)) {
                LinkedHashMap<String, Value> configTemplates = template.recordGroups.get(position);
                arg.recordFields().forEach((name, value) ->
                        fields.put(name, readOnly(configTemplates.get(name), name, value)));
            } else if (template.positionalScalars.containsKey(position)) {
                Field field = template.positionalScalars.get(position);
                String value = arg.scalar() != null ? arg.scalar() : renderRecord(arg.recordFields());
                fields.put(field.key(), readOnly(field.template(), field.key(), value));
            }
        }
        parsed.named().forEach((name, value) ->
                fields.put(name, readOnly(template.named.get(name), name, value)));
        return fields;
    }

    /** Clones the template field (preserving label/type) as a read-only value; falls back to a text value. */
    private static Value readOnly(Value template, String key, String value) {
        if (template == null) {
            return ListenerUtil.buildReadOnlyTextValue(key, "", value);
        }
        Value copy = new Value(template);
        copy.setValue(value);
        copy.setEnabled(true);
        copy.setEditable(false);
        // These are read-only displays of an existing listener's config. Force optional=false and
        // advanced=false so the front end shows them (DropdownChoiceForm hides optional/advanced fields),
        // and drop validations so a read-only value never triggers "required"-style errors.
        copy.setOptional(false);
        copy.setAdvanced(false);
        copy.setValidations(null);
        return copy;
    }

    private static String renderRecord(LinkedHashMap<String, String> recordFields) {
        if (recordFields == null || recordFields.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        recordFields.forEach((name, value) -> parts.add(name + ": " + value));
        return "{" + String.join(", ", parts) + "}";
    }

    // ------------------------------------------------------------------
    // Source side — parse a listener declaration's new(...) arguments
    // ------------------------------------------------------------------

    static Optional<ParsedListener> parseListener(String listenerName, SemanticModel semanticModel, Project project) {
        try {
            ListenerDeclarationNode declaration = findListenerDeclaration(listenerName, semanticModel, project);
            if (declaration == null) {
                return Optional.empty();
            }
            NewExpressionNode newExpression = asNewExpression(declaration.initializer());
            if (newExpression == null) {
                return Optional.empty();
            }
            SeparatedNodeList<FunctionArgumentNode> arguments = ListenerUtil.getArgList(newExpression);
            if (arguments == null) {
                return Optional.empty();
            }
            List<ParsedArg> positional = new ArrayList<>();
            LinkedHashMap<String, String> named = new LinkedHashMap<>();
            for (FunctionArgumentNode argument : arguments) {
                if (argument instanceof PositionalArgumentNode positionalArg) {
                    positional.add(toParsedArg(positionalArg.expression()));
                } else if (argument instanceof NamedArgumentNode namedArg) {
                    named.put(namedArg.argumentName().name().text().trim(),
                            namedArg.expression().toSourceCode().trim());
                }
            }
            return Optional.of(new ParsedListener(positional, named));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static ParsedArg toParsedArg(Node expression) {
        if (expression instanceof MappingConstructorExpressionNode mapping) {
            LinkedHashMap<String, String> recordFields = new LinkedHashMap<>();
            for (MappingFieldNode fieldNode : mapping.fields()) {
                if (fieldNode instanceof SpecificFieldNode specificField) {
                    String name = unquote(specificField.fieldName().toSourceCode().trim());
                    String value = specificField.valueExpr()
                            .map(expr -> expr.toSourceCode().trim())
                            .orElse("");
                    recordFields.put(name, value);
                }
            }
            return ParsedArg.record(recordFields);
        }
        return ParsedArg.scalar(expression.toSourceCode().trim());
    }

    private static NewExpressionNode asNewExpression(Node initializer) {
        if (initializer instanceof CheckExpressionNode checkExpression
                && checkExpression.expression() instanceof NewExpressionNode newExpression) {
            return newExpression;
        }
        if (initializer instanceof NewExpressionNode newExpression) {
            return newExpression;
        }
        return null;
    }

    private static ListenerDeclarationNode findListenerDeclaration(String listenerName, SemanticModel semanticModel,
                                                                   Project project) {
        Optional<VariableSymbol> listenerSymbol = Optional.empty();
        for (Symbol symbol : semanticModel.moduleSymbols()) {
            if (symbol instanceof VariableSymbol variableSymbol
                    && variableSymbol.qualifiers().contains(Qualifier.LISTENER)
                    && variableSymbol.getName().map(listenerName::equals).orElse(false)) {
                listenerSymbol = Optional.of(variableSymbol);
                break;
            }
        }
        if (listenerSymbol.isEmpty() || listenerSymbol.get().getLocation().isEmpty()) {
            return null;
        }
        Location location = listenerSymbol.get().getLocation().get();
        Path path = project.sourceRoot().resolve(location.lineRange().fileName());
        DocumentId documentId = project.documentId(path);
        Document document = project.currentPackage().getDefaultModule().document(documentId);
        if (document == null) {
            return null;
        }
        ModulePartNode rootNode = document.syntaxTree().rootNode();
        TextRange range = TextRange.from(location.textRange().startOffset(), location.textRange().length());
        NonTerminalNode node = rootNode.findNode(range);
        while (node != null && !(node instanceof ListenerDeclarationNode)) {
            node = node.parent();
        }
        return (ListenerDeclarationNode) node;
    }

    private static boolean isGroup(Value field) {
        return field.getTypes() != null
                && field.getTypes().stream().anyMatch(type -> type.fieldType() == Value.FieldType.GROUP_SECTION);
    }

    private static String fieldName(Codedata codedata, String key) {
        if (codedata.getPath() != null && !codedata.getPath().isBlank()) {
            return codedata.getPath();
        }
        if (codedata.getOriginalName() != null && !codedata.getOriginalName().isBlank()) {
            return codedata.getOriginalName();
        }
        return key;
    }

    private static String argName(Codedata codedata, String key) {
        if (codedata.getOriginalName() != null && !codedata.getOriginalName().isBlank()) {
            return codedata.getOriginalName();
        }
        return key;
    }

    private static String unquote(String text) {
        if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
            return text.substring(1, text.length() - 1);
        }
        return text;
    }
}
