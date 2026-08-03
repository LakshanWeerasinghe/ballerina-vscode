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
import io.ballerina.modelgenerator.commons.trigger.models.TypeRef;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>Spec §1 {@code TypeRef}</b>: every type reference names a type, and a cross-module one carries
 * complete coordinates.
 *
 * <p>Incomplete {@code packageInfo} is the interesting case. Spec §1 shows all four keys populated, and
 * the resolver derives both the import path and the alias from {@code moduleName}/{@code org}; a reference
 * missing either is silently treated as <b>same-module</b>, which renders a foreign type with the
 * connector's own prefix — a name that does not resolve, reported by nothing.
 *
 * @since 1.10.0
 */
final class TypeRefCheck implements DocumentCheck {

    @Override
    public String id() {
        return "typeRef";
    }

    @Override
    public String specSection() {
        return "§1";
    }

    @Override
    public List<Finding> check(TriggerMetadataModel document) {
        List<Finding> findings = new ArrayList<>();

        List<TriggerMetadataModel.Listener> listeners = DocumentWalk.safe(document.listeners());
        for (int i = 0; i < listeners.size(); i++) {
            if (listeners.get(i) != null) {
                typeRef(findings, listeners.get(i).type(), "listeners[" + i + "].type");
            }
        }

        List<TriggerMetadataModel.ServiceType> serviceTypes = DocumentWalk.safe(document.serviceTypes());
        for (int i = 0; i < serviceTypes.size(); i++) {
            TriggerMetadataModel.ServiceType serviceType = serviceTypes.get(i);
            if (serviceType == null) {
                continue;
            }
            typeRef(findings, serviceType.type(), DocumentWalk.serviceTypePath(i) + ".type");
            List<TriggerMetadataModel.ServiceType.HandlerOption> options = DocumentWalk.options(serviceType);
            for (int j = 0; j < options.size(); j++) {
                TriggerMetadataModel.ServiceType.HandlerOption option = options.get(j);
                if (option == null) {
                    continue;
                }
                String optionPath = DocumentWalk.optionPath(i, j);
                for (TypeRef ref : DocumentWalk.safe(option.returns())) {
                    typeRef(findings, ref, optionPath + ".returns");
                }
                List<TriggerMetadataModel.ServiceType.Param> params = DocumentWalk.safe(option.params());
                for (int k = 0; k < params.size(); k++) {
                    if (params.get(k) == null) {
                        continue;
                    }
                    String paramPath = DocumentWalk.paramPath(i, j, k);
                    List<TypeRef> types = DocumentWalk.safe(params.get(k).type());
                    if (types.isEmpty()) {
                        findings.add(Finding.error(this, paramPath + ".type",
                                "required: a parameter slot must state its legal type(s)"));
                    }
                    for (TypeRef ref : types) {
                        typeRef(findings, ref, paramPath + ".type");
                    }
                }
            }
        }

        for (TriggerMetadataModel.Annotation annotation : DocumentWalk.safe(document.annotations())) {
            if (annotation != null) {
                typeRef(findings, annotation.type(), "annotations[" + annotation.id() + "].type");
            }
        }
        for (TriggerMetadataModel.DataBindingRule rule : DocumentWalk.safe(document.dataBindingRules())) {
            if (rule == null) {
                continue;
            }
            String path = "dataBindingRules[" + rule.id() + "]";
            if (rule.envelopeType() != null) {
                typeRef(findings, rule.envelopeType(), path + ".envelopeType");
            }
            for (TriggerMetadataModel.DataBindingRule.SupportedMode mode
                    : DocumentWalk.safe(rule.supportedModes())) {
                if (mode == null) {
                    continue;
                }
                String modePath = path + ".supportedModes[" + mode.mode() + "]";
                for (TypeRef ref : DocumentWalk.safe(mode.typeConstraint())) {
                    typeRef(findings, ref, modePath + ".typeConstraint");
                }
                for (TypeRef ref : DocumentWalk.safe(mode.excludes())) {
                    typeRef(findings, ref, modePath + ".excludes");
                }
                if (mode.includes() != null) {
                    typeRef(findings, mode.includes(), modePath + ".includes");
                }
            }
        }
        return findings;
    }

    private void typeRef(List<Finding> findings, TypeRef ref, String path) {
        if (ref == null) {
            findings.add(Finding.error(this, path, "missing type reference"));
            return;
        }
        if (ref.name() == null || ref.name().isBlank()) {
            findings.add(Finding.error(this, path, "a TypeRef must carry a `name`"));
        }
        TypeRef.PackageInfo info = ref.packageInfo();
        if (info == null) {
            return;
        }
        if (info.org() == null || info.org().isBlank()) {
            findings.add(Finding.error(this, path + ".packageInfo.org",
                    "required: without it the reference is read as same-module and rendered with the "
                            + "connector's own prefix"));
        }
        if ((info.moduleName() == null || info.moduleName().isBlank())
                && (info.packageName() == null || info.packageName().isBlank())) {
            findings.add(Finding.error(this, path + ".packageInfo",
                    "needs `moduleName` (or at least `packageName`) to derive the import alias"));
        }
    }
}
