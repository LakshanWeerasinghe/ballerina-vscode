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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.ballerina.modelgenerator.commons.trigger.models.TriggerMetadataModel;

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

    // Spec §10's presence vocabulary, echoed onto the wire so the renderer states the obligation rather
    // than re-deriving it from a boolean.
    private static final String PRESENCE_REQUIRED =
            TriggerMetadataModel.Annotation.PRESENCE_REQUIRED;
    private static final String PRESENCE_OPTIONAL =
            TriggerMetadataModel.Annotation.PRESENCE_OPTIONAL;

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
        ServiceAnnotationResolver.Resolution resolution = ServiceAnnotationResolver.resolve(
                scope.annotations(),
                scope.serviceType() == null ? null : scope.serviceType().id(),
                scope.homeModule(),
                scope.facts());

        for (String name : resolution.undeclared()) {
            draft.veto(id(), specSection(), name,
                    "not declared as an annotation by the resolved package version");
        }

        JsonArray annotations = new JsonArray();
        for (AnnotationRef ref : resolution.refs()) {
            annotations.add(toJson(ref, scope.packageName()));
        }
        draft.setAnnotations(annotations);
    }

    /**
     * Wire shape per plan §2: {@code {name, module?, presence, attachPoint, typeConstraint?}}.
     *
     * <p>{@code typeConstraint} is resolved through {@link TypeResolver} exactly as a parameter type is,
     * so the constraining record is reachable by the same link mechanism rather than a second one.
     * {@code module} is omitted for a home-module annotation, which the renderer then prefixes with the
     * library's own alias — the same division of labour spec §1 already imposes on a service type.
     */
    private static JsonObject toJson(AnnotationRef ref, String packageName) {
        JsonObject json = new JsonObject();
        json.addProperty("name", ref.name());
        if (ref.module() != null) {
            json.addProperty("module", ref.module());
        }
        json.addProperty("presence", ref.required() ? PRESENCE_REQUIRED : PRESENCE_OPTIONAL);
        json.addProperty("attachPoint", ref.attachPoint());
        if (ref.typeConstraint() != null && !ref.typeConstraint().isEmpty()) {
            json.add("typeConstraint", TypeResolver.resolveAnnotationConstraint(
                    ref.typeConstraint(), packageName, ref.module()));
        }
        return json;
    }
}
