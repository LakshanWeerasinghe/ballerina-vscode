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
 * Spec §8 {@code annotations[]} at {@code attachPoint: "service"} — the annotations the generated service
 * must or may carry.
 *
 * <p>Carried on the <b>service</b> rather than hoisted to the library, and deliberately distinct from the
 * library's own {@code annotations} list: that list states which annotations the library <i>declares</i>
 * (a fact the compiler reports), whereas these state which ones <i>this service type is obliged to
 * attach</i> — the obligation, its presence, and its scope, none of which any symbol carries. Before this
 * component, a required annotation reached the prompt only as an available declaration among dozens, with
 * nothing marking it as mandatory for the service being written.
 *
 * <p><b>Known coverage gap, by design elsewhere.</b> Two of the corpus's eleven service-level
 * annotations never reach a prompt, and not because of anything this component does:
 * {@code ballerina/http} and {@code ballerina/graphql} both declare an optional {@code serviceConfig},
 * and both have a curated {@code generic-services.json} entry whose name collides with their service
 * type — so {@code ServiceLoader.mergeWithGenericServices} discards this component's whole entry in
 * favour of the hand-written instructions. That overlay is richer than anything synthesized here and
 * must keep winning, but the consequence is a silent drop: it happens after the pipeline, so it raises
 * no {@link Veto} and logs nothing. Both are {@code optional}, so nothing miscompiles. Making the drop
 * reportable belongs to the validator phase, which owns cross-cutting document diagnostics — it cannot
 * be done here without this component reaching into the merge step it knows nothing about.
 *
 * @since 1.7.0
 */
final class ServiceAnnotationAspect implements ServiceAspect {

    @Override
    public String id() {
        return "serviceAnnotation";
    }

    @Override
    public String specSection() {
        return "§8";
    }

    @Override
    public void contribute(TriggerScope scope, ServiceDraft draft) {
        AnnotationScopeResolver.Resolution resolution = ServiceAnnotationResolver.resolve(
                scope.annotations(),
                scope.serviceType() == null ? null : scope.serviceType().id(),
                scope.homeModule(),
                scope.facts());

        // `drop`, not `veto`. An unresolvable obligation makes the obligation unusable, not the service —
        // which is what this component's own resolver has always documented, while the code it called
        // deleted the whole entry.
        for (AnnotationScopeResolver.Rejection rejection : resolution.rejections()) {
            draft.drop(id(), specSection(), rejection.name(), rejection.reason());
        }
        draft.setAnnotations(AnnotationRefWriter.toJson(resolution.refs(), scope.packageName()));
    }
}
