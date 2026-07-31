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

import io.ballerina.compiler.api.SemanticModel;
import io.ballerina.compiler.api.symbols.ClassSymbol;
import io.ballerina.compiler.api.symbols.Documentation;
import io.ballerina.compiler.api.symbols.MethodSymbol;
import io.ballerina.compiler.api.symbols.ObjectTypeSymbol;
import io.ballerina.compiler.api.symbols.ParameterKind;
import io.ballerina.compiler.api.symbols.ParameterSymbol;
import io.ballerina.compiler.api.symbols.PathParameterSymbol;
import io.ballerina.compiler.api.symbols.Qualifier;
import io.ballerina.compiler.api.symbols.ResourceMethodSymbol;
import io.ballerina.compiler.api.symbols.Symbol;
import io.ballerina.compiler.api.symbols.TypeDefinitionSymbol;
import io.ballerina.compiler.api.symbols.TypeReferenceTypeSymbol;
import io.ballerina.compiler.api.symbols.TypeSymbol;
import io.ballerina.compiler.api.symbols.resourcepath.PathSegmentList;
import io.ballerina.compiler.api.symbols.resourcepath.ResourcePath;
import io.ballerina.compiler.syntax.tree.DefaultableParameterNode;
import io.ballerina.compiler.syntax.tree.ExpressionNode;
import io.ballerina.compiler.syntax.tree.ModulePartNode;
import io.ballerina.compiler.syntax.tree.NonTerminalNode;
import io.ballerina.compiler.syntax.tree.QualifiedNameReferenceNode;
import io.ballerina.compiler.syntax.tree.SimpleNameReferenceNode;
import io.ballerina.compiler.syntax.tree.SyntaxKind;
import io.ballerina.modelgenerator.commons.CommonUtils;
import io.ballerina.modelgenerator.commons.DefaultValueGeneratorUtil;
import io.ballerina.projects.Document;
import io.ballerina.projects.DocumentId;
import io.ballerina.projects.Module;
import io.ballerina.projects.Package;
import io.ballerina.projects.Project;
import io.ballerina.tools.diagnostics.Location;
import io.ballerina.tools.text.TextRange;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Semantic-model facts the schema-driven Copilot service loader needs from a trigger library:
 * the listener class and its init parameters (names, types, documentation via the init method's
 * {@code parameterMap()}, defaults recovered the same way the service-index generator did), the
 * module's service object types with their declared methods, and simple existence checks used to
 * validate {@code trigger-metadata.json} claims against the actually-resolved package.
 *
 * <p>Everything here reads the same compiled package {@code CopilotLibraryManager} already resolves
 * for clients/typeDefs, so no extra package resolution happens. Type signatures use the 3-arg
 * {@link CommonUtils#getTypeSignature(SemanticModel, TypeSymbol, boolean)} overload — the exact
 * function the service-index generator used — so the strings fed to {@link TypeResolver} match the
 * historical SQLite forms (module-prefixed, unions exploded member-wise).
 *
 * @since 1.7.0
 */
final class TriggerSemanticFacts {

    private static final String LISTENER = "Listener";

    private final SemanticModel semanticModel;
    private final Package modulePackage;
    private final Map<String, ClassSymbol> classesByName = new LinkedHashMap<>();
    private final Map<String, ObjectTypeSymbol> serviceObjectTypesByName = new LinkedHashMap<>();
    private final Set<String> declaredTypeNames = new HashSet<>();

    TriggerSemanticFacts(SemanticModel semanticModel, Package modulePackage) {
        this.semanticModel = semanticModel;
        this.modulePackage = modulePackage;
        for (Symbol symbol : semanticModel.moduleSymbols()) {
            String name = symbol.getName().orElse(null);
            if (name == null) {
                continue;
            }
            switch (symbol.kind()) {
                case CLASS, TYPE_DEFINITION, ENUM, CONSTANT, ENUM_MEMBER -> declaredTypeNames.add(name);
                default -> {
                }
            }
            if (symbol instanceof ClassSymbol classSymbol) {
                classesByName.putIfAbsent(name, classSymbol);
            } else if (symbol instanceof TypeDefinitionSymbol typeDef) {
                TypeSymbol raw = CommonUtils.getRawType(typeDef.typeDescriptor());
                if (raw instanceof ObjectTypeSymbol objectType
                        && objectType.qualifiers().contains(Qualifier.SERVICE)) {
                    serviceObjectTypesByName.putIfAbsent(name, objectType);
                }
            }
        }
    }

    /**
     * Whether the module declares a named type-ish symbol (class, type definition, enum, constant)
     * with this exact name — the criterion for alias-prefixing a metadata type name so
     * {@link TypeResolver} strips it back off and links it, matching the historical index behavior
     * where any module-declared referenced type arrived prefixed.
     */
    boolean declaresType(String name) {
        return declaredTypeNames.contains(name);
    }

    Optional<ObjectTypeSymbol> serviceObjectType(String name) {
        return Optional.ofNullable(serviceObjectTypesByName.get(name));
    }

    /**
     * Resolves the listener class: the metadata-declared name when the package actually declares it,
     * else the canonical {@code Listener} class, else the first class that type-includes a
     * {@code Listener} (the {@code CdcListener} pattern).
     */
    Optional<ClassSymbol> resolveListenerClass(String metadataDeclaredName) {
        if (metadataDeclaredName != null && classesByName.containsKey(metadataDeclaredName)) {
            return Optional.of(classesByName.get(metadataDeclaredName));
        }
        if (classesByName.containsKey(LISTENER)) {
            return Optional.of(classesByName.get(LISTENER));
        }
        for (ClassSymbol classSymbol : classesByName.values()) {
            boolean includesListener = classSymbol.typeInclusions().stream()
                    .filter(t -> t instanceof TypeReferenceTypeSymbol)
                    .map(t -> (TypeReferenceTypeSymbol) t)
                    .anyMatch(ref -> ref.definition().nameEquals(LISTENER));
            if (includesListener) {
                return Optional.of(classSymbol);
            }
        }
        return Optional.empty();
    }

    /**
     * One top-level listener init parameter, with everything the Copilot listener spec needs.
     *
     * @param name          the parameter name
     * @param typeSignature the module-prefixed type signature (3-arg {@code getTypeSignature} form)
     * @param description   the parameter documentation from the init method's {@code parameterMap()}
     * @param optional      whether the parameter is defaultable/included-record (optional to supply)
     * @param defaultValue  the declared or type-derived default expression text
     */
    record InitParam(String name, String typeSignature, String description, boolean optional,
                     String defaultValue) {
    }

    /**
     * The listener's top-level init parameters: {@code REQUIRED}, {@code DEFAULTABLE},
     * {@code INCLUDED_RECORD} and rest parameters, in declaration order — the same set the
     * service-index stored under those kinds (never the flattened {@code INCLUDED_FIELD} rows).
     */
    List<InitParam> listenerInitParams(ClassSymbol listenerClass) {
        Optional<MethodSymbol> initOpt = listenerClass.initMethod();
        if (initOpt.isEmpty()) {
            return List.of();
        }
        MethodSymbol init = initOpt.get();
        Map<String, String> paramDocs = init.documentation()
                .map(Documentation::parameterMap)
                .orElse(Collections.emptyMap());

        List<InitParam> result = new ArrayList<>();
        init.typeDescriptor().params().ifPresent(params -> {
            for (ParameterSymbol param : params) {
                result.add(toInitParam(param, paramDocs));
            }
        });
        init.typeDescriptor().restParam().ifPresent(rest -> result.add(toInitParam(rest, paramDocs)));
        return result;
    }

    private InitParam toInitParam(ParameterSymbol param, Map<String, String> paramDocs) {
        String name = param.getName().orElse("");
        TypeSymbol typeSymbol = param.typeDescriptor();
        String typeSignature = CommonUtils.getTypeSignature(semanticModel, typeSymbol, false);
        boolean optional = param.paramKind() == ParameterKind.DEFAULTABLE
                || param.paramKind() == ParameterKind.INCLUDED_RECORD;

        String defaultValue = DefaultValueGeneratorUtil.getDefaultValueForType(typeSymbol);
        if (param.paramKind() == ParameterKind.DEFAULTABLE) {
            String declared = declaredDefaultValue(param);
            if (declared != null) {
                defaultValue = declared;
            }
        }
        return new InitParam(name, typeSignature, paramDocs.getOrDefault(name, ""), optional, defaultValue);
    }

    /**
     * Recovers a defaultable parameter's declared default expression from the package's syntax tree —
     * the same technique the service-index generator used, so values like
     * {@code { webhookSecret: DEFAULT_SECRET }} or {@code 8090} come through verbatim.
     */
    private String declaredDefaultValue(ParameterSymbol param) {
        Optional<Location> location = param.getLocation();
        if (location.isEmpty() || modulePackage == null) {
            return null;
        }
        Document document = findDocument(modulePackage, location.get().lineRange().fileName());
        if (document == null) {
            return null;
        }
        try {
            ModulePartNode rootNode = document.syntaxTree().rootNode();
            NonTerminalNode node = rootNode.findNode(TextRange.from(
                    location.get().textRange().startOffset(), location.get().textRange().length()));
            if (node.kind() != SyntaxKind.DEFAULTABLE_PARAM) {
                return null;
            }
            ExpressionNode expression = (ExpressionNode) ((DefaultableParameterNode) node).expression();
            String module = modulePackage.packageName().value();
            String alias = module.contains(".") ? module.substring(module.lastIndexOf('.') + 1) : module;
            if (expression instanceof SimpleNameReferenceNode simpleRef) {
                return alias + ":" + simpleRef.name().text();
            }
            if (expression instanceof QualifiedNameReferenceNode qualifiedRef) {
                return qualifiedRef.modulePrefix().text() + ":" + qualifiedRef.identifier().text();
            }
            return expression.toSourceCode();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Document findDocument(Package pkg, String fileName) {
        try {
            Project project = pkg.project();
            Module defaultModule = pkg.getDefaultModule();
            String module = pkg.packageName().value();
            Path docPath = project.sourceRoot().resolve("modules").resolve(module).resolve(fileName);
            DocumentId documentId = project.documentId(docPath);
            return defaultModule.document(documentId);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /**
     * One declared method of a concrete service object type.
     *
     * @param name                the method name (resource methods: the resource path)
     * @param kind                {@code "remote"} or {@code "resource"}
     * @param description         the method's doc-comment description
     * @param params              the method's parameters, in declaration order
     * @param returnTypeSignature the module-prefixed return type signature
     */
    record DeclaredMethod(String name, String kind, String description, List<DeclaredParam> params,
                          String returnTypeSignature) {
    }

    /**
     * One parameter of a {@link DeclaredMethod}.
     *
     * @param name          the parameter name
     * @param typeSignature the module-prefixed type signature
     * @param description   the parameter documentation from the method's {@code parameterMap()}
     * @param optional      whether the parameter is defaultable
     */
    record DeclaredParam(String name, String typeSignature, String description, boolean optional) {
    }

    /**
     * The remote/resource methods a concrete service object type declares, in declaration order.
     * Resource methods are named by their path (the service-index convention) and their accessor is
     * not carried — matching what the Copilot serves today.
     */
    List<DeclaredMethod> declaredMethods(ObjectTypeSymbol objectType) {
        List<DeclaredMethod> methods = new ArrayList<>();
        for (Map.Entry<String, MethodSymbol> entry : objectType.methods().entrySet()) {
            MethodSymbol method = entry.getValue();
            boolean remote = method.qualifiers().contains(Qualifier.REMOTE);
            boolean resource = method.qualifiers().contains(Qualifier.RESOURCE);
            if (!remote && !resource) {
                continue;
            }

            String name = method.getName().orElse(entry.getKey());
            if (resource && method instanceof ResourceMethodSymbol resourceMethod) {
                name = resourcePath(resourceMethod);
            }

            Optional<Documentation> documentation = method.documentation();
            String description = documentation.flatMap(Documentation::description).orElse("");
            Map<String, String> paramDocs = documentation.map(Documentation::parameterMap)
                    .orElse(Collections.emptyMap());

            List<DeclaredParam> params = new ArrayList<>();
            method.typeDescriptor().params().ifPresent(list -> {
                for (int i = 0; i < list.size(); i++) {
                    ParameterSymbol param = list.get(i);
                    // A declared method parameter always carries a name; the positional fallback is
                    // defensive only, and is indexed so two of them could never collide.
                    String paramName = param.getName().orElse("param" + (i + 1));
                    params.add(new DeclaredParam(
                            paramName,
                            CommonUtils.getTypeSignature(semanticModel, param.typeDescriptor(), false),
                            paramDocs.getOrDefault(paramName, ""),
                            param.paramKind() == ParameterKind.DEFAULTABLE));
                }
            });

            String returnSignature = method.typeDescriptor().returnTypeDescriptor()
                    .map(ret -> CommonUtils.getTypeSignature(semanticModel, ret, false))
                    .orElse("");
            methods.add(new DeclaredMethod(name, resource ? "resource" : "remote", description, params,
                    returnSignature));
        }
        return methods;
    }

    /** Renders a resource method's path the way the service-index generator named its rows. */
    private String resourcePath(ResourceMethodSymbol resourceMethod) {
        ResourcePath resourcePath = resourceMethod.resourcePath();
        List<String> paths = new ArrayList<>();
        switch (resourcePath.kind()) {
            case PATH_SEGMENT_LIST -> {
                for (Symbol pathSegment : ((PathSegmentList) resourcePath).list()) {
                    if (pathSegment instanceof PathParameterSymbol pathParam) {
                        String type = CommonUtils.getTypeSignature(semanticModel,
                                pathParam.typeDescriptor(), true);
                        paths.add("[%s %s]".formatted(type, pathParam.getName().orElse("")));
                    } else {
                        paths.add(pathSegment.getName().orElse(""));
                    }
                }
            }
            case DOT_RESOURCE_PATH -> paths.add(".");
            default -> paths.add("");
        }
        return String.join("/", paths);
    }
}
