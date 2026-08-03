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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * <b>Spec §6 {@code rules[]} integrity</b>: member shapes, member count, and the handler names a rule
 * refers to.
 *
 * <p>Spec §6 states three things this enforces: a rule's members are "mutually-exclusive alternatives"
 * (so fewer than two states no exclusivity), "exactly one of the three shapes is populated per member",
 * and a {@code handler} member names "one of this service type's own {@code handlers.options[].name}
 * values".
 *
 * <p>The handler cross-check is the one that matters in practice. A rule naming a handler the service type
 * does not declare silently loses its member — the consuming resolver drops it and, if too few survive,
 * drops the whole rule — so an exclusivity the connector really enforces would reach the prompt as
 * nothing, and a model would happily emit both alternatives.
 *
 * @since 1.10.0
 */
final class RuleRefCheck implements DocumentCheck {

    @Override
    public String id() {
        return "ruleRef";
    }

    @Override
    public String specSection() {
        return "§6";
    }

    @Override
    public List<Finding> check(TriggerMetadataModel document) {
        List<Finding> findings = new ArrayList<>();
        List<TriggerMetadataModel.ServiceType> serviceTypes = DocumentWalk.safe(document.serviceTypes());
        for (int i = 0; i < serviceTypes.size(); i++) {
            TriggerMetadataModel.ServiceType serviceType = serviceTypes.get(i);
            if (serviceType == null) {
                continue;
            }
            Set<String> handlerNames = new LinkedHashSet<>();
            for (TriggerMetadataModel.ServiceType.HandlerOption option : DocumentWalk.options(serviceType)) {
                if (option != null && option.name() != null) {
                    handlerNames.add(option.name());
                }
            }
            for (TriggerMetadataModel.ServiceType.Rule rule : DocumentWalk.safe(serviceType.rules())) {
                if (rule == null) {
                    continue;
                }
                checkRule(findings, rule, handlerNames,
                        DocumentWalk.serviceTypePath(i) + ".rules[" + rule.id() + "]");
            }
        }
        return findings;
    }

    private void checkRule(List<Finding> findings, TriggerMetadataModel.ServiceType.Rule rule,
                           Set<String> handlerNames, String path) {
        List<TriggerMetadataModel.ServiceType.Rule.RuleMember> members = DocumentWalk.safe(rule.members());
        if (members.size() < 2) {
            findings.add(Finding.error(this, path + ".members",
                    "a rule needs at least two alternatives to express exclusivity, found " + members.size()));
        }
        for (TriggerMetadataModel.ServiceType.Rule.RuleMember member : members) {
            if (member == null) {
                findings.add(Finding.error(this, path + ".members", "a null member states nothing"));
                continue;
            }
            int populated = 0;
            if (member.annotation() != null || member.field() != null) {
                populated++;
                if (member.annotation() == null || member.field() == null) {
                    findings.add(Finding.error(this, path + ".members",
                            "the annotation-field shape needs both `annotation` and `field`"));
                }
            }
            if (member.part() != null) {
                populated++;
                if (!TriggerMetadataModel.ServiceType.Rule.RuleMember.PART_IDENTIFIER.equals(member.part())) {
                    findings.add(Finding.error(this, path + ".members",
                            "`part` is '" + member.part() + "'; spec §6 defines only 'identifier'"));
                }
            }
            if (member.handler() != null) {
                populated++;
                if (!handlerNames.isEmpty() && !handlerNames.contains(member.handler())) {
                    findings.add(Finding.error(this, path + ".members",
                            "names handler '" + member.handler()
                                    + "', which this service type does not declare"));
                }
            }
            if (populated != 1) {
                findings.add(Finding.error(this, path + ".members",
                        "spec §6: exactly one of the three member shapes must be populated, found "
                                + populated));
            }
        }
    }
}
