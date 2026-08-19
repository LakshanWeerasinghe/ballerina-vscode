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

import io.ballerina.modelgenerator.commons.trigger.models.TypeRef;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.Set;

/**
 * Tests {@link HandlerParamNameGenerator} — the service-handler parameter name generator used when
 * {@code trigger-metadata.json} leaves a slot's name to the service author.
 *
 * @since 1.7.0
 */
public class HandlerParamNameGeneratorTest {

    private static String gen(String typeName, String alias) {
        return HandlerParamNameGenerator.generate(new TypeRef(typeName, null), false, alias, 0, Set.of());
    }

    /**
     * The real slots across every currently onboarded module whose metadata document states no name.
     */
    @DataProvider(name = "realCorpus")
    public Object[][] realCorpus() {
        return new Object[][]{
                // kafka — the AnydataX|BytesX payload union collapses to one stable name.
                {"AnydataConsumerRecord[]", "kafka", "consumerRecords"},
                {"BytesConsumerRecord[]", "kafka", "consumerRecords"},
                {"Caller", "kafka", "caller"},
                {"Error", "kafka", "kafkaError"},
                // rabbitmq — reproduces the names the retired service-index carried.
                {"AnydataMessage", "rabbitmq", "message"},
                {"BytesMessage", "rabbitmq", "message"},
                {"Error", "rabbitmq", "rabbitmqError"},
                // websub — no UI model ever existed for these.
                {"ContentDistributionMessage", "websub", "contentDistributionMessage"},
                {"SubscriptionVerification", "websub", "subscriptionVerification"},
                {"UnsubscriptionVerification", "websub", "unsubscriptionVerification"},
                {"SubscriptionDeniedError", "websub", "subscriptionDeniedError"},
                {"InternalHubError", "websub", "internalHubError"},
                // ftp / smb watch events.
                {"WatchEvent", "ftp", "watchEvent"},
                // cdc / mssql shapes that do carry authored names today, checked for completeness.
                {"Event", "calendar", "event"},
        };
    }

    @Test(dataProvider = "realCorpus")
    public void testRealCorpusNames(String typeName, String alias, String expected) {
        Assert.assertEquals(gen(typeName, alias), expected);
    }

    @Test
    public void testErrorRuleUsesModuleAlias() {
        Assert.assertEquals(gen("Error", "solace"), "solaceError");
        // A submodule alias is already reduced by the caller (trigger.github -> github).
        Assert.assertEquals(gen("Error", "github"), "githubError");
        // Only a bare `Error` triggers the rule; a named error type camel-cases normally.
        Assert.assertEquals(gen("PayloadValidationError", "kafka"), "payloadValidationError");
        // No alias available: falls through to the positional name rather than emitting "error".
        Assert.assertEquals(gen("Error", ""), "param1");
        Assert.assertEquals(gen("Error", null), "param1");
    }

    @Test
    public void testPluralization() {
        Assert.assertEquals(gen("Message[]", "mq"), "messages");
        Assert.assertEquals(gen("Entry[]", "db"), "entries");     // consonant + y
        Assert.assertEquals(gen("Delivery[]", "db"), "deliveries");
        Assert.assertEquals(gen("Box[]", "x"), "boxes");
        Assert.assertEquals(gen("Batch[]", "x"), "batches");
        Assert.assertEquals(gen("Status[]", "x"), "statuses");    // already ends in s
        Assert.assertEquals(gen("Key[]", "x"), "keys");           // vowel + y
    }

    @Test
    public void testUnusableTypesFallBack() {
        // Built-ins and anonymous shapes name no domain concept.
        for (String builtin : new String[]{"json", "xml", "string", "byte[]", "record {}",
                "stream<byte[], error?>", "string[][]", "()", "anydata", ""}) {
            Assert.assertEquals(
                    HandlerParamNameGenerator.generate(new TypeRef(builtin, null), false, "ftp", 2, Set.of()),
                    "param3", "unexpected name for " + builtin);
        }
        // Null ref / null name.
        Assert.assertEquals(HandlerParamNameGenerator.generate(null, false, "ftp", 0, Set.of()), "param1");
        Assert.assertEquals(
                HandlerParamNameGenerator.generate(new TypeRef(null, null), false, "ftp", 1, Set.of()),
                "param2");
    }

