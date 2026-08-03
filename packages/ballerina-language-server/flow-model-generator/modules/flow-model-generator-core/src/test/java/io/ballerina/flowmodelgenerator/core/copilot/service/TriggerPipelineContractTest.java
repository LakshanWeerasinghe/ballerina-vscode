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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.ballerina.modelgenerator.commons.trigger.models.TriggerMetadataModel;
import io.ballerina.modelgenerator.commons.trigger.models.TypeRef;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Contract tests for the component pipeline itself — the invariants that must hold no matter which spec
 * constructs are wired up, so that adding one cannot quietly break the others.
 *
 * <p>What is pinned here:
 * <ul>
 *   <li><b>Traceability.</b> Every registered component names the spec section it owns. This is the guard
 *       that keeps "one construct, one owner" honest as the spec grows: a component with no section, or a
 *       duplicated id, means the ownership map has drifted.</li>
 *   <li><b>Tier ordering.</b> The two orderings that carry meaning — identity first, handler catalog last
 *       — rather than the incidental order of the rest.</li>
 *   <li><b>The omission rule.</b> Spec's "a field that would be empty, unused, or fully derivable … is
 *       left out", enforced by the drafts rather than at each call site.</li>
 *   <li><b>The veto contract.</b> A dropped handler must not drop its service, and every drop must carry
 *       an attributable reason.</li>
 * </ul>
 *
 * @since 1.7.0
 */
public class TriggerPipelineContractTest {

    private static final Predicate<String> NONE = name -> false;
    private static final AspectRegistry REGISTRY = AspectRegistry.forVersion(AspectRegistry.VERSION_V1);

    // ---- traceability -------------------------------------------------------------------

    @Test
    public void testEveryComponentDeclaresAnOwnedSpecSection() {
        List<String> ids = new ArrayList<>();
        for (ServiceAspect aspect : REGISTRY.serviceAspects()) {
            assertDeclaresOwnership(aspect.id(), aspect.specSection());
            ids.add("service:" + aspect.id());
        }
        for (HandlerAspect aspect : REGISTRY.handlerAspects()) {
            assertDeclaresOwnership(aspect.id(), aspect.specSection());
            ids.add("handler:" + aspect.id());
        }
        for (ParamAspect aspect : REGISTRY.paramAspects()) {
            assertDeclaresOwnership(aspect.id(), aspect.specSection());
            ids.add("param:" + aspect.id());
        }
        Assert.assertEquals(new HashSet<>(ids).size(), ids.size(),
                "Component ids must be unique — an id is how a construct's owner is named: " + ids);
    }

    private static void assertDeclaresOwnership(String id, String specSection) {
        Assert.assertNotNull(id);
        Assert.assertFalse(id.isBlank(), "A component must have a stable id");
        Assert.assertNotNull(specSection, id + " declares no spec section");
        Assert.assertTrue(specSection.startsWith("§"),
                id + " must name the spec section it owns, got: " + specSection);
    }

    // ---- tier ordering -------------------------------------------------------------------

    @Test
    public void testIdentityRunsFirstAndHandlerCatalogRunsLast() {
        List<ServiceAspect> aspects = REGISTRY.serviceAspects();
        Assert.assertEquals(aspects.get(0).id(), "serviceIdentity",
                "Identity resolves the id every later component is scoped to, and can veto the entry");
        Assert.assertEquals(aspects.get(aspects.size() - 1).id(), "handlerCatalog",
                "The catalog drives the lower tiers, so every service-level contribution precedes it");
    }

