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

package io.ballerina.servicemodelgenerator.extension.builder.service;

import io.ballerina.compiler.syntax.tree.ModulePartNode;
import io.ballerina.compiler.syntax.tree.ServiceDeclarationNode;
import io.ballerina.servicemodelgenerator.extension.connector.ConnectorModelReader;
import io.ballerina.servicemodelgenerator.extension.connector.ExistingListenerResolver;
import io.ballerina.servicemodelgenerator.extension.connector.SchemaDrivenSourceGenerator;
import io.ballerina.servicemodelgenerator.extension.connector.adapter.MetadataModelAdapter;
import io.ballerina.servicemodelgenerator.extension.model.Codedata;
import io.ballerina.servicemodelgenerator.extension.model.Service;
import io.ballerina.servicemodelgenerator.extension.model.ServiceInitModel;
import io.ballerina.servicemodelgenerator.extension.model.Value;
import io.ballerina.servicemodelgenerator.extension.model.context.AddServiceInitModelContext;
import io.ballerina.servicemodelgenerator.extension.model.context.GetServiceInitModelContext;
import io.ballerina.servicemodelgenerator.extension.model.context.ModelFromSourceContext;
import io.ballerina.servicemodelgenerator.extension.util.ListenerUtil;
import io.ballerina.servicemodelgenerator.extension.util.Utils;
import org.eclipse.lsp4j.TextEdit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static io.ballerina.servicemodelgenerator.extension.model.ServiceInitModel.KEY_CONFIGURE_LISTENER;
import static io.ballerina.servicemodelgenerator.extension.model.ServiceInitModel.KEY_EXISTING_LISTENER;
import static io.ballerina.servicemodelgenerator.extension.model.ServiceInitModel.KEY_LISTENER_VAR_NAME;
import static io.ballerina.servicemodelgenerator.extension.util.Constants.LISTENER_VAR_NAME;
import static io.ballerina.servicemodelgenerator.extension.util.ServiceModelUtils.getProtocol;
import static io.ballerina.servicemodelgenerator.extension.util.ServiceModelUtils.getServiceTypeIdentifier;

/**
 * Generic, schema-driven service builder for connectors that ship the two JSON models in their
 * {@code .bala}. It serves the add-event-integration flow ({@code getServiceInitModel} +
 * {@code addServiceAndListener}) with no per-connector code: the init form comes straight from the
 * connector's Service Creation Model, and the source is emitted by {@link SchemaDrivenSourceGenerator}
 * from the models' {@code codedata}.
 *
 * <p>Selected by {@code ServiceBuilderRouter} only when no hardcoded builder is registered for the
 * module and {@link ConnectorModelReader} finds both models — so existing connectors are unaffected.
 * The remaining {@code NodeBuilder} operations (designer/edit) are inherited from
 * {@link AbstractServiceBuilder} for now and will be specialised in later milestones.
 *
 * @since 1.8.0
 */
public class SchemaDrivenServiceBuilder extends AbstractServiceBuilder {

    public static final String KIND = "schema-driven";

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public ServiceInitModel getServiceInitModel(GetServiceInitModelContext context) {
        Optional<ConnectorModelReader.ConnectorModels> models = ConnectorModelReader.getInstance()
                .read(context.orgName(), context.packageName(), context.version());
        if (models.isEmpty()) {
            return null;
        }
        ServiceInitModel creationModel = models.get().creationModel();
        refreshListenerName(creationModel, context);
        populateExistingListeners(creationModel, context);
        return creationModel;
    }

    @Override
    public Map<String, List<TextEdit>> addServiceInitSource(AddServiceInitModelContext context) {
        ServiceInitModel filledModel = context.serviceInitModel();
        Optional<ConnectorModelReader.ConnectorModels> models = ConnectorModelReader.getInstance()
                .read(filledModel.getOrgName(), filledModel.getPackageName(), filledModel.getVersion());
        if (models.isEmpty()) {
            return Map.of();
        }
        ModulePartNode rootNode = context.document().syntaxTree().rootNode();
        return SchemaDrivenSourceGenerator.buildAddServiceEdits(
                filledModel, models.get().metadataModel(), rootNode, context.filePath());
    }

    @Override
    public Service getModelFromSource(ModelFromSourceContext context) {
        Optional<ConnectorModelReader.ConnectorModels> models = ConnectorModelReader.getInstance()
                .read(context.orgName(), context.packageName(), context.version());
        if (models.isEmpty()) {
            // Not a schema-driven connector after all -> fall back to the DB-backed behaviour.
            return super.getModelFromSource(context);
        }
        if (Objects.isNull(context.serviceType())) {
            return null;
        }
        String serviceType = getServiceTypeIdentifier(context.serviceType());
        Service serviceModel = MetadataModelAdapter.toServiceTemplate(models.get().metadataModel(),
                serviceType, context.orgName(), context.packageName(), context.moduleName());
        if (serviceModel == null) {
            return null;
        }
        // Lock the service type to the resolved value, then merge in the user's source (functions
        // present, base path, listeners, line ranges) using the shared extraction pipeline.
        serviceModel.getServiceType().setValue(serviceType);
        serviceModel.getServiceType().setEditable(false);
        populateServiceModelFromSource(serviceModel, (ServiceDeclarationNode) context.node(), context);
        return serviceModel;
    }

