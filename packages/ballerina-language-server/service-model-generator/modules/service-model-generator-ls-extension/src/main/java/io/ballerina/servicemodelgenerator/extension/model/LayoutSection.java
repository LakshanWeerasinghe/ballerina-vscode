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

package io.ballerina.servicemodelgenerator.extension.model;

import java.util.List;

/**
 * One section of a handler form's authored layout, carried verbatim from a schema-driven trigger's
 * {@code TriggerUISchemaModel.LayoutSection} to the designer. Presentation only -- it never affects the
 * generated function signature, which always follows {@link Function#getParameters()} order.
 *
 * <p>Entirely optional: a {@link Function} with no {@code layout} renders in the designer's default
 * order, which is what every handler shipped before this field existed does.
 *
 * <p>{@code fields} names the form's addressable units. An author's own identifiers are used bare -- a
 * parameter's name, a {@code properties} key, or a payload {@code bindingGroup} (which addresses the
 * whole group, as does any one member's name). The form's built-in units use reserved {@code $}-prefixed
 * ids, which can never collide with a Ballerina identifier: {@code $variant}, {@code $description},
 * {@code $name}, {@code $documentation}, {@code $parameters}, {@code $returnType} and {@code $headers}.
 * Separately, {@code *rest} stands for every unit no section claimed, letting a partial layout say where
 * the remainder goes -- prefixed {@code *} because it is a placement directive rather than the name of a
 * unit, and because a {@code properties} key is an arbitrary schema-authored string (a field literally
 * called {@code $rest} is possible; nothing can be called {@code *rest}). An id matching no unit is
 * skipped, since a handler variant may legitimately lack a field its siblings have.
 *
 * @param id          an identifier for this section, for diagnostics and stable render keys
 * @param label       the heading rendered above this section; absent -> an ordered run with no heading,
 *                    which is how a layout orders inputs without grouping them
 * @param description explanatory text rendered under {@code label}
 * @param advanced    {@code true} renders this section inside the form's collapsed "Advanced Configurations"
 *                    box rather than in the main body; meaningful only on a labelled section
 * @param fields      the ids of the units in this section, in the order they should appear
 * @since 1.9.0
 */
public record LayoutSection(String id, String label, String description, Boolean advanced,
                            List<String> fields) {
}
