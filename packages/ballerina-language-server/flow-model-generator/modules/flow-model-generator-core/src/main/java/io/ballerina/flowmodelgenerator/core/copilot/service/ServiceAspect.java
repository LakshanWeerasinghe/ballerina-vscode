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
 * A service-level component: reads the slice of the document it owns off the {@link TriggerScope} and
 * writes its contribution onto the {@link ServiceDraft}.
 *
 * <p>An aspect is deliberately thin. The spec logic lives in a pure resolver it delegates to, which is
 * what makes that logic testable without a semantic model; the aspect is only the wiring that says
 * where the input comes from and where the output goes. When the spec changes, the resolver changes and
 * the aspect does not.
 *
 * @since 1.7.0
 */
interface ServiceAspect {

    /** Stable identifier, e.g. {@code "serviceIdentity"}. Shared with the TypeScript renderer's id. */
    String id();

    /** The spec section this component owns, e.g. {@code "§3"}. */
    String specSection();

    /** Contributes to the draft; must not mutate the scope. */
    void contribute(TriggerScope scope, ServiceDraft draft);
}
