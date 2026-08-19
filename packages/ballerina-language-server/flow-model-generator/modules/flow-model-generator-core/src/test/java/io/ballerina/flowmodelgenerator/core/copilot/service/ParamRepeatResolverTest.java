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

/**
 * Conformance tests for <b>spec §7 {@code params[].addMode}</b>, written against the spec text.
 *
 * <p>Spec statement pinned: "{@code addMode} | Optional {@code \"many\"} — slot repeats zero or more
 * times, each occurrence independently named/typed (HTTP query/header, MCP tool args). Absent = at most
 * one."
 *
 * <p>The consequence the corpus cares about is what "each occurrence independently named" means for a
 * renderer: the document states no name for the slot, so it has no place in a fixed signature. Before this
 * component such a slot was dropped before it reached the wire at all, which cost {@code mcp} three union
 * type surfaces and three {@code @http:Header} obligations.
 *
 * @since 1.7.0
 */
public class ParamRepeatResolverTest {

    @Test
    public void testOnlyManyMarksASlotRepeatable() {
        // §10's vocabulary for this key has exactly one value.
        Assert.assertTrue(ParamRepeatResolver.isRepeatable(
                TriggerMetadataModel.ServiceType.Handlers.ADD_MODE_MANY));
    }

    @Test
    public void testAbsentAddModeMeansAtMostOne() {
        // §7: "Absent = at most one."
        Assert.assertFalse(ParamRepeatResolver.isRepeatable((String) null));
    }

    @Test
    public void testAnUnrecognisedTermIsNotRepeatable() {
        // Reading an unknown term as repeatable would take a real parameter out of the signature, which
        // is strictly worse than leaving an open-ended slot in it: the first loses code that compiles.
        Assert.assertFalse(ParamRepeatResolver.isRepeatable("subset"));
        Assert.assertFalse(ParamRepeatResolver.isRepeatable("Many"));
    }

    @Test
    public void testASlotWithoutAnAddModeIsNotRepeatable() {
        Assert.assertFalse(ParamRepeatResolver.isRepeatable(param(null)));
        Assert.assertFalse(ParamRepeatResolver.isRepeatable((TriggerMetadataModel.ServiceType.Param) null));
    }

    @Test
    public void testARepeatableSlotIsMarkedOnTheWire() {
        Assert.assertTrue(contribute(param("many")).get("repeatable").getAsBoolean());
    }

    @Test
    public void testANonRepeatableSlotStatesNothing() {
        // The omission rule: "at most one" is the default and is never restated.
        Assert.assertFalse(contribute(param(null)).has("repeatable"));
    }

    @Test
    public void testAConcreteMethodParameterIsNeverRepeatable() {
        // A declared parameter either exists or does not; the notion has no meaning for it, so the aspect
        // must not read a metadata key that is not there.
        ParamDraft draft = new ParamDraft();
        new ParamRepeatAspect().contribute(
                new ParamScope(null, null,
                        new TriggerSemanticFacts.DeclaredParam("caller", "Caller", "", false), 0,
                        java.util.Set.of()),
                draft);
        Assert.assertFalse(draft.toJson().has("repeatable"));
    }

    // ---- fixtures --------------------------------------------------------------------

    private static JsonObject contribute(TriggerMetadataModel.ServiceType.Param param) {
        ParamDraft draft = new ParamDraft();
        new ParamRepeatAspect().contribute(
                new ParamScope(null, param, null, 0, java.util.Set.of()), draft);
        return draft.toJson();
    }

    private static TriggerMetadataModel.ServiceType.Param param(String addMode) {
        return new TriggerMetadataModel.ServiceType.Param(null, List.of(new TypeRef("anydata", null)),
                "optional", addMode, null, null);
    }
}
