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

import io.ballerina.modelgenerator.commons.trigger.models.TriggerMetadataModel;
import io.ballerina.modelgenerator.commons.trigger.models.TypeRef;
import io.ballerina.modelgenerator.commons.trigger.utils.TypeRefResolver;

import java.util.Set;
import java.util.function.Predicate;

/**
 * Owns <b>spec §7 {@code params[]}</b>: a handler parameter slot's type, presence, repeatability and name.
 *
 * <p>Spec §7 makes three statements this component implements:
 * <ul>
 *   <li>{@code type} is a {@code TypeRef} or array, and per §1 "the first element is the codegen
 *       default" — so the emitted signature is the first member.</li>
 *   <li>{@code presence} is {@code required}/{@code optional}.</li>
 *   <li>{@code addMode: "many"} means the slot "repeats zero or more times, each occurrence
 *       independently named/typed" — an open-ended authoring shape with no fixed-signature counterpart,
 *       so such a slot contributes no parameter.</li>
 * </ul>
 *
 * <p>{@code name} is the interesting one: §7 calls it an "optional domain-meaningful name … added only
 * where real source evidence shows it matters". Where the document states one it wins; where it does not,
 * a name must still be synthesized because a method signature cannot be written without one.
 *
 * @since 1.7.0
 */
final class ParamTypeResolver {

    /**
     * Spec §10's presence vocabulary. Declared here rather than borrowed from a sibling construct's
     * constant: {@code params[].presence} is its own slot, and coupling it to an unrelated type's
     * constant would make a future divergence in either invisible.
     */
    private static final String PRESENCE_OPTIONAL = "optional";

    private ParamTypeResolver() {
        // Prevent instantiation
    }

    /** Spec §7 {@code presence}: {@code "optional"} is the only value that changes the signature. */
    static boolean isOptional(TriggerMetadataModel.ServiceType.Param param) {
        return PRESENCE_OPTIONAL.equals(param.presence());
    }

    /**
     * Spec §7 {@code addMode: "many"}: a repeatable slot is user-named and user-typed, so it has no
     * place in a fixed signature and is skipped.
     */
    static boolean isRepeatable(TriggerMetadataModel.ServiceType.Param param) {
        return TriggerMetadataModel.ServiceType.Handlers.ADD_MODE_MANY.equals(param.addMode());
    }

    /**
     * The slot's emitted type signature: its codegen-default member, module-prefixed per spec §1.
     */
    static String signature(TriggerMetadataModel.ServiceType.Param param, String packageName,
                            Predicate<String> declaresType) {
        return TypeRefResolver.render(TypeRefResolver.first(param.type()), packageName, declaresType);
    }

    /**
     * The slot's name: the authored one where the document states it, otherwise a deterministic
     * generated one.
     *
     * @param param       the slot
     * @param position    its zero-based index, used for the positional fallback
     * @param moduleAlias the library's module alias, used for the {@code <alias>Error} convention
     * @param usedNames   names already taken by siblings, which the generated name must avoid
     */
    static String resolveName(TriggerMetadataModel.ServiceType.Param param, int position,
                              String moduleAlias, Set<String> usedNames) {
        if (param.name() != null) {
            return param.name();
        }
        return HandlerParamNameGenerator.generate(TypeRefResolver.first(param.type()),
                param.dataBinding() != null, moduleAlias, position, usedNames);
    }

    /**
     * Whether a handler's emitted signature references a bare, capitalized — i.e. user-defined-looking —
     * same-module type the resolved package does not declare.
     *
     * <p>This is the guard against a document authored for a different release: rendering
     * {@code websub}'s {@code onHubError} when the package declares no {@code HubError} would put an
     * uncompilable signature in the prompt. Only the members that actually reach the signature are
     * inspected — the first type member of each parameter, and every return member.
     */
    static boolean signatureReferencesUndeclaredType(
            TriggerMetadataModel.ServiceType.HandlerOption option, Predicate<String> declaresType) {
        if (option.params() != null) {
            for (TriggerMetadataModel.ServiceType.Param param : option.params()) {
                if (param != null
                        && isUndeclaredBareUserType(TypeRefResolver.first(param.type()), declaresType)) {
                    return true;
                }
            }
        }
        if (option.returns() != null) {
            for (TypeRef ref : option.returns()) {
                if (isUndeclaredBareUserType(ref, declaresType)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * A bare reference (spec §1: same module as the connector's own types) whose base identifier looks
     * user-defined but which the resolved package does not declare. A {@code packageInfo}-carrying
     * reference is cross-module and cannot be checked against this module's symbols, so it is trusted.
     */
    private static boolean isUndeclaredBareUserType(TypeRef ref, Predicate<String> declaresType) {
        if (ref == null || ref.name() == null || ref.packageInfo() != null) {
            return false;
        }
        String base = TypeRefResolver.baseIdentifier(ref.name());
        return base != null && !base.isEmpty() && Character.isUpperCase(base.charAt(0))
                && !declaresType.test(base);
    }
}
