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

import com.google.gson.JsonObject;
import io.ballerina.modelgenerator.commons.trigger.models.TriggerMetadataModel;
import io.ballerina.modelgenerator.commons.trigger.models.TypeRef;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.List;
import java.util.function.Predicate;

/**
 * Conformance tests for <b>spec §3's array cardinality</b> — the rule that decides whether a service type
 * is mandatory.
 *
 * <p>Spec statement pinned, verbatim: "No {@code presence} field here either — derived from array
 * cardinality: one entry = required; multiple entries = each individually optional, choice left to
 * whatever supplied the generation intent (no 'at least one of N' enforcement — a generator is always
 * externally directed)."
 *
 * <p>Two things follow that are easy to get wrong, and both are pinned below. The flag is derived from the
 * <i>document's</i> count rather than from anything about the individual entry; and it means "individually
 * optional", <b>not</b> "mutually exclusive" — spec §3 imposes no "exactly one of N" rule, and
 * {@code websocket} is the corpus counter-example, since its {@code UpgradeService} handler returns its
 * {@code Service} and the two are routinely declared together.
 *
 * @since 1.7.0
 */
public class ServiceIdentityResolverTest {

    private static final Predicate<String> DECLARES_ANYTHING = name -> true;
    private static final String HOME = "mcp";

    @Test
    public void testASoleServiceTypeIsRequiredAndSaysNothing() {
        // "one entry = required" — there is no choice to describe, so no flag is raised.
        Assert.assertFalse(resolve(1).alternatives());
    }

    @Test
    public void testSeveralServiceTypesAreEachIndividuallyOptional() {
        // "multiple entries = each individually optional". mcp declares four, websocket two,
        // trigger.github ten.
        Assert.assertTrue(resolve(2).alternatives());
        Assert.assertTrue(resolve(4).alternatives());
        Assert.assertTrue(resolve(10).alternatives());
    }

    @Test
    public void testTheFlagIsAPropertyOfTheDocumentNotOfTheEntry() {
        // Every entry of a multi-entry document carries it, including the first: §3 makes them each
        // individually optional, not "the first is required and the rest are extras".
        Assert.assertEquals(resolve(3).alternatives(), resolve(3).alternatives());
        Assert.assertTrue(resolve(3).alternatives());
    }

    @Test
    public void testADegenerateCountNeverRaisesTheFlag() {
        // A scope built without a document reports one service type; nothing is claimed from missing input.
        Assert.assertFalse(resolve(0).alternatives());
    }

    @Test
    public void testOnlyServiceTypesTheListenerCanHostAreAlternatives() {
        // websocket's shape, and the reason this count is not `serviceTypes.size()`. It declares two
        // service types but its listener lists only `upgradeService`; `Service` is reached as the RETURN
        // of the upgrade resource, never attached to a listener. Verified with the compiler:
        //   service websocket:Service on new websocket:Listener(9090) { }
        //   ERROR service type is not supported by the listener
        // Calling the two alternatives would tell a generator it may write exactly that.
        TriggerMetadataModel.Listener hostsOne = new TriggerMetadataModel.Listener(
                new TypeRef("Listener", null), List.of("upgradeService"), null);
        Assert.assertEquals(ListenerPairingResolver.hostedServiceTypeCount(hostsOne,
                List.of(named("upgradeService"), named("service"))), 1);
    }

    @Test
    public void testEveryHostableServiceTypeCounts() {
        // mcp's shape: its listener lists all four, so all four genuinely are alternatives.
        TriggerMetadataModel.Listener hostsAll = new TriggerMetadataModel.Listener(
                new TypeRef("Listener", null),
                List.of("service", "advancedService", "streamableHttpService"), null);
        Assert.assertEquals(ListenerPairingResolver.hostedServiceTypeCount(hostsAll,
                List.of(named("service"), named("advancedService"), named("streamableHttpService"))), 3);
    }

    @Test
    public void testAListenerThatNamesNoServicesConstrainsNothing() {
        // Spec §2's `services` is optional. A listener declaring none says nothing about hostability, so
        // the document's own count stands — the same fallback the pairing rule already applies.
        Assert.assertEquals(ListenerPairingResolver.hostedServiceTypeCount(
                new TriggerMetadataModel.Listener(new TypeRef("Listener", null), null, null),
                List.of(named("a"), named("b"))), 2);
        Assert.assertEquals(ListenerPairingResolver.hostedServiceTypeCount(
                null, List.of(named("a"), named("b"))), 2);
    }

    @Test
    public void testTheFlagIsEmittedOnlyWhenSet() {
        // The omission rule: a single-service-type library states nothing at all.
        Assert.assertTrue(contribute(2).get("alternatives").getAsBoolean());
        Assert.assertFalse(contribute(1).has("alternatives"));
    }

    @Test
    public void testAlternativesDoesNotDisturbTheIdentityItTravelsWith() {
        // The flag is additive: the type name and the veto behaviour it shares a component with are
        // unchanged by it.
        JsonObject json = contribute(4);
        Assert.assertEquals(json.get("name").getAsString(), "Service");
        Assert.assertEquals(json.get("type").getAsString(), "fixed");
    }

    // ---- fixtures --------------------------------------------------------------------

    private static ServiceIdentityResolver.ServiceIdentity resolve(int serviceTypeCount) {
        return ServiceIdentityResolver.resolve(serviceType(), HOME, DECLARES_ANYTHING, serviceTypeCount);
    }

    private static JsonObject contribute(int serviceTypeCount) {
        TriggerMetadataModel document = new TriggerMetadataModel(null, null,
                java.util.Collections.nCopies(serviceTypeCount, serviceType()), null, null);
        TriggerScope scope = new TriggerScope("ballerina/mcp", "ballerina", HOME, HOME, document,
                AnnotationRegistry.of(null), serviceType(), null, null, null, DECLARES_ANYTHING);
        ServiceDraft draft = new ServiceDraft();
        new ServiceIdentityAspect().contribute(scope, draft);
        return draft.toJson();
    }

    private static TriggerMetadataModel.ServiceType serviceType() {
        return new TriggerMetadataModel.ServiceType("service", new TypeRef("Service", null), false,
                true, true, null, null, null);
    }

    private static TriggerMetadataModel.ServiceType named(String id) {
        return new TriggerMetadataModel.ServiceType(id, new TypeRef("Service", null), false,
                true, true, null, null, null);
    }
}
