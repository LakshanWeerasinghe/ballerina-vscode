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
 * The method qualifiers a <b>concrete</b> service type's declared handler carries — today, {@code
 * isolated}.
 *
 * <p><b>Not a spec construct.</b> The metadata document models no qualifiers, and it should not: this is
 * introspectable from the library, which is exactly what the governing DRY principle says the document
 * must leave alone. So this aspect only ever fires for a service type whose methods come from the semantic
 * model; a marker type's handlers are described by the document and get nothing.
 *
 * <p><b>Why it earns its own aspect.</b> A qualifier is a distinct construct from a handler's name
 * ({@link HandlerIdentityAspect}) and from its remote/resource kind ({@link HandlerKindAspect}), and the
 * one-construct-one-owner rule is what keeps a change to any of the three from perturbing the others.
 *
 * <p><b>Why it matters.</b> Omitting {@code isolated} does not produce a warning — it produces
 * "mismatched function signatures: expected 'remote function onListTools() returns (…)', found 'remote
 * function onListTools() returns (…)'", where the two printed signatures are character-for-character
 * identical because the compiler prints neither qualifier. A reader given that message has no way to see
 * what differs. Verified against {@code mcp:AdvancedService}: without the qualifier the service fails to
 * compile; with it, {@code bal build} succeeds.
 *
 * @since 1.10.0
 */
final class HandlerQualifierAspect implements HandlerAspect {

    @Override
    public String id() {
        return "handlerQualifier";
    }

    @Override
    public String specSection() {
        return "§5";
    }

    @Override
    public void contribute(HandlerScope scope, HandlerDraft draft) {
        if (scope.isConcrete() && scope.declared().isolatedQualifier()) {
            draft.setIsolated();
        }
    }
}
