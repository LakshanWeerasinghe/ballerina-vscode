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

/**
 * Spec §8 at {@code attachPoint: "return"} — the annotations a handler's return must or may carry.
 *
 * <p>Runs in the handler tier because the return slot belongs to a handler, but selects by the enclosing
 * <b>service type's</b> id: §8's "Residual gap" gives return scope no per-handler reference, so the
 * obligation is stated once for the service type and applies to each of its handlers.
 *
 * <p>The refs are handed to {@link HandlerDraft#setReturnAnnotationRefs}, which merges them into the return
 * object at emit time — so this component carries no ordering dependency on {@link ReturnAspect}.
 *
 * @since 1.7.0
 */
final class ReturnAnnotationAspect implements HandlerAspect {

    @Override
    public String id() {
        return "returnAnnotation";
    }

    @Override
    public String specSection() {
        return "§8";
    }

    @Override
    public void contribute(HandlerScope scope, HandlerDraft draft) {
        TriggerScope service = scope.service();
        AnnotationScopeResolver.Resolution resolution = ReturnAnnotationResolver.resolve(
                service.annotations(),
                service.serviceType() == null ? null : service.serviceType().id(),
                service.homeModule(), AnnotationScopeResolver.factsOf(service.facts()));
        if (resolution.refs().isEmpty() && resolution.rejections().isEmpty()) {
            return;
        }
        for (AnnotationScopeResolver.Rejection rejection : resolution.rejections()) {
            draft.drop(id(), specSection(), rejection.name(), rejection.reason());
        }
        draft.setReturnAnnotationRefs(AnnotationRefWriter.toJson(resolution.refs(), service.packageName()));
    }
}
