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

/**
 * Conformance tests for <b>Spec §8 at {@code attachPoint: "service"}</b>, written against the spec text
 * rather than the implementation: each test names the spec statement it pins.
 *
 * <p>Spec statements pinned by this class:
 * <ul>
 *   <li>"{@code attachPoint} | {@code service} | {@code function} | {@code parameter} | {@code return}" —
 *       this component owns exactly one of the four and must ignore the other three.</li>
 *   <li>"{@code appliesTo} | {@code serviceTypes[].id} array" — the filter is by <i>id</i>, not by type
 *       name.</li>
 *   <li>"{@code presence} | {@code required} / {@code optional}".</li>
 *   <li>§1: "Cross-module (only when the type isn't from this file's own 'home' module)" — a cross-module
 *       annotation carries its own module.</li>
 *   <li>§8 "<b>Residual gap:</b> service-level/return-level annotations have no reference mechanism as
 *       precise as {@code params[].annotations}, so they always rely on {@code appliesTo}" — which is
 *       what leaves the absent-{@code appliesTo} case to us, pinned below as <i>our</i> decision.</li>
 *   <li>The general rule: "a field that would be empty, unused, or fully derivable from other fields is
 *       left out".</li>
 * </ul>
 *
 * @since 1.7.0
 */
public class ServiceAnnotationResolverTest {

    // ---- §8 attachPoint — this component owns exactly one of the four ---------------------

    @Test
    public void testOnlyServiceScopedAnnotationsAreResolved() {
        // §8 lists four attach points; the other three are owned by other components and rendered in
        // other slots. mcp is the corpus case that proves it matters: it declares `tool` at `function`
        // and `httpHeader` at `parameter` alongside its two service-level entries, and both of those
        // carry no `appliesTo` — so a component filtering only on `appliesTo` would attach an MCP tool
        // annotation to the service declaration.
        AnnotationRegistry registry = registryOf(
                annotation("serviceConfig", "ServiceConfig", "service", List.of("service"), "optional"),
                annotation("tool", "Tool", "function", null, "optional"),
                annotation("httpHeader", "Header", "parameter", null, "optional"),
                annotation("cache", "Cache", "return", null, "optional"));

        List<AnnotationRef> refs = resolve(registry, "service");
        Assert.assertEquals(refs.size(), 1, "Only the `service` attach point belongs to this component");
        Assert.assertEquals(refs.get(0).name(), "ServiceConfig");
        Assert.assertEquals(refs.get(0).attachPoint(), "service");
    }

    // ---- §8 appliesTo — the filter is by serviceTypes[].id --------------------------------

    @Test
    public void testAppliesToSelectsByServiceTypeIdNotByTypeName() {
        // §8: "`appliesTo` | `serviceTypes[].id` array". The two differ throughout the corpus — mcp's id
        // `advancedService` names the type `AdvancedService` — so matching on the type name would filter
        // every entry out.
        AnnotationRegistry registry = registryOf(
                annotation("serviceConfig", "ServiceConfig", "service",
                        List.of("service", "advancedService"), "optional"));

        Assert.assertEquals(resolve(registry, "advancedService").size(), 1, "id matches");
        Assert.assertTrue(resolve(registry, "AdvancedService").isEmpty(),
                "the type name is not the id and must not match");
    }

    @Test
    public void testAppliesToPartitionsAnnotationsAcrossServiceTypes() {
        // mcp is the only corpus case that proves filtering rather than blanket attachment: two
        // annotations across four service types, each applying to a different pair.
        AnnotationRegistry registry = registryOf(
                annotation("serviceConfig", "ServiceConfig", "service",
                        List.of("service", "advancedService"), "optional"),
                annotation("streamableHttpServiceConfig", "StreamableHttpServiceConfig", "service",
                        List.of("streamableHttpService", "streamableHttpAdvancedService"), "optional"));

        Assert.assertEquals(names(resolve(registry, "service")), List.of("ServiceConfig"));
        Assert.assertEquals(names(resolve(registry, "advancedService")), List.of("ServiceConfig"));
        Assert.assertEquals(names(resolve(registry, "streamableHttpService")),
                List.of("StreamableHttpServiceConfig"));
        Assert.assertEquals(names(resolve(registry, "streamableHttpAdvancedService")),
                List.of("StreamableHttpServiceConfig"));
    }

