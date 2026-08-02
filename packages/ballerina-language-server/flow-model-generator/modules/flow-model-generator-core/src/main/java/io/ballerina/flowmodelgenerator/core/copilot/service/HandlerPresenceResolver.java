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

import java.util.Optional;

/**
 * Owns <b>spec §5 {@code options[].presence}</b>: whether a handler must be implemented or may be omitted.
 *
 * <p>Spec §5 scopes the field precisely — "{@code presence} | Only under {@code addMode: subset}" — and that
 * one sentence is the whole of this component. Under {@code addMode: "many"} the catalog is open-ended and
 * user-named, so "is this particular handler required" is not a question the document is answering; the key
 * is therefore <b>omitted entirely</b> rather than guessed at as {@code required}. That distinction is why
 * the result is an {@code Optional<Boolean>} and not a {@code boolean}: three states, not two.
 *
 * <table>
 *   <caption>The three states, and what each means downstream</caption>
 *   <tr><th>Document</th><th>Result</th><th>Rendered</th></tr>
 *   <tr><td>{@code subset} + {@code presence: "required"}</td><td>{@code Optional.of(false)}</td>
 *       <td>{@code // required}</td></tr>
 *   <tr><td>{@code subset} + {@code presence: "optional"}</td><td>{@code Optional.of(true)}</td>
 *       <td>{@code // optional}</td></tr>
 *   <tr><td>{@code many}, or no {@code presence}</td><td>{@code Optional.empty()}</td>
 *       <td>nothing</td></tr>
 * </table>
 *
 * <p>The value is inverted into <i>optionality</i> rather than carried as the spec's own word because the
 * wire key is {@code optional} — the same key, and the same polarity, that a parameter already uses. Before
 * this component the key was never emitted at all, so a mandatory handler ({@code kafka}'s
 * {@code onConsumerRecord}, {@code websub}'s {@code onEventNotification}) was indistinguishable from a
 * skippable one.
 *
 * @since 1.7.0
 */
final class HandlerPresenceResolver {

    private HandlerPresenceResolver() {
        // Prevent instantiation
    }

    /**
     * Resolves a handler's optionality.
     *
     * @param presence the option's declared {@code presence}; may be {@code null}
     * @param addMode  the enclosing {@code handlers.addMode}; may be {@code null}
     * @return {@code true} for an optional handler, {@code false} for a required one, or empty when the
     *         document is not answering the question
     */
    static Optional<Boolean> resolveOptional(String presence, String addMode) {
        if (!TriggerMetadataModel.ServiceType.Handlers.ADD_MODE_SUBSET.equals(addMode)) {
            // Spec §5: presence is meaningful "only under addMode: subset".
            return Optional.empty();
        }
        if (TriggerMetadataModel.Annotation.PRESENCE_OPTIONAL.equals(presence)) {
            return Optional.of(true);
        }
        if (TriggerMetadataModel.Annotation.PRESENCE_REQUIRED.equals(presence)) {
            return Optional.of(false);
        }
        // A subset option that states no presence, or states an unrecognised term, is not something to
        // guess at: asserting `required` could oblige generated code to implement a handler the connector
        // treats as optional, and asserting `optional` could omit a mandatory one. Say nothing instead.
        return Optional.empty();
    }
}
