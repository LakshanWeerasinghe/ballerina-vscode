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

import java.util.List;
import java.util.function.Predicate;

/**
 * Owns <b>spec §9's {@code mode: "streamable"}</b>: "Same as {@code direct}, but {@code stream<...>} over
 * the target type."
 *
 * <p><b>It reads its own {@code typeConstraint}.</b> The sibling consumer never does — it detects the
 * mode's presence and then reuses the {@code direct} mode's constraint, which for {@code ftp} and
 * {@code smb} is a different list entirely ({@code string[][]}, {@code record {}[]} versus
 * {@code stream<string[], error?>}, {@code stream<record {}, error?>}). Copying that would emit a stream
 * over the wrong element type.
 *
 * <p>Note what the corpus actually declares: the constraint members are <b>already whole stream types</b>,
 * not element types to be wrapped. So a consumer renders the declared signature as-is; it must not wrap it
 * in a second {@code stream<>}. Kept separate from {@link DirectModeResolver} for exactly this reason —
 * the two slots are rendered differently even though they are read the same way.
 *
 * @since 1.7.0
 */
final class StreamableModeResolver {

    private StreamableModeResolver() {
        // Prevent instantiation
    }

    /**
     * The {@code streamable} mode of one data-binding rule.
     *
     * @param typeConstraint every legal stream-shaped target type, in document order, as module-prefixed
     *                       signature text; read from this mode's own slot, never borrowed from
     *                       {@code direct}
     */
    record Streamable(List<String> typeConstraint) implements DataBindingResolver.Mode {
    }

    /**
     * Resolves a {@code streamable} mode.
     *
     * @param mode         the {@code supportedModes[]} entry
     * @param packageName  the resolved package name, for rendering type references per spec §1
     * @param declaresType whether the home module declares a type of a given name
     * @return the resolved mode; its list may be empty but is never {@code null}
     */
    static Streamable resolve(TriggerMetadataModel.DataBindingRule.SupportedMode mode, String packageName,
                              Predicate<String> declaresType) {
        return new Streamable(DirectModeResolver.render(mode.typeConstraint(), packageName, declaresType));
    }
}
