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

/**
 * A recorded reason an entry was dropped from the catalog.
 *
 * <p>Replaces the inline {@code continue} + {@code LOGGER.warning} pattern, under which a dropped
 * handler left no trace a caller could inspect and a <i>broken</i> metadata document was
 * indistinguishable from an <i>absent</i> one — both silently fell back to the SQLite service index.
 * A veto is attributable: it names the component that raised it, the spec section that component
 * owns, and the subject that was dropped, so "why did websub's {@code onHubError} disappear?" has an
 * answer.
 *
 * <p>Vetoes never reach the emitted JSON. They are diagnostics about the document, not content for the
 * prompt.
 *
 * @param aspectId    the component that raised it, e.g. {@code "handlerCatalog"}
 * @param specSection the spec section that component owns, e.g. {@code "§4"}
 * @param subject     what was dropped — a service-type or handler name
 * @param reason      why, in terms a document author can act on
 * @since 1.7.0
 */
record Veto(String aspectId, String specSection, String subject, String reason) {

    @Override
    public String toString() {
        return "[%s %s] %s: %s".formatted(specSection, aspectId, subject, reason);
    }
}
