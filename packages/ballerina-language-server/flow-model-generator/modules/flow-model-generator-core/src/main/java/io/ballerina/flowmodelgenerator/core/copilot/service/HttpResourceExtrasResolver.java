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

import io.ballerina.modelgenerator.commons.trigger.models.PresenceForm;
import io.ballerina.modelgenerator.commons.trigger.models.PresenceValues;
import io.ballerina.modelgenerator.commons.trigger.models.TriggerMetadataModel;

import java.util.List;
import java.util.Optional;

/**
 * Owns <b>spec §5's {@code method} and {@code path} resource extras</b> — the legal HTTP verbs a handler may
 * use, and the legal shapes of its resource path.
 *
 * <p>Split from {@link GraphqlResourceExtrasResolver} on purpose: the two protocols describe a resource
 * handler through different slots, and a change to one must not force an edit to the other's owner.
 *
 * <p><b>Not HTTP-only, despite the name.</b> Spec §5 introduces these two slots as "HTTP adds …", but
 * {@code ballerina/websocket}'s {@code get} handler declares both as well
 * ({@code method.values: ["get"]}, {@code path.form: ["stringLiteralSegment"]}). The name follows the
 * plan's component inventory; the ownership is "the {@code method}/{@code path} pair", whoever declares it.
 *
 * <p><b>The {@code form} vocabulary is deliberately not validated.</b> Spec §10 defines a vocabulary only
 * for {@code serviceTypes[].identifier.form} ({@code basePath}, {@code stringLiteral}); it says nothing
 * about {@code path.form}, whose corpus values are {@code identifierSegments}, {@code pathParamSegments} and
 * {@code stringLiteralSegment}. The repo's own JSON schema likewise leaves {@code form} an unconstrained
 * string array. Values are therefore passed through verbatim for the renderer to describe, never matched
 * against a closed set — unlike {@link IdentifierResolver}, which owns a slot the spec does enumerate.
 *
 * <p>Per plan §11.2 the <i>values</i> here are intent-derived: which verb, and which concrete path segments,
 * is a decision only the generation intent can make. This resolver reports what is legal; it never picks.
 *
 * @since 1.7.0
 */
final class HttpResourceExtrasResolver {

    private HttpResourceExtrasResolver() {
        // Prevent instantiation
    }

    /**
     * The {@code method}/{@code path} extras of one resource handler.
     *
     * <p>Both halves are independently optional: {@code graphql}'s handlers declare neither, and a document
     * may legitimately declare a path with no verb constraint. The record is only produced when at least one
     * half is present, so an all-empty instance never reaches the wire.
     *
     * @param methodValues   the legal HTTP verbs, in document order; empty when {@code method} is absent
     * @param methodRequired whether the verb slot must be filled; meaningless when {@code methodValues} is
     *                       empty
     * @param pathForm       the legal path shapes, in document order; empty when {@code path} is absent
     * @param pathRequired   whether the path slot must be filled; meaningless when {@code pathForm} is empty
     */
    record HttpExtras(List<String> methodValues, boolean methodRequired,
                      List<String> pathForm, boolean pathRequired) {

        /** Whether the document constrains the verb. */
        boolean hasMethod() {
            return !methodValues.isEmpty();
        }

        /** Whether the document constrains the path shape. */
        boolean hasPath() {
            return !pathForm.isEmpty();
        }
    }

    /**
     * Resolves a handler's {@code method}/{@code path} extras.
     *
     * @param option the handler option; may be {@code null}
     * @return the extras, or empty when the handler declares neither slot
     */
    static Optional<HttpExtras> resolve(TriggerMetadataModel.ServiceType.HandlerOption option) {
        if (option == null) {
            return Optional.empty();
        }
        PresenceValues method = option.method();
        PresenceForm path = option.path();
        List<String> methodValues = values(method);
        List<String> pathForm = form(path);
        if (methodValues.isEmpty() && pathForm.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new HttpExtras(methodValues, isRequired(method == null ? null : method.presence()),
                pathForm, isRequired(path == null ? null : path.presence())));
    }

    /**
     * Spec §10's presence vocabulary applied to a resource slot: anything other than {@code "optional"}
     * reads as required, so an unrecognised term cannot silently downgrade a mandatory slot to a skippable
     * one.
     */
    private static boolean isRequired(String presence) {
        return !PresenceForm.PRESENCE_OPTIONAL.equals(presence);
    }

    private static List<String> values(PresenceValues slot) {
        return slot == null || slot.values() == null ? List.of() : nonBlank(slot.values());
    }

    private static List<String> form(PresenceForm slot) {
        return slot == null || slot.form() == null ? List.of() : nonBlank(slot.form());
    }

    private static List<String> nonBlank(List<String> raw) {
        return raw.stream().filter(value -> value != null && !value.isBlank()).toList();
    }
}
