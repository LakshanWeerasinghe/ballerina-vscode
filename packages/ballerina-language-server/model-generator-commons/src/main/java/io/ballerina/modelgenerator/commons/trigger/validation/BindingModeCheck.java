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

package io.ballerina.modelgenerator.commons.trigger.validation;

import io.ballerina.modelgenerator.commons.trigger.models.TriggerMetadataModel;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>Spec §9 mode shapes</b>: which fields each {@code supportedModes[]} entry may populate, and the
 * rule-level {@code envelopeType} condition.
 *
 * <p>Spec §9's mode table assigns fields per mode — {@code direct} takes {@code typeConstraint} and
 * optionally {@code excludes}, {@code includedRecord} takes {@code includes} and {@code bindableFields},
 * {@code streamable} takes {@code typeConstraint} — and states two prohibitions outright: "No rule-level
 * {@code envelopeType} unless the rule has an {@code includedRecord} mode (otherwise it falsely implies a
 * partial-envelope relationship that isn't there)" and "No {@code fixedFields} — always derivable".
 *
 * <p>The first prohibition has a live corpus instance: {@code mssql.cdc}'s {@code rowState} declares
 * {@code envelopeType: {"name": "record {}"}} with only a {@code direct} mode. It reaches the prompt as
 * nothing at all — the pipeline reads {@code envelopeType} nowhere — so the claim is invisible rather than
 * wrong, which is precisely why only a validator can find it.
 *
 * @since 1.10.0
 */
final class BindingModeCheck implements DocumentCheck {

    @Override
    public String id() {
        return "bindingMode";
    }

    @Override
    public String specSection() {
        return "§9";
    }

    @Override
    public List<Finding> check(TriggerMetadataModel document) {
        List<Finding> findings = new ArrayList<>();
        for (TriggerMetadataModel.DataBindingRule rule : DocumentWalk.safe(document.dataBindingRules())) {
            if (rule == null) {
                continue;
            }
            String path = "dataBindingRules[" + rule.id() + "]";
            List<TriggerMetadataModel.DataBindingRule.SupportedMode> modes =
                    DocumentWalk.safe(rule.supportedModes());
            if (modes.isEmpty()) {
                findings.add(Finding.error(this, path + ".supportedModes",
                        "a rule with no modes describes no binding at all"));
            }

            boolean hasIncludedRecord = false;
            for (TriggerMetadataModel.DataBindingRule.SupportedMode mode : modes) {
                if (mode == null) {
                    continue;
                }
                hasIncludedRecord |= TriggerMetadataModel.DataBindingRule.SupportedMode.MODE_INCLUDED_RECORD
                        .equals(mode.mode());
                checkMode(findings, mode, path);
            }

            if (rule.envelopeType() != null && !hasIncludedRecord) {
                findings.add(Finding.error(this, path + ".envelopeType",
                        "declared without an `includedRecord` mode; spec §9 forbids it because it implies "
                                + "a partial-envelope relationship the rule does not have"));
            }
        }
        return findings;
    }

    private void checkMode(List<Finding> findings, TriggerMetadataModel.DataBindingRule.SupportedMode mode,
                           String rulePath) {
        String path = rulePath + ".supportedModes[" + mode.mode() + "]";
        boolean included = TriggerMetadataModel.DataBindingRule.SupportedMode.MODE_INCLUDED_RECORD
                .equals(mode.mode());
        boolean direct = TriggerMetadataModel.DataBindingRule.SupportedMode.MODE_DIRECT.equals(mode.mode());
        boolean streamable = TriggerMetadataModel.DataBindingRule.SupportedMode.MODE_STREAMABLE
                .equals(mode.mode());

        if (included) {
            if (mode.includes() == null) {
                findings.add(Finding.error(this, path,
                        "`includedRecord` states no `includes`, so there is no envelope to include"));
            }
            if (mode.typeConstraint() != null) {
                findings.add(Finding.error(this, path,
                        "`typeConstraint` belongs to `direct`/`streamable`, not `includedRecord`"));
            }
        } else if (direct || streamable) {
            if (mode.typeConstraint() == null || mode.typeConstraint().isEmpty()) {
                findings.add(Finding.error(this, path,
                        "`" + mode.mode() + "` states no `typeConstraint`, so it names no legal target"));
            }
            if (mode.includes() != null || mode.bindableFields() != null) {
                findings.add(Finding.error(this, path,
                        "`includes`/`bindableFields` belong to `includedRecord`, not `" + mode.mode() + "`"));
            }
        }
        if (streamable && mode.excludes() != null) {
            findings.add(Finding.error(this, path, "spec §9 scopes `excludes` to `direct`"));
        }
    }
}
