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
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * <b>Spec §8 registry integrity</b>: every {@code annotations[]} id is unique, and every reference to one
 * resolves.
 *
 * <p>This is the class of defect a JSON schema cannot catch, which is why the validator tier exists at
 * all: {@code "annotations": ["payload"]} is a perfectly well-formed array of strings whether or not any
 * registry entry declares {@code payload}. A dangling reference is silently dropped by the pipeline (with
 * a non-fatal diagnostic), so the obligation it was meant to state simply never reaches the prompt.
 *
 * @since 1.10.0
 */
final class AnnotationRefCheck implements DocumentCheck {

    @Override
    public String id() {
        return "annotationRef";
    }

    @Override
    public String specSection() {
        return "§8";
    }

    @Override
    public List<Finding> check(TriggerMetadataModel document) {
        List<Finding> findings = new ArrayList<>();
        Set<String> declared = new LinkedHashSet<>();
        Set<String> duplicates = new LinkedHashSet<>();

        for (TriggerMetadataModel.Annotation annotation : DocumentWalk.safe(document.annotations())) {
            if (annotation == null) {
                continue;
            }
            if (annotation.id() == null || annotation.id().isBlank()) {
                findings.add(Finding.error(this, "annotations[]",
                        "an entry declares no `id`, so nothing can reference it"));
                continue;
            }
            if (!declared.add(annotation.id()) && duplicates.add(annotation.id())) {
                findings.add(Finding.error(this, "annotations[" + annotation.id() + "]",
                        "duplicate id: a reference to it is ambiguous"));
            }
        }

        Set<String> serviceTypeIds = new HashSet<>();
        List<TriggerMetadataModel.ServiceType> serviceTypes = DocumentWalk.safe(document.serviceTypes());
        for (TriggerMetadataModel.ServiceType serviceType : serviceTypes) {
            if (serviceType != null && serviceType.id() != null) {
                serviceTypeIds.add(serviceType.id());
            }
        }
        for (TriggerMetadataModel.Annotation annotation : DocumentWalk.safe(document.annotations())) {
            if (annotation == null) {
                continue;
            }
            for (String applied : DocumentWalk.safe(annotation.appliesTo())) {
                if (!serviceTypeIds.contains(applied)) {
                    findings.add(Finding.error(this, "annotations[" + annotation.id() + "].appliesTo",
                            "names service type '" + applied + "', which no serviceTypes[] entry declares"));
                }
            }
        }

        for (int i = 0; i < serviceTypes.size(); i++) {
            TriggerMetadataModel.ServiceType serviceType = serviceTypes.get(i);
            if (serviceType == null) {
                continue;
            }
            for (TriggerMetadataModel.ServiceType.Rule rule : DocumentWalk.safe(serviceType.rules())) {
                for (TriggerMetadataModel.ServiceType.Rule.RuleMember member
                        : DocumentWalk.safe(rule == null ? null : rule.members())) {
                    if (member != null && member.annotation() != null
                            && !declared.contains(member.annotation())) {
                        findings.add(Finding.error(this,
                                DocumentWalk.serviceTypePath(i) + ".rules[" + rule.id() + "].members",
                                "references annotation id '" + member.annotation()
                                        + "', which no annotations[] entry declares"));
                    }
                }
            }
            List<TriggerMetadataModel.ServiceType.HandlerOption> options = DocumentWalk.options(serviceType);
            for (int j = 0; j < options.size(); j++) {
                TriggerMetadataModel.ServiceType.HandlerOption option = options.get(j);
                if (option == null) {
                    continue;
                }
                unresolved(findings, option.annotations(), declared, DocumentWalk.optionPath(i, j));
                List<TriggerMetadataModel.ServiceType.Param> params = DocumentWalk.safe(option.params());
                for (int k = 0; k < params.size(); k++) {
                    if (params.get(k) != null) {
                        unresolved(findings, params.get(k).annotations(), declared,
                                DocumentWalk.paramPath(i, j, k));
                    }
                }
            }
        }
        return findings;
    }

    private void unresolved(List<Finding> findings, List<String> ids, Set<String> declared, String path) {
        for (String id : DocumentWalk.safe(ids)) {
            if (!declared.contains(id)) {
                findings.add(Finding.error(this, path + ".annotations",
                        "references annotation id '" + id
                                + "', which no annotations[] entry declares"));
            }
        }
    }
}
