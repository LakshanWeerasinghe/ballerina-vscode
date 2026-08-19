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

import io.ballerina.modelgenerator.commons.trigger.models.TriggerMetadataModel;
import io.ballerina.modelgenerator.commons.trigger.models.TypeRef;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Pins spec §9 at the rule level: id lookup, {@code cardinality}, mode dispatch, and the two degradations.
 *
 * <p>Fixtures reproduce the corpus rules by name ({@code consumerRecordPayload} is kafka's,
 * {@code csvContent} is ftp's and smb's) so a case that stops matching the corpus is visible.
 *
 * @since 1.7.0
 */
public class DataBindingResolverTest {

    private static final Predicate<String> DECLARES_NONE = name -> false;
    private static final String PKG = "kafka";

    @Test
    public void testAnIdResolvesAgainstTheRegistry() {
        // §9: `id` | "Referenced from `params[].dataBinding`."
        TriggerMetadataModel document = documentOf(rule("consumerRecordPayload", null,
                direct(ref("anydata"))));
        Assert.assertTrue(resolve("consumerRecordPayload", document).isPresent());
    }

    @Test
    public void testAnUnresolvableIdYieldsEmptyRatherThanThrowing() {
        // A broken reference must degrade: the parameter still renders, only its binding note is lost.
        TriggerMetadataModel document = documentOf(rule("consumerRecordPayload", null,
                direct(ref("anydata"))));
        Assert.assertTrue(resolve("noSuchRule", document).isEmpty());
        Assert.assertFalse(DataBindingResolver.declaresRule(document, "noSuchRule"),
                "the caller must be able to tell a missing entry from an unusable one");
        Assert.assertTrue(DataBindingResolver.declaresRule(document, "consumerRecordPayload"));
    }

    @Test
    public void testAnUnknownModeIsSkippedRatherThanThrowing() {
        // §10 lists direct, includedRecord, streamable. A future fourth mode must degrade, not break the
        // library — the surviving modes still resolve.
        TriggerMetadataModel document = documentOf(rule("mixed", null,
                mode("someFutureMode", null, null, null, null),
                direct(ref("anydata"))));
        Optional<DataBindingResolver.BindingSpec> spec = resolve("mixed", document);
        Assert.assertTrue(spec.isPresent());
        Assert.assertEquals(spec.get().modes().size(), 1, "only the unknown mode is dropped");
        Assert.assertTrue(spec.get().modes().get(0) instanceof DirectModeResolver.Direct);
    }

    @Test
    public void testARuleWhoseEveryModeIsUnknownResolvesToNothing() {
        TriggerMetadataModel document = documentOf(rule("mixed", null,
                mode("someFutureMode", null, null, null, null)));
        Assert.assertTrue(resolve("mixed", document).isEmpty(),
                "a rule that states nothing a reader can act on is not a rule");
        Assert.assertTrue(DataBindingResolver.declaresRule(document, "mixed"),
                "...but the entry does exist, which is a different diagnostic");
    }

    @Test
    public void testCardinalityArrayIsCarriedAsAFlag() {
        // §9: `cardinality` | "Optional `array` — the bound value is a batch; a mode's type is the array
        // *element* type, not the whole param type."
        // Corpus: kafka's consumerRecordPayload, the only instance.
        TriggerMetadataModel document = documentOf(rule("consumerRecordPayload",
                TriggerMetadataModel.DataBindingRule.CARDINALITY_ARRAY, direct(ref("anydata"))));
        Assert.assertTrue(resolve("consumerRecordPayload", document).orElseThrow().arrayCardinality());
    }

    @Test
    public void testAbsentCardinalityIsNotAnArray() {
        // Every other corpus rule omits the key; reading absence as "array" would pluralize seven rules.
        TriggerMetadataModel document = documentOf(rule("messagePayload", null, direct(ref("anydata"))));
        Assert.assertFalse(resolve("messagePayload", document).orElseThrow().arrayCardinality());
    }

    @Test
    public void testEachModeIsDispatchedToItsOwnResolver() {
        // §9 defines three modes with different fields; the dispatcher must not collapse them.
        // Corpus shape: ftp/smb's csvContent declares direct + streamable in one rule.
        TriggerMetadataModel document = documentOf(rule("csvContent", null,
                direct(ref("string[][]")),
                mode(TriggerMetadataModel.DataBindingRule.SupportedMode.MODE_STREAMABLE,
                        List.of(ref("stream<string[], error?>")), null, null, null),
                mode(TriggerMetadataModel.DataBindingRule.SupportedMode.MODE_INCLUDED_RECORD,
                        null, null, ref("AnydataMessage"), List.of("content"))));
        List<DataBindingResolver.Mode> modes = resolve("csvContent", document).orElseThrow().modes();

        Assert.assertEquals(modes.size(), 3, "document order is preserved");
        Assert.assertTrue(modes.get(0) instanceof DirectModeResolver.Direct);
        Assert.assertTrue(modes.get(1) instanceof StreamableModeResolver.Streamable);
        Assert.assertTrue(modes.get(2) instanceof IncludedRecordModeResolver.IncludedRecord);
    }

    @Test
    public void testASlotThatNamesNoRuleResolvesToNothing() {
        // §7: `dataBinding` is "Present only when the raw value can be projected into a user-defined type."
        Assert.assertTrue(resolve(null, documentOf(rule("x", null, direct(ref("anydata"))))).isEmpty());
        Assert.assertTrue(resolve("  ", documentOf(rule("x", null, direct(ref("anydata"))))).isEmpty());
        Assert.assertTrue(resolve("x", null).isEmpty(), "no document is not a crash");
        Assert.assertFalse(DataBindingResolver.declaresRule(null, "x"));
    }

    @Test
    public void testADocumentWithNoRegistryResolvesToNothing() {
        // §9's whole key is optional — five of the thirteen documents declare none.
        Assert.assertTrue(resolve("anything", new TriggerMetadataModel(null, null, null, null, null)).isEmpty());
    }

    // ---- fixtures --------------------------------------------------------------------

    private static Optional<DataBindingResolver.BindingSpec> resolve(String id, TriggerMetadataModel doc) {
        return DataBindingResolver.resolve(id, doc, PKG, DECLARES_NONE, name -> List.of());
    }

    private static TriggerMetadataModel documentOf(TriggerMetadataModel.DataBindingRule... rules) {
        return new TriggerMetadataModel(null, null, null, null, List.of(rules));
    }

    private static TriggerMetadataModel.DataBindingRule rule(
            String id, String cardinality,
            TriggerMetadataModel.DataBindingRule.SupportedMode... modes) {
        return new TriggerMetadataModel.DataBindingRule(id, null, cardinality, List.of(modes));
    }

    private static TriggerMetadataModel.DataBindingRule.SupportedMode direct(TypeRef... constraint) {
        return mode(TriggerMetadataModel.DataBindingRule.SupportedMode.MODE_DIRECT,
                List.of(constraint), null, null, null);
    }

    private static TriggerMetadataModel.DataBindingRule.SupportedMode mode(
            String mode, List<TypeRef> typeConstraint, List<TypeRef> excludes, TypeRef includes,
            List<String> bindableFields) {
        return new TriggerMetadataModel.DataBindingRule.SupportedMode(mode, typeConstraint, excludes,
                includes, bindableFields);
    }

    private static TypeRef ref(String name) {
        return new TypeRef(name, null);
    }
}
