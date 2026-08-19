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

import java.util.List;

/**
 * One invariant a {@code trigger-metadata.json} document must satisfy.
 *
 * <p><b>Per invariant, not per component.</b> The Java pipeline that consumes these documents has one
 * resolver per spec construct; a check instead covers one thing that can be <i>wrong</i>, which sometimes
 * spans constructs ({@link ResourceExtrasCheck} covers both protocol resolvers' keys) and sometimes is
 * narrower than one. The traceability guarantee is therefore "at least one check per spec section", not a
 * one-to-one map onto resolvers.
 *
 * <p>A check is a <b>pure function of the document</b>: no compiled package, no I/O, no ordering
 * dependence on another check. That is what lets the whole tier run over the corpus in a unit test and
 * over a shipped document at load time, with the same code.
 *
 * @since 1.10.0
 */
public interface DocumentCheck {

    /**
     * A stable identifier, used to attribute a finding and to assert the check is registered.
     *
     * @return the id, e.g. {@code "bindingMode"}
     */
    String id();

    /**
     * The spec section this check enforces.
     *
     * @return the section, e.g. {@code "§9"}
     */
    String specSection();

    /**
     * Runs the check.
     *
     * @param document the parsed document; never {@code null}
     * @return every finding, in document order; empty when the invariant holds
     */
    List<Finding> check(TriggerMetadataModel document);
}
