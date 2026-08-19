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

import io.ballerina.modelgenerator.commons.trigger.models.PresenceValues;
import io.ballerina.modelgenerator.commons.trigger.models.TriggerMetadataModel;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Decides the <b>accessor</b> of a resource handler — the token between {@code resource function} and the
 * path.
 *
 * <h2>THIS IS INFERENCE, NOT SPEC</h2>
 *
 * <p><b>The spec does not say where a resource handler's accessor lives, and the three documents that
 * declare one disagree.</b> Spec §5 lists the resource extras ({@code method}, {@code path},
 * {@code accessor}, {@code fieldName}, {@code graphqlOperation}) without stating which of them supplies
 * the accessor a generator must write, and the corpus offers three different placements:
 * <ul>
 *   <li><b>{@code graphql}</b> puts it in {@code accessor.values} ({@code ["get"]}, {@code ["subscribe"]})
 *       with {@code name: "*"};</li>
 *   <li><b>{@code http}</b> puts the verb list in {@code method.values} with {@code name: "*"} and declares
 *       no {@code accessor} at all;</li>
 *   <li><b>{@code websocket}</b> names the handler {@code "get"} outright — which is simultaneously the
 *       method name slot and an accessor token — <i>and</i> also declares {@code method.values: ["get"]}.</li>
 * </ul>
 *
 * <p>The precedence encoded below is therefore <b>our choice, not the spec's</b>, and it lives alone in a
 * single function for exactly that reason: when the spec author clarifies the rule, this is a one-function,
 * one-test change, and until then the guess is auditable in isolation rather than smeared across the
 * renderer. <b>Recommendation: raise this with the spec author</b> (plan §11.3, §14.3).
 *
 * <p><b>Precedence:</b> {@code accessor.values[0]} → {@code method.values[0]} → {@code name} when the name
 * is itself an accessor token.
 *
 * <p>Verified against the corpus: {@code graphql} resolves through the first branch and {@code websocket}
 * through the <i>second</i> (it declares {@code method}, so its name is never consulted). The third branch
 * has <b>no corpus instance</b> and exists so that a document naming a handler {@code "get"} without
 * declaring either slot still renders a compilable accessor rather than none.
 *
 * @since 1.7.0
 */
final class AccessorPrecedencePolicy {

    /**
     * Ballerina's resource accessor tokens. Used only by the last-resort branch, to tell a handler whose
     * name <i>is</i> an accessor ({@code "get"}) from one that merely names a method ({@code "onMessage"}).
     * {@code default} is included because spec §5's own HTTP verb list carries it.
     */
    private static final Set<String> ACCESSOR_TOKENS = Set.of(
            "get", "post", "put", "delete", "patch", "head", "options", "default", "subscribe");

    private AccessorPrecedencePolicy() {
        // Prevent instantiation
    }

    /**
     * The accessor to write for a resource handler.
     *
     * <p>Returns empty when no branch applies, which the renderer must treat as "this handler cannot be
     * written as a resource" rather than substituting a verb of its own — inventing {@code get} would be
     * inventing API.
     *
     * @param option the handler option; may be {@code null}
     * @return the accessor token, or empty when the document supplies none
     */
    static Optional<String> accessorOf(TriggerMetadataModel.ServiceType.HandlerOption option) {
        if (option == null) {
            return Optional.empty();
        }
        // 1. GraphQL states the accessor outright.
        Optional<String> declared = firstValue(option.accessor());
        if (declared.isPresent()) {
            return declared;
        }
        // 2. HTTP (and websocket) state the legal verbs; the first is spec §1's codegen default applied to
        //    a value list rather than a type union.
        Optional<String> verb = firstValue(option.method());
        if (verb.isPresent()) {
            return verb;
        }
        // 3. Last resort: the handler's own name, when that name is an accessor token rather than a method
        //    name. No corpus document reaches this branch.
        String name = option.name();
        if (name != null && ACCESSOR_TOKENS.contains(name)) {
            return Optional.of(name);
        }
        return Optional.empty();
    }

    /** The first declared value of a {@link PresenceValues} slot, ignoring blanks. */
    private static Optional<String> firstValue(PresenceValues slot) {
        if (slot == null || slot.values() == null) {
            return Optional.empty();
        }
        List<String> values = slot.values();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }
}
