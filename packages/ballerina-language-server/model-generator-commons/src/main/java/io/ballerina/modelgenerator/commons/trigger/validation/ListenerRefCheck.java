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

package io.ballerina.modelgenerator.commons.trigger.validation;

import io.ballerina.modelgenerator.commons.trigger.models.TriggerMetadataModel;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * <b>Spec §2 {@code listeners[]}</b>: at least one listener, and every {@code services} id names a
 * declared service type.
 *
 * <p>A service type that <b>no</b> listener names is reported as WARN, not ERROR, because it is legal and
 * the corpus contains a correct instance: {@code websocket} declares {@code Service} but lists only
 * {@code upgradeService} under its listener, because {@code Service} is reached as the return of the
 * upgrade resource rather than by attachment. The compiler agrees — attaching it gives "service type is
 * not supported by the listener". So the finding exists to make the shape <i>visible</i> (a consumer must
 * render such a type differently), not to forbid it.
 *
 * <p>The reverse — a {@code services} id naming a service type that does not exist — is an ERROR: nothing
 * can host it, and the id is simply wrong.
 *
 * @since 1.10.0
 */
final class ListenerRefCheck implements DocumentCheck {

    @Override
    public String id() {
        return "listenerRef";
    }

    @Override
    public String specSection() {
        return "§2";
    }

    @Override
    public List<Finding> check(TriggerMetadataModel document) {
        List<Finding> findings = new ArrayList<>();
        List<TriggerMetadataModel.Listener> listeners = DocumentWalk.safe(document.listeners());
        if (listeners.isEmpty()) {
            findings.add(Finding.error(this, "listeners",
                    "spec §2: a connector declares at least one listener entry point"));
            return findings;
        }

        Set<String> serviceTypeIds = new LinkedHashSet<>();
        for (TriggerMetadataModel.ServiceType serviceType : DocumentWalk.safe(document.serviceTypes())) {
            if (serviceType != null && serviceType.id() != null) {
                serviceTypeIds.add(serviceType.id());
            }
        }

        Set<String> hosted = new LinkedHashSet<>();
        for (int i = 0; i < listeners.size(); i++) {
            TriggerMetadataModel.Listener listener = listeners.get(i);
            if (listener == null) {
                findings.add(Finding.error(this, "listeners[" + i + "]", "a null listener entry"));
                continue;
            }
            if (listener.type() == null || listener.type().name() == null) {
                findings.add(Finding.error(this, "listeners[" + i + "].type",
                        "a listener must name its class"));
            }
            for (String id : DocumentWalk.safe(listener.services())) {
                hosted.add(id);
                if (!serviceTypeIds.contains(id)) {
                    findings.add(Finding.error(this, "listeners[" + i + "].services",
                            "names '" + id + "', which no serviceTypes[] entry declares"));
                }
            }
        }

        for (String id : serviceTypeIds) {
            if (!hosted.contains(id)) {
                findings.add(Finding.warn(this, "serviceTypes[" + id + "]",
                        "no listener declares it hostable, so it cannot be written as `service … on new …`;"
                                + " a consumer must render it another way"));
            }
        }
        return findings;
    }
}
