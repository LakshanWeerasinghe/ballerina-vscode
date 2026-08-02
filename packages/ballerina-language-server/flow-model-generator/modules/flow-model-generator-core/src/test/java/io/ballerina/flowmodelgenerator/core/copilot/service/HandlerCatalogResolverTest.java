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

/**
 * Conformance tests for <b>Spec §4 {@code handlers}</b>, written against the spec text rather than the
 * implementation.
 *
 * <p>Spec statement pinned: {@code backedByConcreteType} — "{@code true} → {@code options: []}, nothing
 * else to say. {@code false} → {@code options} is the only source of truth." Which of the two a service
 * type is decides where every handler, parameter name and description comes from, so it is the single
 * most consequential branch in the loader.
 *
 * <p>The concrete branch resolves against a compiled package and is covered end-to-end by
 * {@code CopilotSchemaServicesTest}; what is pinned here is the classification itself, which is pure.
 *
 * @since 1.7.0
 */
public class HandlerCatalogResolverTest {

    @Test
    public void testBackedByConcreteTypeMeansTheTypeIsTheSourceOfTruth() {
        // trigger.github's shape: `concrete: true` with `backedByConcreteType: true` and no options.
        Assert.assertTrue(HandlerCatalogResolver.isConcrete(
                serviceType(true, new TriggerMetadataModel.ServiceType.Handlers(true, null, List.of()))));
    }

    @Test
    public void testEitherConcreteFlagAloneIsEnough() {
        // The two flags say the same thing from different angles; a document setting only one is still
        // unambiguous, and treating it as a marker type would discard the type's real methods.
        Assert.assertTrue(HandlerCatalogResolver.isConcrete(
                serviceType(true, new TriggerMetadataModel.ServiceType.Handlers(false, "subset", List.of()))));
        Assert.assertTrue(HandlerCatalogResolver.isConcrete(
                serviceType(false, new TriggerMetadataModel.ServiceType.Handlers(true, null, List.of()))));
    }

    @Test
    public void testMarkerTypeIsNotConcrete() {
        // kafka's shape: the type declares no methods, so `options` is the only source of truth.
        Assert.assertFalse(HandlerCatalogResolver.isConcrete(serviceType(false,
                new TriggerMetadataModel.ServiceType.Handlers(false, "subset", List.of(option("onEvent"))))));
    }

    @Test
    public void testMissingHandlersBlockIsTreatedAsConcrete() {
        // With nothing to enumerate, the only possible source of truth is the type itself. Treating it as
        // a marker type would emit a service with no handlers at all.
        Assert.assertTrue(HandlerCatalogResolver.isConcrete(serviceType(false, null)));
    }

    @Test
    public void testMarkerTypeResolvesToItsDocumentedOptions() {
        // §4: for a marker type, `options` is the only source of truth — and it is passed through whole,
        // in document order, because §7 states "Array order is meaningful".
        TriggerMetadataModel.ServiceType.HandlerOption first = option("onConsumerRecord");
        TriggerMetadataModel.ServiceType.HandlerOption second = option("onError");
        HandlerCatalogResolver.HandlerCatalog catalog = HandlerCatalogResolver.resolve(
                serviceType(false, new TriggerMetadataModel.ServiceType.Handlers(
                        false, "subset", List.of(first, second))),
                "Service", null);

        Assert.assertTrue(catalog instanceof HandlerCatalogResolver.HandlerCatalog.Options);
        List<TriggerMetadataModel.ServiceType.HandlerOption> options =
                ((HandlerCatalogResolver.HandlerCatalog.Options) catalog).options();
        Assert.assertEquals(options.size(), 2);
        Assert.assertSame(options.get(0), first, "Document order must be preserved");
        Assert.assertSame(options.get(1), second);
    }

    @Test
    public void testManyModeStillResolvesThroughTheOptionsPath() {
        // §4's `addMode: "many"` is represented "as one options entry named \"*\"". It resolves like any
        // other option list; the wildcard is recognised when the handlers are built, not here.
        HandlerCatalogResolver.HandlerCatalog catalog = HandlerCatalogResolver.resolve(
                serviceType(false, new TriggerMetadataModel.ServiceType.Handlers(
                        false, "many", List.of(option("*")))),
                "Service", null);
        Assert.assertTrue(catalog instanceof HandlerCatalogResolver.HandlerCatalog.Options);
    }

    // ---- fixtures --------------------------------------------------------------------

    private static TriggerMetadataModel.ServiceType serviceType(
            boolean concrete, TriggerMetadataModel.ServiceType.Handlers handlers) {
        return new TriggerMetadataModel.ServiceType("service", new TypeRef("Service", null), concrete,
                true, true, null, handlers, null);
    }

    private static TriggerMetadataModel.ServiceType.HandlerOption option(String name) {
        return new TriggerMetadataModel.ServiceType.HandlerOption(name, "remote", "optional", null, null,
                null, null, null, null, null, null);
    }
}