    @Test
    public void testAnnotationsAreResolvedAfterIdentityAndBeforeTheHandlerCatalog() {
        // Identity is what can veto the entry, so an obligation resolved for a service type about to be
        // dropped would be output nothing reads; and the catalog drives the lower tiers, so every
        // service-level contribution has to precede it. Not a data dependency in either direction —
        // §8's `appliesTo` matches `serviceTypes[].id`, which the scope already carries.
        List<String> ids = REGISTRY.serviceAspects().stream().map(ServiceAspect::id).toList();
        Assert.assertTrue(ids.contains("serviceAnnotation"), "§8's service scope must be registered: " + ids);
        Assert.assertTrue(ids.indexOf("serviceAnnotation") > ids.indexOf("serviceIdentity"),
                "annotations resolve after the identity that can veto the entry: " + ids);
        Assert.assertTrue(ids.indexOf("serviceAnnotation") < ids.indexOf("handlerCatalog"),
                "every service-level contribution precedes the catalog: " + ids);
    }

    @Test
    public void testEverySpecSectionWiredHasAtLeastOneOwningComponent() {
        // The traceability guard from plan §9.4: a spec section the pipeline claims to serve must name a
        // component that owns it, so a construct cannot be half-wired.
        Set<String> owned = new HashSet<>();
        REGISTRY.serviceAspects().forEach(aspect -> owned.add(aspect.specSection()));
        REGISTRY.handlerAspects().forEach(aspect -> owned.add(aspect.specSection()));
        REGISTRY.paramAspects().forEach(aspect -> owned.add(aspect.specSection()));
        for (String section : List.of("§2", "§3", "§4", "§5", "§6", "§7", "§8", "§9")) {
            Assert.assertTrue(owned.contains(section),
                    "no registered component owns spec " + section + "; owned: " + owned);
        }
    }

    @Test
    public void testEveryConstructAddedForP5HasARegisteredOwner() {
        // Same traceability guard as P4's, extended: each id is the single owner of one spec construct, so
        // deleting a registry line cannot silently unwire it.
        List<String> handlerIds = REGISTRY.handlerAspects().stream().map(HandlerAspect::id).toList();
        List<String> paramIds = REGISTRY.paramAspects().stream().map(ParamAspect::id).toList();

        Assert.assertTrue(handlerIds.containsAll(List.of("handlerAnnotation", "returnAnnotation")),
                "§8's function and return attach points must each name an owner: " + handlerIds);
        Assert.assertTrue(paramIds.containsAll(List.of("paramAnnotation", "dataBinding")),
                "§8's parameter attach point and §9's binding must each name an owner: " + paramIds);
    }

    @Test
    public void testEachAttachPointHasItsOwnComponentRatherThanOneSharedOne() {
        // §8's four attach points are four constructs: only service scope carries the `appliesTo` fallback,
        // only return scope attaches to a different syntactic slot, and each runs in a different tier. One
        // component covering all four would make an attach-point-specific spec change touch every scope.
        List<String> ids = new ArrayList<>();
        REGISTRY.serviceAspects().forEach(aspect -> ids.add(aspect.id()));
        REGISTRY.handlerAspects().forEach(aspect -> ids.add(aspect.id()));
        REGISTRY.paramAspects().forEach(aspect -> ids.add(aspect.id()));
        Assert.assertTrue(ids.containsAll(List.of("serviceAnnotation", "handlerAnnotation",
                "paramAnnotation", "returnAnnotation")), ids.toString());
    }

    @Test
    public void testParamKeyOrderIsOwnedByTheDraftNotByTheRegistry() {
        // The same property HandlerDraft has, now that three components write parameter keys: a component
        // can be registered anywhere without reshuffling the JSON.
        ParamDraft draft = new ParamDraft();
        draft.setBinding(new JsonObject());
        draft.setAnnotationRefs(arrayOf("x"));
        draft.setAlternatives(arrayOf("T"));
        draft.setOptional(true);
        draft.setType(new JsonObject());
        draft.setName("message");
        Assert.assertEquals(new ArrayList<>(draft.toJson().keySet()),
                List.of("name", "type", "optional", "alternatives", "annotationRefs", "binding"));
    }

