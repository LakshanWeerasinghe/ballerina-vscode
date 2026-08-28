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

package io.ballerina.modelgenerator.commons.trigger;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.ballerina.modelgenerator.commons.ModuleInfo;
import io.ballerina.modelgenerator.commons.trigger.models.TriggerMetadataModel;
import io.ballerina.modelgenerator.commons.trigger.models.TriggerUIMetadataModel;
import io.ballerina.projects.SemanticVersion;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Round-trips every packaged {@code trigger-metadata-models/<module>/trigger-metadata.json} sample
 * through {@link LibraryMetadataReader}, catching a sample that silently fails to bind against
 * {@link TriggerMetadataModel} or the reader's version gate.
 */
public class PackagedTriggerModelsTest {

    private static final LibraryMetadataReader READER = LibraryMetadataReader.getInstance();

    @DataProvider(name = "packagedModules")
    public Object[][] packagedModules() {
        return new Object[][] {
                {"ftp"}, {"kafka"}, {"mcp"}, {"mssql"}, {"rabbitmq"}, {"smb"},
                {"trigger.github"}, {"trigger.google.calendar"}, {"websub"},
                {"http"}, {"graphql"}, {"grpc"}, {"websocket"}, {"sap.jco"},
                {"solace"}, {"solace.jms"}, {"mqtt"}, {"asb"}, {"aws.sqs"},
                {"mysql"}, {"postgresql"}, {"oracledb"}, {"salesforce"},
                {"trigger.shopify"}, {"trigger.hubspot"}, {"trigger.twilio"},
                {"whatsapp.business"}, {"telegram"}, {"googleapis.chat"}, {"file"},
                {"azure.storage.files"}, {"tcp"},
        };
    }

    @Test(dataProvider = "packagedModules")
    public void testPackagedSampleParsesAndValidates(String moduleName) {
        ModuleInfo moduleInfo = new ModuleInfo("ballerinax", moduleName, moduleName, "1.0.0");
        TriggerMetadataModel model = READER.getPackagedTriggerMetadataModel(moduleInfo)
                .orElseThrow(() -> new AssertionError(moduleName + " failed to parse or pass the version gate"));
        Assert.assertTrue(model.version() != null && model.version().startsWith("v1."),
                moduleName + " has an unexpected version: " + model.version());
        Assert.assertFalse(model.listeners() == null || model.listeners().isEmpty(), moduleName + " has no listeners");
        Assert.assertFalse(model.serviceTypes() == null || model.serviceTypes().isEmpty(),
                moduleName + " has no serviceTypes");
    }

    /**
     * Connectors deliberately authored with no {@code listeners} overlay at all: the connector is
     * presented as a flat form, described entirely by {@code initForm}, rather than a create-new/use-
     * existing listener choice -- see {@code TriggerUIMetadataCompiler#flattenListenerWhenUnauthored}.
     */
    private static final Set<String> NO_LISTENER_OVERLAY = Set.of("file");

    @Test(dataProvider = "packagedModules")
    public void testPackagedUIMetadataParsesAndTargetsKnownSections(String moduleName) {
        ModuleInfo moduleInfo = new ModuleInfo("ballerinax", moduleName, moduleName, "1.0.0");
        TriggerUIMetadataModel model = READER.getPackagedTriggerUIMetadataModel(moduleInfo)
                .orElseThrow(() -> new AssertionError(moduleName + " UI metadata failed to parse"));
        Assert.assertTrue(model.version().startsWith("v1."), moduleName + " has an unexpected UI version");
        Assert.assertNotNull(model.trigger(), moduleName + " has no trigger metadata");
        if (!NO_LISTENER_OVERLAY.contains(moduleName)) {
            Assert.assertFalse(model.listeners() == null || model.listeners().isEmpty(),
                    moduleName + " has no listener overlays");
        }
        Assert.assertFalse(model.serviceTypes() == null || model.serviceTypes().isEmpty(),
                moduleName + " has no service type overlays");
    }

    // ---- the `variants` envelope is an LS packaging convention, not part of the L2 spec (see
    //      LibraryMetadataReader#selectUIMetadataVariant); this guards its shape against a typo'd root
    //      key, which Gson would otherwise silently drop during binding rather than reject. ----

