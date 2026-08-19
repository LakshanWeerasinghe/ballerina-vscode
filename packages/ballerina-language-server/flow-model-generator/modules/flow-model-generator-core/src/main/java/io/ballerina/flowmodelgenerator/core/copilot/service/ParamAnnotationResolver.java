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
 * Owns <b>spec §8 at {@code attachPoint: "parameter"}</b>: the annotations a handler parameter may carry.
 *
 * <p>Reached by <b>id</b> from {@code params[].annotations}. Spec §8 calls this the most precise reference
 * mechanism it has, which is why an entry reachable this way never carries {@code appliesTo}.
 *
 * <p>The rendered slot differs from every other scope: a parameter annotation is written <b>inline</b>,
 * before the parameter's type ({@code remote function onMessage(@rabbitmq:Payload AnydataMessage msg)}) —
 * verified to compile. That is also why its presence cannot be marked with a trailing {@code //} comment,
 * which inside a parameter list would comment out the closing paren and the return type.
 *
 * <p>Cross-module references work unchanged: {@code mcp}'s {@code httpHeader} names
 * {@code ballerina/http}'s {@code Header}, and carries its own module's alias.
 *
 * @since 1.7.0
 */
final class ParamAnnotationResolver {

    private ParamAnnotationResolver() {
        // Prevent instantiation
    }

    /**
     * Resolves the annotations one parameter slot references.
     *
     * @param registry   the document's §8 registry
     * @param ids        the slot's {@code annotations} ids, in document order; may be {@code null}
     * @param homeModule spec §1's home module
     * @param facts      the compiler-backed facts; {@code null} skips the checks that need them
     * @return the references to emit and the entries dropped
     */
    static AnnotationScopeResolver.Resolution resolve(AnnotationRegistry registry, List<String> ids,
                                                      String homeModule,
                                                      AnnotationScopeResolver.AnnotationFacts facts) {
        return AnnotationScopeResolver.byIds(registry, ids, AnnotationScopeResolver.Scope.PARAMETER,
                homeModule, facts);
    }
}