    @Test
    public void testTheNewParamKeysFollowTheOmissionRule() {
        ParamDraft draft = new ParamDraft();
        draft.setAlternatives(null);
        draft.setAlternatives(new JsonArray());
        draft.setAnnotationRefs(null);
        draft.setAnnotationRefs(new JsonArray());
        Assert.assertFalse(draft.toJson().has("alternatives"),
                "a scalar slot states no alternatives");
        Assert.assertFalse(draft.toJson().has("annotationRefs"),
                "§8's key is optional and most slots carry no annotation");
        Assert.assertFalse(draft.toJson().has("binding"),
                "§7: `dataBinding` is present only when the value can be projected");
    }

    @Test
    public void testReturnAnnotationsAreMergedIntoTheReturnObjectAtEmitTime() {
        // Why the refs are held in their own slot rather than written into the return object directly: it
        // removes the only ordering dependency the handler tier would otherwise have.
        HandlerDraft afterReturn = new HandlerDraft();
        afterReturn.setReturn(new JsonObject());
        afterReturn.setReturnAnnotationRefs(arrayOf("Cache"));

        HandlerDraft beforeReturn = new HandlerDraft();
        beforeReturn.setReturnAnnotationRefs(arrayOf("Cache"));
        beforeReturn.setReturn(new JsonObject());

        Assert.assertEquals(afterReturn.toJson().toString(), beforeReturn.toJson().toString(),
                "registration order must not change the emitted JSON");
        Assert.assertTrue(afterReturn.toJson().getAsJsonObject("return").has("annotationRefs"));
    }

    @Test
    public void testReturnAnnotationsAreDroppedWhenThereIsNoReturnToAttachThemTo() {
        // A return that carries no type carries no annotation either; there is no slot to write.
        HandlerDraft draft = new HandlerDraft();
        draft.setReturnAnnotationRefs(arrayOf("Cache"));
        Assert.assertFalse(draft.toJson().has("return"));
        Assert.assertFalse(draft.toJson().has("annotationRefs"),
                "a return annotation must never be mistaken for a function-scoped one");
    }

    @Test
    public void testEveryConstructAddedForP6HasARegisteredOwner() {
        List<String> serviceIds = REGISTRY.serviceAspects().stream().map(ServiceAspect::id).toList();
        List<String> paramIds = REGISTRY.paramAspects().stream().map(ParamAspect::id).toList();

        Assert.assertTrue(serviceIds.contains("cardinality"),
                "§3's multiple*Allowed pair must name an owner: " + serviceIds);
        Assert.assertTrue(paramIds.contains("paramRepeat"),
                "§7's addMode must name an owner: " + paramIds);
        // §3's alternatives and §4's open-ended catalog are owned by components that already exist, so
        // what is pinned for them is that they did not acquire a second owner.
        Assert.assertEquals(serviceIds.stream().filter("serviceIdentity"::equals).count(), 1);
        Assert.assertEquals(serviceIds.stream().filter("handlerCatalog"::equals).count(), 1);
    }

    @Test
    public void testAddModeAndPresenceAreSeparateOwners() {
        // §7 has four keys and the renderer treats two of them oppositely: `presence` keeps a slot in the
        // signature and notes it, `addMode` takes the slot out entirely. They were one component until the
        // second behaviour existed; merging them again would put one component in charge of both.
        List<String> paramIds = REGISTRY.paramAspects().stream().map(ParamAspect::id).toList();
        Assert.assertTrue(paramIds.containsAll(List.of("paramType", "paramRepeat")), paramIds.toString());
        Assert.assertNotEquals(paramIds.indexOf("paramType"), paramIds.indexOf("paramRepeat"));
    }

    @Test
    public void testCardinalityRunsBetweenIdentityAndTheCatalog() {
        List<String> ids = REGISTRY.serviceAspects().stream().map(ServiceAspect::id).toList();
        Assert.assertTrue(ids.indexOf("cardinality") > ids.indexOf("serviceIdentity"), ids.toString());
        Assert.assertTrue(ids.indexOf("cardinality") < ids.indexOf("handlerCatalog"), ids.toString());
    }