    private static final Set<String> L2_DOCUMENT_KEYS = Set.of(
            "version", "extends", "metadata", "trigger", "readOnlyMetadata", "initForm", "listeners",
            "serviceTypes", "importPrefix");

    @Test(dataProvider = "packagedModules")
    public void testPackagedUIMetadataEnvelopeShapeIsValid(String moduleName) throws IOException {
        JsonObject root = readRawPackagedUIMetadata(moduleName);
        if (root.has("variants")) {
            Assert.assertEquals(root.keySet(), Set.of("variants"),
                    moduleName + ": a `variants` envelope must carry no sibling keys");
            JsonArray variants = root.getAsJsonArray("variants");
            Assert.assertFalse(variants.isEmpty(), moduleName + ": variants must not be empty");
            String previousMinVersion = null;
            for (int i = 0; i < variants.size(); i++) {
                JsonObject variant = variants.get(i).getAsJsonObject();
                Assert.assertTrue(Set.of("minVersion", "model").containsAll(variant.keySet()),
                        moduleName + ": variant " + i + " has unexpected keys " + variant.keySet());
                Assert.assertTrue(variant.has("model"), moduleName + ": variant " + i + " has no model");
                assertL2DocumentShape(moduleName + " variant[" + i + "]", variant.getAsJsonObject("model"));

                String minVersion = variant.has("minVersion") && !variant.get("minVersion").isJsonNull()
                        ? variant.get("minVersion").getAsString() : null;
                Assert.assertTrue(minVersion != null || i == variants.size() - 1,
                        moduleName + ": an unversioned (\"matches anything\") variant must be last, found at "
                                + i);
                if (previousMinVersion != null && minVersion != null) {
                    Assert.assertTrue(
                            SemanticVersion.from(previousMinVersion).greaterThanOrEqualTo(
                                    SemanticVersion.from(minVersion)),
                            moduleName + ": variants must be ordered newest to oldest by minVersion, but "
                                    + previousMinVersion + " precedes " + minVersion);
                }
                previousMinVersion = minVersion;
            }
        } else {
            assertL2DocumentShape(moduleName, root);
        }
    }

    private static void assertL2DocumentShape(String label, JsonObject document) {
        Assert.assertTrue(L2_DOCUMENT_KEYS.containsAll(document.keySet()),
                label + ": unexpected top-level key(s) outside the L2 spec's root property set -- found "
                        + document.keySet());
    }

    /** Reads the raw JSON, bypassing {@link TriggerUIMetadataModel} binding: Gson silently drops
     * unmodeled fields, which is exactly the drift this test exists to catch. */
    private static JsonObject readRawPackagedUIMetadata(String moduleName) throws IOException {
        String resource = "trigger-metadata-models/" + moduleName + "/trigger-ui-metadata.json";
        try (InputStream is = PackagedTriggerModelsTest.class.getClassLoader().getResourceAsStream(resource)) {
            Assert.assertNotNull(is, "missing classpath resource " + resource);
            try (InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                JsonElement parsed = JsonParser.parseReader(reader);
                Assert.assertTrue(parsed.isJsonObject(), moduleName + ": root must be a JSON object");
                return parsed.getAsJsonObject();
            }
        }
    }

    @Test
    public void testMcpVersionVariants() {
        TriggerUIMetadataModel current = READER.getPackagedTriggerUIMetadataModel(
                new ModuleInfo("ballerina", "mcp", "mcp", "1.2.0")).orElseThrow();
        Assert.assertEquals(current.listeners().size(), 2);
        Assert.assertEquals(current.serviceTypes().size(), 2);

        TriggerUIMetadataModel legacy = READER.getPackagedTriggerUIMetadataModel(
                new ModuleInfo("ballerina", "mcp", "mcp", "1.0.3")).orElseThrow();
        Assert.assertEquals(legacy.listeners().size(), 1);
        Assert.assertEquals(legacy.serviceTypes().size(), 1);
        Assert.assertEquals(legacy.serviceTypes().getFirst().target().id(), "$service");
    }
}
