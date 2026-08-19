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
 * Spec §3's {@code multiple*Allowed} pair — and the decision of which half of it is worth saying.
 *
 * <h2>Only a prohibition is emitted</h2>
 *
 * <p>Both keys are set on all 26 service types in the corpus, so writing both unconditionally would land a
 * note on essentially every trigger service the Copilot renders. Only the {@code false} values earn that
 * space:
 *
 * <ul>
 *   <li>{@code true} grants a permission a generator would not exercise unprompted. The default shape a
 *       model writes — one service, one listener — is legal under {@code true} <i>and</i> under
 *       {@code false}, so stating {@code true} changes no output it would otherwise produce.</li>
 *   <li>{@code false} forbids something a model can plausibly reach for. Asked to consume two Kafka
 *       topics, the obvious shape is two services on one listener, which {@code kafka}'s
 *       {@code multipleServicesPerListenerAllowed: false} makes illegal. That is the case where saying
 *       nothing costs a compile.</li>
 * </ul>
 *
 * <p>This is the same asymmetry the pipeline already applies elsewhere: {@link HandlerPresenceResolver}
 * emits nothing when the document is not answering the question, and {@link IdentifierResolver} yields a
 * placeholder only for a slot that must be filled. Across the whole corpus this rule emits <b>three</b>
 * lines — {@code kafka} (both) and {@code ballerinax/trigger.google.calendar} (the second) — instead of
 * twenty-four.
 *
 * <p>The two keys are written <b>separately</b> rather than merged into one note: they answer different
 * questions, and {@code kafka} is the only service type in the corpus where both fire, so a combined line
 * would be wrong for {@code trigger.google.calendar}.
 *
 * @since 1.7.0
 */
final class CardinalityAspect implements ServiceAspect {

    @Override
    public String id() {
        return "cardinality";
    }

    @Override
    public String specSection() {
        return "§3";
    }

    @Override
    public void contribute(TriggerScope scope, ServiceDraft draft) {
        CardinalityResolver.Cardinality cardinality = CardinalityResolver.resolve(scope.serviceType());
        if (!cardinality.multipleListeners()) {
            draft.setSingleListenerOnly();
        }
        if (!cardinality.multipleServicesPerListener()) {
            draft.setSingleServicePerListenerOnly();
        }
    }
}