    @Test
    public void testTheP6ServiceKeysFollowTheOmissionRule() {
        ServiceDraft draft = new ServiceDraft();
        draft.setAlternatives(false);
        draft.setHandlerTemplate(null);
        Assert.assertFalse(draft.toJson().has("alternatives"),
                "Spec §3: a sole service type is required, and that is not restated");
        Assert.assertFalse(draft.toJson().has("handlerTemplate"),
                "A fixed vocabulary has no template");
        Assert.assertFalse(draft.toJson().has("singleListenerOnly"),
                "A permissive cardinality states nothing");
        Assert.assertFalse(draft.toJson().has("singleServicePerListenerOnly"));
    }

    @Test
    public void testTheP6ParamKeyFollowsTheOmissionRule() {
        ParamDraft draft = new ParamDraft();
        draft.setRepeatable(false);
        Assert.assertFalse(draft.toJson().has("repeatable"),
                "Spec §7: \"Absent = at most one\" is the default and is never restated");
    }

    @Test
    public void testRepeatableSitsAfterPresenceInTheParamKeyOrder() {
        // Key order stays a property of the draft even as §7 gains a fourth writer.
        ParamDraft draft = new ParamDraft();
        draft.setBinding(new JsonObject());
        draft.setRepeatable(true);
        draft.setAnnotationRefs(arrayOf("x"));
        draft.setAlternatives(arrayOf("T"));
        draft.setOptional(true);
        draft.setType(new JsonObject());
        draft.setName("headerValue");
        Assert.assertEquals(new ArrayList<>(draft.toJson().keySet()),
                List.of("name", "type", "optional", "repeatable", "alternatives", "annotationRefs",
                        "binding"));
    }

    @Test
    public void testAnOpenEndedTemplateIsNeverEmittedAsAMethod() {
        // The separation the whole design rests on: a template has no name, so putting it in `methods`
        // would place an unwritable signature in a list whose every other member is copyable.
        JsonArray methods = TriggerSchemaServiceLoader.buildOptionMethods(
                List.of(option("*")), "Service", NONE, "testmod");
        Assert.assertTrue(methods.isEmpty());
    }

    @Test
    public void testAVetoedTemplateDoesNotVetoItsService() {
        ServiceDraft draft = new ServiceDraft();
        HandlerDraft template = new HandlerDraft();
        template.veto("handlerCatalog", "§4", "Service", "references an undeclared type");
        draft.setHandlerTemplate(template);

        Assert.assertFalse(draft.isVetoed(), "A dropped template must not drop its service");
        Assert.assertFalse(draft.toJson().has("handlerTemplate"));
        Assert.assertEquals(draft.vetoes().size(), 1, "...but the reason is still reported");
    }

    @Test
    public void testEveryConstructAddedForP4HasARegisteredOwner() {
        // The traceability guard made concrete: each of these ids is the single owner of one spec construct,
        // so a construct cannot be silently unwired by deleting its registry line.
        List<String> serviceIds = REGISTRY.serviceAspects().stream().map(ServiceAspect::id).toList();
        List<String> handlerIds = REGISTRY.handlerAspects().stream().map(HandlerAspect::id).toList();
        Assert.assertTrue(serviceIds.containsAll(List.of("identifier", "constraints")),
                "§3's identifier and §6's rules must each name an owner: " + serviceIds);
        Assert.assertTrue(handlerIds.containsAll(List.of(
                        "handlerKind", "handlerPresence", "httpResourceExtras", "graphqlResourceExtras")),
                "§5's kind, presence and the two protocol extras must each name an owner: " + handlerIds);
    }

