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
import java.util.Set;

/**
 * Pins spec §6's two rule kinds and three member shapes.
 *
 * <p>Every case names the corpus rule it reproduces, or states that it is synthetic — §6 has only five real
 * instances, so the degradation paths need hand-built fixtures.
 *
 * @since 1.7.0
 */
public class ConstraintResolverTest {

    private static final String LIB = "ballerinax/test";
    private static final Set<String> HANDLERS = Set.of("onMessage", "onRequest", "onTextMessage");

    /**
     * Spec §8's registry, as rabbitmq and smb declare it: the id is lowercase, the annotation it names is
     * not. Keeping them distinct is what makes the resolution observable.
     */
    private static final AnnotationRegistry REGISTRY = AnnotationRegistry.of(new TriggerMetadataModel(null,
            null, null,
            List.of(new TriggerMetadataModel.Annotation("serviceConfig",
                    new TypeRef("ServiceConfig", null),
                    TriggerMetadataModel.Annotation.ATTACH_POINT_SERVICE, null,
                    TriggerMetadataModel.Annotation.PRESENCE_OPTIONAL)),
            null));

    @Test
    public void testOneOfMeansExactlyOne() {
        // §6: `oneOf` | "Exactly one member — not zero, not more than one."
        // Corpus: rabbitmq's messageHandlerChoice.
        List<ConstraintResolver.Constraint> resolved = ConstraintResolver.resolve(LIB,
                List.of(rule("messageHandlerChoice", TriggerMetadataModel.ServiceType.Rule.TYPE_ONE_OF,
                        handler("onMessage"), handler("onRequest"))),
                HANDLERS, REGISTRY);
        Assert.assertEquals(resolved.size(), 1);
        Assert.assertEquals(resolved.get(0).kind(), ConstraintResolver.Kind.EXACTLY_ONE);
        Assert.assertEquals(resolved.get(0).id(), "messageHandlerChoice");
        Assert.assertEquals(resolved.get(0).members().size(), 2);
    }

    @Test
    public void testAtMostOneMeansZeroOrOne() {
        // §6: `atMostOne` | "Zero or one member — never more than one, but zero is fine."
        // Corpus: websocket's textMessageVsGeneric. The distinction from oneOf is load-bearing — websocket
        // does NOT require either handler, and reading this as oneOf would invent an obligation.
        List<ConstraintResolver.Constraint> resolved = ConstraintResolver.resolve(LIB,
                List.of(rule("textMessageVsGeneric",
                        TriggerMetadataModel.ServiceType.Rule.TYPE_AT_MOST_ONE,
                        handler("onMessage"), handler("onTextMessage"))),
                HANDLERS, REGISTRY);
        Assert.assertEquals(resolved.get(0).kind(), ConstraintResolver.Kind.AT_MOST_ONE);
    }

    @Test
    public void testTheTwoKindsAreNotCollapsed() {
        // Stated as its own case because collapsing them is the one mistake that silently changes meaning in
        // both directions at once.
        Assert.assertNotEquals(ConstraintResolver.Kind.EXACTLY_ONE, ConstraintResolver.Kind.AT_MOST_ONE);
    }

    @Test
    public void testTheAnnotationFieldMemberShapeCarriesItsIdFieldAndPreferred() {
        // §6: `{ "annotation": id, "field": name, "preferred"?: true }`.
        // Corpus: rabbitmq's queueNameSource — {annotation: serviceConfig, field: queueName, preferred: true}
        //                                       vs {part: identifier}.
        List<ConstraintResolver.Constraint> resolved = ConstraintResolver.resolve(LIB,
                List.of(rule("queueNameSource", TriggerMetadataModel.ServiceType.Rule.TYPE_ONE_OF,
                        annotationField("serviceConfig", "queueName", true), identifier())),
                HANDLERS, REGISTRY);
        List<ConstraintResolver.Member> members = resolved.get(0).members();
        ConstraintResolver.Member.AnnotationField field =
                (ConstraintResolver.Member.AnnotationField) members.get(0);
        Assert.assertEquals(field.annotationId(), "serviceConfig");
        Assert.assertEquals(field.field(), "queueName");
        Assert.assertTrue(field.preferred(), "`preferred` marks the canonical choice and must survive");
        Assert.assertTrue(members.get(1) instanceof ConstraintResolver.Member.Identifier);
    }

    @Test
    public void testPreferredDefaultsToFalseWhenAbsent() {
        // §6 marks only the canonical alternative, so absence must not read as preferred.
        List<ConstraintResolver.Constraint> resolved = ConstraintResolver.resolve(LIB,
                List.of(rule("r", TriggerMetadataModel.ServiceType.Rule.TYPE_ONE_OF,
                        annotationField("serviceConfig", "path", null), identifier())),
                HANDLERS, REGISTRY);
        ConstraintResolver.Member.AnnotationField field =
                (ConstraintResolver.Member.AnnotationField) resolved.get(0).members().get(0);
        Assert.assertFalse(field.preferred());
    }

