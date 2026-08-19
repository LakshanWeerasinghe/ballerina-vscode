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
 * Spec §5 {@code options[].kind} and the accessor of a resource handler.
 *
 * <p>Owns the two facts the renderer's keyword choice needs: whether to write {@code remote function} or
 * {@code resource function}, and — for the latter — which accessor follows it. Taken out of
 * {@link HandlerIdentityAspect}, which now owns only the handler's name and description, so that a change to
 * how a resource handler is written cannot perturb how it is named.
 *
 * <p>The accessor is emitted only for a resource handler. For a remote one it would be meaningless, and
 * {@code graphql}'s mutation shows why that matters: it carries GraphQL field metadata while being
 * {@code kind: "remote"}, so an accessor resolved from its siblings' vocabulary must not leak onto it.
 *
 * @since 1.7.0
 */
final class HandlerKindAspect implements HandlerAspect {

    @Override
    public String id() {
        return "handlerKind";
    }

    @Override
    public String specSection() {
        return "§5";
    }

    @Override
    public void contribute(HandlerScope scope, HandlerDraft draft) {
        HandlerKindResolver.Kind kind = scope.isConcrete()
                ? HandlerKindResolver.resolveDeclared(scope.declared().kind())
                : HandlerKindResolver.resolve(scope.option().kind());
        draft.setKind(kind.wireValue());

        if (!kind.isResource() || scope.isConcrete()) {
            // A concrete type's resource method already carries its real accessor and path inside the name
            // TriggerSemanticFacts derived from its resource path, so there is nothing to resolve for it.
            return;
        }
        AccessorPrecedencePolicy.accessorOf(scope.option()).ifPresent(draft::setAccessor);
    }
}