    @Test
    public void testTheServiceLevelConstructsSitBetweenIdentityAndTheCatalog() {
        // Same reasoning as the annotation ordering: identity can veto the entry, and the catalog drives the
        // lower tiers, so every other service-level contribution belongs between them.
        List<String> ids = REGISTRY.serviceAspects().stream().map(ServiceAspect::id).toList();
        for (String id : List.of("identifier", "constraints")) {
            Assert.assertTrue(ids.indexOf(id) > ids.indexOf("serviceIdentity"), id + ": " + ids);
            Assert.assertTrue(ids.indexOf(id) < ids.indexOf("handlerCatalog"), id + ": " + ids);
        }
    }

    @Test
    public void testHandlerKeyOrderIsOwnedByTheDraftNotByTheRegistry() {
        // HandlerDraft holds every slot as a field and emits them in the wire contract's order, so a
        // component can be registered anywhere without reshuffling the JSON. Without this, splitting `kind`
        // out of `handlerIdentity` would have silently reordered every handler object.
        HandlerDraft draft = new HandlerDraft();
        draft.setReturn(new JsonObject());
        draft.setGraphqlOperation("query");
        draft.setOptional(true);
        draft.setKind("resource");
        draft.setName("onEvent");
        draft.setAccessor("get");
        Assert.assertEquals(new ArrayList<>(draft.toJson().keySet()),
                List.of("name", "type", "optional", "accessor", "graphqlOperation", "return"));
    }

    @Test
    public void testAHandlerPresenceOfRequiredIsEmittedRatherThanOmitted() {
        // Unlike a parameter, a handler needs all three states: `optional: false` is the only way to say
        // "you must implement this", and absence is reserved for "the document is not saying".
        HandlerDraft required = new HandlerDraft();
        required.setOptional(false);
        Assert.assertTrue(required.toJson().has("optional"));
        Assert.assertFalse(required.toJson().get("optional").getAsBoolean());
        Assert.assertFalse(new HandlerDraft().toJson().has("optional"),
                "Never stated unless a component states it");
    }

    @Test
    public void testTheNewServiceLevelKeysFollowTheOmissionRule() {
        ServiceDraft draft = new ServiceDraft();
        draft.setIdentifier(null);
        draft.setConstraints(null);
        draft.setConstraints(new JsonArray());
        Assert.assertFalse(draft.toJson().has("identifier"),
                "Spec §3: omit the whole key when the identifier carries no meaning");
        Assert.assertFalse(draft.toJson().has("constraints"),
                "Spec §6 is optional, and 8 of 13 documents declare no rules");
    }

    @Test
    public void testRegistryIsNotSharedBetweenLibraries() {
        // A component may memoize per-library state (the listener object is built once and shared by
        // identity), so handing two libraries the same registry would leak one's listener into the other.
        Assert.assertNotSame(AspectRegistry.forVersion(AspectRegistry.VERSION_V1),
                AspectRegistry.forVersion(AspectRegistry.VERSION_V1));
    }

    // ---- the omission rule ----------------------------------------------------------------

    @Test
    public void testDraftsOmitEveryFieldWithNothingToSay() {
        // The spec's general rule, enforced once in the drafts: never an empty array, null or placeholder.
        JsonObject service = new ServiceDraft().toJson();
        Assert.assertFalse(service.has("methods"), "A method-less service type is legitimate");
        Assert.assertFalse(service.has("requiredImports"));
        Assert.assertFalse(service.has("serviceTypeModule"), "Absent for a home-module type");

        JsonObject handler = new HandlerDraft().toJson();
        Assert.assertFalse(handler.has("parameters"));
        Assert.assertFalse(handler.has("description"), "Never fabricated for a marker-type handler");
        Assert.assertFalse(handler.has("return"), "A nil return carries no information");

        JsonObject param = new ParamDraft().toJson();
        Assert.assertFalse(param.has("optional"), "`required` is the default and is not restated");
        Assert.assertFalse(param.has("description"));
    }

