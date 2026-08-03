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
 * <b>Spec §3 {@code serviceTypes[]} identity</b>: at least one entry, unique ids, and a named type.
 *
 * <p>The id is what {@code listeners[].services}, {@code annotations[].appliesTo} and sibling rules all
 * reference, so a duplicate makes every one of those references ambiguous — and the pipeline resolves such
 * a reference by first match, which means the ambiguity silently resolves one way rather than failing.
 *
 * @since 1.10.0
 */
final class ServiceTypeRefCheck implements DocumentCheck {

    @Override
    public String id() {
        return "serviceTypeRef";
    }

    @Override
    public String specSection() {
        return "§3";
    }

    @Override
    public List<Finding> check(TriggerMetadataModel document) {
        List<Finding> findings = new ArrayList<>();
        List<TriggerMetadataModel.ServiceType> serviceTypes = DocumentWalk.safe(document.serviceTypes());
        if (serviceTypes.isEmpty()) {
            findings.add(Finding.error(this, "serviceTypes",
                    "spec §3: a connector exposes at least one service type"));
            return findings;
        }

        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < serviceTypes.size(); i++) {
            TriggerMetadataModel.ServiceType serviceType = serviceTypes.get(i);
            String path = DocumentWalk.serviceTypePath(i);
            if (serviceType == null) {
                findings.add(Finding.error(this, path, "a null service type entry"));
                continue;
            }
            if (serviceType.id() == null || serviceType.id().isBlank()) {
                findings.add(Finding.error(this, path + ".id",
                        "required: listeners[].services and rules reference a service type by id"));
            } else if (!seen.add(serviceType.id())) {
                findings.add(Finding.error(this, path + ".id",
                        "duplicate id '" + serviceType.id() + "': every reference to it is ambiguous"));
            }
            if (serviceType.type() == null || serviceType.type().name() == null
                    || serviceType.type().name().isBlank()) {
                findings.add(Finding.error(this, path + ".type",
                        "required: a service type must name the object type it is written with"));
            }
            if (serviceType.handlers() == null) {
                findings.add(Finding.error(this, path + ".handlers",
                        "required: without it there is no way to know where the handlers come from"));
            }
        }
        return findings;
    }
}
