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

import java.util.List;

/**
 * Wire-contract tests for the spec §8 service-annotation component: what a consumer is promised, as
 * opposed to how the resolver decides it (pinned in {@link ServiceAnnotationResolverTest}).
 *
 * <p>Spec statements pinned by this class:
 * <ul>
 *   <li>"{@code presence} | {@code required} / {@code optional}" reaches the wire as that vocabulary,
 *       not as a boolean a renderer would have to re-interpret.</li>
 *   <li>§1: a cross-module annotation states its own {@code org/module}; a home-module one states
 *       nothing, per the general omission rule.</li>
 *   <li>The general rule — "a field that would be empty, unused, or fully derivable … is left out" —
 *       so a service type with no obligation carries no {@code annotations} key at all.</li>
 * </ul>
 *
 * @since 1.7.0
 */
public class ServiceAnnotationAspectTest {

    @Test
    public void testTheComponentDeclaresTheSpecSectionItOwns() {
        ServiceAnnotationAspect aspect = new ServiceAnnotationAspect();
        Assert.assertEquals(aspect.id(), "serviceAnnotation");
        Assert.assertEquals(aspect.specSection(), "§8");
    }

    @Test
    public void testARequiredCrossModuleAnnotationReachesTheWireWithItsModule() {
        // mssql.cdc, the case that motivated this phase: `@cdc:ServiceConfig` with presence `required`
        // reached the prompt nowhere before, and CDC code generated without it does not work.
        JsonObject annotation = firstAnnotation(contribute("mssql", "service",
                new TriggerMetadataModel.Annotation("serviceConfig",
                        new TypeRef("ServiceConfig",
                                new TypeRef.PackageInfo("ballerinax", "cdc", "cdc", "1.3.2")),
                        "service", List.of("service"), "required")));

        Assert.assertEquals(annotation.get("name").getAsString(), "ServiceConfig");
        Assert.assertEquals(annotation.get("module").getAsString(), "ballerinax/cdc");
        Assert.assertEquals(annotation.get("presence").getAsString(), "required");
        Assert.assertEquals(annotation.get("attachPoint").getAsString(), "service");
    }

    @Test
    public void testPresenceIsCarriedAsTheSpecsOwnVocabulary() {
        Assert.assertEquals(firstAnnotation(contribute("ftp", "service",
                serviceAnnotation("ServiceConfig", "optional"))).get("presence").getAsString(), "optional");
        Assert.assertEquals(firstAnnotation(contribute("ftp", "service",
                serviceAnnotation("ServiceConfig", "required"))).get("presence").getAsString(), "required");
    }

    @Test
    public void testAHomeModuleAnnotationOmitsTheModuleKey() {
        // Nothing to state, so nothing is stated — the renderer supplies the library's own alias.
        Assert.assertFalse(firstAnnotation(contribute("ftp", "service",
                serviceAnnotation("ServiceConfig", "required"))).has("module"));
    }

    @Test
    public void testAServiceTypeWithNoObligationCarriesNoAnnotationsKey() {
        // The general omission rule, enforced by the draft rather than at the call site: never an empty
        // array. Most libraries are this case, and their output must be untouched by this component.
        Assert.assertFalse(contribute("kafka", "service").has("annotations"));
        Assert.assertFalse(contribute("kafka", "service",
                serviceAnnotation("ServiceConfig", "required", "someOtherServiceType")).has("annotations"),
                "an annotation scoped away from this service type leaves no trace on it");
    }

    @Test
    public void testAnnotationsFromOtherAttachPointsNeverReachTheServiceSlot() {
        // mcp declares `tool` at `function` and `httpHeader` at `parameter`, both without `appliesTo`.
        Assert.assertFalse(contribute("mcp", "service",
                new TriggerMetadataModel.Annotation("tool", new TypeRef("Tool", null), "function", null,
                        "optional")).has("annotations"));
    }

    // ---- fixtures --------------------------------------------------------------------

    /** Runs the real aspect over a scope with no compiled package behind it. */
    private static JsonObject contribute(String packageName, String serviceTypeId,
                                         TriggerMetadataModel.Annotation... annotations) {
        TriggerMetadataModel document = new TriggerMetadataModel(List.of(), List.of(),
                List.of(annotations), null);
        TriggerMetadataModel.ServiceType serviceType = new TriggerMetadataModel.ServiceType(
                serviceTypeId, new TypeRef("Service", null), false, false, false, null, null, null);
        TriggerScope scope = new TriggerScope("testorg/" + packageName, "testorg", packageName,
                packageName, document, AnnotationRegistry.of(document), serviceType, null, null, null,
                name -> false);

        ServiceDraft draft = new ServiceDraft();
        new ServiceAnnotationAspect().contribute(scope, draft);
        return draft.toJson();
    }

    private static JsonObject firstAnnotation(JsonObject service) {
        Assert.assertTrue(service.has("annotations"), "expected an annotations array: " + service);
        JsonArray annotations = service.getAsJsonArray("annotations");
        Assert.assertFalse(annotations.isEmpty());
        return annotations.get(0).getAsJsonObject();
    }

    private static TriggerMetadataModel.Annotation serviceAnnotation(String type, String presence,
                                                                     String... appliesTo) {
        return new TriggerMetadataModel.Annotation("id", new TypeRef(type, null), "service",
                appliesTo.length == 0 ? null : List.of(appliesTo), presence);
    }
}
