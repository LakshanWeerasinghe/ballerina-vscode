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
 * The spec's <b>General rule</b>: "a field that would be empty, unused, or fully derivable from other
 * fields is left out — never included as an empty array / null / default placeholder."
 *
 * <p>Five corpus instances motivated this check: {@code kafka}'s {@code annotations: []} and the
 * {@code dataBindingRules: []} carried by {@code graphql}, {@code grpc}, {@code mcp} and {@code websub}.
 * None changes behaviour, which is exactly why they survived — an empty array and an absent key
 * deserialize alike, so nothing downstream could ever notice.
 *
 * <p><b>This check is deliberately stricter than the repo's JSON schema</b>, whose description says an
 * empty array "is also accepted". The spec's General rule is unconditional; the schema's leniency was a
 * convenience that let the five instances in. Where the two disagree, the spec wins and the schema text is
 * brought into line rather than the other way round.
 *
 * @since 1.10.0
 */
final class OmissionRuleCheck implements DocumentCheck {

    @Override
    public String id() {
        return "omissionRule";
    }

    @Override
    public String specSection() {
        return "§0";
    }

    @Override
    public List<Finding> check(TriggerMetadataModel document) {
        List<Finding> findings = new ArrayList<>();
        emptyArray(findings, document.annotations(), "annotations");
        emptyArray(findings, document.dataBindingRules(), "dataBindingRules");

        List<TriggerMetadataModel.ServiceType> serviceTypes = DocumentWalk.safe(document.serviceTypes());
        for (int i = 0; i < serviceTypes.size(); i++) {
            TriggerMetadataModel.ServiceType serviceType = serviceTypes.get(i);
            if (serviceType == null) {
                continue;
            }
            String path = DocumentWalk.serviceTypePath(i);
            emptyArray(findings, serviceType.rules(), path + ".rules");
            List<TriggerMetadataModel.ServiceType.HandlerOption> options = DocumentWalk.options(serviceType);
            for (int j = 0; j < options.size(); j++) {
                TriggerMetadataModel.ServiceType.HandlerOption option = options.get(j);
                if (option == null) {
                    continue;
                }
                String optionPath = DocumentWalk.optionPath(i, j);
                emptyArray(findings, option.params(), optionPath + ".params");
                emptyArray(findings, option.annotations(), optionPath + ".annotations");
                emptyArray(findings, option.returns(), optionPath + ".returns");
            }
        }
        return findings;
    }

    private void emptyArray(List<Finding> findings, List<?> value, String path) {
        // Only a *present but empty* array is a defect. An absent one is the rule being obeyed.
        if (value != null && value.isEmpty()) {
            findings.add(Finding.error(this, path,
                    "empty array: the spec's general rule says to omit the key entirely"));
        }
    }
}
