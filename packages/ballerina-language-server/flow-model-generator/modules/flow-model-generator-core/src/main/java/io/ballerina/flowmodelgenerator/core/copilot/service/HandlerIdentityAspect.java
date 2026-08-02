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

/**
 * Spec §5 {@code options[].name} and {@code .kind} — a handler's name and whether it is a remote or
 * resource method.
 *
 * <p>The two provenances differ in what they can supply. A concrete type's declared method carries a
 * real name, kind and doc comment. A marker type's handler has only what the document states, and the
 * document models no descriptions — so no {@code description} is emitted for one, rather than inventing
 * text.
 *
 * <p>The emitted {@code kind} is currently only ever consumed as a discriminator by the renderer, which
 * hardcodes {@code remote function}; honouring {@code resource} in the rendered syntax is a later phase.
 * The value itself is already carried faithfully here.
 *
 * @since 1.7.0
 */
final class HandlerIdentityAspect implements HandlerAspect {

    private static final String KIND_REMOTE = "remote";
    private static final String KIND_RESOURCE = "resource";

    @Override
    public String id() {
        return "handlerIdentity";
    }

    @Override
    public String specSection() {
        return "§5";
    }

    @Override
    public void contribute(HandlerScope scope, HandlerDraft draft) {
        if (scope.isConcrete()) {
            TriggerSemanticFacts.DeclaredMethod declared = scope.declared();
            draft.setName(declared.name());
            draft.setKind(declared.kind());
            draft.setDescription(declared.description());
            return;
        }
        TriggerMetadataModel.ServiceType.HandlerOption option = scope.option();
        draft.setName(option.name());
        draft.setKind(TriggerMetadataModel.ServiceType.HandlerOption.KIND_RESOURCE.equals(option.kind())
                ? KIND_RESOURCE : KIND_REMOTE);
        // No description: neither the document nor the library has one for a marker-type handler.
    }
}
