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
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Optional;

/**
 * Pins spec §3's {@code identifier} slot and §10's vocabulary for its {@code form}.
 *
 * @since 1.7.0
 */
public class IdentifierResolverTest {

    @Test
    public void testAnAbsentKeyMeansTheSlotCarriesNoMeaning() {
        // §3: "Omit the whole key if the identifier slot carries no meaning for this connector. Present only
        // when genuinely consulted." Corpus: ftp, kafka, mssql.cdc, grpc, trigger.* omit it.
        Assert.assertEquals(IdentifierResolver.resolve(null), Optional.empty());
    }

    @Test
    public void testSpec10VocabularyIsBasePathAndStringLiteral() {
        // §10: `serviceTypes[].identifier.form` | `basePath`, `stringLiteral`. This is the ONE form slot the
        // spec enumerates — path.form and fieldName.form are deliberately not validated anywhere.
        Assert.assertEquals(IdentifierResolver.resolve(form("required", "basePath"))
                .orElseThrow().form(), IdentifierResolver.IdentifierForm.BASE_PATH);
        Assert.assertEquals(IdentifierResolver.resolve(form("optional", "stringLiteral"))
                .orElseThrow().form(), IdentifierResolver.IdentifierForm.STRING_LITERAL);
    }

    @Test
    public void testPresenceIsCarriedInBothDirections() {
        // Corpus: graphql/http/websocket declare `required`; mcp/rabbitmq/smb/websub declare `optional`.
        // A required slot the model omits produces a service that does not do what was asked.
        Assert.assertTrue(IdentifierResolver.resolve(form("required", "basePath")).orElseThrow().required());
        Assert.assertFalse(IdentifierResolver.resolve(form("optional", "basePath")).orElseThrow().required());
    }

    @Test
    public void testAnUnrecognisedPresenceIsNotReadAsRequired() {
        // Asserting an obligation the document did not state would make the model fill a slot the connector
        // ignores.
        Assert.assertFalse(IdentifierResolver.resolve(form("recommended", "basePath"))
                .orElseThrow().required());
        Assert.assertFalse(IdentifierResolver.resolve(form(null, "basePath")).orElseThrow().required());
    }

    @Test
    public void testAnUnknownFormDegradesToUnknownRatherThanThrowing() {
        // The renderer then states that the slot is consulted, without inventing a placeholder whose syntax
        // it cannot know. Losing the whole service over one token would be far worse.
        IdentifierResolver.IdentifierSlot slot =
                IdentifierResolver.resolve(form("required", "regexPattern")).orElseThrow();
        Assert.assertEquals(slot.form(), IdentifierResolver.IdentifierForm.UNKNOWN);
        Assert.assertTrue(slot.required(), "An unknown form must not lose the presence alongside it");
    }

    @Test
    public void testTheRawTokenSurvivesSoTheRendererCanNameIt() {
        // `forms` deliberately carries the document's own tokens, not resolved enum names: an unrecognised
        // form must still be nameable in the note the renderer emits.
        Assert.assertEquals(IdentifierResolver.resolve(form("required", "regexPattern"))
                .orElseThrow().forms(), List.of("regexPattern"));
        Assert.assertEquals(IdentifierResolver.resolve(form("optional", "stringLiteral"))
                .orElseThrow().forms(), List.of("stringLiteral"));
    }

    @Test
    public void testTheFirstFormIsTheOneRendered() {
        // Spec §1's "first element is the codegen default", applied to a form list. No corpus document lists
        // more than one, so this pins the rule rather than an observation.
        IdentifierResolver.IdentifierSlot slot = IdentifierResolver.resolve(
                new PresenceForm("required", List.of("stringLiteral", "basePath"))).orElseThrow();
        Assert.assertEquals(slot.form(), IdentifierResolver.IdentifierForm.STRING_LITERAL);
        Assert.assertEquals(slot.forms(), List.of("stringLiteral", "basePath"),
                "Every legal form is kept so the renderer can state the alternatives");
    }

    @Test
    public void testAKeyPresentButNamingNoFormYieldsNothing() {
        // The repo schema requires `form`, so this is a malformed document. Describing a slot whose syntax is
        // entirely unknown would put a placeholder in the prompt with nothing to say about it.
        Assert.assertEquals(IdentifierResolver.resolve(new PresenceForm("required", null)), Optional.empty());
        Assert.assertEquals(IdentifierResolver.resolve(new PresenceForm("required", List.of())),
                Optional.empty());
        Assert.assertEquals(IdentifierResolver.resolve(new PresenceForm("required", List.of("  "))),
                Optional.empty());
    }

    private static PresenceForm form(String presence, String form) {
        return new PresenceForm(presence, List.of(form));
    }
}
