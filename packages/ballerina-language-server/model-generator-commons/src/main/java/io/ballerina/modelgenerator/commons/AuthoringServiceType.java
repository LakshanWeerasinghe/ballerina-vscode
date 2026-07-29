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

package io.ballerina.modelgenerator.commons;

import java.util.List;

/**
 * One service-type alternative a connector exposes, as declared in a {@code trigger-authoring.json}
 * document's {@code serviceTypes[]}. See {@link TriggerAuthoringModel#serviceTypes()} for how array
 * cardinality (rather than a {@code presence} field) determines whether an entry is mandatory.
 *
 * @param id                                  local identifier, referenced from
 *                                            {@link TriggerAuthoringModel.Listener#services()} and
 *                                            from other service types' {@link Rule.RuleMember}s
 * @param type                                the service object type
 * @param concrete                            {@code true} if the type declares its own methods
 *                                            directly (fully introspectable — {@code handlers} then
 *                                            carries {@code backedByConcreteType: true} and no
 *                                            {@code options}); {@code false} for a marker/abstract type
 * @param multipleListenersAllowed            can one service instance attach to more than one
 *                                            listener in a single declaration ({@code service X on l1, l2 {}})
 * @param multipleServicesPerListenerAllowed  can one listener instance host more than one service of
 *                                            this type simultaneously
 * @param identifier                          the identifier/base-path slot (the string/path after
 *                                            {@code service}); {@code null}/absent when the slot
 *                                            carries no meaning for this service type
 * @param handlers                            the handler catalog
 * @param rules                               cross-construct constraints scoped to this service type;
 *                                            {@code null}/absent when none apply
 * @since 1.10.0
 */
