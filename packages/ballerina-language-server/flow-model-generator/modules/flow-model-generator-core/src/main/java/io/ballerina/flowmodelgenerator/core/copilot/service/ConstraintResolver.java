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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Owns <b>spec §6 {@code rules[]}</b>: the cross-construct exclusivity constraints a service type declares.
 *
 * <p>This is the only place rule semantics live. Spec §6 defines two kinds, and the distinction is not
 * cosmetic:
 * <ul>
 *   <li>{@code oneOf} → {@link Kind#EXACTLY_ONE} — "Exactly one member — not zero, not more than one." The
 *       generated service is <b>obliged</b> to pick one.</li>
 *   <li>{@code atMostOne} → {@link Kind#AT_MOST_ONE} — "Zero or one member — never more than one, but zero
 *       is fine." Picking none is legal.</li>
 * </ul>
 * Collapsing the two would either invent an obligation ({@code websocket} does not require
 * {@code onMessage}) or drop one ({@code rabbitmq} does require a queue-name source). Nothing before this
 * component stated either, so a model could emit both {@code onMessage} and {@code onTextMessage} — a
 * combination the connector's compiler plugin rejects.
 *
 * <p><b>Deliberately not reusing {@code Repeatable}.</b> Plan §12 suggests the existing
 * {@code Repeatable.ONE_OF_GROUP} vocabulary could carry these. It cannot: {@code ONE_OF_GROUP} means
 * "adding one member removes its siblings", which is at-most-one semantics, so it has no way to express
 * {@code oneOf}'s mandatory "not zero". It also belongs to a different model — the UI schema's addable
 * catalog — and coupling this to it would tie the Copilot's prompt to a form-builder concern.
 *
 * <p>An unknown {@code type} is skipped <b>with a warning</b>: a future rule kind must degrade visibly
 * rather than be silently swallowed, because a dropped constraint is invisible in the output it should have
 * changed.
 *
 * @since 1.7.0
 */
final class ConstraintResolver {

    private static final Logger LOGGER = Logger.getLogger(ConstraintResolver.class.getName());

    private ConstraintResolver() {
        // Prevent instantiation
    }

    /** The two rule kinds spec §6 defines. */
    enum Kind {
        /** {@code oneOf}: exactly one member must be chosen. */
        EXACTLY_ONE,
        /** {@code atMostOne}: zero or one member, never more. */
        AT_MOST_ONE
    }

    /**
     * One alternative in a rule. Spec §6: "Exactly one of the three shapes is populated per member."
     *
     * <p>Sealed so a new member shape cannot be added without every consumer being forced to handle it —
     * the renderer switches over these, and a silently unhandled shape would drop an alternative from a
     * constraint that is only correct when all its alternatives are stated.
     */
    sealed interface Member {

        /**
         * A field inside a top-level annotation's record, e.g. {@code @rabbitmq:ServiceConfig}'s
         * {@code queueName}.
         *
         * <p>Carries both the reference and the resolved name because they differ and both matter: the
         * document says {@code "annotation": "serviceConfig"} — a registry <i>id</i>, lowercase by
         * convention — while the annotation a reader must write is {@code @rabbitmq:ServiceConfig}. Rendering
         * the id would put a name in the prompt that does not exist, sitting two lines from the real one.
         *
         * @param annotationId   the {@code annotations[].id} this member references
         * @param annotationName the annotation's actual name, resolved through the §8 registry
         * @param field          the field name inside that annotation's record
         * @param preferred      whether spec §6's {@code preferred} marks this the canonical choice
         */
        record AnnotationField(String annotationId, String annotationName, String field, boolean preferred)
                implements Member {
        }

        /**
         * The enclosing service type's own identifier slot, i.e. spec §6's {@code {"part": "identifier"}}.
         *
         * @param preferred whether spec §6's {@code preferred} marks this the canonical choice
         */
        record Identifier(boolean preferred) implements Member {
        }

        /**
         * One of the enclosing service type's own handlers.
         *
         * @param name      the {@code handlers.options[].name} this member references
         * @param preferred whether spec §6's {@code preferred} marks this the canonical choice
         */
        record Handler(String name, boolean preferred) implements Member {
        }
    }

    /**
     * One resolved rule.
     *
     * @param id      the document's local rule id, carried for diagnostics and for the rendered note
     * @param kind    the rule's semantics
     * @param members the mutually-exclusive alternatives, in document order; never fewer than two
     */
    record Constraint(String id, Kind kind, List<Member> members) {
    }

    /**
     * Resolves a service type's rules.
     *
     * <p>A rule is dropped whole, with a warning, when it names an unknown kind or when fewer than two
     * usable members survive — a one-alternative "choose exactly one of" is not a constraint a reader can
     * act on, and stating it would be noise at best and misleading at worst.
     *
     * @param libraryName          the library, for log attribution only
     * @param rules                the service type's {@code rules[]}; may be {@code null}
     * @param declaredHandlerNames the handler names this service type actually declares, used to drop a
     *                             {@code {handler}} member that names something absent. Pass {@code null}
     *                             when the catalog is not knowable, which suppresses the cross-check rather
     *                             than dropping every handler member; an <b>empty</b> set means "this
     *                             service type declares no handlers" and does drop them
     * @param annotations          spec §8's registry, the single lookup from a member's annotation id to the
     *                             annotation it names; may be {@code null}, which suppresses the resolution
     *                             and keeps the id as the name
     * @return the resolved rules, in document order
     */
    static List<Constraint> resolve(String libraryName,
                                    List<TriggerMetadataModel.ServiceType.Rule> rules,
                                    Set<String> declaredHandlerNames,
                                    AnnotationRegistry annotations) {
        List<Constraint> resolved = new ArrayList<>();
        if (rules == null) {
            return resolved;
        }
        for (TriggerMetadataModel.ServiceType.Rule rule : rules) {
            if (rule == null) {
                continue;
            }
            Kind kind = kindOf(rule.type());
            if (kind == null) {
                LOGGER.warning("Skipped rule '" + rule.id() + "' for " + libraryName
                        + ": unknown rules[].type '" + rule.type() + "' (spec §6 defines "
                        + TriggerMetadataModel.ServiceType.Rule.TYPE_ONE_OF + " and "
                        + TriggerMetadataModel.ServiceType.Rule.TYPE_AT_MOST_ONE + ")");
                continue;
            }
            List<Member> members = members(libraryName, rule, declaredHandlerNames, annotations);
            if (members.size() < 2) {
                LOGGER.warning("Skipped rule '" + rule.id() + "' for " + libraryName
                        + ": " + members.size() + " usable member(s) — a choice needs at least two");
                continue;
            }
            resolved.add(new Constraint(rule.id(), kind, members));
        }
        return resolved;
    }

    private static Kind kindOf(String type) {
        if (TriggerMetadataModel.ServiceType.Rule.TYPE_ONE_OF.equals(type)) {
            return Kind.EXACTLY_ONE;
        }
        if (TriggerMetadataModel.ServiceType.Rule.TYPE_AT_MOST_ONE.equals(type)) {
            return Kind.AT_MOST_ONE;
        }
        return null;
    }

    private static List<Member> members(String libraryName,
                                        TriggerMetadataModel.ServiceType.Rule rule,
                                        Set<String> declaredHandlerNames,
                                        AnnotationRegistry annotations) {
        List<Member> members = new ArrayList<>();
        if (rule.members() == null) {
            return members;
        }
        for (TriggerMetadataModel.ServiceType.Rule.RuleMember member : rule.members()) {
            if (member == null) {
                continue;
            }
            boolean preferred = Boolean.TRUE.equals(member.preferred());
            if (member.annotation() != null && member.field() != null) {
                String name = annotationName(member.annotation(), annotations);
                if (name == null) {
                    // The rule references a registry entry that does not exist, so there is no annotation for
                    // a reader to attach. Same policy as a phantom handler: drop it and say why.
                    LOGGER.warning("Dropped member of rule '" + rule.id() + "' for " + libraryName
                            + ": annotation id '" + member.annotation() + "' is not in annotations[]");
                    continue;
                }
                members.add(new Member.AnnotationField(member.annotation(), name, member.field(), preferred));
                continue;
            }
            if (TriggerMetadataModel.ServiceType.Rule.RuleMember.PART_IDENTIFIER.equals(member.part())) {
                members.add(new Member.Identifier(preferred));
                continue;
            }
            if (member.handler() != null) {
                // A rule referencing a handler this service type does not declare is a document defect: the
                // constraint could never be satisfied through that alternative. Drop it and say so, rather
                // than telling the model to choose between a real handler and a phantom.
                if (declaredHandlerNames != null && !declaredHandlerNames.contains(member.handler())) {
                    LOGGER.warning("Dropped member of rule '" + rule.id() + "' for " + libraryName
                            + ": handler '" + member.handler() + "' is not declared by this service type");
                    continue;
                }
                members.add(new Member.Handler(member.handler(), preferred));
                continue;
            }
            LOGGER.warning("Dropped member of rule '" + rule.id() + "' for " + libraryName
                    + ": none of spec §6's three member shapes is populated");
        }
        return members;
    }

    /**
     * The name of the annotation a member references, via spec §8's registry — the access path §8 describes
     * for a {@code rules[].members[].annotation} reference.
     *
     * <p>With no registry the id is returned unchanged, so a caller exercising rule semantics without a
     * document still gets a usable name.
     */
    private static String annotationName(String annotationId, AnnotationRegistry annotations) {
        if (annotations == null) {
            return annotationId;
        }
        return annotations.byId(annotationId)
                .map(annotation -> annotation.type() == null ? null : annotation.type().name())
                .orElse(null);
    }
}
