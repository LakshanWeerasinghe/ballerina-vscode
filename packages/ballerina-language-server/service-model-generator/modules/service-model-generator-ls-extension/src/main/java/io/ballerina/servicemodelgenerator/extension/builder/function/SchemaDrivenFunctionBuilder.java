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

package io.ballerina.servicemodelgenerator.extension.builder.function;

import io.ballerina.servicemodelgenerator.extension.connector.AnnotationEmitter;
import io.ballerina.servicemodelgenerator.extension.connector.ConnectorModelReader;
import io.ballerina.servicemodelgenerator.extension.connector.adapter.PropertyValueAdapter;
import io.ballerina.servicemodelgenerator.extension.connector.model.TriggerModel;
import io.ballerina.servicemodelgenerator.extension.model.Codedata;
import io.ballerina.servicemodelgenerator.extension.model.Function;
import io.ballerina.servicemodelgenerator.extension.model.MetaData;
import io.ballerina.servicemodelgenerator.extension.model.Parameter;
import io.ballerina.servicemodelgenerator.extension.model.Value;
import io.ballerina.servicemodelgenerator.extension.model.context.AddModelContext;
import io.ballerina.servicemodelgenerator.extension.model.context.ModelFromSourceContext;
import io.ballerina.servicemodelgenerator.extension.model.context.UpdateModelContext;
import org.eclipse.lsp4j.TextEdit;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static io.ballerina.servicemodelgenerator.extension.util.Constants.CD_TYPE_ANNOTATION_ATTACHMENT;
import static io.ballerina.servicemodelgenerator.extension.util.ServiceModelUtils.getServiceTypeIdentifier;

/**
 * Generic, schema-driven function builder for connectors that ship a unified {@link TriggerModel}.
 * Function <b>source generation</b> (add/update) is already connector-agnostic in {@link AbstractFunctionBuilder}
 * (via {@code Utils.generateFunctionDefSource}), so this builder inherits it. Its own contributions:
 *
 * <ul>
 *   <li><b>Edit enrichment</b> — when a function is read from source for editing, the raw parse is
 *       overlaid with the connector's curated metadata (labels/descriptions/type constraints) and
 *       stamped with the connector identity so the follow-up {@code updateFunction} routes back here.</li>
 *   <li><b>Annotation pre-render</b> — before add/update, an edited unified-model annotation tree
 *       ({@code codedata.type == COMPLEX_FUNCTION_ANNOTATION}, e.g. {@code @smb:FunctionConfig})
 *       collapses into the generic {@code ANNOTATION_ATTACHMENT} property the wire emitter already
 *       understands, its value rendered by {@link AnnotationEmitter}.</li>
 * </ul>
 *
 * @since 1.8.0
 */
public class SchemaDrivenFunctionBuilder extends AbstractFunctionBuilder {

    public static final String KIND = "schema-driven";

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public Map<String, List<TextEdit>> addModel(AddModelContext context) throws Exception {
        renderComplexAnnotations(context.function());
        return super.addModel(context);
    }

    @Override
    public Map<String, List<TextEdit>> updateModel(UpdateModelContext context) {
        renderComplexAnnotations(context.function());
        return super.updateModel(context);
    }

    /**
     * Collapses every COMPLEX_FUNCTION_ANNOTATION property (the granular MAPPING_FIELD /
     * FIELD_VALUE_CHOICE tree the UI edits) into an ANNOTATION_ATTACHMENT property carrying the
     * rendered mapping body, which the generic wire emitter turns into
     * {@code @<module>:<Name> {field: value, ...}}. A tree whose fields are all unchecked renders no
     * attachment (the property is disabled instead). Public for testing.
     */
    public static void renderComplexAnnotations(Function function) {
        if (function == null || function.getProperties() == null) {
            return;
        }
        for (Map.Entry<String, Value> entry : function.getProperties().entrySet()) {
            Value property = entry.getValue();
            Codedata codedata = property.getCodedata();
            if (codedata == null || !"COMPLEX_FUNCTION_ANNOTATION".equals(codedata.getType())) {
                continue;
            }
            Optional<String> body = AnnotationEmitter.annotationBody(PropertyValueAdapter.toProperty(property));
            Codedata attachment = new Codedata(CD_TYPE_ANNOTATION_ATTACHMENT);
            attachment.setOriginalName(codedata.getOriginalName());
            attachment.setModuleName(codedata.getModuleName());
            Value rendered = new Value.ValueBuilder()
                    .setMetadata(property.getMetadata())
                    .value(body.orElse(""))
                    .enabled(body.isPresent())
                    .editable(true)
                    .setCodedata(attachment)
                    .build();
            entry.setValue(rendered);
        }
    }

