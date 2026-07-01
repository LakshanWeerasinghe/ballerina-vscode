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

package io.ballerina.servicemodelgenerator.extension.builder.function;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.ballerina.servicemodelgenerator.extension.connector.model.LibraryArtifact;
import io.ballerina.servicemodelgenerator.extension.model.Function;
import io.ballerina.servicemodelgenerator.extension.model.MetaData;
import io.ballerina.servicemodelgenerator.extension.model.Parameter;
import io.ballerina.servicemodelgenerator.extension.model.PropertyType;
import io.ballerina.servicemodelgenerator.extension.model.Value;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * M4 — edit flow. Verifies {@link SchemaDrivenFunctionBuilder#overlayConnectorMetadata} enriches a
 * raw source-parsed function with the connector's curated labels/descriptions (which the source
 * cannot supply), keeping the source-derived names/types intact.
 *
 * @since 1.8.0
 */
public class FunctionEditOverlayTest {

    @Test
    public void testOverlayAppliesConnectorMetadata() throws Exception {
        LibraryArtifact metadata = loadKafkaMetadata();

        // A function as it would come back from a raw source parse: real names, but generic labels.
        Function parsed = new Function.FunctionBuilder()
                .setMetadata(new MetaData("onConsumerRecord", ""))
                .kind("REMOTE")
                .name(identifier("onConsumerRecord"))
                .parameters(List.of(param("messages"), param("caller")))
                .enabled(true)
                .build();

        SchemaDrivenFunctionBuilder.overlayConnectorMetadata(parsed, metadata, "Service");

        Assert.assertTrue(parsed.getMetadata().description().contains("onConsumerRecord"),
                "function description overlaid from the connector model");
        Parameter messages = parsed.getParameters().stream()
                .filter(p -> "messages".equals(p.getName().getValue())).findFirst().orElseThrow();
        Assert.assertEquals(messages.getMetadata().label(), "Consumer messages",
                "parameter label overlaid from the connector model");
        // source-derived identity preserved
        Assert.assertEquals(parsed.getName().getValue(), "onConsumerRecord");
    }

    private static Value identifier(String value) {
        return new Value.ValueBuilder()
                .metadata(value, "")
                .value(value)
                .types(List.of(PropertyType.types(Value.FieldType.IDENTIFIER)))
                .enabled(true)
                .build();
    }

    private static Parameter param(String name) {
        return new Parameter.Builder()
                .metadata(new MetaData(name, ""))
                .kind("REQUIRED")
                .name(identifier(name))
                .type(new Value.ValueBuilder().value("anydata")
                        .types(List.of(PropertyType.types(Value.FieldType.TYPE))).enabled(true).build())
                .enabled(true)
                .build();
    }

    private LibraryArtifact loadKafkaMetadata() throws Exception {
        Path path = Paths.get(getClass().getClassLoader()
                .getResource("connector_models/kafka/resources/service-metadata.json").toURI());
        JsonObject root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject artifact = root.has("libraryArtifact") ? root.getAsJsonObject("libraryArtifact") : root;
        return new Gson().fromJson(artifact, LibraryArtifact.class);
    }
}
