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

import io.ballerina.compiler.api.symbols.ClassSymbol;
import io.ballerina.modelgenerator.commons.trigger.models.TriggerMetadataModel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Owns <b>spec §2 {@code listeners[].type} and {@code .services}</b>: which listener hosts which service
 * type.
 *
 * <p>Spec §2 defines {@code services} as "{@code serviceTypes[].id} values this listener can host", so a
 * service type is paired with the listener that names its id. When no listener names it — or the document
 * omits {@code services} — the first listener is used, which is the only behaviour the corpus exercises:
 * all 13 documents declare exactly one listener, so every pairing resolves to it either way.
 *
 * <p>The multi-listener path is therefore <b>latent</b>: implemented and unit-testable against a synthetic
 * document, but not reachable from any shipped connector today.
 *
 * @since 1.7.0
 */
final class ListenerPairingResolver {

    private ListenerPairingResolver() {
        // Prevent instantiation
    }

    /**
     * One service type bound to the listener that hosts it, with that listener's resolved class.
     *
     * @param serviceType   the service type
     * @param listener      the hosting listener; never {@code null}
     * @param listenerClass the listener's class in the resolved package; never {@code null}
     */
    record ListenerPairing(TriggerMetadataModel.ServiceType serviceType,
                           TriggerMetadataModel.Listener listener,
                           ClassSymbol listenerClass) {
    }

    /**
     * The listener hosting a service type: the one whose {@code services} names its id, else the first.
     *
     * @param listeners   the document's listeners; never empty
     * @param serviceType the service type to place
     * @return the hosting listener
     */
    static TriggerMetadataModel.Listener hostOf(List<TriggerMetadataModel.Listener> listeners,
                                                TriggerMetadataModel.ServiceType serviceType) {
        String id = serviceType == null ? null : serviceType.id();
        if (id != null) {
            for (TriggerMetadataModel.Listener listener : listeners) {
                if (listener != null && listener.services() != null && listener.services().contains(id)) {
                    return listener;
                }
            }
        }
        return listeners.get(0);
    }

    /**
     * Pairs every service type with its listener and that listener's resolved class.
     *
     * <p>A listener class that cannot be resolved means the resolved package no longer matches the
     * document's world view, so pairings depending on it are omitted rather than emitted as a listener the
     * generated code could not instantiate. Each distinct listener is resolved once.
     *
     * @param listeners    the document's listeners
     * @param serviceTypes the document's service types
     * @param facts        the resolved package's symbols
     * @return one pairing per service type whose listener resolved, in document order
     */
    static List<ListenerPairing> resolve(List<TriggerMetadataModel.Listener> listeners,
                                         List<TriggerMetadataModel.ServiceType> serviceTypes,
                                         TriggerSemanticFacts facts) {
        List<ListenerPairing> pairings = new ArrayList<>();
        if (listeners == null || listeners.isEmpty() || serviceTypes == null) {
            return pairings;
        }
        Map<TriggerMetadataModel.Listener, Optional<ClassSymbol>> resolved = new LinkedHashMap<>();
        for (TriggerMetadataModel.ServiceType serviceType : serviceTypes) {
            TriggerMetadataModel.Listener listener = hostOf(listeners, serviceType);
            Optional<ClassSymbol> listenerClass = resolved.computeIfAbsent(listener,
                    l -> facts.resolveListenerClass(l != null && l.type() != null ? l.type().name() : null));
            if (listenerClass.isEmpty()) {
                continue;
            }
            pairings.add(new ListenerPairing(serviceType, listener, listenerClass.get()));
        }
        return pairings;
    }
}
