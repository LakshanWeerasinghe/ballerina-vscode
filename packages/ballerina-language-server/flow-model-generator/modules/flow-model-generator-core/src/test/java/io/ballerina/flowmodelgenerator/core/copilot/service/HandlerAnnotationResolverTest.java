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

/**
 * Pins spec §8 at {@code attachPoint: "function"} — including the attach-point guard, whose admissible sets
 * were established with the Ballerina compiler rather than inferred.
 *
 * @since 1.7.0
 */
public class HandlerAnnotationResolverTest {

    private static final String HOME = "smb";

    @Test
    public void testARequiredFunctionAnnotationReachesTheWire() {
        // §8: `presence` | "`required` / `optional` — whether this annotation must be attached at all."
        // Corpus: smb's functionConfig is the only `required` non-service annotation, and generated smb
        // handlers may not work without it.
        AnnotationScopeResolver.Resolution resolution = resolve(
                registryOf(annotation("functionConfig", "FunctionConfig",
                        TriggerMetadataModel.Annotation.ATTACH_POINT_FUNCTION,
                        TriggerMetadataModel.Annotation.PRESENCE_REQUIRED)),
                List.of("functionConfig"), false, facts("FunctionConfig", "FUNCTION"));

        Assert.assertEquals(resolution.refs().size(), 1);
        AnnotationRef ref = resolution.refs().get(0);
        Assert.assertEquals(ref.name(), "FunctionConfig");
        Assert.assertTrue(ref.required(), "`required` must survive to the renderer as an obligation");
        Assert.assertEquals(ref.attachPoint(), TriggerMetadataModel.Annotation.ATTACH_POINT_FUNCTION);
        Assert.assertTrue(resolution.rejections().isEmpty());
    }

    @Test
    public void testResolutionIsByIdNotByAppliesTo() {
        // §8: `id` | "Referenced from `params[].annotations`, `handlers.options[].annotations`, ...".
        // An entry the handler does not name must not be picked up even when it is at the right point.
        AnnotationRegistry registry = registryOf(
                annotation("functionConfig", "FunctionConfig",
                        TriggerMetadataModel.Annotation.ATTACH_POINT_FUNCTION, null),
                annotation("otherConfig", "OtherConfig",
                        TriggerMetadataModel.Annotation.ATTACH_POINT_FUNCTION, null));

        Assert.assertEquals(names(resolve(registry, List.of("functionConfig"), false,
                facts("FunctionConfig", "FUNCTION"))), List.of("FunctionConfig"));
    }

    @Test
    public void testAMisFiledIdIsRejectedRatherThanRenderedAtTheWrongSlot() {
        // §8 files each entry at exactly one `attachPoint`. Rendering a service-scoped entry above a method
        // would emit an attachment the compiler rejects outright.
        AnnotationScopeResolver.Resolution resolution = resolve(
                registryOf(annotation("serviceConfig", "ServiceConfig",
                        TriggerMetadataModel.Annotation.ATTACH_POINT_SERVICE, null)),
                List.of("serviceConfig"), false, facts("ServiceConfig", "SERVICE"));

        Assert.assertTrue(resolution.refs().isEmpty());
        Assert.assertEquals(resolution.rejections().size(), 1);
        Assert.assertTrue(resolution.rejections().get(0).reason().contains("attachPoint"),
                resolution.rejections().get(0).reason());
    }

    @Test
    public void testAnIdThatNamesNothingIsRejectedWithTheId() {
        AnnotationScopeResolver.Resolution resolution = resolve(registryOf(), List.of("noSuchId"), false,
                null);
        Assert.assertTrue(resolution.refs().isEmpty());
        Assert.assertEquals(resolution.rejections().get(0).name(), "noSuchId",
                "the id is the only name available to report");
    }

    @Test
    public void testTheCompilerNotTheDocumentDecidesWhetherAnAttachmentIsLegal() {
        // Established by compiling, not inferred:
        //   annotation declared `on service`, attached to a remote method ->
        //     ERROR: annotation 'X' is not allowed on service_remote, object_method, function
        // A document may file such an entry at `attachPoint: "function"`; the package still rejects it.
        AnnotationScopeResolver.Resolution resolution = resolve(
                registryOf(annotation("functionConfig", "FunctionConfig",
                        TriggerMetadataModel.Annotation.ATTACH_POINT_FUNCTION, null)),
                List.of("functionConfig"), false, facts("FunctionConfig", "SERVICE"));

        Assert.assertTrue(resolution.refs().isEmpty(), "an attachment that cannot compile is not emitted");
        Assert.assertEquals(resolution.rejections().size(), 1);
        Assert.assertTrue(resolution.rejections().get(0).reason().contains("SERVICE"),
                resolution.rejections().get(0).reason());
    }

    @Test
    public void testARemoteHandlerAdmitsTheServiceRemotePoint() {
        // Probed: `on service remote function` (compiler constant RESOURCE) attaches to a remote method.
        // Corpus: ftp declares `public annotation FtpFunctionConfig FunctionConfig on service remote
        // function;` and files it at `attachPoint: "function"` for its eight remote handlers.
        Assert.assertEquals(names(resolve(
                        registryOf(annotation("functionConfig", "FunctionConfig",
                                TriggerMetadataModel.Annotation.ATTACH_POINT_FUNCTION, null)),
                        List.of("functionConfig"), false, facts("FunctionConfig", "RESOURCE"))),
                List.of("FunctionConfig"));
    }

