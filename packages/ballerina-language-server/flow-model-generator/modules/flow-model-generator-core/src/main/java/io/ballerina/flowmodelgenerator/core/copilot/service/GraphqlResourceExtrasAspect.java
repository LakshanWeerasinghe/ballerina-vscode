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
 * Spec §5's GraphQL resource extras — {@code fieldName} and the informational {@code graphqlOperation}.
 *
 * <p>{@code accessor} is deliberately <b>not</b> written here even though the GraphQL resolver reads it: the
 * accessor is one decision with one owner ({@link HandlerKindAspect}, via
 * {@link AccessorPrecedencePolicy}), because HTTP's {@code method} can supply it too. This component owns
 * only what is GraphQL's alone.
 *
 * <p>Metadata-driven handlers only, for the same reason as {@link HttpResourceExtrasAspect}.
 *
 * @since 1.7.0
 */
final class GraphqlResourceExtrasAspect implements HandlerAspect {

    @Override
    public String id() {
        return "graphqlResourceExtras";
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
        GraphqlResourceExtrasResolver.resolve(scope.option()).ifPresent(extras -> {
            draft.setFieldName(extras.fieldNameForm(), extras.fieldNameRequired());
            draft.setGraphqlOperation(extras.operation());
        });
    }
}
