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
import io.ballerina.modelgenerator.commons.trigger.models.TypeRef;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Conformance tests for <b>Spec §5 {@code options[].returns}</b>, written against the spec text rather
 * than the implementation.
 *
 * <p>Spec statements pinned by this class:
 * <ul>
 *   <li>§5 {@code returns} — "{@code TypeRef} or array"; a handler's return genuinely <i>is</i> the union,
 *       so joining the members is correct here (and only here).</li>
 *   <li>§1 nilable — "{@code T?} = {@code T|()} is an explicit {@code ()} union member, not a separate
 *       flag", which is what makes {@code error|()} canonicalize to {@code error?}.</li>
 *   <li>General rule — "a field that would be empty, unused, or fully derivable from other fields is left
 *       out": a nil-only return says nothing and is omitted.</li>
 * </ul>
 *
 * @since 1.7.0
 */
public class ReturnResolverTest {

    private static final Predicate<String> NONE = name -> false;

    @Test
    public void testNilableUnionCanonicalizesToTheQuestionMarkForm() {
        // The corpus shape: `"returns": [{"name": "error"}, {"name": "()"}]`.
        Assert.assertEquals(signatureOf(List.of(new TypeRef("error", null), new TypeRef("()", null))),
                "error|()");
        Assert.assertEquals(typeNameOf("error|()"), "error?");
    }

    @Test
    public void testNilOnlyReturnIsOmitted() {
        // mssql.cdc's onError returns `()`. A nil return carries no information, so per the general
        // omission rule no `return` key is emitted at all.
        Assert.assertEquals(ReturnResolver.resolve("()", "mssql"), Optional.empty());
    }

    @Test
    public void testAbsentReturnIsOmitted() {
        Assert.assertEquals(ReturnResolver.resolve("", "kafka"), Optional.empty());
        Assert.assertEquals(ReturnResolver.resolve(null, "kafka"), Optional.empty());
        Assert.assertEquals(signatureOf(List.of()), "");
        Assert.assertEquals(signatureOf(null), "");
    }

    @Test
    public void testMultiMemberUnionKeepsEveryMember() {
        // websub's onEventNotification: three real alternatives, none of which may be dropped.
        Assert.assertEquals(signatureOf(List.of(
                new TypeRef("Acknowledgement", null),
                new TypeRef("SubscriptionDeletedError", null),
                new TypeRef("()", null))), "Acknowledgement|SubscriptionDeletedError|()");
    }

    @Test
    public void testHomeModuleReturnTypesAreLinkedBackToTheLibrary() {
        // A return naming one of the library's own types must resolve to a link, so the prompt also
        // carries that type's definition rather than naming a type it never defines.
        JsonObject resolved = ReturnResolver.resolve("kafka:Error", "kafka").orElseThrow();
        JsonObject type = resolved.getAsJsonObject("type");
        Assert.assertEquals(type.get("name").getAsString(), "Error",
                "The home alias is stripped once the link records it");
        Assert.assertEquals(type.getAsJsonArray("links").get(0).getAsJsonObject()
                .get("recordName").getAsString(), "Error");
    }

    @Test
    public void testCrossModuleReturnTypesAreNotLinked() {
        // §1: a foreign type belongs to another module, so this library has no definition to link to.
        JsonObject type = ReturnResolver.resolve("cdc:Error", "mssql").orElseThrow()
                .getAsJsonObject("type");
        Assert.assertEquals(type.get("name").getAsString(), "cdc:Error");
        Assert.assertFalse(type.has("links"));
    }

    @Test
    public void testUnionMembersAreResolvedPerSpecOne() {
        // Each member follows §1 independently: a declared home type takes the alias, a built-in does not.
        Assert.assertEquals(signatureOf(List.of(new TypeRef("Acknowledgement", null),
                        new TypeRef("()", null)), Set.of("Acknowledgement")::contains, "websub"),
                "websub:Acknowledgement|()");
    }

    private static String signatureOf(List<TypeRef> returns) {
        return signatureOf(returns, NONE, "kafka");
    }

    private static String signatureOf(List<TypeRef> returns, Predicate<String> declaresType, String pkg) {
        return ReturnResolver.signature(returns, pkg, declaresType);
    }

    private static String typeNameOf(String signature) {
        return ReturnResolver.resolve(signature, "kafka").orElseThrow()
                .getAsJsonObject("type").get("name").getAsString();
    }
}
