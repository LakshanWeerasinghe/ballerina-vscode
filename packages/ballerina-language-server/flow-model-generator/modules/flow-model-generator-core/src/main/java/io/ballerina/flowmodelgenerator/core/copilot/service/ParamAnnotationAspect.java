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
 * Spec §8 at {@code attachPoint: "parameter"} — the annotations a handler parameter may carry.
 *
 * <p>Only a metadata-described slot can declare them; a concrete method's parameters carry whatever the
 * library already declares, which is a fact rather than an obligation.
 *
 * @since 1.7.0
 */
final class ParamAnnotationAspect implements ParamAspect {

    @Override
    public String id() {
        return "paramAnnotation";
    }

    @Override
    public String specSection() {
        return "§8";
    }

    @Override
    public void contribute(ParamScope scope, ParamDraft draft) {
        TriggerMetadataModel.ServiceType.Param param = scope.param();
        if (param == null || param.annotations() == null || param.annotations().isEmpty()) {
            return;
        }
        TriggerScope service = scope.handler().service();
        AnnotationScopeResolver.Resolution resolution = ParamAnnotationResolver.resolve(
                service.annotations(), param.annotations(), service.homeModule(),
                AnnotationScopeResolver.factsOf(service.facts()));

        for (AnnotationScopeResolver.Rejection rejection : resolution.rejections()) {
            draft.drop(id(), specSection(), rejection.name(), rejection.reason());
        }
        draft.setAnnotationRefs(AnnotationRefWriter.toJson(resolution.refs(), service.packageName()));
    }
}