    @Test
    public void testDataBindingSlotBecomesPayloadOnlyWhenTypeIsUnusable() {
        // Unusable type + data binding -> the idiomatic bound-body name.
        Assert.assertEquals(
                HandlerParamNameGenerator.generate(new TypeRef("json", null), true, "ftp", 0, Set.of()),
                "payload");
        Assert.assertEquals(
                HandlerParamNameGenerator.generate(new TypeRef("record {}", null), true, "cdc", 0, Set.of()),
                "payload");
        // A usable type name always wins over the generic "payload".
        Assert.assertEquals(
                HandlerParamNameGenerator.generate(new TypeRef("AnydataConsumerRecord[]", null), true,
                        "kafka", 0, Set.of()),
                "consumerRecords");
        // "payload" itself already taken by a sibling -> positional.
        Assert.assertEquals(
                HandlerParamNameGenerator.generate(new TypeRef("json", null), true, "ftp", 1,
                        Set.of("payload")),
                "param2");
    }

    @Test
    public void testReservedWordsAndCollisions() {
        // A module-declared type whose camel form is a keyword must never be emitted bare.
        Assert.assertEquals(gen("Service", "http"), "param1");
        Assert.assertEquals(gen("Table", "sql"), "param1");
        Assert.assertEquals(gen("Type", "x"), "param1");
        Assert.assertEquals(gen("Client", "x"), "param1");
        // Collision with a sibling's authored name falls back positionally.
        Assert.assertEquals(HandlerParamNameGenerator.generate(new TypeRef("Caller", null), false,
                "kafka", 1, Set.of("caller")), "param2");
        // The payload-shape prefix itself is not stripped away to nothing.
        Assert.assertEquals(gen("Anydata", "kafka"), "param1");   // camel form is a keyword
        Assert.assertEquals(gen("Bytes", "kafka"), "bytes");
    }

    @Test
    public void testErrorRuleKeepsArrayNessAndHandlesShapedAliases() {
        // An array of errors is pluralized like any other array type.
        Assert.assertEquals(gen("Error[]", "kafka"), "kafkaErrors");
        // A payload-shaped alias resolves the same way a bare Error does (prefix stripped first).
        Assert.assertEquals(gen("AnydataError", "kafka"), "kafkaError");
        Assert.assertEquals(gen("BytesError", "rabbitmq"), "rabbitmqError");
        Assert.assertEquals(gen("AnydataError[]", "kafka"), "kafkaErrors");
    }

    @Test
    public void testPositionalFallbackAdvancesPastUsedNames() {
        // A sibling authored literally as "param1" must not be shadowed by the fallback.
        Assert.assertEquals(HandlerParamNameGenerator.generate(new TypeRef("json", null), false,
                "ftp", 0, Set.of("param1")), "param2");
        Assert.assertEquals(HandlerParamNameGenerator.generate(new TypeRef("json", null), false,
                "ftp", 0, Set.of("param1", "param2", "param3")), "param4");
    }

    @Test
    public void testNewlyReservedWordsRejected() {
        for (String typeName : new String[]{"Default", "Parameter", "Transactional", "Typeof",
                "Ascending", "Descending", "Base16", "Base64"}) {
            Assert.assertEquals(gen(typeName, "x"), "param1", "expected rejection for " + typeName);
        }
    }

    @Test
    public void testDeterminism() {
        for (int i = 0; i < 5; i++) {
            Assert.assertEquals(gen("AnydataConsumerRecord[]", "kafka"), "consumerRecords");
        }
    }
}
