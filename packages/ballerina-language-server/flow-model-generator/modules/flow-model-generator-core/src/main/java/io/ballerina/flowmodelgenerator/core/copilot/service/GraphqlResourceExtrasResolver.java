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
 * Owns <b>spec §5's {@code accessor}, {@code fieldName} and {@code graphqlOperation} resource extras</b> —
 * how a GraphQL field is written and which GraphQL operation it implements.
 *
 * <p>Split from {@link HttpResourceExtrasResolver} on purpose: a GraphQL-only spec change must not force an
 * edit to the component that owns HTTP's {@code method}/{@code path}.
 *
 * <p><b>Not gated on {@code kind: "resource"}.</b> Spec §5 introduces all three as "resource-kind extras",
 * but {@code ballerina/graphql}'s mutation handler declares {@code kind: "remote"} while still carrying
 * {@code fieldName} and {@code graphqlOperation: "mutation"} — a GraphQL mutation genuinely is a remote
 * method whose name is the field name. Gating on kind would drop the mutation's field-name constraint
 * entirely, so this resolver reads whatever the handler declares and leaves the keyword to
 * {@link HandlerKindResolver}. (The mismatch between spec §5's wording and the corpus is worth raising with
 * the spec author.)
 *
 * <p><b>{@code graphqlOperation} is informational.</b> Spec §5 marks it so, and plan §11.2 confirms it may
 * only ever become a comment — never a syntax decision. It is carried here and rendered as prose.
 *
 * <p>The {@code fieldName.form} vocabulary is passed through unvalidated for the same reason given in
 * {@link HttpResourceExtrasResolver}: spec §10 enumerates forms only for
 * {@code serviceTypes[].identifier.form}, and the corpus value here ({@code identifierSegment}) is outside
 * that set by design.
 *
 * @since 1.7.0
 */
final class GraphqlResourceExtrasResolver {

    private GraphqlResourceExtrasResolver() {
        // Prevent instantiation
    }

    /**
     * The GraphQL extras of one handler.
     *
     * <p>Produced only when the handler declares at least one of the three, so an all-empty instance never
     * reaches the wire.
     *
     * @param accessorValues    the legal accessors ({@code get} for a query, {@code subscribe} for a
     *                          subscription), in document order; empty when {@code accessor} is absent
     * @param accessorRequired  whether the accessor slot must be filled; meaningless when
     *                          {@code accessorValues} is empty
     * @param fieldNameForm     the legal field-name shapes, in document order; empty when
     *                          {@code fieldName} is absent
     * @param fieldNameRequired whether the field-name slot must be filled; meaningless when
     *                          {@code fieldNameForm} is empty
     * @param operation         the informational {@code graphqlOperation} ({@code query} /
     *                          {@code mutation} / {@code subscription}), or {@code null}
     */
    record GraphqlExtras(List<String> accessorValues, boolean accessorRequired,
                         List<String> fieldNameForm, boolean fieldNameRequired,
                         String operation) {

        /** Whether the document constrains the field name's shape. */
        boolean hasFieldName() {
            return !fieldNameForm.isEmpty();
        }
    }

    /**
     * Resolves a handler's GraphQL extras.
     *
     * @param option the handler option; may be {@code null}
     * @return the extras, or empty when the handler declares none of the three
     */
    static Optional<GraphqlExtras> resolve(TriggerMetadataModel.ServiceType.HandlerOption option) {
        if (option == null) {
            return Optional.empty();
        }
        PresenceValues accessor = option.accessor();
        PresenceForm fieldName = option.fieldName();
        List<String> accessorValues = values(accessor);
        List<String> fieldNameForm = form(fieldName);
        String operation = blankToNull(option.graphqlOperation());
        if (accessorValues.isEmpty() && fieldNameForm.isEmpty() && operation == null) {
            return Optional.empty();
        }
        return Optional.of(new GraphqlExtras(
                accessorValues, isRequired(accessor == null ? null : accessor.presence()),
                fieldNameForm, isRequired(fieldName == null ? null : fieldName.presence()),
                operation));
    }

    /** As in {@link HttpResourceExtrasResolver}: anything but {@code "optional"} reads as required. */
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

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
