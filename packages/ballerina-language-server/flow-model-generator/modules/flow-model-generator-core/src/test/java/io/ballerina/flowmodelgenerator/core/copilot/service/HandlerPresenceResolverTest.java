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

import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.Optional;

/**
 * Pins spec §5's scoping of {@code options[].presence}: "Only under {@code addMode: subset}".
 *
 * @since 1.7.0
 */
public class HandlerPresenceResolverTest {

    private static final String SUBSET = "subset";
    private static final String MANY = "many";

    @Test
    public void testUnderSubsetPresenceIsStatedInBothDirections() {
        // §10: `handlers.options[].presence` | `required`, `optional`. Both must be expressible, because the
        // whole point is telling a mandatory handler from a skippable one.
        Assert.assertEquals(HandlerPresenceResolver.resolveOptional("optional", SUBSET),
                Optional.of(true));
        Assert.assertEquals(HandlerPresenceResolver.resolveOptional("required", SUBSET),
                Optional.of(false));
    }

    @Test
    public void testUnderManyPresenceIsOmittedRatherThanGuessed() {
        // §5: "presence | Only under `addMode: subset`". Under `many` the catalog is open-ended and
        // user-named, so the document is not answering "is this handler required" at all.
        // Corpus: grpc's four options and http's/mcp's wildcards carry no presence.
        Assert.assertTrue(HandlerPresenceResolver.resolveOptional(null, MANY).isEmpty());
        // Even if a `many`-mode document did state one, it is out of scope and must not be read.
        Assert.assertTrue(HandlerPresenceResolver.resolveOptional("required", MANY).isEmpty(),
                "A presence stated outside its scope must not oblige generated code");
        Assert.assertTrue(HandlerPresenceResolver.resolveOptional("optional", MANY).isEmpty());
    }

    @Test
    public void testAnAbsentAddModeIsNotSubsetSoNothingIsStated() {
        // `addMode` is "absent when `backedByConcreteType` is `true`" (§4), where handlers come from the
        // semantic model and presence has no meaning.
        Assert.assertTrue(HandlerPresenceResolver.resolveOptional("required", null).isEmpty());
        Assert.assertTrue(HandlerPresenceResolver.resolveOptional("optional", "").isEmpty());
    }

    @Test
    public void testAnUnrecognisedPresenceTermIsNotGuessedInEitherDirection() {
        // Reading an unknown term as `required` could oblige code to implement an optional handler; reading
        // it as `optional` could omit a mandatory one. Saying nothing is the only safe degradation.
        Assert.assertTrue(HandlerPresenceResolver.resolveOptional("recommended", SUBSET).isEmpty());
        Assert.assertTrue(HandlerPresenceResolver.resolveOptional(null, SUBSET).isEmpty());
        Assert.assertTrue(HandlerPresenceResolver.resolveOptional("", SUBSET).isEmpty());
        Assert.assertTrue(HandlerPresenceResolver.resolveOptional("Required", SUBSET).isEmpty(),
                "The vocabulary is case-sensitive");
    }

    @Test
    public void testTheThreeStatesAreDistinguishable() {
        // The reason the result is Optional<Boolean> and not boolean: "required", "optional" and "not
        // stated" are three different facts, and collapsing any two loses the P4 goal.
        Optional<Boolean> required = HandlerPresenceResolver.resolveOptional("required", SUBSET);
        Optional<Boolean> optional = HandlerPresenceResolver.resolveOptional("optional", SUBSET);
        Optional<Boolean> unstated = HandlerPresenceResolver.resolveOptional("required", MANY);
        Assert.assertNotEquals(required, optional);
        Assert.assertNotEquals(required, unstated);
        Assert.assertNotEquals(optional, unstated);
    }
}
