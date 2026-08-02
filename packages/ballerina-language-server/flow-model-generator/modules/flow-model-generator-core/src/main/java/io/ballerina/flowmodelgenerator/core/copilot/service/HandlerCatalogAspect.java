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

import io.ballerina.modelgenerator.commons.trigger.models.TriggerMetadataModel;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Spec §4/§5 — builds every handler of a service type, and through them every parameter.
 *
 * <p>This is the only component that knows how many handlers exist, so it is the one that drives the
 * handler and parameter tiers. Running last among the service aspects is therefore not a convention but a
 * consequence: the tiers below it cannot exist until it has resolved the catalog.
 *
 * @since 1.7.0
 */
final class HandlerCatalogAspect implements ServiceAspect {

    private final AspectRegistry registry;

    HandlerCatalogAspect(AspectRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String id() {
        return "handlerCatalog";
    }

    @Override
    public String specSection() {
        return "§4";
    }

    @Override
    public void contribute(TriggerScope scope, ServiceDraft draft) {
        String typeName = scope.serviceTypeName();
        HandlerCatalogResolver.HandlerCatalog catalog =
                HandlerCatalogResolver.resolve(scope.serviceType(), typeName, scope.facts());

        switch (catalog) {
            case HandlerCatalogResolver.HandlerCatalog.None none ->
                    draft.veto(id(), specSection(), typeName, none.reason());
            case HandlerCatalogResolver.HandlerCatalog.Concrete concrete ->
                    buildDeclared(scope, draft, concrete.methods());
            case HandlerCatalogResolver.HandlerCatalog.Options options ->
                    buildFromOptions(scope, draft, options.options());
        }
    }

    /** A concrete service type: every handler and parameter is read from the semantic model. */
    private void buildDeclared(TriggerScope scope, ServiceDraft draft,
                               List<TriggerSemanticFacts.DeclaredMethod> methods) {
        for (TriggerSemanticFacts.DeclaredMethod declared : methods) {
            HandlerScope handlerScope = new HandlerScope(scope, null, declared);
            HandlerDraft handlerDraft = runHandlerAspects(handlerScope);

            List<TriggerSemanticFacts.DeclaredParam> params = declared.params();
            for (int i = 0; i < params.size(); i++) {
                ParamScope paramScope = new ParamScope(handlerScope, null, params.get(i), i, Set.of());
                handlerDraft.addParam(runParamAspects(paramScope));
            }
            draft.addHandler(handlerDraft);
        }
    }

    /** A marker service type: the document's options are the only source of truth. */
    private void buildFromOptions(TriggerScope scope, ServiceDraft draft,
                                  List<TriggerMetadataModel.ServiceType.HandlerOption> options) {
        if (options == null) {
            return;
        }
        for (TriggerMetadataModel.ServiceType.HandlerOption option : options) {
            if (option == null || option.name() == null
                    || TriggerMetadataModel.ServiceType.HandlerOption.WILDCARD_NAME.equals(option.name())) {
                // A wildcard slot is an open-ended, user-named handler; it has no fixed signature to
                // emit. Rendering it as a template is a later phase.
                continue;
            }

            HandlerScope handlerScope = new HandlerScope(scope, option, null);
            HandlerDraft handlerDraft = new HandlerDraft();

            if (ParamTypeResolver.signatureReferencesUndeclaredType(option, scope.declaresType())) {
                handlerDraft.veto(id(), specSection(), option.name(),
                        "its signature references a type the resolved package does not declare");
                draft.addHandler(handlerDraft);
                continue;
            }

            for (HandlerAspect aspect : registry.handlerAspects()) {
                aspect.contribute(handlerScope, handlerDraft);
            }
            buildOptionParams(handlerScope, handlerDraft, option.params());
            draft.addHandler(handlerDraft);
        }
    }

    /**
     * Builds a metadata handler's parameters.
     *
     * <p>The name pool is seeded with every authored name in the option <i>before</i> any name is
     * generated, so a generated name can never collide with an authored one declared later in the list.
     * The positional fallback uses the slot's index in the full list, including slots skipped as
     * repeatable, which keeps a generated name stable when an unrelated slot is added or removed.
     */
    private void buildOptionParams(HandlerScope handlerScope, HandlerDraft handlerDraft,
                                   List<TriggerMetadataModel.ServiceType.Param> params) {
        if (params == null || params.isEmpty()) {
            return;
        }
        Set<String> usedNames = new HashSet<>();
        for (TriggerMetadataModel.ServiceType.Param param : params) {
            if (param != null && param.name() != null) {
                usedNames.add(param.name());
            }
        }
        for (int i = 0; i < params.size(); i++) {
            TriggerMetadataModel.ServiceType.Param param = params.get(i);
            if (ParamTypeResolver.isRepeatable(param)) {
                continue;
            }
            ParamScope paramScope = new ParamScope(handlerScope, param, null, i, usedNames);
            handlerDraft.addParam(runParamAspects(paramScope));
        }
    }

    private HandlerDraft runHandlerAspects(HandlerScope scope) {
        HandlerDraft draft = new HandlerDraft();
        for (HandlerAspect aspect : registry.handlerAspects()) {
            aspect.contribute(scope, draft);
        }
        return draft;
    }

    private ParamDraft runParamAspects(ParamScope scope) {
        ParamDraft draft = new ParamDraft();
        for (ParamAspect aspect : registry.paramAspects()) {
            aspect.contribute(scope, draft);
        }
        return draft;
    }
}
