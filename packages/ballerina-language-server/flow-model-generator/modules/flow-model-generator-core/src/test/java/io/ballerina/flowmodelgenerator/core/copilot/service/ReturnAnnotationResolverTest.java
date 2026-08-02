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

import static io.ballerina.flowmodelgenerator.core.copilot.service.HandlerAnnotationResolverTest.facts;
import static io.ballerina.flowmodelgenerator.core.copilot.service.HandlerAnnotationResolverTest.names;
import static io.ballerina.flowmodelgenerator.core.copilot.service.HandlerAnnotationResolverTest.registryOf;

/**
 * Pins spec §8 at {@code attachPoint: "return"}, whose selection path is the one the component sketch got
 * wrong.
 *
 * @since 1.7.0
 */
public class ReturnAnnotationResolverTest {

    private static final String HOME = "http";

    @Test
    public void testReturnScopeSelectsByAppliesToNotById() {
        // §8, "Residual gap": "service-level/return-level annotations have no reference mechanism as
        // precise as `params[].annotations`, so they always rely on `appliesTo`."
        // Corpus: http's cache is filed at `attachPoint: "return"` with `appliesTo: ["service"]` and is
        // referenced from nowhere — an id-based resolver would find nothing at all.
        AnnotationRegistry registry = registryOf(new TriggerMetadataModel.Annotation("cache",
                new TypeRef("Cache", null), TriggerMetadataModel.Annotation.ATTACH_POINT_RETURN,
                List.of("service"), TriggerMetadataModel.Annotation.PRESENCE_OPTIONAL));

        Assert.assertEquals(names(resolve(registry, "service", facts("Cache", "RETURN"))),
                List.of("Cache"));
        Assert.assertTrue(resolve(registry, "otherServiceType", facts("Cache", "RETURN")).refs().isEmpty(),
                "`appliesTo` scopes it to the named service types");
    }

    @Test
    public void testAnAbsentAppliesToAppliesToEveryServiceType() {
        // The same reading ServiceAnnotationResolver documents for service scope, which §8's Residual gap
        // leaves open for both: it is the reading that cannot lose a required annotation.
        AnnotationRegistry registry = registryOf(new TriggerMetadataModel.Annotation("cache",
                new TypeRef("Cache", null), TriggerMetadataModel.Annotation.ATTACH_POINT_RETURN, null,
                null));
        Assert.assertEquals(names(resolve(registry, "anyServiceType", facts("Cache", "RETURN"))),
                List.of("Cache"));
        Assert.assertEquals(names(resolve(registry, null, facts("Cache", "RETURN"))), List.of("Cache"));
    }

    @Test
    public void testOnlyTheReturnPointIsSelected() {
        // An entry at another point belongs to another component; return scope must not adopt it.
        AnnotationRegistry registry = registryOf(new TriggerMetadataModel.Annotation("serviceConfig",
                new TypeRef("ServiceConfig", null), TriggerMetadataModel.Annotation.ATTACH_POINT_SERVICE,
                null, null));
        Assert.assertTrue(resolve(registry, "service", facts("ServiceConfig", "SERVICE")).refs().isEmpty());
    }

    @Test
    public void testAnAnnotationTheCompilerDoesNotAllowOnAReturnIsRejected() {
        // The guard again: the document files it at `return`, the package declares it somewhere else.
        AnnotationRegistry registry = registryOf(new TriggerMetadataModel.Annotation("cache",
                new TypeRef("Cache", null), TriggerMetadataModel.Annotation.ATTACH_POINT_RETURN, null,
                null));
        AnnotationScopeResolver.Resolution resolution = resolve(registry, "service",
                facts("Cache", "PARAMETER"));
        Assert.assertTrue(resolution.refs().isEmpty());
        Assert.assertEquals(resolution.rejections().size(), 1);
    }

    @Test
    public void testAnEmptyRegistryResolvesToNothing() {
        Assert.assertTrue(resolve(registryOf(), "service", null).refs().isEmpty());
        Assert.assertTrue(resolve(AnnotationRegistry.of(null), "service", null).refs().isEmpty());
    }

    // ---- fixtures --------------------------------------------------------------------

    private static AnnotationScopeResolver.Resolution resolve(
            AnnotationRegistry registry, String serviceTypeId,
            AnnotationScopeResolver.AnnotationFacts facts) {
        return ReturnAnnotationResolver.resolve(registry, serviceTypeId, HOME, facts);
    }
}
