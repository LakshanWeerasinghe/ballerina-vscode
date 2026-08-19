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
 * Spec §5's {@code method}/{@code path} resource extras — the legal verbs and path shapes a resource handler
 * may be written with.
 *
 * <p>Metadata-driven handlers only: a concrete type's resource method already carries its real path, resolved
 * from the compiler's own {@code ResourcePath}, so there is no slot left to constrain.
 *
 * @since 1.7.0
 */
final class HttpResourceExtrasAspect implements HandlerAspect {

    @Override
    public String id() {
        return "httpResourceExtras";
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
        HttpResourceExtrasResolver.resolve(scope.option()).ifPresent(extras -> {
            draft.setMethod(extras.methodValues(), extras.methodRequired());
            draft.setPath(extras.pathForm(), extras.pathRequired());
        });
    }
}
