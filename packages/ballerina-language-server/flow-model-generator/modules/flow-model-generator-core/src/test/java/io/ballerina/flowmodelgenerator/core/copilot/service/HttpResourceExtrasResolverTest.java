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
 * Pins spec §5's {@code method}/{@code path} resource extras.
 *
 * @since 1.7.0
 */
public class HttpResourceExtrasResolverTest {

    @Test
    public void testHttpsFullVerbListAndPathFormsAreKeptWhole() {
        // Corpus: http's "*" — method.values is the full HTTP verb set and path.form lists three shapes.
        // §11.2: these are intent-derived, so every legal value must reach the prompt for the intent to
        // choose from. Truncating to the first would silently narrow the API.
        HttpResourceExtrasResolver.HttpExtras extras = HttpResourceExtrasResolver.resolve(option(
                        values("required", "get", "post", "put", "delete", "patch", "head", "options",
                                "default"),
                        form("required", "identifierSegments", "pathParamSegments", "stringLiteralSegment")))
                .orElseThrow();
        Assert.assertEquals(extras.methodValues().size(), 8);
        Assert.assertEquals(extras.methodValues().get(0), "get");
        Assert.assertEquals(extras.pathForm(),
                List.of("identifierSegments", "pathParamSegments", "stringLiteralSegment"));
        Assert.assertTrue(extras.methodRequired());
        Assert.assertTrue(extras.pathRequired());
        Assert.assertTrue(extras.hasMethod());
        Assert.assertTrue(extras.hasPath());
    }

    @Test
    public void testWebsocketAlsoDeclaresTheseExtrasDespiteTheSpecCallingThemHttps() {
        // Corpus: websocket's `get` declares method.values=["get"], path.form=["stringLiteralSegment"].
        // Spec §5 introduces the pair as "HTTP adds ...", but ownership here is the pair itself, whoever
        // declares it — otherwise websocket's only handler would lose its path constraint.
        HttpResourceExtrasResolver.HttpExtras extras = HttpResourceExtrasResolver.resolve(option(
                values("required", "get"), form("required", "stringLiteralSegment"))).orElseThrow();
        Assert.assertEquals(extras.methodValues(), List.of("get"));
        Assert.assertEquals(extras.pathForm(), List.of("stringLiteralSegment"));
    }

    @Test
    public void testTheFormVocabularyIsPassedThroughUnvalidated() {
        // Spec §10 enumerates forms ONLY for `serviceTypes[].identifier.form`. path.form's corpus values
        // (identifierSegments, pathParamSegments, stringLiteralSegment) appear in no spec vocabulary and in no
        // schema enum, so an unrecognised token must survive rather than be normalised away.
        HttpResourceExtrasResolver.HttpExtras extras = HttpResourceExtrasResolver.resolve(
                option(null, form("required", "somethingEntirelyNew"))).orElseThrow();
        Assert.assertEquals(extras.pathForm(), List.of("somethingEntirelyNew"));
    }

    @Test
    public void testEitherHalfMayBeAbsentIndependently() {
        // graphql's handlers declare neither; a document may legitimately constrain a path but not a verb.
        HttpResourceExtrasResolver.HttpExtras pathOnly = HttpResourceExtrasResolver.resolve(
                option(null, form("required", "stringLiteralSegment"))).orElseThrow();
        Assert.assertFalse(pathOnly.hasMethod());
        Assert.assertTrue(pathOnly.hasPath());

        HttpResourceExtrasResolver.HttpExtras methodOnly = HttpResourceExtrasResolver.resolve(
                option(values("required", "get"), null)).orElseThrow();
        Assert.assertTrue(methodOnly.hasMethod());
        Assert.assertFalse(methodOnly.hasPath());
    }

    @Test
    public void testNoExtrasAreProducedWhenTheHandlerDeclaresNeither() {
        // The omission rule: a remote handler must not acquire an empty extras block.
        Assert.assertTrue(HttpResourceExtrasResolver.resolve(option(null, null)).isEmpty());
        Assert.assertTrue(HttpResourceExtrasResolver.resolve(null).isEmpty());
        Assert.assertTrue(HttpResourceExtrasResolver.resolve(
                option(values("required"), form("required"))).isEmpty(),
                "Empty value lists carry no information and must not produce a block");
    }

    @Test
    public void testPresenceIsCarriedAndAnUnknownTermReadsAsRequired() {
        // §10's presence vocabulary. An unrecognised term must not downgrade a mandatory slot to skippable,
        // so only an explicit "optional" is optional.
        Assert.assertFalse(HttpResourceExtrasResolver.resolve(
                option(values("optional", "get"), null)).orElseThrow().methodRequired());
        Assert.assertTrue(HttpResourceExtrasResolver.resolve(
                option(values("recommended", "get"), null)).orElseThrow().methodRequired());
        Assert.assertTrue(HttpResourceExtrasResolver.resolve(
                option(values(null, "get"), null)).orElseThrow().methodRequired());
        Assert.assertFalse(HttpResourceExtrasResolver.resolve(
                option(null, form("optional", "stringLiteralSegment"))).orElseThrow().pathRequired());
    }

    // ---- fixtures --------------------------------------------------------------------

    private static PresenceValues values(String presence, String... items) {
        return new PresenceValues(presence, List.of(items));
    }

    private static PresenceForm form(String presence, String... items) {
        return new PresenceForm(presence, List.of(items));
    }

    private static TriggerMetadataModel.ServiceType.HandlerOption option(
            PresenceValues method, PresenceForm path) {
        return new TriggerMetadataModel.ServiceType.HandlerOption(
                "*", TriggerMetadataModel.ServiceType.HandlerOption.KIND_RESOURCE, null, null, null, null,
                method, path, null, null, null);
    }
}
