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

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Conformance tests for <b>Spec §7 {@code params[]}</b>, written against the spec text rather than the
 * implementation.
 *
 * <p>Spec statements pinned by this class:
 * <ul>
 *   <li>{@code name} — "Optional domain-meaningful name — added only where real source evidence shows it
 *       matters, not retrofitted everywhere." An authored name therefore always wins; an absent one must
 *       still yield a valid signature.</li>
 *   <li>{@code type} — "{@code TypeRef} or array", resolved per §1, whose first element is the codegen
 *       default.</li>
 *   <li>{@code presence} — "{@code required} / {@code optional}".</li>
 *   <li>{@code addMode} — "Optional {@code "many"} — slot repeats zero or more times, each occurrence
 *       independently named/typed". Such a slot has no fixed-signature counterpart.</li>
 * </ul>
 *
 * @since 1.7.0
 */
public class ParamTypeResolverTest {

    private static final Predicate<String> KAFKA_TYPES =
            Set.of("AnydataConsumerRecord", "BytesConsumerRecord", "Caller", "Error")::contains;
    private static final Predicate<String> NONE = name -> false;

    // ---- §7 presence -------------------------------------------------------------------

    @Test
    public void testOnlyAnOptionalSlotIsOptional() {
        Assert.assertTrue(ParamTypeResolver.isOptional(param(null, "Caller", "optional", null)));
        Assert.assertFalse(ParamTypeResolver.isOptional(param(null, "Caller", "required", null)));
    }

    @Test
    public void testAbsentPresenceIsNotOptional() {
        // `required` is the safe reading of an unstated presence: emitting a required slot that is in
        // fact optional still compiles, whereas omitting a required one does not.
        Assert.assertFalse(ParamTypeResolver.isOptional(param(null, "Caller", null, null)));
    }

    // ---- §7 addMode --------------------------------------------------------------------

    @Test
    public void testRepeatableSlotIsRecognised() {
        // "each occurrence independently named/typed" — an authoring concept with no fixed signature.
        Assert.assertTrue(ParamTypeResolver.isRepeatable(param(null, "string", "optional", "many")));
    }

    @Test
    public void testAbsentAddModeMeansAtMostOne() {
        // §7: "Absent = at most one."
        Assert.assertFalse(ParamTypeResolver.isRepeatable(param(null, "string", "required", null)));
    }

    // ---- §7 type, resolved per §1 -------------------------------------------------------

    @Test
    public void testSignatureUsesTheUnionsFirstMember() {
        // §1: "first element = codegen default". kafka's onConsumerRecord payload slot.
        TriggerMetadataModel.ServiceType.Param union = new TriggerMetadataModel.ServiceType.Param(
                null,
                List.of(new TypeRef("AnydataConsumerRecord[]", null),
                        new TypeRef("BytesConsumerRecord[]", null)),
                "required", null, "consumerRecordPayload", null);
        Assert.assertEquals(ParamTypeResolver.signature(union, "kafka", KAFKA_TYPES),
                "kafka:AnydataConsumerRecord[]");
    }

    @Test
    public void testSignatureOfAnAbsentTypeIsEmpty() {
        Assert.assertEquals(ParamTypeResolver.signature(
                new TriggerMetadataModel.ServiceType.Param(null, null, "required", null, null, null),
                "kafka", KAFKA_TYPES), "");
    }

    // ---- §7 name -----------------------------------------------------------------------

    @Test
    public void testAuthoredNameAlwaysWins() {
        // mssql.cdc states `afterEntry`/`tableName`; a generated name must never override them.
        Assert.assertEquals(ParamTypeResolver.resolveName(
                param("afterEntry", "record {}", "required", null), 0, "mssql", new HashSet<>()),
                "afterEntry");
    }

    @Test
    public void testNamelessSlotIsNamedFromItsType() {
        // The name is the author's choice, so the document omits it; a signature still needs one.
        Assert.assertEquals(ParamTypeResolver.resolveName(
                param(null, "WatchEvent", "required", null), 0, "ftp", new HashSet<>()), "watchEvent");
    }

