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

import io.ballerina.servicemodelgenerator.extension.connector.ConnectorModelReader;
import io.ballerina.servicemodelgenerator.extension.connector.model.LibraryArtifact;
import io.ballerina.servicemodelgenerator.extension.model.Codedata;
import io.ballerina.servicemodelgenerator.extension.model.Function;
import io.ballerina.servicemodelgenerator.extension.model.MetaData;
import io.ballerina.servicemodelgenerator.extension.model.Parameter;
import io.ballerina.servicemodelgenerator.extension.model.context.ModelFromSourceContext;

import java.util.Objects;
import java.util.Optional;

import static io.ballerina.servicemodelgenerator.extension.util.ServiceModelUtils.getServiceTypeIdentifier;

/**
 * Generic, schema-driven function builder for connectors that ship the two JSON models. Function
 * <b>source generation</b> (add/update) is already connector-agnostic in {@link AbstractFunctionBuilder}
 * (via {@code Utils.generateFunctionDefSource}), so this builder inherits it. Its own contribution is
 * <b>edit enrichment</b>: when a function is read from source for editing, the raw parse is overlaid
 * with the connector's curated metadata (labels/descriptions/type constraints) and stamped with the
 * connector identity so the follow-up {@code updateFunction} routes back here.
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
    public Function getModelFromSource(ModelFromSourceContext context) {
        Function function = super.getModelFromSource(context);
        Optional<ConnectorModelReader.ConnectorModels> models = ConnectorModelReader.getInstance()
                .read(context.orgName(), context.packageName(), context.version());
        if (models.isPresent() && function != null) {
            overlayConnectorMetadata(function, models.get().metadataModel(), context.serviceType());
            stampCodedata(function, context);
        }
        return function;
    }

    /**
     * Overlays the connector's curated function/parameter metadata onto a source-parsed function.
     * The source parse yields the real names/types/ranges; the connector model supplies the human
     * labels, descriptions and type constraints the raw source cannot. Package-visible for testing.
     */
    static void overlayConnectorMetadata(Function function, LibraryArtifact metadata, String serviceType) {
        LibraryArtifact.FunctionModel model = findFunctionModel(metadata, serviceType,
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
                    .filter(p -> Objects.equals(p.name(), name))
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

    private static LibraryArtifact.FunctionModel findFunctionModel(LibraryArtifact metadata, String serviceType,
                                                                  String functionName) {
        if (metadata == null || metadata.serviceTypes() == null || functionName == null) {
            return null;
        }
        String typeKey = serviceType == null ? null : getServiceTypeIdentifier(serviceType);
        LibraryArtifact.ServiceType type = typeKey != null ? metadata.serviceTypes().get(typeKey) : null;
        if (type == null && metadata.serviceTypes().size() == 1) {
            type = metadata.serviceTypes().values().iterator().next();
        }
        if (type == null || type.functions() == null) {
            return null;
        }
        return type.functions().stream()
                .filter(f -> functionName.equals(f.name()))
                .findFirst()
                .orElse(null);
    }

    private static String orElse(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