    @Test
    public void testTheIdentifierMemberShapeIsRecognisedByItsPart() {
        // §6: `{ "part": "identifier" }` — "this service type's identifier". Corpus: rabbitmq, smb.
        List<ConstraintResolver.Constraint> resolved = ConstraintResolver.resolve(LIB,
                List.of(rule("pathSource", TriggerMetadataModel.ServiceType.Rule.TYPE_ONE_OF,
                        annotationField("serviceConfig", "path", true), identifier())),
                HANDLERS, REGISTRY);
        Assert.assertTrue(resolved.get(0).members().get(1) instanceof ConstraintResolver.Member.Identifier);
    }

    @Test
    public void testAHandlerMemberNamingAnUndeclaredHandlerIsDropped() {
        // §6: a `{handler}` member names "one of this service type's own `handlers.options[].name`". A member
        // naming something absent could never be satisfied, so offering it as a choice would mislead.
        List<ConstraintResolver.Constraint> resolved = ConstraintResolver.resolve(LIB,
                List.of(rule("r", TriggerMetadataModel.ServiceType.Rule.TYPE_ONE_OF,
                        handler("onMessage"), handler("onGhost"), handler("onRequest"))),
                HANDLERS, REGISTRY);
        List<String> names = resolved.get(0).members().stream()
                .map(member -> ((ConstraintResolver.Member.Handler) member).name())
                .toList();
        Assert.assertEquals(names, List.of("onMessage", "onRequest"));
    }

    @Test
    public void testARuleLeftWithFewerThanTwoUsableMembersIsDroppedWhole() {
        // "Choose exactly one of: onMessage" is not a constraint a reader can act on.
        Assert.assertTrue(ConstraintResolver.resolve(LIB,
                List.of(rule("r", TriggerMetadataModel.ServiceType.Rule.TYPE_ONE_OF,
                        handler("onMessage"), handler("onGhost"))),
                HANDLERS, REGISTRY).isEmpty());
    }

    @Test
    public void testANullHandlerSetSuppressesTheCrossCheckRatherThanDroppingEveryMember() {
        // When the catalog is not knowable, a rule must degrade to "stated but unverified" — not to empty.
        // An empty set, by contrast, genuinely means "this service type declares no handlers".
        Assert.assertEquals(ConstraintResolver.resolve(LIB,
                List.of(rule("r", TriggerMetadataModel.ServiceType.Rule.TYPE_ONE_OF,
                        handler("onMessage"), handler("onRequest"))),
                null, REGISTRY).size(), 1);
        Assert.assertTrue(ConstraintResolver.resolve(LIB,
                List.of(rule("r", TriggerMetadataModel.ServiceType.Rule.TYPE_ONE_OF,
                        handler("onMessage"), handler("onRequest"))),
                Set.of(), REGISTRY).isEmpty());
    }

    @Test
    public void testAnUnknownRuleKindIsSkippedRatherThanSwallowedSilently() {
        // Synthetic: no corpus instance. A future rule kind must degrade visibly — the resolver warns and
        // emits nothing, so a constraint never half-renders as though it were oneOf.
        Assert.assertTrue(ConstraintResolver.resolve(LIB,
                List.of(rule("r", "allOf", handler("onMessage"), handler("onRequest"))),
                HANDLERS, REGISTRY).isEmpty());
        Assert.assertTrue(ConstraintResolver.resolve(LIB,
                List.of(rule("r", null, handler("onMessage"), handler("onRequest"))),
                HANDLERS, REGISTRY).isEmpty());
    }

    @Test
    public void testAMemberPopulatingNoneOfTheThreeShapesIsDropped() {
        // §6: "Exactly one of the three shapes is populated per member." An `{annotation}` with no `field` is
        // not a usable reference into an annotation's record.
        Assert.assertTrue(ConstraintResolver.resolve(LIB,
                List.of(rule("r", TriggerMetadataModel.ServiceType.Rule.TYPE_ONE_OF,
                        annotationField("serviceConfig", null, true),
                        new TriggerMetadataModel.ServiceType.Rule.RuleMember(null, null, null, null, null))),
                HANDLERS, REGISTRY).isEmpty());
    }

