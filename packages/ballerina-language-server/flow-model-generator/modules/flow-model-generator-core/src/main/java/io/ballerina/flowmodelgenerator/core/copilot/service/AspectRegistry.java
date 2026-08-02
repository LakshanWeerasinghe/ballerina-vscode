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

import java.util.List;

/**
 * The single ordered list of components, and the one place a future spec version diverges: registering a
 * replacement resolver here is the whole change, because nothing else names a component.
 *
 * <p><b>Ordering.</b> Within a tier the order is declared once, here. Only two entries have a real
 * dependency, and both are noted at their line; the rest are order-independent and are listed to match
 * the wire contract's key order purely so the emitted JSON reads naturally.
 *
 * <p><b>Lifetime.</b> A registry is built per library load, never shared: {@link ListenerAspect} memoizes
 * the listener object it builds, and that cache is only valid within one library's resolved package.
 *
 * @since 1.7.0
 */
final class AspectRegistry {

    /** The spec version the corpus is authored against. */
    static final String VERSION_V1 = "v1";

    private final List<ServiceAspect> serviceAspects;
    private final List<HandlerAspect> handlerAspects;
    private final List<ParamAspect> paramAspects;

    private AspectRegistry() {
        this.handlerAspects = List.of(
                new HandlerIdentityAspect(),
                new ReturnAspect());
        this.paramAspects = List.of(
                new ParamTypeAspect());
        this.serviceAspects = List.of(
                // Must run first: it resolves the service-type id every later component is scoped to,
                // and it is the component that can veto the entry outright.
                new ServiceIdentityAspect(),
                new RequiredImportAspect(),
                new ListenerAspect(),
                // Must run last: it drives the handler and parameter tiers, so every service-level
                // contribution has to be in place before it starts.
                new HandlerCatalogAspect(this));
    }

    /**
     * The component set for a spec version.
     *
     * <p>Every document in the corpus predates the spec's {@code version} key, so an absent version is
     * read as v1 rather than rejected — rejecting would disable every trigger library. Enforcing the key
     * is a later phase, and it belongs at the reader, not here.
     *
     * @param specVersion the document's declared version; {@code null} when it declares none
     * @return a fresh registry; never shared between libraries
     */
    static AspectRegistry forVersion(String specVersion) {
        // v1 is the only version that exists; the parameter is the seam a v2 set plugs into.
        return new AspectRegistry();
    }

    List<ServiceAspect> serviceAspects() {
        return serviceAspects;
    }

    List<HandlerAspect> handlerAspects() {
        return handlerAspects;
    }

    List<ParamAspect> paramAspects() {
        return paramAspects;
    }
}
