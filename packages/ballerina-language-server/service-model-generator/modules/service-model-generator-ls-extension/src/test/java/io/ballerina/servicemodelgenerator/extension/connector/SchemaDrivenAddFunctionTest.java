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
import io.ballerina.servicemodelgenerator.extension.util.Utils;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;

/**
 * M3 — add-function. A connector function adapted to the wire {@link Function} must be consumable by
 * the existing generic emitter ({@code Utils.generateFunctionDefSource}) — the same path the fallback
 * {@code DefaultFunctionBuilder} uses, which {@code FunctionBuilderRouter} already selects for any
 * non-hardcoded module. This closes the loop: designer (M2) → wire Function → add-function source.
 *
 * @since 1.8.0
 */
public class SchemaDrivenAddFunctionTest {

    private final Gson gson = new Gson();

    @Test
    public void testAdaptedFunctionGeneratesSource() throws Exception {
        LibraryArtifact metadata = loadKafkaMetadata();
        LibraryArtifact.FunctionModel onConsumerRecord = metadata.serviceTypes().get("Service").functions()
                .stream().filter(f -> "onConsumerRecord".equals(f.name())).findFirst().orElseThrow();

        Function wire = FunctionModelAdapter.toFunction(onConsumerRecord);
        String source = Utils.generateFunctionDefSource(wire, List.of(),
                Utils.FunctionAddContext.TRIGGER_ADD, Utils.FunctionSignatureContext.FUNCTION_ADD, new HashMap<>());

        Assert.assertTrue(source.contains("remote function onConsumerRecord("),
                "remote handler signature emitted; got:\n" + source);
        Assert.assertTrue(source.contains("kafka:AnydataConsumerRecord[] messages"),
                "required param emitted; got:\n" + source);
        Assert.assertTrue(source.contains("returns error?"),
                "optional error return emitted; got:\n" + source);
    }

    @Test
    public void testDesignerFunctionsCarryConnectorCodedata() throws Exception {
        // The wire functions from the designer must carry codedata identity so addFunction/updateFunction
        // route back to the schema-driven path (FunctionBuilderRouter reads codedata.moduleName).
        LibraryArtifact metadata = loadKafkaMetadata();
        Service template = MetadataModelAdapter.toServiceTemplate(
                metadata, "Service", "ballerinax", "kafka", "kafka");

        Function fn = template.getFunctions().getFirst();
        Assert.assertNotNull(fn.getCodedata(), "adapted designer function must carry codedata");
        Assert.assertEquals(fn.getCodedata().getOrgName(), "ballerinax");
        Assert.assertEquals(fn.getCodedata().getModuleName(), "kafka");
        Assert.assertEquals(fn.getCodedata().getPackageName(), "kafka");
    }

    private LibraryArtifact loadKafkaMetadata() throws Exception {
        Path path = Paths.get(getClass().getClassLoader()
                .getResource("connector_models/kafka/resources/service-metadata.json").toURI());
        JsonObject root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject artifact = root.has("libraryArtifact") ? root.getAsJsonObject("libraryArtifact") : root;
        return gson.fromJson(artifact, LibraryArtifact.class);
    }
}
