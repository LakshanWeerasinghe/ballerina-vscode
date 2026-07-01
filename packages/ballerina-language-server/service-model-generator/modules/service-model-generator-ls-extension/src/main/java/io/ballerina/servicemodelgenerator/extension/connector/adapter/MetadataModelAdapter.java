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

package io.ballerina.servicemodelgenerator.extension.connector.adapter;

import io.ballerina.modelgenerator.commons.CommonUtils;
import io.ballerina.servicemodelgenerator.extension.connector.model.LibraryArtifact;
import io.ballerina.servicemodelgenerator.extension.model.Codedata;
import io.ballerina.servicemodelgenerator.extension.model.Function;
import io.ballerina.servicemodelgenerator.extension.model.PropertyType;
import io.ballerina.servicemodelgenerator.extension.model.MetaData;
import io.ballerina.servicemodelgenerator.extension.model.Service;
import io.ballerina.servicemodelgenerator.extension.model.Value;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.ballerina.servicemodelgenerator.extension.util.Constants.PROP_KEY_BASE_PATH;
import static io.ballerina.servicemodelgenerator.extension.util.Constants.PROP_KEY_LISTENER;
import static io.ballerina.servicemodelgenerator.extension.util.Constants.PROP_KEY_SERVICE_TYPE;
import static io.ballerina.servicemodelgenerator.extension.util.ServiceModelUtils.getListenersProperty;
import static io.ballerina.servicemodelgenerator.extension.util.ServiceModelUtils.getProtocol;

/**
 * Adapts a connector-shipped {@link LibraryArtifact} (Service Metadata Model) into a wire
 * {@link Service} <i>template</i> — the connector-model equivalent of
 * {@code AbstractServiceBuilder.getModelTemplate} (which builds the same shape from the SQLite index).
 * Used by the designer flow: the template (identity + listener/serviceType/basePath properties + the
 * service type's handler {@link io.ballerina.servicemodelgenerator.extension.model.Function}s) is then
 * merged with the user's source.
 *
 * @since 1.8.0
 */
public final class MetadataModelAdapter {

    private static final String DEFAULT_SERVICE_TYPE = "Service";

    private MetadataModelAdapter() {
    }

    /**
     * Builds a wire service template for the given service type from the metadata model.
     *
     * @param metadata    the connector's Service Metadata Model
     * @param serviceType the service type identifier (e.g. {@code Service}, {@code IssuesService});
     *                    falls back to the sole service type when null/absent
     * @param orgName     the connector org (from the resolved source)
     * @param packageName the connector package (from the resolved source)
     * @param moduleName  the connector module (drives the listener protocol)
     * @return the wire service template, or {@code null} if the service type is unknown
     */
    public static Service toServiceTemplate(LibraryArtifact metadata, String serviceType,
                                            String orgName, String packageName, String moduleName) {
        if (metadata == null || metadata.serviceTypes() == null) {
            return null;
        }
        LibraryArtifact.ServiceType type = resolveServiceType(metadata, serviceType);
        if (type == null) {
            return null;
        }
        LibraryArtifact.ServiceDeclaration declaration = metadata.serviceDeclaration();
        String protocol = getProtocol(moduleName);
        String displayName = declaration != null && declaration.displayName() != null
                ? declaration.displayName() : metadata.name();

        Map<String, Value> properties = new LinkedHashMap<>();
        Service service = new Service.ServiceModelBuilder()
                .setId("0")
                .setName(displayName)
                .setType(moduleName)
                .setDisplayName(displayName)
                .setModuleName(moduleName)
                .setOrgName(orgName)
                .setVersion(metadata.version())
                .setPackageName(packageName)
                .setListenerProtocol(protocol)
                .setIcon(CommonUtils.generateIcon(orgName, packageName, metadata.version()))
                .setProperties(properties)
                .setFunctions(new ArrayList<>())
                .build();

        properties.put(PROP_KEY_LISTENER, getListenersProperty(protocol, listenerKind(declaration)));
        properties.put(PROP_KEY_SERVICE_TYPE, serviceTypeProperty(type.name(), declaration));
        if (declaration != null && !declaration.optionalAbsoluteResourcePath()) {
            properties.put(PROP_KEY_BASE_PATH, basePathProperty(declaration));
        }

        if (type.functions() != null) {
            type.functions().forEach(function -> {
                Function wireFunction = FunctionModelAdapter.toFunction(function);
                // Stamp connector identity so the round-tripped function resolves back to the
                // schema-driven path on add/edit (FunctionBuilderRouter routes by codedata.moduleName).
                wireFunction.setCodedata(new Codedata.Builder()
                        .setOrgName(orgName)
                        .setPackageName(packageName)
                        .setModuleName(moduleName)
                        .setVersion(metadata.version())
                        .build());
                service.getFunctions().add(wireFunction);
            });
        }
        return service;
    }

    private static LibraryArtifact.ServiceType resolveServiceType(LibraryArtifact metadata, String serviceType) {
        if (serviceType != null && metadata.serviceTypes().containsKey(serviceType)) {
            return metadata.serviceTypes().get(serviceType);
        }
        if (metadata.serviceTypes().size() == 1) {
            return metadata.serviceTypes().values().iterator().next();
        }
        return metadata.serviceTypes().get(DEFAULT_SERVICE_TYPE);
    }

    private static Value.FieldType listenerKind(LibraryArtifact.ServiceDeclaration declaration) {
        if (declaration != null && declaration.listenerKind() != null) {
            try {
                return Value.FieldType.valueOf(declaration.listenerKind());
            } catch (IllegalArgumentException ignored) {
                // unknown kind -> default below
            }
        }
        return Value.FieldType.SINGLE_SELECT_LISTENER;
    }

    private static Value serviceTypeProperty(String serviceType, LibraryArtifact.ServiceDeclaration declaration) {
        String label = declaration != null && declaration.typeDescriptorLabel() != null
                ? declaration.typeDescriptorLabel() : "Service Type";
        String description = declaration != null ? declaration.typeDescriptorDescription() : "";
        return new Value.ValueBuilder()
                .setMetadata(new MetaData(label, description))
                .value(serviceType)
                .types(List.of(PropertyType.types(Value.FieldType.TYPE)))
                .setPlaceholder(serviceType)
                .enabled(true)
                .editable(false)
                .build();
    }

    private static Value basePathProperty(LibraryArtifact.ServiceDeclaration declaration) {
        String label = declaration.absoluteResourcePathLabel() != null
                ? declaration.absoluteResourcePathLabel() : "Service Base Path";
        return new Value.ValueBuilder()
                .setMetadata(new MetaData(label, declaration.absoluteResourcePathDescription()))
                .value(declaration.absoluteResourcePathDefaultValue())
                .types(List.of(PropertyType.types(Value.FieldType.SERVICE_PATH)))
                .setPlaceholder(declaration.absoluteResourcePathDefaultValue())
                .enabled(true)
                .editable(true)
                .build();
    }
}
