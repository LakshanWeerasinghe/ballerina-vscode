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
 * Spec §5 {@code options[].returns} — the handler's return type.
 *
 * <p>A concrete method's return comes from the semantic model already rendered; a marker type's is the
 * document's union, joined and canonicalized. Both then drop a nil-only return, which carries no
 * information.
 *
 * @since 1.7.0
 */
final class ReturnAspect implements HandlerAspect {

    @Override
    public String id() {
        return "return";
    }

    @Override
    public String specSection() {
        return "§5";
    }

    @Override
    public void contribute(HandlerScope scope, HandlerDraft draft) {
        TriggerScope service = scope.service();
        String signature = scope.isConcrete()
                ? scope.declared().returnTypeSignature()
                : ReturnResolver.signature(scope.option().returns(), service.packageName(),
                        service.declaresType());
        ReturnResolver.resolve(signature, service.packageName()).ifPresent(draft::setReturn);
    }
}