public record AuthoringServiceType(
        String id,
        TypeRef type,
        boolean concrete,
        boolean multipleListenersAllowed,
        boolean multipleServicesPerListenerAllowed,
        PresenceForm identifier,
        Handlers handlers,
        List<Rule> rules) {

    /**
     * The handler catalog for one service type.
     *
     * @param backedByConcreteType {@code true} means the service type's own declared methods are the
     *                             handlers ({@code options} is empty/absent and nothing further is
     *                             specified — fully introspectable); {@code false} means the type
     *                             declares no legal handlers itself, so {@code options} is the only
     *                             source of truth
     * @param addMode              how many of {@code options} can/must be implemented at once; absent
     *                             when {@code backedByConcreteType} is {@code true}
     * @param options              the handler vocabulary; empty/absent when {@code backedByConcreteType}
     *                             is {@code true}
     */
    public record Handlers(boolean backedByConcreteType, String addMode, List<HandlerOption> options) {

        /** A fixed, named vocabulary; each option carries its own {@code presence}. */
        public static final String ADD_MODE_SUBSET = "subset";
        /** An open-ended, user-named set; represented as a single option named {@link HandlerOption#WILDCARD_NAME}. */
        public static final String ADD_MODE_MANY = "many";
    }

    /**
     * One handler in the catalog — either a named option under {@code addMode: "subset"}, or the
     * single {@link #WILDCARD_NAME} entry under {@code addMode: "many"}.
     *
     * @param name            the handler's method name, or {@link #WILDCARD_NAME} for an open/many-shaped handler
     * @param kind            {@link #KIND_REMOTE} or {@link #KIND_RESOURCE}
     * @param presence        meaningful only under {@code addMode: "subset"} —
     *                       {@code "required"}/{@code "optional"} for this specific named option
     * @param annotations     ids into {@link TriggerAuthoringModel#annotations()} attached at
     *                        {@code attachPoint: "function"} for this handler
     * @param params          the handler's parameters, in meaningful positional order
     * @param returns         the handler's return type(s) — a union is expressed as more than one
     *                        element
     * @param method          resource-kind extra (HTTP): the legal HTTP verbs; {@code null} for a
     *                        remote-kind handler
     * @param path            resource-kind extra (HTTP): the legal path-segment shapes; {@code null}
     *                        for a remote-kind handler
     * @param accessor        resource-kind extra (GraphQL): {@code "get"} for a query field,
     *                        {@code "subscribe"} for a subscription; {@code null} otherwise
     * @param fieldName       resource-kind extra (GraphQL): the legal field-name shapes; {@code null}
     *                        otherwise
     * @param graphqlOperation resource-kind extra (GraphQL), informational: {@code "query"},
     *                        {@code "mutation"}, or {@code "subscription"}; {@code null} otherwise
     */
    public record HandlerOption(
            String name,
            String kind,
            String presence,
            List<String> annotations,
            List<Param> params,
            List<TypeRef> returns,
            PresenceValues method,
            PresenceForm path,
            PresenceValues accessor,
            PresenceForm fieldName,
            String graphqlOperation) {

        public static final String KIND_REMOTE = "remote";
        public static final String KIND_RESOURCE = "resource";
        public static final String WILDCARD_NAME = "*";
    }

    /**
     * One parameter slot of a {@link HandlerOption}. Order in the array is meaningful and is trusted
     * to convey positional constraints.
     *
     * @param name        the conventional/domain-meaningful parameter name; {@code null} when the
     *                    slot's name carries no meaning beyond its position
     * @param type        the parameter's legal type(s) — a union is expressed as more than one element
     * @param presence    {@code "required"} or {@code "optional"} for this slot
     * @param addMode     {@link Handlers#ADD_MODE_MANY} when this slot can repeat zero or more times,
     *                    each occurrence independently named/typed by the user; {@code null} means "at
     *                    most one"
     * @param dataBinding id into {@link TriggerAuthoringModel#dataBindingRules()}; present only when
     *                    the raw value can be projected into a different, user-defined type
     * @param annotations ids into {@link TriggerAuthoringModel#annotations()} attached at
     *                    {@code attachPoint: "parameter"} for this param
     */
    public record Param(
            String name,
            List<TypeRef> type,
            String presence,
            String addMode,
            String dataBinding,
            List<String> annotations) {
    }

    /**
     * A cross-construct constraint scoped to the enclosing service type. {@code type} lets new rule
     * kinds be added later without another shape change; {@link #TYPE_ONE_OF} is the only value that
     * exists today — exactly one of {@code members} must be chosen, not zero, not more than one.
     *
     * @param id      a local identifier for the rule, for diagnostics/documentation
     * @param type    the rule kind
     * @param members the mutually-exclusive alternatives
     */
    public record Rule(String id, String type, List<RuleMember> members) {

        public static final String TYPE_ONE_OF = "oneOf";

        /**
         * One alternative in a {@link Rule#TYPE_ONE_OF} group. Exactly one of the three shapes is
         * populated per member: a field inside a top-level annotation's record
         * ({@code annotation} + {@code field}), the enclosing service type's own identifier slot
         * ({@code part: "identifier"}), or one of the enclosing service type's own
         * {@code handlers.options[].name} values ({@code handler}). A member never repeats the
         * enclosing service type's id — that's implicit from nesting.
         *
         * @param annotation id into {@link TriggerAuthoringModel#annotations()}; set only for the
         *                   annotation-field alternative
         * @param field      the field name inside {@code annotation}'s record; set only alongside
         *                   {@code annotation}
         * @param preferred  {@code true} marks the canonical/idiomatic choice for a generator to
         *                   default to when nothing else disambiguates; {@code null} otherwise
         * @param part       {@link #PART_IDENTIFIER}; set only for the identifier alternative
         * @param handler    a {@link HandlerOption#name()} value; set only for the handler-choice
         *                   alternative
         */
        public record RuleMember(String annotation, String field, Boolean preferred, String part, String handler) {

            public static final String PART_IDENTIFIER = "identifier";
        }
    }
}
