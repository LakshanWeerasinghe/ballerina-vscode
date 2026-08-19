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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Owns <b>spec §9's {@code mode: "direct"}</b>: "Param type directly <i>is</i> the target type — no
 * wrapping."
 *
 * <p><b>Every {@code typeConstraint} member is kept, and so is the whole {@code excludes} list.</b> The
 * sibling consumer truncates the constraint to {@code get(0)} ({@code TriggerModelSynthesizer:711}) and
 * drops {@code excludes} entirely; both are bugs and neither is copied here. Truncating would silently
 * narrow what the model is told is legal, and dropping {@code excludes} would state the opposite of the
 * document: {@code kafka} and {@code rabbitmq} both allow "any {@code anydata}" <i>except</i> their own
 * envelope type, and binding the envelope directly is precisely the mistake the exclusion exists to prevent.
 *
 * <p>{@code excludes} is a <b>negative</b> constraint. Nothing else in the document, and no amount of
 * introspection, can reconstruct it, so it must survive every downstream filter — including the renderer's
 * suppression of type names already visible in the signature.
 *
 * @since 1.7.0
 */
final class DirectModeResolver {

    private DirectModeResolver() {
        // Prevent instantiation
    }

    /**
     * The {@code direct} mode of one data-binding rule.
     *
     * @param typeConstraint every legal target type, in document order, as module-prefixed signature text;
     *                       never truncated to the first member
     * @param excludes       the types explicitly disallowed within {@code typeConstraint}'s category, in
     *                       document order; empty when the document excludes nothing
     */
    record Direct(List<String> typeConstraint, List<String> excludes) implements DataBindingResolver.Mode {
    }

    /**
     * Resolves a {@code direct} mode.
     *
     * @param mode         the {@code supportedModes[]} entry
     * @param packageName  the resolved package name, for rendering type references per spec §1
     * @param declaresType whether the home module declares a type of a given name
     * @return the resolved mode; its lists may be empty but are never {@code null}
     */
    static Direct resolve(TriggerMetadataModel.DataBindingRule.SupportedMode mode, String packageName,
                          Predicate<String> declaresType) {
        return new Direct(render(mode.typeConstraint(), packageName, declaresType),
                render(mode.excludes(), packageName, declaresType));
    }

    /**
     * Renders every member of a {@code TypeRef} list, dropping nothing.
     *
     * <p>Shared with {@link StreamableModeResolver}, which needs exactly the same rendering over its own
     * slot — the two modes differ in what the list <i>means</i>, not in how a member is written.
     *
     * @param refs         the members; may be {@code null}
     * @param packageName  the resolved package name
     * @param declaresType whether the home module declares a type of a given name
     * @return the rendered signatures, in document order
     */
    static List<String> render(List<TypeRef> refs, String packageName, Predicate<String> declaresType) {
        if (refs == null || refs.isEmpty()) {
            return List.of();
        }
        List<String> rendered = new ArrayList<>();
        for (TypeRef ref : refs) {
            String signature = TypeRefResolver.render(ref, packageName, declaresType);
            if (signature != null && !signature.isEmpty()) {
                rendered.add(signature);
            }
        }
        return rendered;
    }
}