    @Test
    public void testAServiceTypeOutsideAppliesToGetsNothing() {
        // websocket declares two service types and scopes its annotation to `service`, so
        // `upgradeService` must carry none.
        AnnotationRegistry registry = registryOf(
                annotation("serviceConfig", "ServiceConfig", "service", List.of("service"), "optional"));
        Assert.assertTrue(resolve(registry, "upgradeService").isEmpty());
    }

    // ---- OUR decision, not the spec's ----------------------------------------------------

    @Test
    public void testAbsentAppliesToAppliesToEveryServiceType() {
        // §8's "Residual gap" leaves this undefined: a service-level annotation has no more precise
        // reference to fall back on, so an omitted `appliesTo` states nothing. WE decide it applies to
        // every service type, because that is the only reading that cannot lose a *required* annotation
        // (ballerina/smb declares exactly this shape). Documented in ServiceAnnotationResolver as ours.
        AnnotationRegistry registry = registryOf(
                annotation("serviceConfig", "ServiceConfig", "service", null, "required"));

        Assert.assertEquals(resolve(registry, "service").size(), 1);
        Assert.assertEquals(resolve(registry, "anyOtherServiceType").size(), 1);
        Assert.assertEquals(resolve(registry, null).size(), 1,
                "even a service type the document names no id for");
    }

    @Test
    public void testAnEmptyAppliesToBehavesAsAnAbsentOne() {
        // The general rule says an empty array is never written in place of an omission, so a document
        // that writes one anyway must not be read as "applies to nothing".
        AnnotationRegistry registry = registryOf(
                annotation("serviceConfig", "ServiceConfig", "service", List.of(), "required"));
        Assert.assertEquals(resolve(registry, "service").size(), 1);
    }

    // ---- §8 presence ---------------------------------------------------------------------

    @Test
    public void testPresenceDistinguishesRequiredFromOptional() {
        // §8: "`presence` | `required` / `optional`". ftp, smb and mssql.cdc are the three required ones
        // in the corpus, and a required annotation omitted from generated code breaks it.
        Assert.assertTrue(resolve(registryOf(
                annotation("a", "ServiceConfig", "service", null, "required")), "service")
                .get(0).required());
        Assert.assertFalse(resolve(registryOf(
                annotation("a", "ServiceConfig", "service", null, "optional")), "service")
                .get(0).required());
    }

    @Test
    public void testAnUnrecognisedPresenceIsNotTreatedAsRequired() {
        // Asserting an obligation the document did not state would make generated code carry an
        // annotation the connector may reject, so anything outside §10's vocabulary degrades to optional.
        for (String presence : Arrays.asList(null, "", "mandatory", "REQUIRED")) {
            Assert.assertFalse(resolve(registryOf(
                    annotation("a", "ServiceConfig", "service", null, presence)), "service")
                    .get(0).required(), "presence=" + presence);
        }
    }

    // ---- §1 cross-module ------------------------------------------------------------------

    @Test
    public void testCrossModuleAnnotationCarriesItsOwnModule() {
        // §1: `packageInfo` is present "only when the type isn't from this file's own 'home' module".
        // mssql.cdc's annotation belongs to ballerinax/cdc, so it must render `@cdc:ServiceConfig` — the
        // home alias would produce `@mssql:ServiceConfig`, which does not exist.
        AnnotationRef ref = resolve(registryOf(new TriggerMetadataModel.Annotation("serviceConfig",
                new TypeRef("ServiceConfig", new TypeRef.PackageInfo("ballerinax", "cdc", "cdc", "1.3.2")),
                "service", List.of("service"), "required")), "service", "mssql").get(0);

        Assert.assertEquals(ref.module(), "ballerinax/cdc");
        Assert.assertTrue(ref.required());
    }

    @Test
    public void testAHomeModuleAnnotationCarriesNoModule() {
        // A bare TypeRef "always means same module as this connector's own types", so there is nothing
        // to state — and per the general rule, nothing is stated. The renderer then uses the home alias.
        AnnotationRef ref = resolve(registryOf(
                annotation("serviceConfig", "ServiceConfig", "service", null, "optional")),
                "service", "ftp").get(0);
        Assert.assertNull(ref.module(), "a home-module annotation states no module");
    }

    @Test
    public void testAnAnnotationDeclaringTheHomeModuleExplicitlyIsNotForeign() {
        // Coordinates that name the home module are not a cross-module reference; treating them as one
        // would prefix a type with its own alias twice over.
        AnnotationRef ref = resolve(registryOf(new TriggerMetadataModel.Annotation("serviceConfig",
                new TypeRef("ServiceConfig", new TypeRef.PackageInfo("ballerina", "ftp", "ftp", "2.0.0")),
                "service", null, "optional")), "service", "ftp").get(0);
        Assert.assertNull(ref.module());
    }

