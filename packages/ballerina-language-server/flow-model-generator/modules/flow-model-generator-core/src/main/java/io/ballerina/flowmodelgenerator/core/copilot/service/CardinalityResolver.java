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

/**
 * Owns <b>spec §3's {@code multipleListenersAllowed} and {@code multipleServicesPerListenerAllowed}</b>:
 * how many listeners one service may attach to, and how many services of this type one listener may host.
 *
 * <p>A pure passthrough of the document's two booleans. Which of them is worth <i>stating</i> is not
 * decided here — that is {@link CardinalityAspect}'s omission rule — so this resolver stays the single
 * place the spec's meaning lives and the aspect stays the single place the editorial judgement lives.
 *
 * <p><b>Known hazard, deliberately not worked around here.</b>
 * {@link TriggerMetadataModel.ServiceType} declares both fields as a primitive {@code boolean}, so a
 * document that omits a key deserializes to {@code false} — indistinguishable from a document that states
 * {@code false}. Under the "state only the prohibition" reading that turns an omission into a claim the
 * document never made, which is the tri-state defect §5's {@code presence} already had, in reverse. It is
 * harmless today only because every service type in every bundled document states both keys explicitly,
 * and {@code TriggerMetadataSpecTest} pins exactly that. Boxing the two fields (or having the reader
 * reject a document missing them) belongs to the validator phase.
 *
 * @since 1.7.0
 */
final class CardinalityResolver {

    private CardinalityResolver() {
        // Prevent instantiation
    }

    /**
     * Spec §3's two cardinality answers for one service type.
     *
     * @param multipleListeners           whether one service instance may attach to more than one listener
     *                                    at once ({@code service X on l1, l2 {}})
     * @param multipleServicesPerListener whether one listener may host more than one service of this type
     *                                    at once
     */
    record Cardinality(boolean multipleListeners, boolean multipleServicesPerListener) {
    }

    /**
     * Reads a service type's cardinality.
     *
     * @param serviceType the service type; may be {@code null}
     * @return its cardinality; a {@code null} service type reads as fully permissive, which states nothing
     */
    static Cardinality resolve(TriggerMetadataModel.ServiceType serviceType) {
        if (serviceType == null) {
            return new Cardinality(true, true);
        }
        return new Cardinality(serviceType.multipleListenersAllowed(),
                serviceType.multipleServicesPerListenerAllowed());
    }
}
