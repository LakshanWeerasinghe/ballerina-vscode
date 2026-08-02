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

import java.util.List;

/**
 * The wire shape of a spec §8 annotation requirement, written once and shared by all four attach-point
 * aspects. Owned by nobody, like {@link AnnotationRegistry} and {@link TypeResolver}: the shape is the same
 * at every attach point, and having four aspects each build it would be four places for it to drift.
 *
 * <p>Shape: {@code {name, module?, presence, attachPoint, typeConstraint?}}.
 *
 * @since 1.7.0
 */
final class AnnotationRefWriter {

    private AnnotationRefWriter() {
        // Prevent instantiation
    }

    /**
     * Renders a scope's resolved references.
     *
     * @param refs        the references, in document order
     * @param packageName the library being rendered, for resolving the constraint's links
     * @return the array to write onto a draft; empty when there is nothing to state
     */
    static JsonArray toJson(List<AnnotationRef> refs, String packageName) {
        JsonArray array = new JsonArray();
        for (AnnotationRef ref : refs) {
            array.add(toJson(ref, packageName));
        }
        return array;
    }

    /**
     * Renders one reference.
     *
     * <p>{@code typeConstraint} goes through {@link TypeResolver} exactly as a parameter type does, so the
     * constraining record reaches the prompt by the same link mechanism rather than a second one.
     * {@code module} is omitted for a home-module annotation, which the renderer then prefixes with the
     * library's own alias — the division of labour spec §1 already imposes on a service type.
     */
    private static JsonObject toJson(AnnotationRef ref, String packageName) {
        JsonObject json = new JsonObject();
        json.addProperty("name", ref.name());
        if (ref.module() != null) {
            json.addProperty("module", ref.module());
        }
        json.addProperty("presence", ref.required()
                ? TriggerMetadataModel.Annotation.PRESENCE_REQUIRED
                : TriggerMetadataModel.Annotation.PRESENCE_OPTIONAL);
        json.addProperty("attachPoint", ref.attachPoint());
        if (ref.typeConstraint() != null && !ref.typeConstraint().isEmpty()) {
            json.add("typeConstraint", TypeResolver.resolveAnnotationConstraint(
                    ref.typeConstraint(), packageName, ref.module()));
        }
        return json;
    }
}
