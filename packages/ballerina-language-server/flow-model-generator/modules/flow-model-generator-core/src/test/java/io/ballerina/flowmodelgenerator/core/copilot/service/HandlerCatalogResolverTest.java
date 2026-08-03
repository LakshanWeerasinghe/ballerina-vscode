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
    public void testManyModeResolvesToAnOpenEndedCatalog() {
        // §4: `addMode: "many"` is "open-ended, user-named … represented as one options entry named
        // \"*\"". It is a different kind of catalog from a fixed vocabulary, not a list containing one
        // odd member, because nothing in it can be emitted as a signature.
        HandlerCatalogResolver.HandlerCatalog catalog = HandlerCatalogResolver.resolve(
                serviceType(false, new TriggerMetadataModel.ServiceType.Handlers(
                        false, "many", List.of(option("*")))),
                "Service", null);
        Assert.assertTrue(catalog instanceof HandlerCatalogResolver.HandlerCatalog.Many,
                "Expected an open-ended catalog, got: " + catalog);
    }

    @Test
    public void testTheWildcardItselfIsCarriedAsTheTemplate() {
        // Everything §5 states about such a handler — kind, params, returns, annotations — lives on the
        // wildcard entry, so the entry itself is what the lower tiers must be given.
        TriggerMetadataModel.ServiceType.HandlerOption wildcard = option("*");
        HandlerCatalogResolver.HandlerCatalog catalog = HandlerCatalogResolver.resolve(
                serviceType(false, new TriggerMetadataModel.ServiceType.Handlers(
                        false, "many", List.of(wildcard))),
                "Service", null);
        Assert.assertSame(((HandlerCatalogResolver.HandlerCatalog.Many) catalog).template(), wildcard);
    }

    @Test
    public void testManyWithNamedOptionsAndNoWildcardKeepsTheNamedOptions() {
        // ballerina/grpc's real shape: `addMode: "many"` with four *named* options and no "*" entry.
        // Spec §4 says a many-shaped catalog is represented by a wildcard, so the document is
        // non-conformant — but its four options are fully-specified signatures, and discarding them in
        // favour of a template would lose real API over a document defect. Degrade, never drop.
        HandlerCatalogResolver.HandlerCatalog catalog = HandlerCatalogResolver.resolve(
                serviceType(false, new TriggerMetadataModel.ServiceType.Handlers(
                        false, "many", List.of(option("unary"), option("serverStreaming")))),
                "Service", null);
        Assert.assertTrue(catalog instanceof HandlerCatalogResolver.HandlerCatalog.Options,
                "Expected the named options to survive, got: " + catalog);
        Assert.assertEquals(((HandlerCatalogResolver.HandlerCatalog.Options) catalog).options().size(), 2);
    }

    @Test
    public void testSeveralWildcardsTakeTheFirst() {
        // ballerina/graphql declares three "*" entries under one options list where §4 allows one.
        // Dropping the service type over that would lose more than it protects.
        TriggerMetadataModel.ServiceType.HandlerOption first = option("*");
        HandlerCatalogResolver.HandlerCatalog catalog = HandlerCatalogResolver.resolve(
                serviceType(false, new TriggerMetadataModel.ServiceType.Handlers(
                        false, "many", List.of(first, option("*"), option("*")))),
                "Service", null);
        Assert.assertSame(((HandlerCatalogResolver.HandlerCatalog.Many) catalog).template(), first);
    }

    @Test
    public void testAWildcardIsRecognisedEvenWhenTheDocumentSaysSubset() {
        // The wildcard, not `addMode`, is what makes a catalog open-ended: a "*" entry has no name to
        // emit whatever the flag says, so trusting the flag here would render a method called `*`.
        HandlerCatalogResolver.HandlerCatalog catalog = HandlerCatalogResolver.resolve(
                serviceType(false, new TriggerMetadataModel.ServiceType.Handlers(
                        false, "subset", List.of(option("*")))),
                "Service", null);
        Assert.assertTrue(catalog instanceof HandlerCatalogResolver.HandlerCatalog.Many,
                "Expected an open-ended catalog, got: " + catalog);
    }

    @Test
    public void testAFixedVocabularyIsUnaffectedByTheWildcardRule() {
        // The overwhelmingly common shape must be untouched by the tolerance logic above.
        HandlerCatalogResolver.HandlerCatalog catalog = HandlerCatalogResolver.resolve(
                serviceType(false, new TriggerMetadataModel.ServiceType.Handlers(
                        false, "subset", List.of(option("onMessage"), option("onError")))),
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