    /**
     * Populates the "use existing" branch of the listener {@code configureListener} CHOICE with the
     * listeners of this connector's type already present in the project, so the user can attach to one
     * instead of creating a new listener. Mirrors the hardcoded builders (FTP/Solace/…): by convention
     * {@code choices[0]} is "create new" and {@code choices[1]} is "use existing", and the selection is
     * a {@code SINGLE_SELECT} bound to {@code KEY_EXISTING_LISTENER}. When the project has no compatible
     * listener, the "use existing" branch is disabled so only the create-new path is offered.
     */
    private void populateExistingListeners(ServiceInitModel creationModel, GetServiceInitModelContext context) {
        Value configureListener = creationModel.getProperties().get(KEY_CONFIGURE_LISTENER);
        if (configureListener == null || configureListener.getChoices() == null
                || configureListener.getChoices().size() < 2) {
            return;
        }
        List<Value> choices = configureListener.getChoices();
        Set<String> listeners = ListenerUtil.getCompatibleListeners(context.moduleName(),
                context.semanticModel(), context.project());
        Value selector = null;
        if (!listeners.isEmpty()) {
            Value createNewBranch = choices.get(indexOfCreateNewBranch(choices));
            // Resolve each existing listener's config from the model (create-new params as the field
            // template) + the source (its new(...) args), like the FTP/RabbitMQ builders do.
            selector = ExistingListenerResolver.buildSelector(createNewBranch, new ArrayList<>(listeners),
                    context.semanticModel(), context.project(), getProtocol(context.moduleName()));
        }
        applyListenerChoiceSelection(configureListener, selector);
    }

    /**
     * Wires the listener {@code configureListener} CHOICE (pure; unit-testable). Identifies the "create
     * new" branch by content (it carries listener params) and treats the other as "use existing". When a
     * {@code selector} is supplied (listeners exist), it is nested in the use-existing branch's
     * {@code listenerConfig} GROUP_SECTION (like FTP/RabbitMQ) and that branch becomes the enabled
     * default; when {@code selector} is null, the use-existing branch is disabled and create-new is the
     * default.
     */
    static void applyListenerChoiceSelection(Value configureListener, Value selector) {
        List<Value> choices = configureListener.getChoices();
        if (choices == null || choices.size() < 2) {
            return;
        }
        int createNewIndex = indexOfCreateNewBranch(choices);
        int useExistingIndex = createNewIndex == 0 ? 1 : 0;
        Value createNewChoice = choices.get(createNewIndex);
        Value useExistingChoice = choices.get(useExistingIndex);

        if (selector == null) {
            // Nothing to attach to: offer only the create-new path and disable the use-existing radio.
            useExistingChoice.setEnabled(false);
            useExistingChoice.setEditable(false);
            createNewChoice.setEnabled(true);
            createNewChoice.setEditable(true);
            configureListener.setValue(String.valueOf(createNewIndex));
            return;
        }

        Map<String, Value> existingProps = new LinkedHashMap<>();
        existingProps.put(KEY_EXISTING_LISTENER, selector);
        Value group = firstGroupSection(useExistingChoice);
        if (group != null) {
            group.setProperties(existingProps);
        } else {
            useExistingChoice.setProperties(existingProps);
        }
        // Existing listeners are available -> enable and default-select the "use existing" branch.
        // Both branches must be `editable` so the front-end radio (ChoiceForm) lets the user switch
        // between "Create new" and "Use existing".
        useExistingChoice.setEnabled(true);
        useExistingChoice.setEditable(true);
        createNewChoice.setEnabled(false);
        createNewChoice.setEditable(true);
        configureListener.setValue(String.valueOf(useExistingIndex));
    }

    private static Value firstGroupSection(Value branch) {
        if (branch.getProperties() == null) {
            return null;
        }
        for (Value child : branch.getProperties().values()) {
            if (child.getTypes() != null
                    && child.getTypes().stream().anyMatch(type -> type.fieldType() == Value.FieldType.GROUP_SECTION)) {
                return child;
            }
        }
        return null;
    }

    /**
     * Returns the index of the "create new" branch — the one that carries listener parameters
     * ({@code LISTENER_PARAM_*} / {@code LISTENER_VAR_NAME}). Defaults to 0 when none is detected.
     */
    private static int indexOfCreateNewBranch(List<Value> choices) {
        for (int i = 0; i < choices.size(); i++) {
            if (hasListenerParams(choices.get(i))) {
                return i;
            }
        }
        return 0;
    }

    private static boolean hasListenerParams(Value node) {
        if (node == null) {
            return false;
        }
        Codedata codedata = node.getCodedata();
        if (codedata != null) {
            String argType = codedata.getArgType();
            if ((argType != null && argType.startsWith("LISTENER_PARAM"))
                    || "LISTENER_VAR_NAME".equals(codedata.getType())
                    || "LISTENER_VAR_NAME".equals(argType)) {
                return true;
            }
        }
        if (node.getProperties() != null) {
            for (Value child : node.getProperties().values()) {
                if (hasListenerParams(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Replaces the shipped default listener variable name with a project-unique identifier, mirroring
     * the hardcoded builders so a second trigger of the same kind does not collide.
     */
    private void refreshListenerName(ServiceInitModel creationModel, GetServiceInitModelContext context) {
        Value listenerName = creationModel.getProperties().get(KEY_LISTENER_VAR_NAME);
        if (listenerName == null) {
            return;
        }
        String uniqueName = Utils.generateVariableIdentifier(context.semanticModel(), context.document(),
                context.document().syntaxTree().rootNode().lineRange().endLine(),
                LISTENER_VAR_NAME.formatted(getProtocol(context.moduleName())));
        listenerName.setValue(uniqueName);
    }
}
