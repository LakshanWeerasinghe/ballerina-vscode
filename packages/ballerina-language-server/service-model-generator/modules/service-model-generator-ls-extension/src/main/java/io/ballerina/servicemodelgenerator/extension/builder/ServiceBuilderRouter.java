/*
 *  Copyright (c) 2025, WSO2 LLC. (http://www.wso2.com)
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

package io.ballerina.servicemodelgenerator.extension.builder;

import io.ballerina.compiler.api.ModuleID;
import io.ballerina.compiler.api.SemanticModel;
import io.ballerina.compiler.syntax.tree.Node;
import io.ballerina.compiler.syntax.tree.ServiceDeclarationNode;
import io.ballerina.projects.Document;
import io.ballerina.projects.Project;
import io.ballerina.servicemodelgenerator.extension.builder.service.AiChatServiceBuilder;
import io.ballerina.servicemodelgenerator.extension.builder.service.DefaultServiceBuilder;
import io.ballerina.servicemodelgenerator.extension.builder.service.GraphqlServiceBuilder;
import io.ballerina.servicemodelgenerator.extension.builder.service.HttpServiceBuilder;
import io.ballerina.servicemodelgenerator.extension.builder.service.McpServiceBuilder;
import io.ballerina.servicemodelgenerator.extension.builder.service.SchemaDrivenServiceBuilder;
import io.ballerina.servicemodelgenerator.extension.builder.service.SolaceServiceBuilder;
import io.ballerina.servicemodelgenerator.extension.builder.service.TCPServiceBuilder;
import io.ballerina.servicemodelgenerator.extension.connector.ConnectorModelReader;
import io.ballerina.servicemodelgenerator.extension.model.Service;
import io.ballerina.servicemodelgenerator.extension.model.ServiceInitModel;
import io.ballerina.servicemodelgenerator.extension.model.ServiceMetadata;
import io.ballerina.servicemodelgenerator.extension.model.context.AddModelContext;
import io.ballerina.servicemodelgenerator.extension.model.context.AddServiceInitModelContext;
import io.ballerina.servicemodelgenerator.extension.model.context.GetModelContext;
import io.ballerina.servicemodelgenerator.extension.model.context.GetServiceInitModelContext;
import io.ballerina.servicemodelgenerator.extension.model.context.ModelFromSourceContext;
import io.ballerina.servicemodelgenerator.extension.model.context.UpdateModelContext;
import io.ballerina.servicemodelgenerator.extension.model.request.ServiceModelRequest;
import io.ballerina.servicemodelgenerator.extension.util.ServiceModelUtils;
import org.ballerinalang.langserver.commons.workspace.WorkspaceManager;
import org.eclipse.lsp4j.TextEdit;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import static io.ballerina.servicemodelgenerator.extension.util.Constants.AI;
import static io.ballerina.servicemodelgenerator.extension.util.Constants.GRAPHQL;
import static io.ballerina.servicemodelgenerator.extension.util.Constants.HTTP;
import static io.ballerina.servicemodelgenerator.extension.util.Constants.MCP;
import static io.ballerina.servicemodelgenerator.extension.util.Constants.SOLACE;
import static io.ballerina.servicemodelgenerator.extension.util.Constants.TCP;
import static io.ballerina.servicemodelgenerator.extension.util.Constants.TRIGGER_HUBSPOT;

/**
 * ServiceBuilderRouter is responsible for routing service building requests to the appropriate service builder
 * based on the protocol type.
 *
 * @since 1.2.0
 */
public class ServiceBuilderRouter {

    // RABBITMQ/KAFKA/MSSQL/POSTGRESQL/MYSQL/FTP/TRIGGER_GITHUB/TRIGGER_SHOPIFY (and ASB, never
    // registered here) are deliberately absent: each now ships a bundled TriggerModel schema (see
    // ConnectorModelReader.BUNDLED_TRIGGER_MODEL_RESOURCES), so useSchemaDrivenPath always routes
    // them to SchemaDrivenServiceBuilder before this map is consulted — a hardcoded entry here
    // would be dead code. HTTP/AI/TCP/GRAPHQL/MCP/SOLACE are not (yet) schema-driven and keep their
    // dedicated builders.
    private static final Map<String, Supplier<? extends ServiceNodeBuilder>> CONSTRUCTOR_MAP = new HashMap<>() {{
        put(HTTP, HttpServiceBuilder::new);
        put(AI, AiChatServiceBuilder::new);
        put(TCP, TCPServiceBuilder::new);
        put(GRAPHQL, GraphqlServiceBuilder::new);
        put(MCP, McpServiceBuilder::new);
        put(SOLACE, SolaceServiceBuilder::new);
        // Hubspot has no bundled/`.bala` TriggerModel schema yet, so it keeps its dedicated builder
        // (unlike MSSQL/POSTGRESQL/MYSQL/FTP/TRIGGER_GITHUB/TRIGGER_SHOPIFY above, which are already
        // schema-driven and would be dead entries here).
    }};

    public static ServiceNodeBuilder getServiceBuilder(String protocol) {
        return CONSTRUCTOR_MAP.getOrDefault(protocol, DefaultServiceBuilder::new).get();
    }

