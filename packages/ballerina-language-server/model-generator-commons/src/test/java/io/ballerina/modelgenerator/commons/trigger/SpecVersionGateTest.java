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
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Conformance tests for the spec's top-level {@code version} key, written against the spec text.
 *
 * @since 1.10.0
 */
public class SpecVersionGateTest {

    @Test
    public void testTheImplementedVersionIsAccepted() {
        // Spec: "`version` is a required top-level string (currently "v1")."
        Assert.assertEquals(SpecVersionGate.evaluate("v1"), SpecVersionGate.VersionVerdict.ACCEPT);
    }

    @Test
    public void testAnAbsentVersionIsAcceptedWithAWarningRatherThanRejected() {
        // Every document predates the key. Rejecting would disable every trigger library at once, turning
        // a forward-compatibility guard into an outage — so the runtime gate stays permissive and the
        // corpus test is what keeps this repo's own documents honest.
        Assert.assertEquals(SpecVersionGate.evaluate((String) null),
                SpecVersionGate.VersionVerdict.ACCEPT_WITH_WARNING);
        Assert.assertTrue(SpecVersionGate.evaluate((String) null).isUsable());
    }

    @Test
    public void testABlankVersionIsReadAsAbsentRatherThanUnknown() {
        // A blank string states nothing, so it cannot be a version this build fails to implement. The
        // permissive reading is the one that cannot take a working library offline over a formatting slip.
        Assert.assertEquals(SpecVersionGate.evaluate("   "),
                SpecVersionGate.VersionVerdict.ACCEPT_WITH_WARNING);
    }

    @Test
    public void testAnUnimplementedVersionIsRejected() {
        // The spec says to bump `version` "whenever a field's meaning changes incompatibly", so reading a
        // v2 document with v1 semantics would produce confident, wrong API guidance. Rejection degrades to
        // the service index instead, which is a poorer catalog rather than a wrong one.
        Assert.assertEquals(SpecVersionGate.evaluate("v2"), SpecVersionGate.VersionVerdict.REJECT);
        Assert.assertFalse(SpecVersionGate.evaluate("v2").isUsable());
        Assert.assertEquals(SpecVersionGate.evaluate("1"), SpecVersionGate.VersionVerdict.REJECT);
        Assert.assertEquals(SpecVersionGate.evaluate("V1"), SpecVersionGate.VersionVerdict.REJECT);
    }

    @Test
    public void testSurroundingWhitespaceDoesNotChangeTheVerdict() {
        Assert.assertEquals(SpecVersionGate.evaluate(" v1 "), SpecVersionGate.VersionVerdict.ACCEPT);
    }

    @Test
    public void testTheDocumentOverloadReadsTheDeclaredVersion() {
        Assert.assertEquals(
                SpecVersionGate.evaluate(new TriggerMetadataModel("v1", null, null, null, null)),
                SpecVersionGate.VersionVerdict.ACCEPT);
        Assert.assertEquals(
                SpecVersionGate.evaluate(new TriggerMetadataModel("v9", null, null, null, null)),
                SpecVersionGate.VersionVerdict.REJECT);
        Assert.assertEquals(SpecVersionGate.evaluate((TriggerMetadataModel) null),
                SpecVersionGate.VersionVerdict.ACCEPT_WITH_WARNING);
    }
}
