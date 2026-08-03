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
 * Runs every {@link DocumentCheck} over a {@code trigger-metadata.json} document.
 *
 * <p><b>Why this tier exists.</b> The consuming pipeline is deliberately forgiving: an unresolvable
 * annotation id is dropped with a non-fatal diagnostic, an unknown binding mode is skipped with a log
 * line, a handler naming a type the package does not declare is vetoed and the rest of the service is
 * served. Every one of those is the right behaviour at request time — a partly-broken document should
 * still yield a usable catalog — and every one of them turns a document defect into silence. This tier is
 * where that silence is broken, at the only moment it can be acted on: in a test, over the corpus this
 * repo owns.
 *
 * <p><b>What it deliberately does not do.</b> It does not validate against
 * {@code resources/schemas/trigger-metadata.schema.json} literally: no JSON-schema implementation is on
 * this build's classpath, and adding one would break every {@code --offline} build that has not already
 * cached it. That is a real gap and is recorded as one — but it is the smaller half. A JSON schema can
 * check shapes and enumerations; it cannot check that {@code params[].dataBinding} names a rule that
 * exists, that a {@code rules[].members[].handler} names a handler the service type declares, or that an
 * annotation is referenced from the slot its {@code attachPoint} allows. Those are the checks here, and
 * they are the ones that were silently failing.
 *
 * @since 1.10.0
 */
public final class TriggerMetadataValidator {

    /**
     * The registered checks. Order is presentational only — checks are pure and independent, so a finding
     * from one can never depend on another having run.
     */
    private static final List<DocumentCheck> CHECKS = List.of(
            new VersionCheck(),
            new TypeRefCheck(),
            new ListenerRefCheck(),
            new ImportTypeCheck(),
            new ServiceTypeRefCheck(),
            new AddModeCheck(),
            new PresenceScopeCheck(),
            new ResourceExtrasCheck(),
            new RuleRefCheck(),
            new AnnotationRefCheck(),
            new AnnotationScopeCheck(),
            new DataBindingRefCheck(),
            new BindingModeCheck(),
            new VocabularyCheck(),
            new OmissionRuleCheck());

    private TriggerMetadataValidator() {
        // Prevent instantiation
    }

    /**
     * The registered checks, for a traceability test that asserts every spec section has an owner.
     *
     * @return the checks, in registration order
     */
    public static List<DocumentCheck> checks() {
        return CHECKS;
    }

    /**
     * Validates a document.
     *
     * <p>A check that throws is reported as a finding rather than propagated: this runs at load time as
     * well as in tests, and a defect in a check must not be able to take a library offline.
     *
     * @param document the parsed document; {@code null} yields no findings
     * @return every finding, grouped by check in registration order
     */
    public static List<Finding> validate(TriggerMetadataModel document) {
        List<Finding> findings = new ArrayList<>();
        if (document == null) {
            return findings;
        }
        for (DocumentCheck check : CHECKS) {
            try {
                findings.addAll(check.check(document));
            } catch (RuntimeException e) {
                findings.add(new Finding(Finding.Severity.WARN, check.id(), check.specSection(), "-",
                        "check failed to run: " + e));
            }
        }
        return findings;
    }

    /**
     * The findings of a given severity.
     *
     * @param document the parsed document; may be {@code null}
     * @param severity the severity to keep
     * @return the matching findings
     */
    public static List<Finding> validate(TriggerMetadataModel document, Finding.Severity severity) {
        return validate(document).stream().filter(finding -> finding.severity() == severity).toList();
    }
}
