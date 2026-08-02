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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Logger;

/**
 * Owns <b>spec §9 {@code dataBindingRules[]}</b> at the rule level: resolving a
 * {@code params[].dataBinding} id against the registry, reading {@code cardinality}, and dispatching each
 * {@code supportedModes[]} entry to the resolver that owns its mode.
 *
 * <p>Before this component the whole registry was collapsed to a <b>boolean</b>: {@code dataBinding != null}
 * was consulted only to decide whether an unnamed parameter should be called {@code payload}. Everything
 * §9 actually says — which types the slot may be bound to, which are excluded, that a user record may
 * include the envelope and override named fields, that a stream form exists — reached the prompt nowhere.
 *
 * <p><b>The mode is the unit of change.</b> Adding a fourth mode, or changing what {@code includedRecord}
 * means, touches exactly one sibling resolver and leaves this dispatcher and the other two untouched.
 *
 * <p><b>Degradation.</b> Neither failure is fatal to anything: an unresolvable id yields
 * {@link Optional#empty()} so the caller can report it against the parameter that named it, and an
 * unrecognised {@code mode} is skipped with a warning so a future mode degrades instead of breaking the
 * library. Nothing here throws.
 *
 * @since 1.7.0
 */
final class DataBindingResolver {

    private static final Logger LOGGER = Logger.getLogger(DataBindingResolver.class.getName());

    private DataBindingResolver() {
        // Prevent instantiation
    }

    /**
     * One binding mode a slot supports. Sealed so a new mode cannot be added without every consumer being
     * forced to handle it — the renderer switches over these, and a silently unhandled mode would drop a
     * whole binding capability from the prompt.
     */
    sealed interface Mode permits DirectModeResolver.Direct, IncludedRecordModeResolver.IncludedRecord,
            StreamableModeResolver.Streamable {
    }

    /**
     * A resolved {@code dataBindingRules[]} entry.
     *
     * @param arrayCardinality spec §9 {@code cardinality: "array"} — "the bound value is a batch; a mode's
     *                         type is the array <i>element</i> type, not the whole param type". Carried as
     *                         a flag rather than folded into the mode types precisely so the renderer
     *                         cannot pluralize a type that the parameter's own signature already pluralized
     * @param modes            the supported modes, in document order; never empty
     */
    record BindingSpec(boolean arrayCardinality, List<Mode> modes) {
    }

    /**
     * Resolves the rule a parameter's {@code dataBinding} id names.
     *
     * <p>{@code envelopeFields} is a plain lookup function rather than a facts object on purpose: it is the
     * only introspected input §9 needs (spec §9: "No {@code fixedFields} — always derivable as the
     * envelope's fields minus {@code bindableFields}"), and taking it as a function keeps this resolver and
     * {@link IncludedRecordModeResolver} unit-testable without a compiled package behind them.
     *
     * @param dataBindingId  the {@code params[].dataBinding} id; may be {@code null}
     * @param document       the whole document, which owns the {@code dataBindingRules[]} registry
     * @param packageName    the resolved package name, for rendering type references per spec §1
     * @param declaresType   whether the home module declares a type of a given name
     * @param envelopeFields the declared field names of a record, by bare type name
     * @return the resolved rule, or empty when the slot names none or names one that does not exist
     */
    static Optional<BindingSpec> resolve(String dataBindingId, TriggerMetadataModel document,
                                         String packageName, Predicate<String> declaresType,
                                         Function<String, List<String>> envelopeFields) {
        if (dataBindingId == null || dataBindingId.isBlank() || document == null) {
            return Optional.empty();
        }
        TriggerMetadataModel.DataBindingRule rule = ruleById(document, dataBindingId);
        if (rule == null) {
            return Optional.empty();
        }
        List<Mode> modes = new ArrayList<>();
        for (TriggerMetadataModel.DataBindingRule.SupportedMode supported : safe(rule.supportedModes())) {
            if (supported == null) {
                continue;
            }
            Mode mode = dispatch(supported, packageName, declaresType, envelopeFields);
            if (mode == null) {
                LOGGER.warning("Skipped unknown dataBindingRules[].supportedModes[].mode '" + supported.mode()
                        + "' in rule '" + dataBindingId + "' (spec §9 defines direct, includedRecord, "
                        + "streamable)");
                continue;
            }
            modes.add(mode);
        }
        if (modes.isEmpty()) {
            // A rule whose every mode was unusable states nothing a reader can act on.
            return Optional.empty();
        }
        return Optional.of(new BindingSpec(isArray(rule), modes));
    }

    /**
     * Whether the document declares a rule under this id — the question a caller asks to tell "the slot
     * names no rule" from "the slot names a rule that does not exist", which are different defects.
     *
     * @param document      the document; may be {@code null}
     * @param dataBindingId the id to look for; may be {@code null}
     * @return whether the registry holds an entry with this id
     */
    static boolean declaresRule(TriggerMetadataModel document, String dataBindingId) {
        return document != null && dataBindingId != null && ruleById(document, dataBindingId) != null;
    }

    private static TriggerMetadataModel.DataBindingRule ruleById(TriggerMetadataModel document, String id) {
        if (document.dataBindingRules() == null) {
            return null;
        }
        for (TriggerMetadataModel.DataBindingRule rule : document.dataBindingRules()) {
            if (rule != null && id.equals(rule.id())) {
                return rule;
            }
        }
        return null;
    }

    private static Mode dispatch(TriggerMetadataModel.DataBindingRule.SupportedMode supported,
                                 String packageName, Predicate<String> declaresType,
                                 Function<String, List<String>> envelopeFields) {
        String mode = supported.mode();
        if (TriggerMetadataModel.DataBindingRule.SupportedMode.MODE_DIRECT.equals(mode)) {
            return DirectModeResolver.resolve(supported, packageName, declaresType);
        }
        if (TriggerMetadataModel.DataBindingRule.SupportedMode.MODE_INCLUDED_RECORD.equals(mode)) {
            return IncludedRecordModeResolver.resolve(supported, packageName, declaresType, envelopeFields);
        }
        if (TriggerMetadataModel.DataBindingRule.SupportedMode.MODE_STREAMABLE.equals(mode)) {
            return StreamableModeResolver.resolve(supported, packageName, declaresType);
        }
        return null;
    }

    private static boolean isArray(TriggerMetadataModel.DataBindingRule rule) {
        return TriggerMetadataModel.DataBindingRule.CARDINALITY_ARRAY.equals(rule.cardinality());
    }

    private static List<TriggerMetadataModel.DataBindingRule.SupportedMode> safe(
            List<TriggerMetadataModel.DataBindingRule.SupportedMode> modes) {
        return modes == null ? List.of() : modes;
    }
}