    @Test
    public void testDocumentOrderIsPreservedAcrossRulesAndMembers() {
        // §7's "Array order is meaningful" applies to the rendered choice list too: reordering alternatives
        // would move which one reads as primary.
        List<ConstraintResolver.Constraint> resolved = ConstraintResolver.resolve(LIB,
                List.of(rule("first", TriggerMetadataModel.ServiceType.Rule.TYPE_ONE_OF,
                                handler("onMessage"), handler("onRequest")),
                        rule("second", TriggerMetadataModel.ServiceType.Rule.TYPE_AT_MOST_ONE,
                                handler("onTextMessage"), handler("onMessage"))),
                HANDLERS, REGISTRY);
        Assert.assertEquals(resolved.stream().map(ConstraintResolver.Constraint::id).toList(),
                List.of("first", "second"));
        Assert.assertEquals(
                ((ConstraintResolver.Member.Handler) resolved.get(1).members().get(0)).name(),
                "onTextMessage");
    }

    @Test
    public void testNullAndEmptyInputsYieldNoConstraints() {
        // 8 of the 13 corpus documents declare no rules at all.
        Assert.assertTrue(ConstraintResolver.resolve(LIB, null, HANDLERS, REGISTRY).isEmpty());
        Assert.assertTrue(ConstraintResolver.resolve(LIB, List.of(), HANDLERS, REGISTRY).isEmpty());
        Assert.assertTrue(ConstraintResolver.resolve(LIB, Arrays.asList((
                TriggerMetadataModel.ServiceType.Rule) null), HANDLERS, REGISTRY).isEmpty());
    }

    @Test
    public void testAnAnnotationMemberIsResolvedToTheAnnotationNameNotItsRegistryId() {
        // §8 gives `rules[].members[].annotation` as one of the registry's access paths, and the two strings
        // differ: rabbitmq's document says `"annotation": "serviceConfig"` while the annotation a reader has to
        // write is `@rabbitmq:ServiceConfig`. Rendering the id would put a name in the prompt that does not
        // exist, two lines from the real one.
        ConstraintResolver.Member.AnnotationField field =
                (ConstraintResolver.Member.AnnotationField) ConstraintResolver.resolve(LIB,
                        List.of(rule("queueNameSource",
                                TriggerMetadataModel.ServiceType.Rule.TYPE_ONE_OF,
                                annotationField("serviceConfig", "queueName", true), identifier())),
                        HANDLERS, REGISTRY).get(0).members().get(0);
        Assert.assertEquals(field.annotationName(), "ServiceConfig");
        Assert.assertEquals(field.annotationId(), "serviceConfig", "the reference itself is still carried");
    }

    @Test
    public void testAMemberReferencingAnAbsentRegistryEntryIsDropped() {
        // Same policy as a phantom handler: a reference with no entry names no annotation a reader could
        // attach, so offering it as an alternative would mislead.
        Assert.assertTrue(ConstraintResolver.resolve(LIB,
                List.of(rule("r", TriggerMetadataModel.ServiceType.Rule.TYPE_ONE_OF,
                        annotationField("noSuchId", "queueName", true), identifier())),
                HANDLERS, REGISTRY).isEmpty(),
                "one usable member is not a choice, so the rule goes too");
    }

    @Test
    public void testWithNoRegistryTheIdIsKeptSoRuleSemanticsStayTestableAlone() {
        // The resolver stays usable without a document behind it, which is what keeps these tests pure.
        ConstraintResolver.Member.AnnotationField field =
                (ConstraintResolver.Member.AnnotationField) ConstraintResolver.resolve(LIB,
                        List.of(rule("r", TriggerMetadataModel.ServiceType.Rule.TYPE_ONE_OF,
                                annotationField("serviceConfig", "queueName", true), identifier())),
                        HANDLERS, null).get(0).members().get(0);
        Assert.assertEquals(field.annotationName(), "serviceConfig");
    }

    // ---- fixtures --------------------------------------------------------------------

    private static TriggerMetadataModel.ServiceType.Rule rule(
            String id, String type, TriggerMetadataModel.ServiceType.Rule.RuleMember... members) {
        return new TriggerMetadataModel.ServiceType.Rule(id, type, List.of(members));
    }

    private static TriggerMetadataModel.ServiceType.Rule.RuleMember handler(String name) {
        return new TriggerMetadataModel.ServiceType.Rule.RuleMember(null, null, null, null, name);
    }

    private static TriggerMetadataModel.ServiceType.Rule.RuleMember identifier() {
        return new TriggerMetadataModel.ServiceType.Rule.RuleMember(null, null, null,
                TriggerMetadataModel.ServiceType.Rule.RuleMember.PART_IDENTIFIER, null);
    }

    private static TriggerMetadataModel.ServiceType.Rule.RuleMember annotationField(
            String annotation, String field, Boolean preferred) {
        return new TriggerMetadataModel.ServiceType.Rule.RuleMember(annotation, field, preferred, null, null);
    }
}
