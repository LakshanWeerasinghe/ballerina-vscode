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
import io.ballerina.modelgenerator.commons.trigger.models.PresenceForm;

/**
 * Spec §3 {@code serviceTypes[].identifier} — the identifier/base-path slot the generated service must or may
 * fill.
 *
 * <p>Ten service types across six libraries declare one, and before this component none of them rendered it:
 * a required base path ({@code graphql}, {@code http}, {@code websocket}) simply never reached the prompt,
 * and {@code rabbitmq}'s {@code stringLiteral} slot — one of the two alternatives its {@code queueNameSource}
 * rule offers — was invisible.
 *
 * <p>The wire shape mirrors the document's, {@code {presence, form[]}}, rather than a pre-rendered string:
 * turning {@code basePath} into {@code /basePath} is a syntax decision belonging to the renderer, which
 * already owns every other one.
 *
 * @since 1.7.0
 */
final class IdentifierAspect implements ServiceAspect {

    @Override
    public String id() {
        return "identifier";
    }

    @Override
    public String specSection() {
        return "§3";
    }

    @Override
    public void contribute(TriggerScope scope, ServiceDraft draft) {
        if (scope.serviceType() == null) {
            return;
        }
        IdentifierResolver.resolve(scope.serviceType().identifier()).ifPresent(slot -> {
            JsonObject json = new JsonObject();
            json.addProperty("presence", slot.required()
                    ? PresenceForm.PRESENCE_REQUIRED : PresenceForm.PRESENCE_OPTIONAL);
            JsonArray forms = new JsonArray();
            slot.forms().forEach(forms::add);
            json.add("form", forms);
            draft.setIdentifier(json);
        });
    }
}
