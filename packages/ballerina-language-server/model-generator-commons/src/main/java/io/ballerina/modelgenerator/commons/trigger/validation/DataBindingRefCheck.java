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
 * <b>Spec §9 registry integrity</b>: every {@code dataBindingRules[]} id is unique, every
 * {@code params[].dataBinding} resolves to one, and every declared rule is used.
 *
 * <p>An unused rule is a WARN rather than an ERROR: it states something true about the connector that
 * simply nothing references yet, which is a tidiness issue rather than a contradiction. A dangling
 * reference is the reverse — the parameter claims a binding capability the document never describes, and
 * the pipeline drops it with a diagnostic nobody reads.
 *
 * @since 1.10.0
 */
final class DataBindingRefCheck implements DocumentCheck {

    @Override
    public String id() {
        return "dataBindingRef";
    }

    @Override
    public String specSection() {
        return "§9";
    }

    @Override
    public List<Finding> check(TriggerMetadataModel document) {
        List<Finding> findings = new ArrayList<>();
        Set<String> declared = new LinkedHashSet<>();
        Set<String> duplicates = new LinkedHashSet<>();
        for (TriggerMetadataModel.DataBindingRule rule : DocumentWalk.safe(document.dataBindingRules())) {
            if (rule == null) {
                continue;
            }
            if (rule.id() == null || rule.id().isBlank()) {
                findings.add(Finding.error(this, "dataBindingRules[]",
                        "an entry declares no `id`, so no parameter can reference it"));
                continue;
            }
            if (!declared.add(rule.id()) && duplicates.add(rule.id())) {
                findings.add(Finding.error(this, "dataBindingRules[" + rule.id() + "]",
                        "duplicate id: a reference to it is ambiguous"));
            }
        }

        Set<String> referenced = new LinkedHashSet<>();
        List<TriggerMetadataModel.ServiceType> serviceTypes = DocumentWalk.safe(document.serviceTypes());
        for (int i = 0; i < serviceTypes.size(); i++) {
            List<TriggerMetadataModel.ServiceType.HandlerOption> options =
                    DocumentWalk.options(serviceTypes.get(i));
            for (int j = 0; j < options.size(); j++) {
                TriggerMetadataModel.ServiceType.HandlerOption option = options.get(j);
                if (option == null) {
                    continue;
                }
                List<TriggerMetadataModel.ServiceType.Param> params = DocumentWalk.safe(option.params());
                for (int k = 0; k < params.size(); k++) {
                    TriggerMetadataModel.ServiceType.Param param = params.get(k);
                    if (param == null || param.dataBinding() == null) {
                        continue;
                    }
                    referenced.add(param.dataBinding());
                    if (!declared.contains(param.dataBinding())) {
                        findings.add(Finding.error(this, DocumentWalk.paramPath(i, j, k) + ".dataBinding",
                                "references '" + param.dataBinding()
                                        + "', which no dataBindingRules[] entry declares"));
                    }
                }
            }
        }

        for (String id : declared) {
            if (!referenced.contains(id)) {
                findings.add(Finding.warn(this, "dataBindingRules[" + id + "]",
                        "declared but never referenced by any params[].dataBinding"));
            }
        }
        return findings;
    }
}
