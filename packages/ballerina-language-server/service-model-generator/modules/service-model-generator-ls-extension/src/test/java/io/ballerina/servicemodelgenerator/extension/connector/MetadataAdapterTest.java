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

package io.ballerina.servicemodelgenerator.extension.connector;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.ballerina.servicemodelgenerator.extension.connector.adapter.FunctionModelAdapter;
import io.ballerina.servicemodelgenerator.extension.connector.adapter.MetadataModelAdapter;
import io.ballerina.servicemodelgenerator.extension.connector.model.LibraryArtifact;
import io.ballerina.servicemodelgenerator.extension.model.Function;
import io.ballerina.servicemodelgenerator.extension.model.Service;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Verifies the connector-model → wire-POJO adapters used by the designer flow (M2): a connector
 * {@code FunctionModel} becomes a wire {@link Function}, and a {@code LibraryArtifact} becomes a wire
 * {@link Service} template, both matching the shapes the hardcoded (DB-backed) path produces.
 *
 * @since 1.8.0
 */
public class MetadataAdapterTest {

    private final Gson gson = new Gson();

    @Test
    public void testFunctionModelToWireFunction() throws Exception {
        LibraryArtifact metadata = loadKafkaMetadata();
        LibraryArtifact.FunctionModel onConsumerRecord = metadata.serviceTypes().get("Service").functions()
                .stream().filter(f -> "onConsumerRecord".equals(f.name())).findFirst().orElseThrow();

        Function wire = FunctionModelAdapter.toFunction(onConsumerRecord);
        Assert.assertEquals(wire.getName().getValue(), "onConsumerRecord");
        Assert.assertEquals(wire.getKind(), "REMOTE");
        Assert.assertEquals(wire.getParameters().size(), 2, "both declared params carried over");
        Assert.assertEquals(wire.getParameters().get(0).getType().getValue(), "kafka:AnydataConsumerRecord[]");
        Assert.assertEquals(wire.getParameters().get(0).getName().getValue(), "messages");
        Assert.assertTrue(wire.getParameters().get(1).isOptional(), "caller is optional");
        Assert.assertNotNull(wire.getReturnType());
        Assert.assertEquals(wire.getReturnType().getValue(), "error?",
                "return type text is rendered from type+optional+hasError");
        Assert.assertTrue(wire.getReturnType().hasError(), "return carries error");
    }

    @Test
    public void testLibraryArtifactToServiceTemplate() throws Exception {
        LibraryArtifact metadata = loadKafkaMetadata();
        Service template = MetadataModelAdapter.toServiceTemplate(
                metadata, "Service", "ballerinax", "kafka", "kafka");

        Assert.assertNotNull(template);
        Assert.assertEquals(template.getListenerProtocol(), "kafka");
        Assert.assertNotNull(template.getListener(), "listener property present");
        Assert.assertNotNull(template.getServiceType(), "serviceType property present");
        Assert.assertEquals(template.getServiceType().getValue(), "Service");
        Assert.assertEquals(template.getFunctions().size(), 2, "both handlers adapted");
    }

    @Test
    public void testUnknownServiceTypeFallsBackToSingle() throws Exception {
        LibraryArtifact metadata = loadKafkaMetadata();
        // kafka ships exactly one service type, so a null request resolves to it
        Service template = MetadataModelAdapter.toServiceTemplate(
                metadata, null, "ballerinax", "kafka", "kafka");
        Assert.assertNotNull(template);
        Assert.assertEquals(template.getFunctions().size(), 2);
    }

    private LibraryArtifact loadKafkaMetadata() throws Exception {
        Path path = Paths.get(getClass().getClassLoader()
                .getResource("connector_models/kafka/resources/service-metadata.json").toURI());
        JsonObject root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject artifact = root.has("libraryArtifact") ? root.getAsJsonObject("libraryArtifact") : root;
        return gson.fromJson(artifact, LibraryArtifact.class);
    }
}
