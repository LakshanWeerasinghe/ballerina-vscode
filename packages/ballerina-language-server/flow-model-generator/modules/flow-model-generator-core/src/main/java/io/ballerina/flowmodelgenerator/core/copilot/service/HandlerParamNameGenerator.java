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

import io.ballerina.modelgenerator.commons.trigger.models.TypeRef;

import java.util.List;
import java.util.Set;

/**
 * Generates a parameter name for a <b>service handler</b> (remote/resource method of a trigger's
 * service type) whose name {@code trigger-metadata.json} deliberately does not state.
 *
 * <p><b>Why generation is needed at all.</b> A handler parameter's name is chosen by whoever writes
 * the service — it is not part of the connector's contract, so the authoring schema intentionally
 * omits {@code params[].name} for such slots (only the <i>type</i> and <i>presence</i> are fixed).
 * The Copilot, however, emits a full method signature, which cannot be written without a name. So a
 * name must be synthesized, and it must be deterministic, idiomatic, and valid Ballerina.
 *
 * <p><b>SCOPE — handler parameters only.</b> This generator is called from exactly one place:
 * {@link TriggerSchemaServiceLoader}'s marker-service-type handler path, for a slot whose metadata
 * {@code name} is absent. It is deliberately <b>not</b> used for anything whose name is already
 * fixed and available:
 * <ul>
 *   <li><b>listener init parameters</b> — real names come from the listener class's {@code init}
 *       signature via the semantic model;</li>
 *   <li><b>concrete service-type methods</b> (e.g. {@code trigger.github}'s {@code IssuesService},
 *       {@code trigger.google.calendar}'s {@code CalendarService}) — the methods are declared, so
 *       their parameter names come from the declaration;</li>
 *   <li><b>client methods, module functions, record fields and type definitions</b> — an entirely
 *       separate Copilot code path (symbol processing) that reads declared names directly.</li>
 * </ul>
 * Adding a call site outside the handler path would be a bug: it would invent a name for something
 * that already has one.
 *
 * <p><b>Rules</b>, applied in order (all deterministic — the same input always yields the same name):
 * <ol>
 *   <li>The type is taken from the slot's <b>first</b> type member: per the authoring schema, "the
 *       first element is the codegen default" for a union.</li>
 *   <li>A slot typed exactly {@code Error} becomes {@code <moduleAlias>Error}
 *       ({@code kafka:Error} → {@code kafkaError}) — the convention used throughout the Ballerina
 *       trigger ecosystem, and unambiguous when a handler also takes a message parameter.</li>
 *   <li>Otherwise the declared type name drives the name: a payload-shape prefix is dropped
 *       ({@code AnydataMessage} → {@code Message}, {@code BytesConsumerRecord} →
 *       {@code ConsumerRecord}) so that unions differing only by that prefix — the common
 *       {@code AnydataX|BytesX} shape — yield one stable name; the result is lower-camel-cased; and
 *       an array type is pluralized ({@code AnydataConsumerRecord[]} → {@code consumerRecords}).</li>
 *   <li>If the type yields no usable identifier (a built-in such as {@code json}/{@code string}, an
 *       anonymous shape such as {@code record {}}, or a name that would collide with a Ballerina
 *       keyword) and the slot declares a {@code dataBinding} rule, it becomes {@code payload} — the
 *       idiomatic name for a bound message body.</li>
 *   <li>Any remaining case, or a name already used by a sibling parameter of the same handler, falls
 *       back to the positional {@code paramN} (1-based), which is always valid and never collides.</li>
 * </ol>
 *
 * <p>Worked examples against the current documents (all verified by unit test):
 * <pre>
 *   kafka    onConsumerRecord  AnydataConsumerRecord[]|BytesConsumerRecord[]  → consumerRecords
 *   kafka    onConsumerRecord  Caller                                        → caller
 *   kafka    onError           Error                                         → kafkaError
 *   rabbitmq onMessage         AnydataMessage|BytesMessage                    → message
 *   rabbitmq onError           Error                                         → rabbitmqError
 *   websub   onEventNotification  ContentDistributionMessage                 → contentDistributionMessage
 *   ftp      onFileChange      WatchEvent                                    → watchEvent
 * </pre>
 *
 * @since 1.7.0
 */
final class HandlerParamNameGenerator {

    /**
     * Data-shape prefixes that distinguish the members of a connector's payload union rather than
     * naming a distinct domain concept. Dropping them keeps the generated name identical across the
     * members of an {@code AnydataX|BytesX} union, so a name never depends on which member the
     * codegen default happens to be.
     */
    private static final List<String> PAYLOAD_SHAPE_PREFIXES = List.of("Anydata", "Bytes");

    /** The idiomatic name for a slot that binds a message body but has no usable type name. */
    private static final String PAYLOAD_NAME = "payload";

    private static final String ERROR_TYPE = "Error";

