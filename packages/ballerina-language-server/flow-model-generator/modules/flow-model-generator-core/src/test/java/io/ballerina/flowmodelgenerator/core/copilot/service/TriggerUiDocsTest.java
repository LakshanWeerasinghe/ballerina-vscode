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

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import io.ballerina.modelgenerator.commons.trigger.models.TriggerUISchemaModel;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.Optional;

/**
 * Tests for {@link TriggerUiDocs}: registry-entry resolution (plain string vs version-gated
 * variants), UI-model documentation indexing, and lookup semantics.
 *
 * @since 1.7.0
 */
public class TriggerUiDocsTest {

    private static final Gson GSON = new Gson();

    // ---- resolveResource ----------------------------------------------------------

    @Test
    public void testResolveResourcePlainString() {
        Assert.assertEquals(TriggerUiDocs.resolveResource(
                JsonParser.parseString("\"trigger-models/kafka.json\""), "4.6.5"), "trigger-models/kafka.json");
    }

    @Test
    public void testResolveResourceNullEntry() {
        Assert.assertNull(TriggerUiDocs.resolveResource(null, "1.0.0"));
        Assert.assertNull(TriggerUiDocs.resolveResource(JsonParser.parseString("null"), "1.0.0"));
    }

    @Test
    public void testResolveResourceVersionGatedVariants() {
        String registry = "[{\"minVersion\": \"1.2.0\", \"resource\": \"trigger-models/mcp.json\"},"
                + "{\"resource\": \"trigger-models/mcp_1.0.3.json\"}]";
        Assert.assertEquals(TriggerUiDocs.resolveResource(JsonParser.parseString(registry), "1.2.0"),
                "trigger-models/mcp.json");
        Assert.assertEquals(TriggerUiDocs.resolveResource(JsonParser.parseString(registry), "1.3.1"),
                "trigger-models/mcp.json");
        Assert.assertEquals(TriggerUiDocs.resolveResource(JsonParser.parseString(registry), "1.1.0"),
                "trigger-models/mcp_1.0.3.json");
        // Absent/unparsable resolved versions resolve to the newest (gated) document, mirroring
        // ConnectorModelReader's variant matching.
        Assert.assertEquals(TriggerUiDocs.resolveResource(JsonParser.parseString(registry), null),
                "trigger-models/mcp.json");
        Assert.assertEquals(TriggerUiDocs.resolveResource(JsonParser.parseString(registry), "not-a-version"),
                "trigger-models/mcp.json");
    }

    @Test
    public void testResolveResourceMalformedEntries() {
        // Neither primitive nor array → unresolvable.
        Assert.assertNull(TriggerUiDocs.resolveResource(JsonParser.parseString("{\"foo\": 1}"), "1.0.0"));
        // Variants without a resource are ignored.
        Assert.assertNull(TriggerUiDocs.resolveResource(
                JsonParser.parseString("[{\"minVersion\": \"1.0.0\"}]"), "2.0.0"));
        // Multiple version-less variants: the first wins as the fallback.
        Assert.assertEquals(TriggerUiDocs.resolveResource(
                JsonParser.parseString("[{\"resource\": \"a.json\"}, {\"resource\": \"b.json\"}]"), "1.0.0"),
                "a.json");
    }

    // ---- index + lookups ------------------------------------------------------------

    private static final String MODEL_JSON = """
            {
              "serviceTypes": [
                {
                  "name": "kafka:Service",
                  "functions": [],
                  "schemaFunctions": [
                    {
                      "name": "onConsumerRecord",
                      "kind": "REMOTE",
                      "enabled": false,
                      "metadata": { "label": "onConsumerRecord",
                                    "description": "Triggered when a batch of messages is received." },
                      "parameters": [
                        {
                          "metadata": { "label": "Consumer Records",
                                        "description": "The batch of messages." },
                          "kind": "DATA_BINDING",
                          "name": { "value": "records", "enabled": false, "editable": true,
                                    "optional": false, "advanced": false },
                          "enabled": false, "editable": true, "optional": false, "advanced": false
                        },
                        {
                          "kind": "OPTIONAL",
                          "name": { "value": "caller",
                                    "metadata": { "label": "caller", "description": "Caller doc from name node." },
                                    "enabled": false, "editable": true, "optional": false, "advanced": false },
                          "enabled": false, "editable": true, "optional": true, "advanced": false
                        }
                      ]
                    }
                  ]
                }
              ]
            }
            """;

