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
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Pins spec §9's {@code includedRecord} mode: the full bindable list, and the derivation of the complement.
 *
 * <p>The envelope-field lookup is a lambda here, which is the point of taking one: the derivation is proven
 * without a compiled package.
 *
 * @since 1.7.0
 */
public class IncludedRecordModeResolverTest {

    private static final Predicate<String> DECLARES_NONE = name -> false;

    /** kafka's real envelope, in declaration order. */
    private static final Function<String, List<String>> KAFKA_FIELDS = Map.of(
            "AnydataConsumerRecord", List.of("key", "value", "timestamp", "offset", "headers"))::get;

    @Test
    public void testFixedFieldsIsDerivedAsTheEnvelopeMinusBindableFields() {
        // §9: "No `fixedFields` — always derivable as 'the envelope's fields minus `bindableFields`.'"
        // Corpus: kafka's consumerRecordPayload binds `value` and pins everything else.
        IncludedRecordModeResolver.IncludedRecord resolved = resolve(
                ref("AnydataConsumerRecord"), List.of("value"), KAFKA_FIELDS);

        Assert.assertEquals(resolved.bindableFields(), List.of("value"));
        Assert.assertEquals(resolved.fixedFields(), List.of("key", "timestamp", "offset", "headers"),
                "the complement, in the envelope's declaration order");
    }

    @Test
    public void testFixedFieldsIsNeverReadFromTheDocument() {
        // Same §9 sentence, from the other side: the document carries no such key, so with no way to
        // introspect the envelope there is nothing to state — and inventing a list would be worse.
        IncludedRecordModeResolver.IncludedRecord resolved = resolve(
                ref("UnknownEnvelope"), List.of("value"), KAFKA_FIELDS);
        Assert.assertTrue(resolved.fixedFields().isEmpty(),
                "an envelope the package does not declare yields no claim about which fields are pinned");
        Assert.assertEquals(resolved.bindableFields(), List.of("value"),
                "...while what the document does state survives");
    }

    @Test
    public void testEveryBindableFieldIsKept() {
        // §9: `includedRecord` | "User record does `*EnvelopeType;`, overrides only `bindableFields`".
        // TriggerModelSynthesizer:708-709 keeps only get(0); both corpus rules declare one field, so the
        // truncation is invisible today and would silently drop the second the moment one is added.
        IncludedRecordModeResolver.IncludedRecord resolved = resolve(
                ref("AnydataConsumerRecord"), List.of("value", "headers"), KAFKA_FIELDS);

        Assert.assertEquals(resolved.bindableFields(), List.of("value", "headers"));
        Assert.assertEquals(resolved.fixedFields(), List.of("key", "timestamp", "offset"),
                "the complement shrinks accordingly");
    }

    @Test
    public void testTheEnvelopeIsRenderedWithItsModuleAliasButLookedUpBare() {
        // §1: a declared same-module type is written `kafka:AnydataConsumerRecord`; the package's own
        // symbols know it as `AnydataConsumerRecord`. Confusing the two loses the field derivation.
        IncludedRecordModeResolver.IncludedRecord resolved = IncludedRecordModeResolver.resolve(
                mode(ref("AnydataConsumerRecord"), List.of("value")), "kafka",
                Set.of("AnydataConsumerRecord")::contains, KAFKA_FIELDS);

        Assert.assertEquals(resolved.envelope(), "kafka:AnydataConsumerRecord");
        Assert.assertEquals(resolved.fixedFields(), List.of("key", "timestamp", "offset", "headers"));
    }

    @Test
    public void testAModeWithNoEnvelopeStatesNothingAboutFields() {
        IncludedRecordModeResolver.IncludedRecord resolved = resolve(null, List.of("value"), KAFKA_FIELDS);
        Assert.assertNull(resolved.envelope());
        Assert.assertTrue(resolved.fixedFields().isEmpty());
    }

    @Test
    public void testABlankBindableFieldIsIgnored() {
        IncludedRecordModeResolver.IncludedRecord resolved = IncludedRecordModeResolver.resolve(
                new TriggerMetadataModel.DataBindingRule.SupportedMode(
                        TriggerMetadataModel.DataBindingRule.SupportedMode.MODE_INCLUDED_RECORD, null, null,
                        ref("AnydataConsumerRecord"), java.util.Arrays.asList("value", null, "  ")),
                "kafka", DECLARES_NONE, KAFKA_FIELDS);
        Assert.assertEquals(resolved.bindableFields(), List.of("value"));
    }

    // ---- fixtures --------------------------------------------------------------------

    private static IncludedRecordModeResolver.IncludedRecord resolve(
            TypeRef includes, List<String> bindableFields, Function<String, List<String>> fields) {
        return IncludedRecordModeResolver.resolve(mode(includes, bindableFields), "kafka", DECLARES_NONE,
                fields);
    }

    private static TriggerMetadataModel.DataBindingRule.SupportedMode mode(TypeRef includes,
                                                                           List<String> bindableFields) {
        return new TriggerMetadataModel.DataBindingRule.SupportedMode(
                TriggerMetadataModel.DataBindingRule.SupportedMode.MODE_INCLUDED_RECORD, null, null,
                includes, bindableFields);
    }

    private static TypeRef ref(String name) {
        return new TypeRef(name, null);
    }
}
