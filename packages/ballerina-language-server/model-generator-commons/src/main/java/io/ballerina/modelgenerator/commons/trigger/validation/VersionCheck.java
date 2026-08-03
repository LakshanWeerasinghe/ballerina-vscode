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

import io.ballerina.modelgenerator.commons.trigger.SpecVersionGate;
import io.ballerina.modelgenerator.commons.trigger.models.TriggerMetadataModel;

import java.util.List;

/**
 * The spec's top-level {@code version}: "a required top-level string (currently {@code "v1"})".
 *
 * <p><b>ERROR where {@link SpecVersionGate} only warns, and the asymmetry is the point.</b> The gate runs
 * at load time against documents this repo does not own — a connector may ship its own — so it must keep a
 * working library working. This check runs over the repo's own corpus in a test, where an omission is a
 * defect somebody can simply fix. Making both permissive would leave the key optional forever; making both
 * strict would take every trigger library offline the moment one document lagged.
 *
 * @since 1.10.0
 */
final class VersionCheck implements DocumentCheck {

    @Override
    public String id() {
        return "version";
    }

    @Override
    public String specSection() {
        return "§0";
    }

    @Override
    public List<Finding> check(TriggerMetadataModel document) {
        String version = document.version();
        if (version == null || version.isBlank()) {
            return List.of(Finding.error(this, "version",
                    "the spec makes `version` a required top-level string; add \"version\": \""
                            + SpecVersionGate.VERSION_V1 + "\""));
        }
        if (SpecVersionGate.evaluate(version) == SpecVersionGate.VersionVerdict.REJECT) {
            return List.of(Finding.error(this, "version",
                    "declares version '" + version + "', which this build does not implement (expected '"
                            + SpecVersionGate.VERSION_V1 + "')"));
        }
        return List.of();
    }
}