    // ---- the general omission rule ----------------------------------------------------

    @Test
    public void testNoAnnotationsYieldsAnEmptyListRatherThanAFailure() {
        // Most documents declare none at this attach point, and the general rule omits the key entirely.
        Assert.assertTrue(resolve(registryOf(), "service").isEmpty());
        Assert.assertTrue(resolve(AnnotationRegistry.of(null), "service").isEmpty());
    }

    @Test
    public void testAnEntryNamingNoAnnotationIsSkippedRatherThanEmittedNameless() {
        // §8's `type` is what names the annotation; without it there is nothing a renderer could write.
        AnnotationRegistry registry = AnnotationRegistry.of(new TriggerMetadataModel(null, List.of(), List.of(),
                Arrays.asList(
                        new TriggerMetadataModel.Annotation("noType", null, "service", null, "required"),
                        new TriggerMetadataModel.Annotation("blank", new TypeRef("", null), "service",
                                null, "required"),
                        annotation("sound", "ServiceConfig", "service", null, "optional")),
                null));
        Assert.assertEquals(names(resolve(registry, "service")), List.of("ServiceConfig"),
                "the sound entry beside them still resolves");
    }

    @Test
    public void testDocumentOrderIsPreserved() {
        // §7 states the principle the whole schema follows: "Array order is meaningful". Two annotations
        // on one service must be emitted in the order the document declares them.
        AnnotationRegistry registry = registryOf(
                annotation("first", "FirstConfig", "service", null, "optional"),
                annotation("second", "SecondConfig", "service", null, "required"));
        Assert.assertEquals(names(resolve(registry, "service")), List.of("FirstConfig", "SecondConfig"));
    }

    @Test
    public void testTheAttachPointIsCarriedOnEveryReference() {
        // The wire states which point an entry was resolved for, so a mis-filed id is detectable rather
        // than silently rendered in the wrong slot once the other three attach points land.
        Assert.assertEquals(resolve(registryOf(
                annotation("a", "ServiceConfig", "service", null, "optional")), "service")
                .get(0).attachPoint(), "service");
    }

    // ---- decision 2: the document names the annotation, not its constraint -------------------

    @Test
    public void testConstraintIsNotTakenFromTheDocumentsTypeName() {
        // Verified against the corpus: ballerina/ftp's document says `type: {"name": "ServiceConfig"}`
        // while the package declares `public annotation ServiceConfiguration ServiceConfig on service;`.
        // The document names the tag written after `@`; the constraining record has a different name
        // entirely. So with no compiled package to introspect, no constraint may be invented from the
        // document — a fabricated `ServiceConfig` record does not exist in ballerina/ftp.
        AnnotationRef ref = resolve(registryOf(
                annotation("serviceConfig", "ServiceConfig", "service", null, "required")),
                "service", "ftp").get(0);
        Assert.assertEquals(ref.name(), "ServiceConfig", "the tag is what the document names");
        Assert.assertNull(ref.typeConstraint(),
                "no constraint is invented from the tag name; it is introspected from the compiler");
    }

    // ---- fixtures --------------------------------------------------------------------

    /** Resolves with no compiled package behind it — the point of keeping the resolver pure. */
    private static List<AnnotationRef> resolve(AnnotationRegistry registry, String serviceTypeId) {
        return resolve(registry, serviceTypeId, "testmod");
    }

    private static List<AnnotationRef> resolve(AnnotationRegistry registry, String serviceTypeId,
                                               String homeModule) {
        return ServiceAnnotationResolver.resolve(registry, serviceTypeId, homeModule, null).refs();
    }

    private static List<String> names(List<AnnotationRef> refs) {
        return refs.stream().map(AnnotationRef::name).toList();
    }

    private static AnnotationRegistry registryOf(TriggerMetadataModel.Annotation... annotations) {
        return AnnotationRegistry.of(
                new TriggerMetadataModel(null, List.of(), List.of(), List.of(annotations), null));
    }

    private static TriggerMetadataModel.Annotation annotation(String id, String type, String attachPoint,
                                                              List<String> appliesTo, String presence) {
        return new TriggerMetadataModel.Annotation(id, new TypeRef(type, null), attachPoint, appliesTo,
                presence);
    }
}
