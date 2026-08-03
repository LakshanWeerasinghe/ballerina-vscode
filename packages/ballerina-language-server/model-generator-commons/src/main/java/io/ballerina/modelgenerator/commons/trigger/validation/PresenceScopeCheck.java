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
 * <b>Spec §5 {@code options[].presence} scoping</b>: "Only under {@code addMode: subset}".
 *
 * <p>Not folded into {@link VocabularyCheck}, because this is not a membership question. The value may be
 * perfectly legal and still be meaningless where it sits: under {@code addMode: "many"} the catalog is
 * open-ended and user-named, so "is this particular handler required" is not a question the document is
 * answering, and the consuming resolver deliberately emits nothing. A {@code presence} written there is
 * silently discarded.
 *
 * <p>The converse — a {@code subset} option that states no {@code presence} — is the more damaging half
 * and is reported too: with three states on the wire ({@code required} / {@code optional} / "not saying"),
 * an omission collapses a real obligation into silence. {@code grpc}'s four options are exactly this.
 *
 * @since 1.10.0
 */
final class PresenceScopeCheck implements DocumentCheck {

    private static final String SUBSET = TriggerMetadataModel.ServiceType.Handlers.ADD_MODE_SUBSET;
    private static final String WILDCARD = TriggerMetadataModel.ServiceType.HandlerOption.WILDCARD_NAME;

    @Override
    public String id() {
        return "presenceScope";
    }

    @Override
    public String specSection() {
        return "§5";
    }

    @Override
    public List<Finding> check(TriggerMetadataModel document) {
        List<Finding> findings = new ArrayList<>();
        List<TriggerMetadataModel.ServiceType> serviceTypes = DocumentWalk.safe(document.serviceTypes());
        for (int i = 0; i < serviceTypes.size(); i++) {
            TriggerMetadataModel.ServiceType serviceType = serviceTypes.get(i);
            if (serviceType == null || serviceType.handlers() == null) {
                continue;
            }
            boolean subset = SUBSET.equals(serviceType.handlers().addMode());
            List<TriggerMetadataModel.ServiceType.HandlerOption> options = DocumentWalk.options(serviceType);
            for (int j = 0; j < options.size(); j++) {
                TriggerMetadataModel.ServiceType.HandlerOption option = options.get(j);
                if (option == null || WILDCARD.equals(option.name())) {
                    continue;
                }
                String path = DocumentWalk.optionPath(i, j) + ".presence";
                if (subset && option.presence() == null) {
                    findings.add(Finding.warn(this, path,
                            "a `subset` option that states no presence is read as 'the document is not "
                                    + "saying', so a mandatory handler becomes indistinguishable from a "
                                    + "skippable one"));
                } else if (!subset && option.presence() != null) {
                    findings.add(Finding.warn(this, path,
                            "spec §5 scopes presence to `addMode: subset`; here it is discarded"));
                }
            }
        }
        return findings;
    }
}
