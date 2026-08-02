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
 * Conformance tests for <b>Spec §2 {@code listeners[].services}</b> — "{@code serviceTypes[].id} values
 * this listener can host" — written against the spec text rather than the implementation.
 *
 * <p>All 13 corpus documents declare exactly one listener, so the multi-listener path is <b>latent</b>:
 * it is covered here with synthetic documents, which is the only way it can be covered at all. The
 * listener-class resolution half of the resolver needs a compiled package and is covered end-to-end by
 * {@code CopilotSchemaServicesTest}.
 *
 * @since 1.7.0
 */
public class ListenerPairingResolverTest {

    @Test
    public void testServiceTypeGoesToTheListenerThatNamesItsId() {
        // §2: `services` lists the ids a listener can host, so the id is what binds the two.
        TriggerMetadataModel.Listener http = listener("HttpListener", "restService");
        TriggerMetadataModel.Listener grpc = listener("GrpcListener", "rpcService");

        Assert.assertSame(ListenerPairingResolver.hostOf(List.of(http, grpc), serviceType("rpcService")),
                grpc);
        Assert.assertSame(ListenerPairingResolver.hostOf(List.of(http, grpc), serviceType("restService")),
                http);
    }

    @Test
    public void testOneListenerHostingSeveralServiceTypes() {
        // trigger.github's shape: a single listener naming every event service type it can host.
        TriggerMetadataModel.Listener only = listener("Listener", "issues", "push", "release");
        for (String id : List.of("issues", "push", "release")) {
            Assert.assertSame(ListenerPairingResolver.hostOf(List.of(only), serviceType(id)), only);
        }
    }

    @Test
    public void testUnmatchedIdFallsBackToTheFirstListener() {
        // A document whose `services` omits an id is incomplete, not unusable: the service type still has
        // to be placed somewhere, and the first listener is the only defensible default.
        TriggerMetadataModel.Listener first = listener("Listener", "other");
        TriggerMetadataModel.Listener second = listener("Second", "alsoOther");
        Assert.assertSame(ListenerPairingResolver.hostOf(List.of(first, second), serviceType("unlisted")),
                first);
    }

    @Test
    public void testAbsentServicesListFallsBackToTheFirstListener() {
        TriggerMetadataModel.Listener noServices =
                new TriggerMetadataModel.Listener(new TypeRef("Listener", null), null, null);
        Assert.assertSame(
                ListenerPairingResolver.hostOf(List.of(noServices), serviceType("service")), noServices);
    }

    @Test
    public void testServiceTypeWithNoIdFallsBackToTheFirstListener() {
        TriggerMetadataModel.Listener first = listener("Listener", "service");
        Assert.assertSame(ListenerPairingResolver.hostOf(List.of(first), serviceType(null)), first);
        Assert.assertSame(ListenerPairingResolver.hostOf(List.of(first), null), first);
    }

    @Test
    public void testFirstNamingListenerWinsWhenSeveralClaimTheSameId() {
        // The spec does not forbid two listeners claiming one id. Document order decides, so the outcome
        // is at least deterministic rather than dependent on iteration order.
        TriggerMetadataModel.Listener first = listener("First", "shared");
        TriggerMetadataModel.Listener second = listener("Second", "shared");
        Assert.assertSame(ListenerPairingResolver.hostOf(List.of(first, second), serviceType("shared")),
                first);
    }

    @Test
    public void testNullListenerEntryIsSkippedRatherThanMatched() {
        TriggerMetadataModel.Listener real = listener("Listener", "service");
        Assert.assertSame(
                ListenerPairingResolver.hostOf(Arrays.asList(real, null), serviceType("service")), real);
    }

    @Test
    public void testNoServiceTypesYieldsNoPairings() {
        Assert.assertTrue(ListenerPairingResolver.resolve(List.of(listener("Listener", "s")), null, null)
                .isEmpty());
        Assert.assertTrue(ListenerPairingResolver.resolve(List.of(), List.of(serviceType("s")), null)
                .isEmpty());
        Assert.assertTrue(ListenerPairingResolver.resolve(null, List.of(serviceType("s")), null)
                .isEmpty());
    }

    // ---- fixtures --------------------------------------------------------------------

    private static TriggerMetadataModel.Listener listener(String className, String... hostedIds) {
        return new TriggerMetadataModel.Listener(new TypeRef(className, null), List.of(hostedIds), null);
    }

    private static TriggerMetadataModel.ServiceType serviceType(String id) {
        return new TriggerMetadataModel.ServiceType(id, new TypeRef("Service", null), false, true, true,
                null, new TriggerMetadataModel.ServiceType.Handlers(false, "subset", List.of()), null);
    }
}
