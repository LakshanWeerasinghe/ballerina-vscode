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

package io.ballerina.modelgenerator.commons.trigger;

import io.ballerina.modelgenerator.commons.trigger.models.TriggerMetadataModel;

/**
 * Owns the spec's <b>top-level {@code version} key</b>: whether a {@code trigger-metadata.json} document
 * may be read at all.
 *
 * <p>The spec makes {@code version} required and says to "bump it whenever a field's meaning changes
 * incompatibly with what's described here". That is precisely a compatibility gate: a document declaring a
 * version this build does not implement cannot be read safely, because the same keys may mean different
 * things.
 *
 * <h2>Why an absent version is accepted</h2>
 *
 * <p>Every document in the corpus predates the key. Rejecting an absent version would therefore disable
 * <b>every</b> trigger library at once — turning a forward-compatibility guard into an outage. An absent
 * version is read as {@link VersionVerdict#ACCEPT_WITH_WARNING}: the document is served as v1, and the
 * warning is what makes the omission visible rather than silent. The corresponding validator check reports
 * the same omission as an ERROR against the corpus, so the two are not in tension — the gate keeps
 * <i>runtime</i> permissive while the validator keeps <i>the repo's own documents</i> strict.
 *
 * <h2>Why an unknown version is rejected rather than tolerated</h2>
 *
 * <p>Rejection is not a failure: the caller degrades to the SQLite service index, which serves a usable
 * (if poorer) catalog. Reading a v2 document with v1 semantics is the genuinely dangerous outcome, because
 * it produces confident, wrong API guidance rather than an obvious absence.
 *
 * @since 1.10.0
 */
public final class SpecVersionGate {

    /** The version this build implements. */
    public static final String VERSION_V1 = "v1";

    private SpecVersionGate() {
        // Prevent instantiation
    }

    /** What a caller must do with a document, given the version it declares. */
    public enum VersionVerdict {
        /** The document declares a version this build implements; read it. */
        ACCEPT,
        /** The document declares no version; read it as v1 and say so. */
        ACCEPT_WITH_WARNING,
        /** The document declares a version this build does not implement; do not read it. */
        REJECT;

        /**
         * Whether the document may be read.
         *
         * @return whether a caller may use the document
         */
        public boolean isUsable() {
            return this != REJECT;
        }
    }

    /**
     * Evaluates a declared version.
     *
     * <p>A blank version is treated as absent rather than as an unknown value. It states nothing, so it
     * cannot be a version this build fails to implement, and the permissive reading is the one that cannot
     * take a working library offline over a formatting slip.
     *
     * @param documentVersion the document's declared {@code version}; may be {@code null}
     * @return the verdict
     */
    public static VersionVerdict evaluate(String documentVersion) {
        if (documentVersion == null || documentVersion.isBlank()) {
            return VersionVerdict.ACCEPT_WITH_WARNING;
        }
        return VERSION_V1.equals(documentVersion.trim()) ? VersionVerdict.ACCEPT : VersionVerdict.REJECT;
    }

    /**
     * {@link #evaluate(String)} for a parsed document. A {@code null} document has nothing to gate and is
     * reported as acceptable; the caller's own empty-check is what handles it.
     *
     * @param document the parsed document; may be {@code null}
     * @return the verdict
     */
    public static VersionVerdict evaluate(TriggerMetadataModel document) {
        return document == null ? VersionVerdict.ACCEPT_WITH_WARNING : evaluate(document.version());
    }
}
