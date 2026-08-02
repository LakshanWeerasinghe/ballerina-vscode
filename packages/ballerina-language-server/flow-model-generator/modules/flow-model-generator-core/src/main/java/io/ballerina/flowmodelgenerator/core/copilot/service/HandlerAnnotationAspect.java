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
 * Spec §8 at {@code attachPoint: "function"} — the annotations a handler must or may carry.
 *
 * <p>Only a metadata-described handler can declare them: a concrete service type's methods come from the
 * semantic model, and any annotation they already carry is a fact about the library rather than an
 * obligation on generated code.
 *
 * @since 1.7.0
 */
final class HandlerAnnotationAspect implements HandlerAspect {

    @Override
    public String id() {
        return "handlerAnnotation";
    }

    @Override
    public String specSection() {
        return "§8";
    }

    @Override
    public void contribute(HandlerScope scope, HandlerDraft draft) {
        TriggerMetadataModel.ServiceType.HandlerOption option = scope.option();
        if (option == null || option.annotations() == null || option.annotations().isEmpty()) {
            return;
        }
        TriggerScope service = scope.service();
        boolean resource = HandlerKindResolver.resolve(option.kind()).isResource();
        AnnotationScopeResolver.Resolution resolution = HandlerAnnotationResolver.resolve(
                service.annotations(), option.annotations(), resource, service.homeModule(),
                AnnotationScopeResolver.factsOf(service.facts()));

        for (AnnotationScopeResolver.Rejection rejection : resolution.rejections()) {
            draft.drop(id(), specSection(), rejection.name(), rejection.reason());
        }
        draft.setAnnotationRefs(AnnotationRefWriter.toJson(resolution.refs(), service.packageName()));
    }
}
