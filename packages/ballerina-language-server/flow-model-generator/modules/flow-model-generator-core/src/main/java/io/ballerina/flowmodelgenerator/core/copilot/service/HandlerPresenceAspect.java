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
 * Spec §5 {@code options[].presence} — whether a handler must be implemented or may be omitted.
 *
 * <p>Applies to a metadata-driven handler only. A <b>concrete</b> service type's methods are read from the
 * semantic model, where no such notion exists: the type declares them and the compiler plugin decides which
 * a service must implement, so the document says nothing and neither does this component. That is why
 * {@code trigger.github}'s and {@code mcp:AdvancedService}'s handlers carry no presence marker.
 *
 * @since 1.7.0
 */
final class HandlerPresenceAspect implements HandlerAspect {

    @Override
    public String id() {
        return "handlerPresence";
    }

    @Override
    public String specSection() {
        return "§5";
    }

    @Override
    public void contribute(HandlerScope scope, HandlerDraft draft) {
        if (scope.isConcrete()) {
            return;
        }
        TriggerMetadataModel.ServiceType.Handlers handlers = scope.service().serviceType() == null
                ? null : scope.service().serviceType().handlers();
        String addMode = handlers == null ? null : handlers.addMode();
        HandlerPresenceResolver.resolveOptional(scope.option().presence(), addMode)
                .ifPresent(draft::setOptional);
    }
}