    @Override
    public Function getModelFromSource(ModelFromSourceContext context) {
        Function function = super.getModelFromSource(context);
        if (function == null) {
            return null;
        }
        // Prefer a bundled schema, then the unified TriggerModel resolved from the connector's .bala.
        // Either way, stamp the connector identity so the follow-up addFunction/updateFunction routes
        // back to this builder (FunctionBuilderRouter reads org/pkg/module off the function's Codedata).
        Optional<TriggerModel> triggerModel = ConnectorModelReader.getInstance()
                .getBundledTriggerModel(context.moduleName())
                .or(() -> ConnectorModelReader.getInstance()
                        .readTriggerModel(context.orgName(), context.packageName(), context.version()));
        if (triggerModel.isPresent()) {
            overlayConnectorMetadata(function, triggerModel.get(), context.serviceType());
            stampCodedata(function, context);
        }
        return function;
    }

    /**
     * Overlays the connector's curated function/parameter metadata onto a source-parsed function from
     * the unified {@link TriggerModel} (bundled or resolved from a {@code .bala}). The source parse
     * yields the real names/types/ranges; the connector model supplies the human labels, descriptions
     * and type constraints the raw source cannot. Package-visible for testing.
     */
    static void overlayConnectorMetadata(Function function, TriggerModel triggerModel, String serviceType) {
        TriggerModel.FunctionModel model = findFunctionModel(triggerModel, serviceType,
                function.getName() != null ? function.getName().getValue() : null);
        if (model == null) {
            return;
        }
        if (model.metadata() != null) {
            function.setMetadata(new MetaData(
                    orElse(model.metadata().label(), function.getName().getValue()),
                    orElse(model.metadata().description(), "")));
        }
        if (model.parameters() == null || function.getParameters() == null) {
            return;
        }
        for (Parameter wireParam : function.getParameters()) {
            String name = wireParam.getName() != null ? wireParam.getName().getValue() : null;
            model.parameters().stream()
                    .filter(p -> p.name() != null && Objects.equals(String.valueOf(p.name().value()), name))
                    .findFirst()
                    .ifPresent(p -> {
                        if (p.metadata() != null) {
                            wireParam.setMetadata(new MetaData(
                                    orElse(p.metadata().label(), name),
                                    orElse(p.metadata().description(), "")));
                        }
                    });
        }
    }

    private static TriggerModel.FunctionModel findFunctionModel(TriggerModel triggerModel, String serviceType,
                                                                  String functionName) {
        if (triggerModel == null || triggerModel.serviceTypes() == null || functionName == null) {
            return null;
        }
        TriggerModel.ServiceTypeModel type = findServiceType(triggerModel, serviceType);
        if (type == null) {
            return null;
        }
        TriggerModel.FunctionModel found = findByName(type.functions(), functionName);
        return found != null ? found : findByName(type.schemaFunctions(), functionName);
    }

    private static TriggerModel.ServiceTypeModel findServiceType(TriggerModel triggerModel, String serviceType) {
        String typeKey = serviceType == null ? null : getServiceTypeIdentifier(serviceType);
        if (typeKey != null) {
            for (TriggerModel.ServiceTypeModel candidate : triggerModel.serviceTypes()) {
                if (typeKey.equals(candidate.name())
                        || (candidate.name() != null && candidate.name().endsWith(":" + typeKey))
                        || (candidate.codedata() != null && typeKey.equals(candidate.codedata().originalName()))) {
                    return candidate;
                }
            }
        }
        return triggerModel.serviceTypes().size() == 1 ? triggerModel.serviceTypes().get(0) : null;
    }

    private static TriggerModel.FunctionModel findByName(List<TriggerModel.FunctionModel> functions, String name) {
        if (functions == null) {
            return null;
        }
        return functions.stream().filter(f -> name.equals(f.name())).findFirst().orElse(null);
    }

    private void stampCodedata(Function function, ModelFromSourceContext context) {
        Codedata codedata = function.getCodedata();
        if (codedata == null) {
            codedata = new Codedata();
            function.setCodedata(codedata);
        }
        codedata.setOrgName(context.orgName());
        codedata.setPackageName(context.packageName());
        codedata.setModuleName(context.moduleName());
        if (context.version() != null) {
            codedata.setVersion(context.version());
        }
    }

    private static String orElse(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