    @Test
    public void testIndexAndFunctionDocsLookup() {
        TriggerUISchemaModel model = GSON.fromJson(MODEL_JSON, TriggerUISchemaModel.class);
        TriggerUiDocs docs = TriggerUiDocs.index(model);
        Assert.assertFalse(docs.isEmpty());

        // Lookup works with and without the module prefix on the service-type name.
        Optional<TriggerUiDocs.FunctionDocs> byBareName = docs.functionDocs("Service", "onConsumerRecord");
        Optional<TriggerUiDocs.FunctionDocs> byPrefixedName = docs.functionDocs("kafka:Service", "onConsumerRecord");
        Assert.assertTrue(byBareName.isPresent());
        Assert.assertTrue(byPrefixedName.isPresent());

        TriggerUiDocs.FunctionDocs functionDocs = byBareName.get();
        Assert.assertEquals(functionDocs.description(), "Triggered when a batch of messages is received.");

        // Param 0: name from the name-property value, description from the parameter metadata.
        Assert.assertEquals(functionDocs.paramAt(0).orElseThrow().name(), "records");
        Assert.assertEquals(functionDocs.paramAt(0).orElseThrow().description(), "The batch of messages.");

        // Param 1: no parameter-level metadata — description falls back to the name node's metadata.
        Assert.assertEquals(functionDocs.paramAt(1).orElseThrow().name(), "caller");
        Assert.assertEquals(functionDocs.paramAt(1).orElseThrow().description(), "Caller doc from name node.");

        // Named lookup and out-of-range positional lookup.
        Assert.assertEquals(functionDocs.paramNamed("caller").orElseThrow().description(),
                "Caller doc from name node.");
        Assert.assertTrue(functionDocs.paramNamed("nope").isEmpty());
        Assert.assertTrue(functionDocs.paramNamed(null).isEmpty());
        Assert.assertTrue(functionDocs.paramAt(2).isEmpty());
        Assert.assertTrue(functionDocs.paramAt(-1).isEmpty());

        Assert.assertTrue(docs.functionDocs("Service", "noSuchFunction").isEmpty());
        Assert.assertTrue(docs.functionDocs("NoSuchType", "onConsumerRecord").isEmpty());
    }

    @Test
    public void testIndexNullAndEmptyModels() {
        Assert.assertTrue(TriggerUiDocs.index(null).isEmpty());
        Assert.assertTrue(TriggerUiDocs.index(GSON.fromJson("{}", TriggerUISchemaModel.class)).isEmpty());
        Assert.assertTrue(TriggerUiDocs.empty().isEmpty());
        Assert.assertTrue(TriggerUiDocs.empty().functionDocs("Service", "onError").isEmpty());
    }

    @Test
    public void testIndexSkipsMalformedEntriesAndKeepsFirstFunction() {
        String json = """
                {
                  "serviceTypes": [
                    { "functions": [] },
                    {
                      "name": "Service",
                      "functions": [
                        { "kind": "REMOTE", "enabled": false },
                        { "name": "onMessage", "kind": "REMOTE", "enabled": false,
                          "metadata": { "label": "onMessage", "description": "From functions." } }
                      ],
                      "schemaFunctions": [
                        { "name": "onMessage", "kind": "REMOTE", "enabled": false,
                          "metadata": { "label": "onMessage", "description": "From schemaFunctions." } },
                        { "name": "onError", "kind": "REMOTE", "enabled": false,
                          "parameters": [
                            { "kind": "REQUIRED",
                              "name": { "value": 42, "enabled": false, "editable": true,
                                        "optional": false, "advanced": false },
                              "enabled": false, "editable": true, "optional": false, "advanced": false }
                          ] }
                      ]
                    }
                  ]
                }
                """;
        TriggerUiDocs docs = TriggerUiDocs.index(GSON.fromJson(json, TriggerUISchemaModel.class));
        // The name-less service type and function are skipped; functions win over schemaFunctions.
        Assert.assertEquals(docs.functionDocs("Service", "onMessage").orElseThrow().description(),
                "From functions.");
        // A non-string name property value yields a null ParamDocs name and an empty description.
        TriggerUiDocs.FunctionDocs onError = docs.functionDocs("Service", "onError").orElseThrow();
        Assert.assertNull(onError.paramAt(0).orElseThrow().name());
        Assert.assertEquals(onError.paramAt(0).orElseThrow().description(), "");
        Assert.assertTrue(onError.paramNamed("anything").isEmpty());
    }

    @Test
    public void testLoadFromTestRegistry() {
        // A miniature bundled_trigger_models.json + trigger-models/testmod.json live in this
        // module's test resources, exercising the full successful load path.
        TriggerUiDocs docs = TriggerUiDocs.load("testmod", "1.0.0");
        Assert.assertFalse(docs.isEmpty());
        Assert.assertEquals(docs.functionDocs("Service", "onEvent").orElseThrow().description(),
                "Test event handler.");
        // A registry entry pointing at a missing resource degrades to empty.
        Assert.assertTrue(TriggerUiDocs.load("missing-resource", "1.0.0").isEmpty());
        // A module absent from the registry degrades to empty.
        Assert.assertTrue(TriggerUiDocs.load("unknown-module", "1.0.0").isEmpty());
    }

    @Test
    public void testStripModulePrefix() {
        Assert.assertEquals(TriggerUiDocs.stripModulePrefix("github:IssuesService"), "IssuesService");
        Assert.assertEquals(TriggerUiDocs.stripModulePrefix("Service"), "Service");
        Assert.assertNull(TriggerUiDocs.stripModulePrefix(null));
    }

    @Test
    public void testLoadForModuleAbsentFromRegistryYieldsEmpty() {
        // The production registry ships in the service-model-generator jar; the test classpath only
        // carries a miniature registry without a "kafka" entry — load() must degrade to empty.
        Assert.assertTrue(TriggerUiDocs.load("kafka", "4.6.5").isEmpty());
    }
}
