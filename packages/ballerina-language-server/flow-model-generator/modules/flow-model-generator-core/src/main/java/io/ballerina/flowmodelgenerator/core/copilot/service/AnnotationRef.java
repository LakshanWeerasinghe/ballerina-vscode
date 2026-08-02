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

/**
 * One annotation a metadata document says the generated code must (or may) attach — spec §8.
 *
 * <p><b>Deliberately not {@code AnnotationAttachment}</b>, the semantic-model POJO for an annotation the
 * compiler reports as <i>already present</i> on a library symbol. The two look alike and behave
 * oppositely:
 * <ul>
 *   <li>an <b>attachment</b> is a fact about the library, and renders verbatim with its real value —
 *       {@code @display {label: "Kafka"}};</li>
 *   <li>a <b>reference</b> is a requirement on code that does not exist yet, and renders as a
 *       requirement: a placeholder value the model has to fill, plus a presence marker stating whether
 *       omitting it is legal.</li>
 * </ul>
 * Reusing one type for both would make "the library has this" indistinguishable from "your code needs
 * this", which is the whole content of §8.
 *
 * <p>{@code recordFields} is deliberately absent. It appears in the plan's sketch of this record, but
 * the annotation record's field shape is <b>fully derivable from {@code typeConstraint}</b> — whose
 * links carry the definition into the prompt as a real type declaration — so restating the field names
 * here would violate the spec's own general rule ("a field … fully derivable from other fields is left
 * out"). It would also be empty in the one case that matters: the introspector reads the <i>home</i>
 * module's symbols, and {@code mssql}'s package does not declare {@code cdc:ServiceConfig}.
 *
 * @param name           the annotation's name, unqualified, e.g. {@code "ServiceConfig"}
 * @param module         the {@code org/module} a cross-module annotation belongs to, e.g.
 *                       {@code "ballerinax/cdc"}; {@code null} for one declared by the home module,
 *                       which the renderer then prefixes with the library's own alias
 * @param required       spec §8 {@code presence}: whether the annotation must be attached at all
 * @param attachPoint    spec §8 {@code attachPoint}; always {@code "service"} for this tier
 * @param typeConstraint the annotation's type as module-prefixed signature text, or {@code null} for a
 *                       marker annotation the document gives no type
 * @since 1.7.0
 */
record AnnotationRef(String name, String module, boolean required, String attachPoint,
                     String typeConstraint) {
}