    /**
     * Ballerina keywords and built-in type names: a generated identifier must never be one of these,
     * or the emitted signature would not compile. (Ballerina can quote such identifiers with a
     * leading {@code '}, but a quoted parameter name is not idiomatic in generated code, so the
     * positional fallback is preferred.)
     */
    private static final Set<String> RESERVED_WORDS = Set.of(
            "error", "service", "client", "listener", "type", "function", "record", "object", "table",
            "map", "stream", "string", "int", "float", "decimal", "boolean", "byte", "json", "xml",
            "anydata", "any", "never", "readonly", "distinct", "worker", "fork", "transaction", "retry",
            "new", "isolated", "final", "const", "var", "if", "else", "while", "foreach", "in", "return",
            "returns", "break", "continue", "fail", "panic", "trap", "from", "where", "select", "let",
            "on", "do", "is", "null", "true", "false", "import", "public", "private", "remote",
            "resource", "abstract", "class", "enum", "annotation", "external", "check", "checkpanic",
            "future", "typedesc", "handle", "match", "source", "field", "start", "wait", "flush",
            "lock", "commit", "rollback", "version", "key", "limit", "order", "group", "join", "outer",
            "equals", "conflict", "collect", "configurable", "xmlns", "as", "default", "parameter",
            "transactional", "typeof", "ascending", "descending", "base16", "base64");

    private HandlerParamNameGenerator() {
        // Prevent instantiation
    }

    /**
     * Generates the name for one unnamed handler parameter slot.
     *
     * @param ref             the slot's codegen-default type (first union member), may be null
     * @param hasDataBinding  whether the slot declares a {@code dataBinding} rule
     * @param moduleAlias     the connector's module alias, used for the {@code <alias>Error} rule
     * @param index           the slot's 0-based position, used for the positional fallback
     * @param usedNames       names already taken by sibling parameters of the same handler
     * @return a deterministic, valid, non-colliding Ballerina identifier
     */
    static String generate(TypeRef ref, boolean hasDataBinding, String moduleAlias, int index,
                           Set<String> usedNames) {
        String candidate = fromType(ref, moduleAlias);
        if (candidate == null && hasDataBinding) {
            candidate = PAYLOAD_NAME;
        }
        if (candidate == null || RESERVED_WORDS.contains(candidate) || usedNames.contains(candidate)) {
            return positionalName(index, usedNames);
        }
        return candidate;
    }

    /**
     * The positional fallback, advanced past any name a sibling parameter already holds — so it stays
     * collision-free even next to a slot authored literally as {@code param2}.
     */
    private static String positionalName(int index, Set<String> usedNames) {
        int position = index + 1;
        String name = "param" + position;
        while (usedNames.contains(name)) {
            name = "param" + (++position);
        }
        return name;
    }

    /**
     * Derives a name from the declared type, or null when the type carries no usable identifier
     * (built-in, anonymous shape, or cross-module reference whose name this module does not own).
     */
    private static String fromType(TypeRef ref, String moduleAlias) {
        if (ref == null || ref.name() == null || ref.name().isEmpty()) {
            return null;
        }
        String typeName = ref.name();
        boolean isArray = typeName.endsWith("[]");
        String base = TriggerSchemaServiceLoader.baseIdentifier(typeName);
        if (base == null || base.isEmpty() || !Character.isUpperCase(base.charAt(0))) {
            // Built-ins (json, string, byte[], ...) and anonymous shapes (record {}) start lowercase
            // or yield a keyword; they name no domain concept.
            return null;
        }
        String stripped = stripPayloadShapePrefix(base);
        // An `Error` slot is the handler's error channel: <alias>Error reads naturally and never
        // clashes with the message parameter of the same handler. Applied after prefix stripping so
        // a shaped alias (AnydataError) resolves the same way a bare Error does.
        if (ERROR_TYPE.equals(stripped)) {
            if (moduleAlias == null || moduleAlias.isEmpty()) {
                return null;
            }
            return isArray ? pluralize(moduleAlias + ERROR_TYPE) : moduleAlias + ERROR_TYPE;
        }
        String camelCase = Character.toLowerCase(stripped.charAt(0)) + stripped.substring(1);
        return isArray ? pluralize(camelCase) : camelCase;
    }

    /**
     * Drops a leading {@link #PAYLOAD_SHAPE_PREFIXES} entry when what remains is still a
     * capitalized identifier (so {@code Anydata} itself, or {@code Bytes}, is left untouched).
     */
    private static String stripPayloadShapePrefix(String base) {
        for (String prefix : PAYLOAD_SHAPE_PREFIXES) {
            if (base.length() > prefix.length() && base.startsWith(prefix)
                    && Character.isUpperCase(base.charAt(prefix.length()))) {
                return base.substring(prefix.length());
            }
        }
        return base;
    }

    /** Naive English pluralization, sufficient for connector type names. */
    private static String pluralize(String name) {
        if (name.endsWith("s") || name.endsWith("x") || name.endsWith("z")
                || name.endsWith("ch") || name.endsWith("sh")) {
            return name + "es";
        }
        if (name.length() > 1 && name.endsWith("y")
                && !isVowel(name.charAt(name.length() - 2))) {
            return name.substring(0, name.length() - 1) + "ies";
        }
        return name + "s";
    }

    private static boolean isVowel(char c) {
        return "aeiouAEIOU".indexOf(c) >= 0;
    }
}
