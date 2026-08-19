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
 * A handler-level component. Invoked once per handler, which is why it cannot run before the handler
 * catalog exists — the tier structure makes that ordering hazard impossible rather than merely
 * documented.
 *
 * @since 1.7.0
 */
interface HandlerAspect {

    /** Stable identifier, e.g. {@code "handlerIdentity"}. */
    String id();

    /** The spec section this component owns, e.g. {@code "§5"}. */
    String specSection();

    /** Contributes to the draft; must not mutate the scope. */
    void contribute(HandlerScope scope, HandlerDraft draft);
}
