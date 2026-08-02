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
 * Pins spec §5's GraphQL resource extras — {@code accessor}, {@code fieldName}, {@code graphqlOperation}.
 *
 * @since 1.7.0
 */
public class GraphqlResourceExtrasResolverTest {

    @Test
    public void testGraphqlsQueryFieldCarriesAccessorFieldNameAndOperation() {
        // Corpus: graphql's first "*" — accessor {required, [get]}, fieldName {required, [identifierSegment]},
        // graphqlOperation "query".
        GraphqlResourceExtrasResolver.GraphqlExtras extras = GraphqlResourceExtrasResolver.resolve(
                option(TriggerMetadataModel.ServiceType.HandlerOption.KIND_RESOURCE,
                        values("required", "get"), form("required", "identifierSegment"), "query"))
                .orElseThrow();
        Assert.assertEquals(extras.accessorValues(), List.of("get"));
        Assert.assertEquals(extras.fieldNameForm(), List.of("identifierSegment"));
        Assert.assertEquals(extras.operation(), "query");
        Assert.assertTrue(extras.accessorRequired());
        Assert.assertTrue(extras.fieldNameRequired());
        Assert.assertTrue(extras.hasFieldName());
    }

    @Test
    public void testTheMutationHandlerIsRemoteAndStillKeepsItsExtras() {
        // Corpus: graphql's second "*" is {"kind": "remote", fieldName: {...}, graphqlOperation: "mutation"}
        // and declares NO accessor. Spec §5 calls these "resource-kind extras", but a GraphQL mutation
        // genuinely is a remote method whose name is the field name — so gating on kind would drop its
        // field-name constraint entirely. This is the mismatch worth raising with the spec author.
        GraphqlResourceExtrasResolver.GraphqlExtras extras = GraphqlResourceExtrasResolver.resolve(
                option(TriggerMetadataModel.ServiceType.HandlerOption.KIND_REMOTE,
                        null, form("required", "identifierSegment"), "mutation")).orElseThrow();
        Assert.assertTrue(extras.accessorValues().isEmpty());
        Assert.assertEquals(extras.fieldNameForm(), List.of("identifierSegment"));
        Assert.assertEquals(extras.operation(), "mutation");
    }

    @Test
    public void testTheSubscriptionFieldUsesTheSubscribeAccessor() {
        // Corpus: graphql's third "*" — accessor {required, [subscribe]}, graphqlOperation "subscription".
        // The three "*" entries are three DISTINCT operations, not duplicates.
        GraphqlResourceExtrasResolver.GraphqlExtras extras = GraphqlResourceExtrasResolver.resolve(
                option(TriggerMetadataModel.ServiceType.HandlerOption.KIND_RESOURCE,
                        values("required", "subscribe"), form("required", "identifierSegment"),
                        "subscription")).orElseThrow();
        Assert.assertEquals(extras.accessorValues(), List.of("subscribe"));
        Assert.assertEquals(extras.operation(), "subscription");
    }

    @Test
    public void testIdentifierSegmentIsAFieldNameFormNotAnIdentifierForm() {
        // `identifierSegment` is outside spec §10's identifier vocabulary by design: it describes a GraphQL
        // field name, not a service identifier. It is passed through verbatim, and IdentifierResolver would
        // (correctly) call the same token UNKNOWN in its own slot.
        Assert.assertEquals(GraphqlResourceExtrasResolver.resolve(
                        option(TriggerMetadataModel.ServiceType.HandlerOption.KIND_RESOURCE, null,
                                form("required", "identifierSegment"), null))
                .orElseThrow().fieldNameForm(), List.of("identifierSegment"));
        Assert.assertEquals(IdentifierResolver.resolve(form("required", "identifierSegment"))
                .orElseThrow().form(), IdentifierResolver.IdentifierForm.UNKNOWN);
    }

    @Test
    public void testTheOperationAloneIsEnoughToProduceExtras() {
        // graphqlOperation is informational (§5, §11.2) and renders as a comment — but it is still the only
        // thing that tells the model whether a field is a query, a mutation or a subscription, so it must not
        // be dropped just because the handler constrains nothing else.
        Assert.assertEquals(GraphqlResourceExtrasResolver.resolve(
                        option(TriggerMetadataModel.ServiceType.HandlerOption.KIND_REMOTE, null, null,
                                "mutation"))
                .orElseThrow().operation(), "mutation");
    }

    @Test
    public void testNoExtrasWhenTheHandlerDeclaresNoneOfTheThree() {
        // The omission rule: an ordinary remote handler must not acquire an empty GraphQL block.
        Assert.assertTrue(GraphqlResourceExtrasResolver.resolve(
                option(TriggerMetadataModel.ServiceType.HandlerOption.KIND_REMOTE, null, null, null))
                .isEmpty());
        Assert.assertTrue(GraphqlResourceExtrasResolver.resolve(null).isEmpty());
        Assert.assertTrue(GraphqlResourceExtrasResolver.resolve(
                option(TriggerMetadataModel.ServiceType.HandlerOption.KIND_RESOURCE, values("required"),
                        form("required"), "  ")).isEmpty(),
                "Empty vocabularies and a blank operation carry no information");
    }

    @Test
    public void testPresenceIsCarriedAndOnlyAnExplicitOptionalIsOptional() {
        Assert.assertFalse(GraphqlResourceExtrasResolver.resolve(
                        option(TriggerMetadataModel.ServiceType.HandlerOption.KIND_RESOURCE, null,
                                form("optional", "identifierSegment"), null))
                .orElseThrow().fieldNameRequired());
        Assert.assertTrue(GraphqlResourceExtrasResolver.resolve(
                        option(TriggerMetadataModel.ServiceType.HandlerOption.KIND_RESOURCE, null,
                                form("unheardOf", "identifierSegment"), null))
                .orElseThrow().fieldNameRequired());
    }

    // ---- fixtures --------------------------------------------------------------------

    private static PresenceValues values(String presence, String... items) {
        return new PresenceValues(presence, List.of(items));
    }

    private static PresenceForm form(String presence, String... items) {
        return new PresenceForm(presence, List.of(items));
    }

    private static TriggerMetadataModel.ServiceType.HandlerOption option(
            String kind, PresenceValues accessor, PresenceForm fieldName, String operation) {
        return new TriggerMetadataModel.ServiceType.HandlerOption(
                "*", kind, null, null, null, null, null, null, accessor, fieldName, operation);
    }
}
