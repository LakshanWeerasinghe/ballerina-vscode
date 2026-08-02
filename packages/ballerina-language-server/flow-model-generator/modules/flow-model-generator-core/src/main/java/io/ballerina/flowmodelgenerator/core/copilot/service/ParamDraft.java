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

import com.google.gson.JsonObject;

/**
 * The accumulating output of one handler parameter.
 *
 * <p>{@code optional} is emitted only when true — spec §7's {@code presence: "required"} is the
 * default and stating it would violate the general omission rule.
 *
 * @since 1.7.0
 */
final class ParamDraft {

    private final JsonObject json = new JsonObject();

    /** The slot's name: authored where the document states one, generated where it does not. */
    void setName(String name) {
        json.addProperty("name", name);
    }

    /** The parameter's doc-comment description; omitted when the source has none. */
    void setDescription(String description) {
        if (description != null && !description.isEmpty()) {
            json.addProperty("description", description);
        }
    }

    /** Spec §7 {@code params[].type}, resolved to a {@code {name, links}} pair. */
    void setType(JsonObject type) {
        json.add("type", type);
    }

    /** Spec §7 {@code params[].presence}: emitted only for an optional slot. */
    void setOptional(boolean optional) {
        if (optional) {
            json.addProperty("optional", true);
        }
    }

    JsonObject toJson() {
        return json;
    }
}
