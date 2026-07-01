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
import io.ballerina.servicemodelgenerator.extension.connector.model.LibraryArtifact;
import io.ballerina.servicemodelgenerator.extension.model.ServiceInitModel;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Verifies the schema-driven source generator produces source <b>byte-identical</b> to the hardcoded
 * Kafka builder's expected output (the {@code add_service_and_listener} corpus), proving the generic
 * codedata-driven path is a faithful drop-in. A second test proves function (handler) emission.
 *
 * @since 1.8.0
 */
public class SchemaDrivenSourceGeneratorTest {

    private final Gson gson = new Gson();

    @Test
    public void testKafkaAddSourceMatchesHardcodedOutput() throws Exception {
        // The FILLED creation model the client submits (from the hardcoded-path corpus).
        Path configPath = resource("add_service_and_listener/config/kafka_service_model.json");
        JsonObject config = JsonParser.parseString(Files.readString(configPath, StandardCharsets.UTF_8))
                .getAsJsonObject();
        ServiceInitModel filled = gson.fromJson(config.get("serviceInitModel"), ServiceInitModel.class);

        // The connector's metadata model (shipped in the .bala).
        LibraryArtifact metadata = loadMetadata("connector_models/kafka/resources/service-metadata.json");

        // Expected edits from the hardcoded Kafka builder.
        JsonObject output = config.getAsJsonObject("output");
        String filePath = output.keySet().iterator().next();
        var edits = output.getAsJsonArray(filePath);
        String expectedImport = edits.get(0).getAsJsonObject().get("newText").getAsString();
        String expectedBlock = edits.get(1).getAsJsonObject().get("newText").getAsString();

        Assert.assertEquals(SchemaDrivenSourceGenerator.buildImport(filled), expectedImport,
                "import statement must match the hardcoded builder");
        Assert.assertEquals(SchemaDrivenSourceGenerator.buildServiceBlock(filled, metadata), expectedBlock,
                "listener + service block must match the hardcoded builder byte-for-byte");
    }

    @Test
    public void testFunctionEmissionFromMetadata() {
        // A correctly-authored metadata model: the locked handler is enabled=true (per authoring rule).
        String metadataJson = """
                {
                  "schemaVersion": "1.0", "name": "kafka", "version": "4.5.0",
                  "serviceDeclaration": { "kind": "event", "listenerKind": "SINGLE_SELECT_LISTENER" },
                  "serviceTypes": {
                    "Service": {
                      "name": "Service", "description": "",
                      "functions": [
                        {
                          "name": "onConsumerRecord", "kind": "REMOTE", "qualifiers": ["remote"],
                          "enabled": true, "optional": false,
                          "parameters": [
                            { "name": "messages", "type": "kafka:AnydataConsumerRecord[]", "kind": "REQUIRED",
                              "optional": false, "enabled": true },
                            { "name": "caller", "type": "kafka:Caller", "kind": "OPTIONAL",
                              "optional": true, "enabled": true }
                          ],
                          "returnType": { "type": "error", "enabled": true, "optional": true, "hasError": true }
                        }
                      ]
                    }
                  }
                }
                """;
        LibraryArtifact metadata = gson.fromJson(metadataJson, LibraryArtifact.class);
        LibraryArtifact.FunctionModel fn = metadata.serviceTypes().get("Service").functions().getFirst();

        String source = SchemaDrivenSourceGenerator.buildFunctionSource(fn);
        Assert.assertEquals(source,
                "remote function onConsumerRecord(kafka:AnydataConsumerRecord[] messages) returns error? {"
                        + System.lineSeparator() + "}",
                "required param included, optional caller excluded, error? return emitted");
    }

    private LibraryArtifact loadMetadata(String classpathResource) throws Exception {
        Path path = resource(classpathResource);
        JsonObject root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject artifact = root.has("libraryArtifact") ? root.getAsJsonObject("libraryArtifact") : root;
        return gson.fromJson(artifact, LibraryArtifact.class);
    }

    @Test
    public void testChoiceAndGroupSectionListener() throws Exception {
        // HubSpot ships a CHOICE (create-new vs use-existing) whose create-new branch nests the
        // listener params in a GROUP_SECTION. The generator must resolve the choice, build the
        // config record from the group's CONFIG_FIELD children as positional arg 1, and place the
        // nested listenOn as positional arg 2 — not flatten everything into map order.
        Path creationPath = resource("connector_models/hubspot/resources/service-creation.json");
        ServiceInitModel creation = gson.fromJson(
                Files.readString(creationPath, StandardCharsets.UTF_8), ServiceInitModel.class);

        String listener = SchemaDrivenSourceGenerator.buildListenerDeclaration(creation);
        Assert.assertEquals(listener,
                "listener hubspot:Listener hubspotListener = "
                        + "new ({clientSecret: clientSecretValue, callbackURL: callbackUrlValue}, 8090);",
                "config record must be positional arg 1 (from the group) and listenOn positional arg 2");

        String block = SchemaDrivenSourceGenerator.buildServiceBlock(creation, null);
        Assert.assertTrue(block.contains("service hubspot:CompanyService on hubspotListener {"),
                "service descriptor must come from the SERVICE_TYPE_DESCRIPTOR field, got:\n" + block);
    }

    @Test
    public void testUseExistingListenerAttachesWithoutDeclaration() throws Exception {
        // Filled submission with the "use existing" branch selected: the service must attach to the
        // chosen listener (KEY_EXISTING_LISTENER) and NOT emit a new listener declaration.
        Path creationPath = resource("connector_models/hubspot/resources/service-creation-existing.json");
        ServiceInitModel creation = gson.fromJson(
                Files.readString(creationPath, StandardCharsets.UTF_8), ServiceInitModel.class);

        String block = SchemaDrivenSourceGenerator.buildServiceBlock(creation, null);
        Assert.assertFalse(block.contains("listener hubspot:Listener"),
                "no new listener should be declared when attaching to an existing one, got:\n" + block);
        Assert.assertTrue(block.contains("service hubspot:ContactService on myHubspotListener {"),
                "service must attach to the selected existing listener, got:\n" + block);
    }

    private Path resource(String name) throws Exception {
        return Paths.get(getClass().getClassLoader().getResource(name).toURI());
    }
}
