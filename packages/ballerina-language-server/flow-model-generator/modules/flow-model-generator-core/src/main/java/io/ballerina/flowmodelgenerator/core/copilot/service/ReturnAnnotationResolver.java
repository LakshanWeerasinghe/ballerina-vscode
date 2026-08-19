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
 * Owns <b>spec §8 at {@code attachPoint: "return"}</b>: the annotations a handler's return may carry.
 *
 * <p><b>Resolved by {@code appliesTo}, not by id</b> — and that is a correction to the component sketch,
 * which gave this the same {@code (registry, ids)} signature as the handler and parameter scopes. There is
 * no id list to read: neither {@code handlers.options[]} nor any sibling construct declares a
 * return-annotation reference, and spec §8's own <b>"Residual gap"</b> says so outright —
 * "service-level/return-level annotations have no reference mechanism as precise as
 * {@code params[].annotations}, so they always rely on {@code appliesTo}". The corpus agrees:
 * {@code ballerina/http}'s {@code cache} entry is filed at {@code attachPoint: "return"} with
 * {@code appliesTo: ["service"]} and is referenced from nowhere.
 *
 * <p>So this resolver mirrors {@link ServiceAnnotationResolver}'s selection — attach point plus
 * {@code appliesTo}, including its "absent {@code appliesTo} applies to every service type" reading — while
 * targeting a different syntactic slot: {@code returns @http:Cache {...} T}, which is why it cannot share
 * {@link HandlerAnnotationResolver}.
 *
 * <p><b>No corpus output today.</b> {@code http} is the only user, and its schema-derived service entry is
 * discarded by the curated {@code generic-services.json} overlay. The component exists because the wire
 * contract is what later phases build on, and because a second return-scope document would otherwise land
 * with nothing to resolve it.
 *
 * @since 1.7.0
 */
final class ReturnAnnotationResolver {

    private ReturnAnnotationResolver() {
        // Prevent instantiation
    }

    /**
     * Resolves the annotations the enclosing service type's handlers must or may carry on their return.
     *
     * @param registry      the document's §8 registry
     * @param serviceTypeId the enclosing service type's id; {@code null} when the document names none
     * @param homeModule    spec §1's home module
     * @param facts         the compiler-backed facts; {@code null} skips the checks that need them
     * @return the references to emit and the entries dropped
     */
    static AnnotationScopeResolver.Resolution resolve(AnnotationRegistry registry, String serviceTypeId,
                                                      String homeModule,
                                                      AnnotationScopeResolver.AnnotationFacts facts) {
        return AnnotationScopeResolver.byAttachPoint(registry, serviceTypeId,
                AnnotationScopeResolver.Scope.RETURN, homeModule, facts);
    }
}