    /**
     * Returns {@code true} when the connector's schema is available — either bundled as a classpath
     * resource in this jar (checked first, no network/bala-cache cost; lets a hardcoded builder migrate
     * onto the schema-driven path module-by-module), or, when no hardcoded builder is registered for
     * {@code moduleName}, resolved from the connector's {@code .bala} ({@code trigger-model.json}).
     * Otherwise the hardcoded builder wins (zero regression).
     */
    private static boolean useSchemaDrivenPath(String orgName, String pkgName, String moduleName, String version) {
        if (ConnectorModelReader.getInstance().hasBundledTriggerModel(moduleName)) {
            return true;
        }
        if (CONSTRUCTOR_MAP.containsKey(moduleName)) {
            return false;
        }
        return ConnectorModelReader.getInstance().hasTriggerModel(orgName, pkgName, version);
    }

    public static Optional<Service> getModelTemplate(String orgName, String moduleName) {
        // No package/version identity available at this call site — only the bundled-resource check
        // in useSchemaDrivenPath (which needs just the module name) can fire here; a connector
        // resolved solely via an external .bala schema falls through to the hardcoded/default
        // builder, same as before this method learned about the schema-driven path at all.
        NodeBuilder<?> serviceBuilder = useSchemaDrivenPath(orgName, null, moduleName, null)
                ? new SchemaDrivenServiceBuilder()
                : getServiceBuilder(moduleName);
        GetModelContext context = GetModelContext.fromOrgAndModule(orgName, moduleName);
        Optional<?> modelTemplate = serviceBuilder.getModelTemplate(context);
        if (modelTemplate.isEmpty() || !(modelTemplate.get() instanceof Service)) {
            return Optional.empty();
        }
        return Optional.of((Service) modelTemplate.get());
    }

    public static Service getServiceFromSource(Node node, Project project,
                                               SemanticModel semanticModel,
                                               WorkspaceManager workspaceManager, String filePath) {
        ServiceMetadata serviceMetadata = ServiceModelUtils.deriveServiceType(
                (ServiceDeclarationNode) node, semanticModel);
        if (Objects.isNull(serviceMetadata.moduleId())) {
            return null;
        }
        ModuleID moduleID = serviceMetadata.moduleId();

        NodeBuilder<Service> serviceBuilder = useSchemaDrivenPath(moduleID.orgName(),
                moduleID.packageName(), moduleID.moduleName(), moduleID.version())
                        ? new SchemaDrivenServiceBuilder()
                        : getServiceBuilder(moduleID.moduleName());
        ModelFromSourceContext context = new ModelFromSourceContext(node, project, semanticModel,
                workspaceManager, filePath, serviceMetadata.serviceType(), moduleID.orgName(),
                moduleID.packageName(), moduleID.moduleName(), moduleID.version());
        Service service = serviceBuilder.getModelFromSource(context);
        if (service != null) {
            service.getProperties().forEach((k, v) -> v.setAdvanced(false));
        }
        return service;
    }

    public static Map<String, List<TextEdit>> addService(Service service,
                                                         SemanticModel semanticModel, Project project,
                                                         WorkspaceManager workspaceManager,
                                                         String filePath, Document document) throws Exception {
        NodeBuilder<Service> serviceBuilder = useSchemaDrivenPath(service.getOrgName(), service.getPackageName(),
                service.getModuleName(), service.getVersion())
                        ? new SchemaDrivenServiceBuilder()
                        : getServiceBuilder(service.getModuleName());
        AddModelContext context = new AddModelContext(service, null, semanticModel, project,
                workspaceManager, filePath, document, null);
        return serviceBuilder.addModel(context);
    }

    public static Map<String, List<TextEdit>> updateService(Service service,
                                                            SemanticModel semanticModel,
                                                            WorkspaceManager workspaceManager,
                                                            String filePath, Document document,
                                                            ServiceDeclarationNode serviceNode) throws Exception {
        NodeBuilder<?> serviceBuilder = useSchemaDrivenPath(service.getOrgName(), service.getPackageName(),
                service.getModuleName(), service.getVersion())
                        ? new SchemaDrivenServiceBuilder()
                        : getServiceBuilder(service.getModuleName());
        UpdateModelContext context = new UpdateModelContext(service, null, semanticModel, null,
                workspaceManager, filePath, document, serviceNode, null);
        return serviceBuilder.updateModel(context);
    }

    public static ServiceInitModel getServiceInitModel(ServiceModelRequest request, Project project,
                                                       SemanticModel semanticModel, Document document) {
        GetServiceInitModelContext context = new GetServiceInitModelContext(
                request.orgName(), request.pkgName(), request.moduleName(), request.version(),
                project, semanticModel, document);
        ServiceNodeBuilder serviceBuilder =
                useSchemaDrivenPath(request.orgName(), request.pkgName(), request.moduleName(), request.version())
                        ? new SchemaDrivenServiceBuilder()
                        : getServiceBuilder(request.moduleName());
        return serviceBuilder.getServiceInitModel(context);
    }

    public static Map<String, List<TextEdit>> addServiceInitSource(ServiceInitModel serviceInitModel,
                                                                   SemanticModel semanticModel,
                                                                   Project project, WorkspaceManager workspaceManager,
                                                                   String filePath,
                                                                   Document document)
            throws Exception {
        AddServiceInitModelContext context = new AddServiceInitModelContext(serviceInitModel, semanticModel, project,
                workspaceManager, filePath, document);
        ServiceNodeBuilder serviceBuilder = useSchemaDrivenPath(serviceInitModel.getOrgName(),
                serviceInitModel.getPackageName(), serviceInitModel.getModuleName(), serviceInitModel.getVersion())
                        ? new SchemaDrivenServiceBuilder()
                        : getServiceBuilder(serviceInitModel.getModuleName());
        return serviceBuilder.addServiceInitSource(context);
    }
}
