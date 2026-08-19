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
import java.util.Set;
import java.util.function.Predicate;

/**
 * Pins spec §9's {@code direct} mode, and in particular the two truncations the sibling consumer performs
 * and this resolver must not.
 *
 * @since 1.7.0
 */
public class DirectModeResolverTest {

    private static final Predicate<String> DECLARES_NONE = name -> false;

    @Test
    public void testEveryTypeConstraintMemberIsKept() {
        // §9: `direct` | fields `typeConstraint`, `excludes?`. Nothing in §9 licenses keeping only the
        // first member — TriggerModelSynthesizer:711 does, and narrowing what is legal is a silent loss.
        // Corpus: ftp/smb's csvContent direct mode declares two.
        DirectModeResolver.Direct direct = DirectModeResolver.resolve(
                mode(List.of(ref("string[][]"), ref("record {}[]")), null), "ftp", DECLARES_NONE);
        Assert.assertEquals(direct.typeConstraint(), List.of("string[][]", "record {}[]"));
    }

    @Test
    public void testExcludesIsKeptInFull() {
        // §9's `excludes` is a negative constraint: "the types explicitly disallowed". It is derivable from
        // nothing else, so dropping it (as the sibling does) states the opposite of the document.
        // Corpus: kafka excludes AnydataConsumerRecord from an otherwise-anydata binding.
        DirectModeResolver.Direct direct = DirectModeResolver.resolve(
                mode(List.of(ref("anydata")), List.of(ref("AnydataConsumerRecord"))),
                "kafka", Set.of("AnydataConsumerRecord")::contains);
        Assert.assertEquals(direct.typeConstraint(), List.of("anydata"));
        Assert.assertEquals(direct.excludes(), List.of("kafka:AnydataConsumerRecord"),
                "a declared same-module type is alias-prefixed per spec §1");
    }

    @Test
    public void testAbsentSlotsYieldEmptyListsRatherThanNull() {
        // The omission rule is enforced when writing the wire; a resolver returning null would push
        // null-checks into every consumer.
        DirectModeResolver.Direct direct = DirectModeResolver.resolve(mode(null, null), "ftp",
                DECLARES_NONE);
        Assert.assertTrue(direct.typeConstraint().isEmpty());
        Assert.assertTrue(direct.excludes().isEmpty());
    }

    @Test
    public void testDocumentOrderIsPreserved() {
        // §7's "Array order is meaningful" applies to any list the document authors by hand.
        DirectModeResolver.Direct direct = DirectModeResolver.resolve(
                mode(List.of(ref("json"), ref("xml"), ref("record {}")), null), "ftp", DECLARES_NONE);
        Assert.assertEquals(direct.typeConstraint(), List.of("json", "xml", "record {}"));
    }

    @Test
    public void testACrossModuleMemberCarriesItsOwnAlias() {
        // §1: a `packageInfo`-carrying reference "isn't from this file's own home module" and is written
        // with that module's alias.
        TypeRef foreign = new TypeRef("Request",
                new TypeRef.PackageInfo("ballerina", "http", "http", "2.16.5"));
        DirectModeResolver.Direct direct = DirectModeResolver.resolve(
                mode(List.of(foreign), null), "mcp", DECLARES_NONE);
        Assert.assertEquals(direct.typeConstraint(), List.of("http:Request"));
    }

    // ---- fixtures --------------------------------------------------------------------

    private static TriggerMetadataModel.DataBindingRule.SupportedMode mode(List<TypeRef> typeConstraint,
                                                                           List<TypeRef> excludes) {
        return new TriggerMetadataModel.DataBindingRule.SupportedMode(
                TriggerMetadataModel.DataBindingRule.SupportedMode.MODE_DIRECT, typeConstraint, excludes,
                null, null);
    }

    private static TypeRef ref(String name) {
        return new TypeRef(name, null);
    }
}