    @Test
    public void testAResourceHandlerDoesNotAdmitTheServiceRemotePoint() {
        // Probed, and the asymmetry is real:
        //   annotation declared `on service remote function`, attached to a resource method ->
        //     ERROR: annotation 'X' is not allowed on object_method, function
        AnnotationScopeResolver.Resolution resolution = resolve(
                registryOf(annotation("functionConfig", "FunctionConfig",
                        TriggerMetadataModel.Annotation.ATTACH_POINT_FUNCTION, null)),
                List.of("functionConfig"), true, facts("FunctionConfig", "RESOURCE"));
        Assert.assertTrue(resolution.refs().isEmpty());

        // ...while `on function` attaches to either kind.
        Assert.assertEquals(names(resolve(
                        registryOf(annotation("functionConfig", "FunctionConfig",
                                TriggerMetadataModel.Annotation.ATTACH_POINT_FUNCTION, null)),
                        List.of("functionConfig"), true, facts("FunctionConfig", "FUNCTION"))),
                List.of("FunctionConfig"));
    }

    @Test
    public void testAnUncheckableDeclarationIsTrustedRatherThanDropped() {
        // Refusing on ignorance would drop a real obligation. With no facts at all, and with facts that
        // report no points (an unreachable cross-module annotation), the reference still renders.
        Assert.assertEquals(names(resolve(
                        registryOf(annotation("functionConfig", "FunctionConfig",
                                TriggerMetadataModel.Annotation.ATTACH_POINT_FUNCTION, null)),
                        List.of("functionConfig"), false, null)),
                List.of("FunctionConfig"));
        Assert.assertEquals(names(resolve(
                        registryOf(annotation("functionConfig", "FunctionConfig",
                                TriggerMetadataModel.Annotation.ATTACH_POINT_FUNCTION, null)),
                        List.of("functionConfig"), false, facts("FunctionConfig"))),
                List.of("FunctionConfig"));
    }

    @Test
    public void testAHomeModuleAnnotationThePackageDoesNotDeclareIsDropped() {
        // The same guard ServiceIdentityAspect applies to a service type: a document authored against a
        // different release must not put an unresolvable name in the prompt.
        AnnotationScopeResolver.Resolution resolution = resolve(
                registryOf(annotation("functionConfig", "GhostConfig",
                        TriggerMetadataModel.Annotation.ATTACH_POINT_FUNCTION, null)),
                List.of("functionConfig"), false, facts("FunctionConfig", "FUNCTION"));
        Assert.assertTrue(resolution.refs().isEmpty());
        Assert.assertTrue(resolution.rejections().get(0).reason().contains("not declared"));
    }

    @Test
    public void testDocumentOrderIsPreserved() {
        // §7's "Array order is meaningful" applies to a rendered list too.
        AnnotationRegistry registry = registryOf(
                annotation("a", "AConfig", TriggerMetadataModel.Annotation.ATTACH_POINT_FUNCTION, null),
                annotation("b", "BConfig", TriggerMetadataModel.Annotation.ATTACH_POINT_FUNCTION, null));
        Assert.assertEquals(names(resolve(registry, List.of("b", "a"), false,
                facts("AConfig", "FUNCTION", "BConfig", "FUNCTION"))), List.of("BConfig", "AConfig"));
    }

    @Test
    public void testNoIdsMeansNothingToResolve() {
        Assert.assertTrue(resolve(registryOf(), null, false, null).refs().isEmpty());
        Assert.assertTrue(resolve(registryOf(), List.of(), false, null).refs().isEmpty());
    }

    // ---- fixtures --------------------------------------------------------------------

    private static AnnotationScopeResolver.Resolution resolve(
            AnnotationRegistry registry, List<String> ids, boolean resource,
            AnnotationScopeResolver.AnnotationFacts facts) {
        return HandlerAnnotationResolver.resolve(registry, ids, resource, HOME, facts);
    }

    static List<String> names(AnnotationScopeResolver.Resolution resolution) {
        return resolution.refs().stream().map(AnnotationRef::name).toList();
    }

    /**
     * A fake whose declared attach points are given as {@code name, point, name, point, ...}. Everything
     * named is declared; nothing else is.
     */
    static AnnotationScopeResolver.AnnotationFacts facts(String... namesAndPoints) {
        java.util.Map<String, java.util.Set<String>> points = new java.util.LinkedHashMap<>();
        for (int i = 0; i < namesAndPoints.length; i += 2) {
            String name = namesAndPoints[i];
            points.computeIfAbsent(name, key -> new java.util.LinkedHashSet<>());
            if (i + 1 < namesAndPoints.length) {
                points.get(name).add(namesAndPoints[i + 1]);
            }
        }
        return new AnnotationScopeResolver.AnnotationFacts() {
            @Override
            public boolean declares(String name) {
                return points.containsKey(name);
            }

            @Override
            public java.util.Set<String> attachPoints(String name, String module) {
                return points.getOrDefault(name, java.util.Set.of());
            }

            @Override
            public String constraint(String name, String module) {
                return name + "uration";
            }
        };
    }

    static AnnotationRegistry registryOf(TriggerMetadataModel.Annotation... annotations) {
        return AnnotationRegistry.of(new TriggerMetadataModel(null, null, List.of(annotations), null));
    }

    static TriggerMetadataModel.Annotation annotation(String id, String name, String attachPoint,
                                                      String presence) {
        return new TriggerMetadataModel.Annotation(id, new TypeRef(name, null), attachPoint, null,
                presence);
    }
}
