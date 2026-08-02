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

import java.util.ArrayList;
import java.util.List;

/**
 * The accumulating output of one handler parameter.
 *
 * <p>Every slot is <b>held as a field and emitted once in {@link #toJson()}</b>, the same treatment
 * {@link HandlerDraft} already had. With three components now writing here, wire key order would otherwise
 * become a property of {@link AspectRegistry}'s ordering, and inserting a component would silently reshuffle
 * every parameter object.
 *
 * <p>{@code optional} is emitted only when true — spec §7's {@code presence: "required"} is the
 * default and stating it would violate the general omission rule.
 *
 * @since 1.7.0
 */
final class ParamDraft {

    // Non-fatal only. A parameter has no veto: what makes a slot unusable — a signature member the package
    // does not declare — is caught before the handler is built at all, so a diagnostic here drops a
    // *contribution* to the slot, never the slot itself.
    private final List<Veto> diagnostics = new ArrayList<>();

    private String name;
    private String description;
    private JsonObject type;
    private boolean optional;
    private JsonArray alternatives;
    private JsonArray annotationRefs;
    private JsonObject binding;

    /** The slot's name: authored where the document states one, generated where it does not. */
    void setName(String name) {
        this.name = name;
    }

    /** The parameter's doc-comment description; omitted when the source has none. */
    void setDescription(String description) {
        if (description != null && !description.isEmpty()) {
            this.description = description;
        }
    }

    /** Spec §7 {@code params[].type}, resolved to a {@code {name, links}} pair. */
    void setType(JsonObject type) {
        this.type = type;
    }

    /** Spec §7 {@code params[].presence}: emitted only for an optional slot. */
    void setOptional(boolean optional) {
        this.optional = optional;
    }

    /**
     * Spec §7 — the slot's other legal types, as {@code {name, links}} pairs so the type closure can reach
     * their definitions. Emitted as an array and never joined: see {@link ParamTypeResolver.ParamType}.
     */
    void setAlternatives(JsonArray alternatives) {
        if (alternatives != null && !alternatives.isEmpty()) {
            this.alternatives = alternatives;
        }
    }

    /**
     * Spec §8 at {@code attachPoint: "parameter"} — the annotations this slot may carry.
     *
     * <p>Named {@code annotationRefs} rather than {@code annotations} because the {@code Parameter} POJO
     * this deserializes into already has an {@code annotations} field holding {@code AnnotationAttachment}s
     * — annotations the compiler found <i>already present</i> on a library symbol, which render verbatim
     * with their real value. These are requirements on code that does not exist yet, and collapsing the two
     * onto one key would make "the library has this" indistinguishable from "your code needs this".
     */
    void setAnnotationRefs(JsonArray refs) {
        if (refs != null && !refs.isEmpty()) {
            this.annotationRefs = refs;
        }
    }

    /** Spec §9 — the data-binding rule this slot's {@code dataBinding} id names. */
    void setBinding(JsonObject binding) {
        this.binding = binding;
    }

    /**
     * Records that a contribution was dropped, without dropping the parameter. The reason travels up to the
     * service's report through {@link HandlerDraft#addParam}.
     */
    void drop(String aspectId, String specSection, String subject, String reason) {
        diagnostics.add(new Veto(aspectId, specSection, subject, reason));
    }

    /** Every non-fatal drop recorded while building this parameter. */
    List<Veto> diagnostics() {
        return diagnostics;
    }

    /** The finished parameter, in the wire contract's key order. */
    JsonObject toJson() {
        JsonObject json = new JsonObject();
        if (name != null) {
            json.addProperty("name", name);
        }
        if (description != null) {
            json.addProperty("description", description);
        }
        if (type != null) {
            json.add("type", type);
        }
        if (optional) {
            json.addProperty("optional", true);
        }
        if (alternatives != null) {
            json.add("alternatives", alternatives);
        }
        if (annotationRefs != null) {
            json.add("annotationRefs", annotationRefs);
        }
        if (binding != null) {
            json.add("binding", binding);
        }
        return json;
    }
}
