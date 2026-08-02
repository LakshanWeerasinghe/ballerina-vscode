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

import io.ballerina.modelgenerator.commons.trigger.models.PresenceForm;
import io.ballerina.modelgenerator.commons.trigger.models.PresenceValues;
import io.ballerina.modelgenerator.commons.trigger.models.TriggerMetadataModel;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.List;

/**
 * Pins the accessor precedence rule — <b>which is our inference, not the spec's</b>.
 *
 * <p>Spec §5 lists the resource extras without saying which supplies the accessor a generator must write, and
 * the three documents that declare one disagree. These tests therefore pin <i>our decision</i>, and each names
 * the corpus document it reproduces so that a spec clarification shows up here as a deliberate change rather
 * than a mystery.
 *
 * @since 1.7.0
 */
public class AccessorPrecedencePolicyTest {

    @Test
    public void testGraphqlStatesTheAccessorOutrightAndItWins() {
        // Corpus: graphql's query field — {"name": "*", "kind": "resource",
        //                                 "accessor": {"presence": "required", "values": ["get"]}}
        Assert.assertEquals(AccessorPrecedencePolicy.accessorOf(
                option("*", values("get"), null)).orElseThrow(), "get");
        // ...and its subscription field, which differs only in the accessor.
        Assert.assertEquals(AccessorPrecedencePolicy.accessorOf(
                option("*", values("subscribe"), null)).orElseThrow(), "subscribe");
    }

    @Test
    public void testAnExplicitAccessorBeatsAMethodList() {
        // The precedence itself: when a document states both, `accessor` is the more specific statement.
        Assert.assertEquals(AccessorPrecedencePolicy.accessorOf(
                option("*", values("subscribe"), values("get", "post"))).orElseThrow(), "subscribe");
    }

    @Test
    public void testHttpFallsBackToTheFirstDeclaredVerb() {
        // Corpus: http's "*" declares no accessor, only
        // method.values = [get, post, put, delete, patch, head, options, default].
        // "First element is the codegen default" (spec §1) applied to a value list.
        Assert.assertEquals(AccessorPrecedencePolicy.accessorOf(
                        option("*", null, values("get", "post", "put", "delete")))
                .orElseThrow(), "get");
    }

    @Test
    public void testWebsocketResolvesThroughItsMethodListNotItsName() {
        // Corpus: websocket's {"name": "get", "kind": "resource",
        //                      "method": {"presence": "required", "values": ["get"]}}.
        // Both branch 2 and branch 3 would answer "get" here, and this pins WHICH one does: websocket
        // declares `method`, so its name is never consulted. Documented in the policy's javadoc.
        Assert.assertEquals(AccessorPrecedencePolicy.accessorOf(
                option("get", null, values("get"))).orElseThrow(), "get");
    }

    @Test
    public void testTheNameIsTheLastResortAndHasNoCorpusInstance() {
        // No document reaches this branch today. It exists so a document naming a handler with an accessor
        // token but declaring neither slot still renders a compilable accessor rather than none.
        Assert.assertEquals(AccessorPrecedencePolicy.accessorOf(
                option("get", null, null)).orElseThrow(), "get");
        Assert.assertEquals(AccessorPrecedencePolicy.accessorOf(
                option("subscribe", null, null)).orElseThrow(), "subscribe");
    }

    @Test
    public void testAMethodNameIsNotMistakenForAnAccessor() {
        // The branch must not fire for an ordinary handler name, or every remote handler would acquire a
        // bogus accessor.
        Assert.assertTrue(AccessorPrecedencePolicy.accessorOf(option("onMessage", null, null)).isEmpty());
        Assert.assertTrue(AccessorPrecedencePolicy.accessorOf(option("onFileCsv", null, null)).isEmpty());
    }

    @Test
    public void testNoAccessorIsInventedWhenTheDocumentSuppliesNone() {
        // Substituting `get` would be inventing API. The renderer must be able to tell that it cannot write
        // this handler as a resource.
        Assert.assertTrue(AccessorPrecedencePolicy.accessorOf(option("*", null, null)).isEmpty());
        Assert.assertTrue(AccessorPrecedencePolicy.accessorOf(null).isEmpty());
        Assert.assertTrue(AccessorPrecedencePolicy.accessorOf(option(null, null, null)).isEmpty());
    }

    @Test
    public void testBlankAndEmptyValueListsAreIgnoredRatherThanEmittedAsAnEmptyAccessor() {
        // An empty accessor would erase the keyword it follows: `resource function  path(...)`.
        Assert.assertTrue(AccessorPrecedencePolicy.accessorOf(
                option("*", values(), values())).isEmpty());
        Assert.assertTrue(AccessorPrecedencePolicy.accessorOf(
                option("*", values("  "), null)).isEmpty());
        Assert.assertEquals(AccessorPrecedencePolicy.accessorOf(
                option("*", values("  ", "get"), null)).orElseThrow(), "get");
    }

    // ---- fixtures --------------------------------------------------------------------

    private static PresenceValues values(String... items) {
        return new PresenceValues(PresenceForm.PRESENCE_REQUIRED, List.of(items));
    }

    private static TriggerMetadataModel.ServiceType.HandlerOption option(
            String name, PresenceValues accessor, PresenceValues method) {
        return new TriggerMetadataModel.ServiceType.HandlerOption(
                name, TriggerMetadataModel.ServiceType.HandlerOption.KIND_RESOURCE, null, null, null, null,
                method, null, accessor, null, null);
    }
}
