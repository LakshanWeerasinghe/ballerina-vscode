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

/**
 * One thing a {@link DocumentCheck} found wrong with a {@code trigger-metadata.json} document.
 *
 * <p>Deliberately shaped like a compiler diagnostic rather than a boolean: a validator that only answers
 * "valid / invalid" tells a document author nothing, and the whole point of this tier is to turn defects
 * that were previously silent — a handler dropped for naming an undeclared type, an annotation reference
 * that resolves to nothing — into something a person can act on.
 *
 * @param severity    whether this breaks the build or is merely reported
 * @param checkId     the check that raised it, e.g. {@code "bindingMode"}
 * @param specSection the spec section that check owns, e.g. {@code "§9"}
 * @param path        where in the document, in JSON-pointer-ish form
 *                    ({@code serviceTypes[0].handlers.options[2].params[1]})
 * @param message     what is wrong, in terms a document author can act on
 * @since 1.10.0
 */
public record Finding(Severity severity, String checkId, String specSection, String path, String message) {

    /** How seriously a caller should take a {@link Finding}. */
    public enum Severity {
        /**
         * The document contradicts the spec or refers to something that does not exist. The corpus test
         * fails the build on these.
         */
        ERROR,
        /**
         * The document is legal but says something a consumer cannot use, or omits something optional but
         * expected. Reported, never fatal.
         */
        WARN
    }

    /**
     * An {@link Severity#ERROR} finding.
     *
     * @param check   the check raising it
     * @param path    where in the document
     * @param message what is wrong
     * @return the finding
     */
    public static Finding error(DocumentCheck check, String path, String message) {
        return new Finding(Severity.ERROR, check.id(), check.specSection(), path, message);
    }

    /**
     * A {@link Severity#WARN} finding.
     *
     * @param check   the check raising it
     * @param path    where in the document
     * @param message what is wrong
     * @return the finding
     */
    public static Finding warn(DocumentCheck check, String path, String message) {
        return new Finding(Severity.WARN, check.id(), check.specSection(), path, message);
    }

    @Override
    public String toString() {
        return severity + " [" + checkId + " " + specSection + "] " + path + ": " + message;
    }
}