    @Test
    public void testAnEmptyAnnotationListIsOmittedRatherThanWrittenEmpty() {
        // §8's key is optional, and most service types carry no obligation at all.
        ServiceDraft draft = new ServiceDraft();
        Assert.assertFalse(draft.toJson().has("annotations"));
        draft.setAnnotations(new JsonArray());
        Assert.assertFalse(draft.toJson().has("annotations"));
        draft.setAnnotations(null);
        Assert.assertFalse(draft.toJson().has("annotations"));
    }

    @Test
    public void testEmptyRequiredImportsAreOmittedRatherThanWrittenEmpty() {
        ServiceDraft draft = new ServiceDraft();
        draft.setRequiredImports(new JsonArray());
        draft.setServiceTypeModule("");
        Assert.assertFalse(draft.toJson().has("requiredImports"));
        Assert.assertFalse(draft.toJson().has("serviceTypeModule"));
    }

    // ---- the veto contract ------------------------------------------------------------------

    @Test
    public void testAVetoedHandlerDoesNotVetoItsService() {
        // A service type whose contract is partly unusable still has a usable remainder — websub renders
        // four of its five handlers rather than disappearing.
        ServiceDraft draft = new ServiceDraft();
        HandlerDraft dropped = new HandlerDraft();
        dropped.veto("handlerCatalog", "§4", "onHubError", "references an undeclared type");
        draft.addHandler(dropped);

        Assert.assertFalse(draft.isVetoed(), "A dropped handler must not drop its service");
        Assert.assertFalse(draft.toJson().has("methods"), "The dropped handler is not emitted");
        Assert.assertEquals(draft.vetoes().size(), 1, "...but the reason is still reported");
    }

    @Test
    public void testANonFatalDropReportsWithoutDroppingTheEntry() {
        // The distinction the fatal channel was being used for and could not express. An unresolvable
        // annotation makes the *obligation* unusable, not the service: ServiceAnnotationResolver has always
        // documented "A dropped annotation never drops its service", while the code it called routed the
        // drop through veto() — which makes the loader skip the whole entry.
        ServiceDraft draft = new ServiceDraft();
        draft.drop("serviceAnnotation", "§8", "GhostConfig",
                "not declared as an annotation by the resolved package version");

        Assert.assertFalse(draft.isVetoed(), "the service still renders");
        Assert.assertEquals(draft.vetoes().size(), 1, "...and the reason is still reported");
        Assert.assertEquals(draft.vetoes().get(0).subject(), "GhostConfig");
    }

    @Test
    public void testAHandlerLevelDropIsReportedWithoutDroppingTheHandler() {
        // Same policy one tier down: a mis-filed annotation on a handler must not cost the handler.
        ServiceDraft service = new ServiceDraft();
        HandlerDraft handler = new HandlerDraft();
        handler.setName("onFileJson");
        handler.drop("handlerAnnotation", "§8", "FunctionConfig", "filed at the wrong attach point");
        service.addHandler(handler);

        Assert.assertFalse(service.isVetoed());
        Assert.assertTrue(service.toJson().has("methods"), "the handler still renders");
        Assert.assertEquals(service.vetoes().size(), 1, "the reason reaches the service's report");
    }

    @Test
    public void testAParamLevelDropReachesTheServiceReport() {
        // Three tiers down, the diagnostic must still be attributable rather than swallowed.
        ServiceDraft service = new ServiceDraft();
        HandlerDraft handler = new HandlerDraft();
        ParamDraft param = new ParamDraft();
        param.setName("content");
        param.drop("dataBinding", "§9", "csvContent", "no dataBindingRules[] entry declares the id");
        handler.addParam(param);
        service.addHandler(handler);

        Assert.assertFalse(service.isVetoed());
        Assert.assertEquals(service.vetoes().size(), 1);
        Assert.assertEquals(service.vetoes().get(0).specSection(), "§9");
    }

