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
            case HandlerCatalogResolver.HandlerCatalog.Options options -> {
                if (options.authorNamed()) {
                    draft.setAuthorNamedHandlers();
                }
                buildFromOptions(scope, draft, options.options());
            }
            case HandlerCatalogResolver.HandlerCatalog.Many many ->
                    buildTemplate(scope, draft, many.template());
        }
    }

    /**
     * An open-ended service type: the wildcard describes the shape of a handler the author will name.
     *
     * <p>Built through the <b>same</b> handler and parameter aspects a named option goes through, rather
     * than by a bespoke path. That is what keeps §5's kind and return, §7's types and alternatives, and
     * §8's function- and parameter-scope obligations owned by exactly one component each: the template
     * gains every one of them for free, and a later spec change to any of them cannot leave the template
     * behind.
     */
    private void buildTemplate(TriggerScope scope, ServiceDraft draft,
                               TriggerMetadataModel.ServiceType.HandlerOption template) {
        HandlerScope handlerScope = new HandlerScope(scope, template, null);
        HandlerDraft handlerDraft = new HandlerDraft();

        if (ParamTypeResolver.signatureReferencesUndeclaredType(template, scope.declaresType())) {
            // Same guard a named option gets: a template naming a type the resolved package does not
            // declare would describe a handler nobody can write.
            handlerDraft.veto(id(), specSection(), scope.serviceTypeName(),
                    "its handler template references a type the resolved package does not declare");
            draft.setHandlerTemplate(handlerDraft);
            return;
        }

        for (HandlerAspect aspect : registry.handlerAspects()) {
            aspect.contribute(handlerScope, handlerDraft);
        }
        buildOptionParams(handlerScope, handlerDraft, template.params());
        draft.setHandlerTemplate(handlerDraft);
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
            if (option == null || option.name() == null) {
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
     * The positional fallback uses the slot's index in the full list, which keeps a generated name stable
     * when an unrelated slot is added or removed.
     *
     * <p><b>A repeatable slot is no longer skipped.</b> It used to be dropped here outright, which cost
     * the prompt everything §7 says about it — {@code mcp}'s five repeatable slots carry three union type
     * surfaces and three {@code @http:Header} obligations that reached the prompt nowhere. It is now built
     * like any other slot, marked by {@link ParamRepeatAspect}, and left out of the rendered signature by
     * the consumer rather than by this loop.
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
            ParamScope paramScope = new ParamScope(handlerScope, params.get(i), null, i, usedNames);
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
