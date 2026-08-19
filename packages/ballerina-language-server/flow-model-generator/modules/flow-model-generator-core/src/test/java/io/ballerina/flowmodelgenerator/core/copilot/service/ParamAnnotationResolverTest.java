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

import static io.ballerina.flowmodelgenerator.core.copilot.service.HandlerAnnotationResolverTest.annotation;
import static io.ballerina.flowmodelgenerator.core.copilot.service.HandlerAnnotationResolverTest.facts;
import static io.ballerina.flowmodelgenerator.core.copilot.service.HandlerAnnotationResolverTest.names;
import static io.ballerina.flowmodelgenerator.core.copilot.service.HandlerAnnotationResolverTest.registryOf;

/**
 * Pins spec §8 at {@code attachPoint: "parameter"}.
 *
 * @since 1.7.0
 */
public class ParamAnnotationResolverTest {

    private static final String HOME = "rabbitmq";

    @Test
    public void testAParameterAnnotationResolvesById() {
        // §8: `annotations` on a param is "Ids into `annotations[]`, `attachPoint: "parameter"`".
        // Corpus: rabbitmq's onMessage/onRequest payload parameter.
        AnnotationScopeResolver.Resolution resolution = resolve(
                registryOf(annotation("payload", "Payload",
                        TriggerMetadataModel.Annotation.ATTACH_POINT_PARAMETER,
                        TriggerMetadataModel.Annotation.PRESENCE_OPTIONAL)),
                List.of("payload"), facts("Payload", "PARAMETER"));

        Assert.assertEquals(names(resolution), List.of("Payload"));
        Assert.assertFalse(resolution.refs().get(0).required(), "rabbitmq's payload is optional");
        Assert.assertEquals(resolution.refs().get(0).attachPoint(),
                TriggerMetadataModel.Annotation.ATTACH_POINT_PARAMETER);
    }

    @Test
    public void testOnlyTheParameterPointIsAdmitted() {
        // Probed: an annotation declared `on parameter` attaches inline before a parameter's type and
        // compiles. One declared anywhere else does not belong in that slot.
        Assert.assertTrue(resolve(
                registryOf(annotation("payload", "Payload",
                        TriggerMetadataModel.Annotation.ATTACH_POINT_PARAMETER, null)),
                List.of("payload"), facts("Payload", "FUNCTION")).refs().isEmpty());
    }

    @Test
    public void testAMisFiledIdIsRejected() {
        // A registry entry filed at `function` must not be rendered inline in a parameter list.
        AnnotationScopeResolver.Resolution resolution = resolve(
                registryOf(annotation("tool", "Tool",
                        TriggerMetadataModel.Annotation.ATTACH_POINT_FUNCTION, null)),
                List.of("tool"), facts("Tool", "FUNCTION"));
        Assert.assertTrue(resolution.refs().isEmpty());
        Assert.assertEquals(resolution.rejections().size(), 1);
    }

    @Test
    public void testACrossModuleParameterAnnotationCarriesItsOwnModule() {
        // §1: `packageInfo` is present "only when the type isn't from this file's own home module".
        // Corpus: mcp's httpHeader names ballerina/http's Header.
        TriggerMetadataModel.Annotation header = new TriggerMetadataModel.Annotation("httpHeader",
                new TypeRef("Header", new TypeRef.PackageInfo("ballerina", "http", "http", "2.16.5")),
                TriggerMetadataModel.Annotation.ATTACH_POINT_PARAMETER, null, null);

        AnnotationRef ref = ParamAnnotationResolver.resolve(registryOf(header), List.of("httpHeader"),
                "mcp", facts()).refs().get(0);
        Assert.assertEquals(ref.name(), "Header");
        Assert.assertEquals(ref.module(), "ballerina/http",
                "the renderer derives the alias and the provenance note from the module");
    }

    @Test
    public void testACrossModuleAnnotationIsNotCheckedAgainstTheHomeModulesSymbols() {
        // A foreign annotation is not declared by *this* module by definition, so the existence check is
        // neither possible nor meaningful — the same rule ServiceIdentityResolver applies to a type.
        TriggerMetadataModel.Annotation header = new TriggerMetadataModel.Annotation("httpHeader",
                new TypeRef("Header", new TypeRef.PackageInfo("ballerina", "http", "http", "2.16.5")),
                TriggerMetadataModel.Annotation.ATTACH_POINT_PARAMETER, null, null);
        Assert.assertEquals(names(ParamAnnotationResolver.resolve(registryOf(header),
                List.of("httpHeader"), "mcp", facts("SomethingElse", "PARAMETER"))), List.of("Header"));
    }

    // ---- fixtures --------------------------------------------------------------------

    private static AnnotationScopeResolver.Resolution resolve(
            AnnotationRegistry registry, List<String> ids,
            AnnotationScopeResolver.AnnotationFacts facts) {
        return ParamAnnotationResolver.resolve(registry, ids, HOME, facts);
    }
}
