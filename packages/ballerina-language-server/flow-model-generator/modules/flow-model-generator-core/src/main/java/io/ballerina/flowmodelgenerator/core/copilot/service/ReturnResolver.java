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

import com.google.gson.JsonObject;
import io.ballerina.modelgenerator.commons.trigger.models.TypeRef;
import io.ballerina.modelgenerator.commons.trigger.utils.TypeRefResolver;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Owns <b>spec §5 {@code options[].returns}</b>.
 *
 * <p>Joining the members with {@code |} is right here, and only here: a handler's return type genuinely
 * <i>is</i> the union, and spec §1's nilable rule — "{@code T?} = {@code T|()} is an explicit {@code ()}
 * union member, not a separate flag" — makes {@code error|()} the intended text, which canonicalizes to
 * {@code error?}. Contrast a {@code params[].type} union, which enumerates alternatives legal for one
 * slot and must never be joined.
 *
 * <p>A nil-only return carries no information and is omitted, per the spec's general omission rule.
 *
 * @since 1.7.0
 */
final class ReturnResolver {

    private static final String NIL = "()";

    private ReturnResolver() {
        // Prevent instantiation
    }

    /** The union's joined, module-prefixed signature text. */
    static String signature(List<TypeRef> returns, String packageName, Predicate<String> declaresType) {
        return TypeRefResolver.renderUnion(returns, packageName, declaresType);
    }

    /**
     * Builds the {@code return} object from an already-joined signature.
     *
     * @param returnSignature the joined signature, or the declared method's own return signature
     * @param packageName     the resolved package name, for link resolution
     * @return the {@code {type: {...}}} object, or empty when the return carries no information
     */
    static Optional<JsonObject> resolve(String returnSignature, String packageName) {
        if (returnSignature == null || returnSignature.isEmpty()) {
            return Optional.empty();
        }
        String canonical = ServiceIndexLoader.canonicalizeReturnType(returnSignature);
        if (canonical.isEmpty() || NIL.equals(canonical)) {
            return Optional.empty();
        }
        JsonObject returnObj = new JsonObject();
        returnObj.add("type", TypeResolver.resolveTypeWithLinks(canonical, packageName));
        return Optional.of(returnObj);
    }
}
