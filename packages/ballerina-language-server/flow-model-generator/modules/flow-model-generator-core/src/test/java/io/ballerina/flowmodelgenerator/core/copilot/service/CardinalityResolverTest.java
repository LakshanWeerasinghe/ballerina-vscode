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
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.ballerina.modelgenerator.commons.trigger.models.TriggerMetadataModel;
import io.ballerina.modelgenerator.commons.trigger.models.TypeRef;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Conformance tests for <b>spec §3's {@code multipleListenersAllowed} and
 * {@code multipleServicesPerListenerAllowed}</b>, written against the spec text.
 *
 * <p>Spec statements pinned:
 * <ul>
 *   <li>"{@code multipleListenersAllowed} | Can one service instance attach to more than one listener at
 *       once ({@code service X on l1, l2 {}})?"</li>
 *   <li>"{@code multipleServicesPerListenerAllowed} | Can one listener host more than one service of this
 *       type at once?"</li>
 * </ul>
 *
 * <p>The corpus pin at the bottom is the load-bearing one. {@code TriggerMetadataModel.ServiceType}
 * declares both fields as a primitive {@code boolean}, so a document omitting a key deserializes to
 * {@code false} — indistinguishable from a document that states {@code false}. Since a {@code false} is
 * what makes the pipeline emit a prohibition, an omission would silently manufacture a restriction the
 * connector never declared. That is safe only while every document states both keys, and this suite is
 * what keeps that true.
 *
 * @since 1.7.0
 */
public class CardinalityResolverTest {

    /** Every bundled document. Listed rather than discovered, so a document going missing fails here. */
    private static final List<String> BUNDLED_DOCUMENTS = List.of(
            "ftp", "graphql", "grpc", "http", "kafka", "mcp", "mssql.cdc", "rabbitmq", "smb",
            "trigger.github", "trigger.google.calendar", "websocket", "websub");

    // ---- §3 — the resolver is a faithful passthrough ------------------------------------

    @Test
    public void testBothPermissiveValuesAreReadAsDeclared() {
        CardinalityResolver.Cardinality cardinality = CardinalityResolver.resolve(serviceType(true, true));
        Assert.assertTrue(cardinality.multipleListeners());
        Assert.assertTrue(cardinality.multipleServicesPerListener());
    }

    @Test
    public void testTheTwoAnswersAreIndependent() {
        // ballerinax/trigger.google.calendar's real shape: one service may span listeners, but a listener
        // hosts only one such service. Collapsing the pair into a single answer would state something
        // false for it, which is why the aspect emits two separate lines.
        CardinalityResolver.Cardinality mixed = CardinalityResolver.resolve(serviceType(true, false));
        Assert.assertTrue(mixed.multipleListeners());
        Assert.assertFalse(mixed.multipleServicesPerListener());

        CardinalityResolver.Cardinality opposite = CardinalityResolver.resolve(serviceType(false, true));
        Assert.assertFalse(opposite.multipleListeners());
        Assert.assertTrue(opposite.multipleServicesPerListener());
    }

    @Test
    public void testKafkaShapeForbidsBoth() {
        // ballerinax/kafka is the only corpus service type where both prohibitions fire.
        CardinalityResolver.Cardinality cardinality = CardinalityResolver.resolve(serviceType(false, false));
        Assert.assertFalse(cardinality.multipleListeners());
        Assert.assertFalse(cardinality.multipleServicesPerListener());
    }

    @Test
    public void testAnAbsentServiceTypeStatesNothing() {
        // Read as fully permissive, which the aspect then renders as no note at all — never as a
        // prohibition invented from missing input.
        CardinalityResolver.Cardinality cardinality = CardinalityResolver.resolve(null);
        Assert.assertTrue(cardinality.multipleListeners());
        Assert.assertTrue(cardinality.multipleServicesPerListener());
    }

    // ---- the aspect's omission rule -----------------------------------------------------

    @Test
    public void testOnlyAProhibitionIsEmitted() {
        // The permissive case must write nothing: the one-service-one-listener shape a generator produces
        // by default is legal either way, so a note there would spend context to change no output.
        Assert.assertFalse(contribute(true, true).has("singleListenerOnly"));
        Assert.assertFalse(contribute(true, true).has("singleServicePerListenerOnly"));
    }

    @Test
    public void testEachProhibitionIsEmittedIndependently() {
        JsonObject listenerBound = contribute(false, true);
        Assert.assertTrue(listenerBound.get("singleListenerOnly").getAsBoolean());
        Assert.assertFalse(listenerBound.has("singleServicePerListenerOnly"));

        JsonObject serviceBound = contribute(true, false);
        Assert.assertFalse(serviceBound.has("singleListenerOnly"));
        Assert.assertTrue(serviceBound.get("singleServicePerListenerOnly").getAsBoolean());
    }

    // ---- the corpus pin the primitive-boolean hazard depends on --------------------------

    @Test
    public void testEveryBundledServiceTypeStatesBothCardinalityKeys() throws IOException {
        // The guard described in the class javadoc. If this fails, a document has started relying on the
        // default and the pipeline is now emitting a prohibition nobody wrote: box the two fields in
        // TriggerMetadataModel (or reject such a document at the reader) before shipping.
        int serviceTypes = 0;
        for (String key : BUNDLED_DOCUMENTS) {
            JsonArray declared = serviceTypesOf(key);
            Assert.assertFalse(declared.isEmpty(), key + " declares no service types");
            for (JsonElement element : declared) {
                JsonObject serviceType = element.getAsJsonObject();
                String id = serviceType.has("id") ? serviceType.get("id").getAsString() : "?";
                Assert.assertTrue(serviceType.has("multipleListenersAllowed"),
                        key + "/" + id + " omits multipleListenersAllowed; an absent key deserializes to"
                                + " false and would render a prohibition the document never stated");
                Assert.assertTrue(serviceType.has("multipleServicesPerListenerAllowed"),
                        key + "/" + id + " omits multipleServicesPerListenerAllowed; same hazard");
                serviceTypes++;
            }
        }
        Assert.assertEquals(serviceTypes, 26,
                "The corpus is 26 service types across 13 documents; a change here means the measured"
                        + " render surface of this construct has moved");
    }

    // ---- fixtures --------------------------------------------------------------------

    private static JsonObject contribute(boolean multipleListeners, boolean multipleServicesPerListener) {
        ServiceDraft draft = new ServiceDraft();
        new CardinalityAspect().contribute(
                scopeOf(serviceType(multipleListeners, multipleServicesPerListener)), draft);
        return draft.toJson();
    }

    private static TriggerScope scopeOf(TriggerMetadataModel.ServiceType serviceType) {
        return new TriggerScope("ballerinax/probe", "ballerinax", "probe", "probe", null,
                AnnotationRegistry.of(null), serviceType, null, null, null, name -> false);
    }

    private static TriggerMetadataModel.ServiceType serviceType(boolean multipleListeners,
                                                                boolean multipleServicesPerListener) {
        return new TriggerMetadataModel.ServiceType("service", new TypeRef("Service", null), false,
                multipleListeners, multipleServicesPerListener, null, null, null);
    }

    private static JsonArray serviceTypesOf(String documentKey) throws IOException {
        String resource = "trigger-metadata-models/" + documentKey + "/trigger-metadata.json";
        try (InputStream stream = CardinalityResolverTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            Assert.assertNotNull(stream, "Bundled document not on the classpath: " + resource);
            try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject().getAsJsonArray("serviceTypes");
            }
        }
    }
}