    @Test
    public void testGeneratedNamesAreDeterministic() {
        // The same slot must always produce the same name, or generated code churns between runs.
        for (int i = 0; i < 5; i++) {
            Assert.assertEquals(ParamTypeResolver.resolveName(
                    param(null, "Error", "required", null), 0, "kafka", new HashSet<>()), "kafkaError");
        }
    }

    // ---- the resolved-package guard ------------------------------------------------------

    @Test
    public void testHandlerReferencingAnUndeclaredHomeTypeIsDisqualified() {
        // websub's onHubError: the document names `HubError`, the resolved package does not declare it.
        // Emitting the handler would put an uncompilable signature in the prompt.
        Assert.assertTrue(ParamTypeResolver.signatureReferencesUndeclaredType(
                option(List.of(param(null, "HubError", "required", null)), null), NONE));
        Assert.assertFalse(ParamTypeResolver.signatureReferencesUndeclaredType(
                option(List.of(param(null, "HubError", "required", null)), null),
                Set.of("HubError")::contains));
    }

    @Test
    public void testAnUndeclaredReturnMemberIsEquallyDisqualifying() {
        Assert.assertTrue(ParamTypeResolver.signatureReferencesUndeclaredType(
                option(null, List.of(new TypeRef("Acknowledgement", null))), NONE));
    }

    @Test
    public void testCrossModuleTypesAreTrustedNotVetoed() {
        // §1: a packageInfo-carrying reference belongs to another module, which this module's symbols
        // cannot speak for. Vetoing it would drop every handler that reuses a foreign type.
        TriggerMetadataModel.ServiceType.Param foreign = new TriggerMetadataModel.ServiceType.Param(
                "cdcError",
                List.of(new TypeRef("Error", new TypeRef.PackageInfo("ballerinax", "cdc", "cdc", "1.3.2"))),
                "required", null, null, null);
        Assert.assertFalse(ParamTypeResolver.signatureReferencesUndeclaredType(
                option(List.of(foreign), null), NONE));
    }

    @Test
    public void testBuiltInsAndAnonymousShapesAreNeverDisqualifying() {
        // Lower-case and anonymous shapes name no user-defined type, so there is nothing to declare.
        for (String builtin : List.of("string", "json", "anydata", "record {}", "byte[]")) {
            Assert.assertFalse(ParamTypeResolver.signatureReferencesUndeclaredType(
                    option(List.of(param(null, builtin, "required", null)), null), NONE),
                    builtin + " must not disqualify a handler");
        }
    }

    @Test
    public void testOnlyTheEmittedUnionMemberIsChecked() {
        // Only the first member reaches the signature, so an undeclared *alternative* must not cost the
        // whole handler — the emitted code never mentions it.
        TriggerMetadataModel.ServiceType.Param union = new TriggerMetadataModel.ServiceType.Param(
                null,
                List.of(new TypeRef("Caller", null), new TypeRef("NotDeclaredAnywhere", null)),
                "required", null, null, null);
        Assert.assertFalse(ParamTypeResolver.signatureReferencesUndeclaredType(
                option(List.of(union), null), Set.of("Caller")::contains));
    }

    // ---- fixtures --------------------------------------------------------------------

    private static TriggerMetadataModel.ServiceType.Param param(String name, String type, String presence,
                                                                String addMode) {
        return new TriggerMetadataModel.ServiceType.Param(name, List.of(new TypeRef(type, null)),
                presence, addMode, null, null);
    }

    private static TriggerMetadataModel.ServiceType.HandlerOption option(
            List<TriggerMetadataModel.ServiceType.Param> params, List<TypeRef> returns) {
        return new TriggerMetadataModel.ServiceType.HandlerOption("onEvent", "remote", "optional", null,
                params, returns, null, null, null, null, null);
    }
}
