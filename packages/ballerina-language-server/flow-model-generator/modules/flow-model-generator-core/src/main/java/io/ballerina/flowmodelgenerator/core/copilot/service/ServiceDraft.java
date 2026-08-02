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
 * The accumulating output of one service entry, written by the service-level components in registry
 * order and read once at the end.
 *
 * <p>Wraps a {@link JsonObject} rather than a POJO deliberately: the two enrichers and the
 * generic-services merge that run after this loader all consume a {@code JsonArray}, so the pipeline
 * must produce one. Conversion to the {@code Service} POJO still happens once, further downstream.
 *
 * <p>Every setter is a no-op for absent input, which is how the spec's general rule — "a field that
 * would be empty, unused, or fully derivable from other fields is left out" — is enforced in one place
 * rather than at each call site.
 *
 * @since 1.7.0
 */
final class ServiceDraft {

    private final JsonObject json = new JsonObject();
    private final JsonArray methods = new JsonArray();
    // Vetoes raised against this entry — any one of them drops it.
    private final List<Veto> vetoes = new ArrayList<>();
    // Vetoes raised against individual handlers. Reported, but they drop only their own handler: a
    // service type whose contract is partly unusable still has a usable remainder.
    private final List<Veto> handlerVetoes = new ArrayList<>();

    /** Spec §3: the wire contract's fixed discriminator for a metadata-derived service. */
    void setKind(String kind) {
        json.addProperty("type", kind);
    }

    /** Spec §3 {@code serviceTypes[].type}: the service object type's name. */
    void setName(String name) {
        json.addProperty("name", name);
    }

    /**
     * Spec §1: the {@code org/module} a cross-module service type belongs to. Absent for a home-module
     * type, which the renderer then prefixes with the listener's alias.
     */
    void setServiceTypeModule(String module) {
        if (module != null && !module.isEmpty()) {
            json.addProperty("serviceTypeModule", module);
        }
    }

    /** Spec §2 {@code listeners[].requiredImports}: side-effect-only imports the generated code needs. */
    void setRequiredImports(JsonArray imports) {
        if (imports != null && !imports.isEmpty()) {
            json.add("requiredImports", imports);
        }
    }

    /**
     * Spec §8 {@code annotations[]} at {@code attachPoint: "service"}: the annotations this service type
     * must or may carry. Omitted when it carries none, so a service with no obligation says nothing
     * rather than carrying an empty array.
     */
    void setAnnotations(JsonArray annotations) {
        if (annotations != null && !annotations.isEmpty()) {
            json.add("annotations", annotations);
        }
    }

    /**
     * Spec §3 {@code serviceTypes[].identifier}: the slot between {@code service} and {@code on new …}.
     * Omitted when the connector does not consult it — spec §3: "Omit the whole key if the identifier slot
     * carries no meaning for this connector."
     */
    void setIdentifier(JsonObject identifier) {
        if (identifier != null) {
            json.add("identifier", identifier);
        }
    }

    /**
     * Spec §6 {@code rules[]}: the exclusivity constraints this service type declares. Omitted when it
     * declares none, which is 8 of the 13 corpus documents.
     */
    void setConstraints(JsonArray constraints) {
        if (constraints != null && !constraints.isEmpty()) {
            json.add("constraints", constraints);
        }
    }

    /** Spec §2 {@code listeners[].type}: the listener the service attaches to, with its init params. */
    void setListener(JsonObject listener) {
        if (listener != null) {
            json.add("listener", listener);
        }
    }

    /**
     * Appends one built handler, or records why it was dropped. Order is preserved — spec §7: "Array
     * order is meaningful".
     */
    void addHandler(HandlerDraft handler) {
        if (handler == null) {
            return;
        }
        if (handler.isVetoed()) {
            handlerVetoes.addAll(handler.vetoes());
            return;
        }
        methods.add(handler.toJson());
    }

    /**
     * Records that this service entry must be dropped. The orchestrator, not the component, performs
     * the drop, so every exclusion goes through one place and carries a reason.
     */
    void veto(String aspectId, String specSection, String subject, String reason) {
        vetoes.add(new Veto(aspectId, specSection, subject, reason));
    }

    /** Whether this entry itself was vetoed. A dropped handler does not drop its service. */
    boolean isVetoed() {
        return !vetoes.isEmpty();
    }

    /** Every veto raised while building this entry, whether it dropped the entry or one handler. */
    List<Veto> vetoes() {
        List<Veto> all = new ArrayList<>(vetoes);
        all.addAll(handlerVetoes);
        return all;
    }

    /**
     * The finished entry. {@code methods} is omitted when empty — a service type whose contract
     * declares no methods (mcp's marker {@code Service}) is legitimate and must not render an empty
     * array.
     */
    JsonObject toJson() {
        if (!methods.isEmpty()) {
            json.add("methods", methods);
        }
        return json;
    }
}
