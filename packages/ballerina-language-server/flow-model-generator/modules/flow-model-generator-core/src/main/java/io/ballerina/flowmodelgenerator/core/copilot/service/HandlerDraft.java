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
 * The accumulating output of one handler, written by the handler-level components.
 *
 * <p>A {@code description} is emitted only when one genuinely exists. For a marker service type
 * neither source has one — the metadata document does not model descriptions, and the library declares
 * no method to carry a doc comment — so the key is omitted rather than fabricated.
 *
 * @since 1.7.0
 */
final class HandlerDraft {

    private final JsonObject json = new JsonObject();
    private final JsonArray parameters = new JsonArray();
    private final List<Veto> vetoes = new ArrayList<>();
    // Held rather than written straight through, so `toJson` can emit the wire contract's key order
    // (name, type, description, parameters, return) regardless of the order components ran in.
    private JsonObject returnObj;

    /** Spec §5 {@code options[].name}, or a concrete type's declared method name. */
    void setName(String name) {
        json.addProperty("name", name);
    }

    /** Spec §5 {@code options[].kind}: {@code "remote"} or {@code "resource"}. */
    void setKind(String kind) {
        json.addProperty("type", kind);
    }

    /** The method's doc-comment description; omitted when the source has none. */
    void setDescription(String description) {
        if (description != null && !description.isEmpty()) {
            json.addProperty("description", description);
        }
    }

    /** Spec §5 {@code options[].returns}; omitted when the return carries no information. */
    void setReturn(JsonObject value) {
        this.returnObj = value;
    }

    /** Appends one built parameter, preserving declaration order. */
    void addParam(ParamDraft param) {
        if (param != null) {
            parameters.add(param.toJson());
        }
    }

    /** Records that this handler must be dropped; the orchestrator performs the drop. */
    void veto(String aspectId, String specSection, String subject, String reason) {
        vetoes.add(new Veto(aspectId, specSection, subject, reason));
    }

    boolean isVetoed() {
        return !vetoes.isEmpty();
    }

    List<Veto> vetoes() {
        return vetoes;
    }

    /**
     * The finished handler. {@code parameters} is omitted when empty, which covers both a genuinely
     * param-less handler and one whose every slot was skipped as repeatable.
     */
    JsonObject toJson() {
        if (!parameters.isEmpty()) {
            json.add("parameters", parameters);
        }
        if (returnObj != null) {
            json.add("return", returnObj);
        }
        return json;
    }
}