    @Test
    public void testEveryVetoIsAttributable() {
        // The whole point of replacing an inline `continue`: a drop names its component, the spec section
        // that component owns, what was dropped, and why.
        ServiceDraft draft = new ServiceDraft();
        draft.veto("serviceIdentity", "§3", "Service", "not declared by the resolved package version");
        Veto veto = draft.vetoes().get(0);

        Assert.assertEquals(veto.aspectId(), "serviceIdentity");
        Assert.assertEquals(veto.specSection(), "§3");
        Assert.assertEquals(veto.subject(), "Service");
        Assert.assertTrue(veto.toString().contains("§3") && veto.toString().contains("Service"),
                "A veto must read as a single attributable line: " + veto);
    }

    @Test
    public void testHandlerVetoesAreReportedAlongsideServiceVetoes() {
        ServiceDraft draft = new ServiceDraft();
        draft.veto("serviceIdentity", "§3", "Service", "service-level reason");
        HandlerDraft dropped = new HandlerDraft();
        dropped.veto("handlerCatalog", "§4", "onEvent", "handler-level reason");
        draft.addHandler(dropped);
        Assert.assertEquals(draft.vetoes().size(), 2, "Both levels must reach the report");
    }

    // ---- end-to-end through the real pipeline -------------------------------------------------

    @Test
    public void testHandlersAreBuiltInDocumentOrder() {
        // §7: "Array order is meaningful" — and a handler list read out of order would silently reorder
        // the generated service body.
        JsonArray methods = TriggerSchemaServiceLoader.buildOptionMethods(
                List.of(option("onFirst"), option("onSecond"), option("onThird")),
                "Service", NONE, "testmod");
        Assert.assertEquals(methods.size(), 3);
        Assert.assertEquals(methods.get(0).getAsJsonObject().get("name").getAsString(), "onFirst");
        Assert.assertEquals(methods.get(1).getAsJsonObject().get("name").getAsString(), "onSecond");
        Assert.assertEquals(methods.get(2).getAsJsonObject().get("name").getAsString(), "onThird");
    }

    @Test
    public void testAWildcardHandlerContributesNoFixedSignature() {
        // §4: `addMode: "many"` is "open-ended, user-named … represented as one options entry named
        // \"*\"". There is no fixed signature to emit for it.
        Assert.assertTrue(TriggerSchemaServiceLoader.buildOptionMethods(
                List.of(option("*")), "Service", NONE, "testmod").isEmpty());
    }

    @Test
    public void testASoundHandlerSurvivesAnUnusableSibling() {
        // The veto contract observed through the real pipeline, not just the drafts.
        JsonArray methods = TriggerSchemaServiceLoader.buildOptionMethods(
                List.of(withParam("onGood", param(null, "Declared")),
                        withParam("onBad", param(null, "NotDeclared"))),
                "Service", Set.of("Declared")::contains, "testmod");
        Assert.assertEquals(methods.size(), 1, "Only the unusable handler is dropped");
        Assert.assertEquals(methods.get(0).getAsJsonObject().get("name").getAsString(), "onGood");
    }

    // ---- fixtures --------------------------------------------------------------------

    private static TriggerMetadataModel.ServiceType.HandlerOption option(String name) {
        return new TriggerMetadataModel.ServiceType.HandlerOption(name, "remote", "optional", null, null,
                null, null, null, null, null, null);
    }

    private static TriggerMetadataModel.ServiceType.HandlerOption withParam(
            String name, TriggerMetadataModel.ServiceType.Param param) {
        return new TriggerMetadataModel.ServiceType.HandlerOption(name, "remote", "optional", null,
                List.of(param), null, null, null, null, null, null);
    }

    private static TriggerMetadataModel.ServiceType.Param param(String name, String type) {
        return new TriggerMetadataModel.ServiceType.Param(name, List.of(new TypeRef(type, null)),
                "required", null, null, null);
    }

    private static JsonArray arrayOf(String value) {
        JsonArray array = new JsonArray();
        array.add(value);
        return array;
    }
}
