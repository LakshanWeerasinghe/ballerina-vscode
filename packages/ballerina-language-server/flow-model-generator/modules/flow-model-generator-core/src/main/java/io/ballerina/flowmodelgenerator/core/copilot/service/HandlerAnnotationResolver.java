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
 * Owns <b>spec §8 at {@code attachPoint: "function"}</b>: the annotations a handler must or may carry.
 *
 * <p>Reached by <b>id</b> from {@code handlers.options[].annotations} — §8's precise access path, so unlike
 * service scope this resolver never consults {@code appliesTo}.
 *
 * <p>The gap this closes is the same class as P3's: {@code ballerina/smb} declares its
 * {@code functionConfig} with {@code presence: "required"}, so generated smb handlers are obliged to carry
 * {@code @smb:FunctionConfig} — and that obligation reached the prompt nowhere. {@code ballerina/ftp}
 * declares the optional counterpart on all eight of its handlers.
 *
 * <p>Only a <b>marker</b> service type's handlers can carry one: a concrete type's methods are read from
 * the semantic model, and the document does not describe them.
 *
 * @since 1.7.0
 */
final class HandlerAnnotationResolver {

    private HandlerAnnotationResolver() {
        // Prevent instantiation
    }

    /**
     * Resolves the annotations one handler references.
     *
     * @param registry   the document's §8 registry
     * @param ids        the handler's {@code annotations} ids, in document order; may be {@code null}
     * @param resource   whether the handler renders as a resource method, which admits a narrower set of
     *                   declared attach points than a remote one
     * @param homeModule spec §1's home module
     * @param facts      the compiler-backed facts; {@code null} skips the checks that need them
     * @return the references to emit and the entries dropped
     */
    static AnnotationScopeResolver.Resolution resolve(AnnotationRegistry registry, List<String> ids,
                                                      boolean resource, String homeModule,
                                                      AnnotationScopeResolver.AnnotationFacts facts) {
        return AnnotationScopeResolver.byIds(registry, ids,
                resource ? AnnotationScopeResolver.Scope.RESOURCE_HANDLER
                        : AnnotationScopeResolver.Scope.REMOTE_HANDLER,
                homeModule, facts);
    }
}
