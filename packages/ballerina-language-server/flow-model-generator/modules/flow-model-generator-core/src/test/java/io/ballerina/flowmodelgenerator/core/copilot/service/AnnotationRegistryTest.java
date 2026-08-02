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

import java.util.Arrays;
import java.util.List;

/**
 * Conformance tests for <b>Spec §8 {@code annotations[]}</b>, written against the spec text rather than
 * the implementation.
 *
 * <p>Spec statements pinned by this class:
 * <ul>
 *   <li>The registry holds annotation types "referenced elsewhere … defined once", reached by {@code id}
 *       from {@code params[].annotations}, {@code handlers.options[].annotations} and
 *       {@code rules[].members[].annotation}.</li>
 *   <li>{@code attachPoint} is one of {@code service} / {@code function} / {@code parameter} /
 *       {@code return} — the second access path, used by service- and return-level annotations that have
 *       no more precise reference.</li>
 *   <li>The top-level key is <b>optional</b>, and per the general rule is omitted rather than written as
 *       an empty array — so a document with no annotations must still be queryable.</li>
 * </ul>
 *
 * @since 1.7.0
 */
public class AnnotationRegistryTest {

    @Test
    public void testAnnotationIsReachableByTheIdOtherConstructsReference() {
        // smb references `functionConfig` from a handler; rabbitmq references `serviceConfig` from a rule.
        AnnotationRegistry registry = registryOf(
                annotation("functionConfig", "FunctionConfig", "function", null, "required"));
        Assert.assertTrue(registry.byId("functionConfig").isPresent());
        Assert.assertEquals(registry.byId("functionConfig").orElseThrow().type().name(), "FunctionConfig");
    }

    @Test
    public void testUnknownIdResolvesToNothingRatherThanFailing() {
        // A dangling reference is a document defect; it must be reportable, not fatal.
        Assert.assertTrue(registryOf().byId("noSuchAnnotation").isEmpty());
        Assert.assertTrue(registryOf().byId(null).isEmpty());
    }

    @Test
    public void testAnnotationsAreReachableByAttachPoint() {
        // §8's four attach points, each resolved by a different component in the pipeline.
        AnnotationRegistry registry = registryOf(
                annotation("serviceConfig", "ServiceConfig", "service", List.of("service"), "required"),
                annotation("payload", "Payload", "parameter", null, "optional"),
                annotation("cache", "Cache", "return", null, "optional"));

        Assert.assertEquals(registry.byAttachPoint("service").size(), 1);
        Assert.assertEquals(registry.byAttachPoint("parameter").size(), 1);
        Assert.assertEquals(registry.byAttachPoint("return").size(), 1);
        Assert.assertTrue(registry.byAttachPoint("function").isEmpty());
    }

    @Test
    public void testSeveralAnnotationsAtOneAttachPointKeepDocumentOrder() {
        AnnotationRegistry registry = registryOf(
                annotation("first", "First", "service", null, "optional"),
                annotation("second", "Second", "service", null, "optional"));
        List<TriggerMetadataModel.Annotation> atService = registry.byAttachPoint("service");
        Assert.assertEquals(atService.size(), 2);
        Assert.assertEquals(atService.get(0).id(), "first");
        Assert.assertEquals(atService.get(1).id(), "second");
    }

    @Test
    public void testAbsentAnnotationsKeyYieldsAnEmptyRegistryNotAFailure() {
        // §8's key is optional and the general rule says an unused key is omitted entirely, so both
        // shapes have to be accepted — and a consumer must not have to null-check before every lookup.
        for (TriggerMetadataModel document : List.of(
                new TriggerMetadataModel(List.of(), List.of(), null, null),
                new TriggerMetadataModel(List.of(), List.of(), List.of(), null))) {
            AnnotationRegistry registry = AnnotationRegistry.of(document);
            Assert.assertTrue(registry.byId("anything").isEmpty());
            Assert.assertTrue(registry.byAttachPoint("service").isEmpty());
        }
        Assert.assertTrue(AnnotationRegistry.of(null).byAttachPoint("service").isEmpty());
    }

    @Test
    public void testCrossModuleAnnotationTypesArePreserved() {
        // mssql.cdc's required `@cdc:ServiceConfig`: the coordinates decide the prefix it renders with.
        AnnotationRegistry registry = registryOf(new TriggerMetadataModel.Annotation(
                "serviceConfig",
                new TypeRef("ServiceConfig",
                        new TypeRef.PackageInfo("ballerinax", "cdc", "cdc", "1.3.2")),
                "service", List.of("service"), "required"));
        TriggerMetadataModel.Annotation resolved = registry.byId("serviceConfig").orElseThrow();
        Assert.assertEquals(resolved.type().packageInfo().moduleName(), "cdc");
        Assert.assertEquals(resolved.presence(), "required");
    }

    @Test
    public void testMalformedEntriesDoNotPoisonTheRegistry() {
        // A null entry, or one missing an id or attach point, must not cost the sound entries beside it.
        AnnotationRegistry registry = AnnotationRegistry.of(new TriggerMetadataModel(List.of(), List.of(),
                Arrays.asList(null, annotation(null, "Nameless", "service", null, "optional"),
                        annotation("sound", "Sound", "service", null, "optional")),
                null));
        Assert.assertTrue(registry.byId("sound").isPresent());
        Assert.assertEquals(registry.byAttachPoint("service").size(), 2);
    }

    // ---- fixtures --------------------------------------------------------------------

    private static AnnotationRegistry registryOf(TriggerMetadataModel.Annotation... annotations) {
        return AnnotationRegistry.of(
                new TriggerMetadataModel(List.of(), List.of(), List.of(annotations), null));
    }

    private static TriggerMetadataModel.Annotation annotation(String id, String type, String attachPoint,
                                                              List<String> appliesTo, String presence) {
        return new TriggerMetadataModel.Annotation(id, new TypeRef(type, null), attachPoint, appliesTo,
                presence);
    }
}
