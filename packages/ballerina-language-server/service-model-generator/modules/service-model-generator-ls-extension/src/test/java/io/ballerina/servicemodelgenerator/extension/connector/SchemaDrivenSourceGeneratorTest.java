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
import io.ballerina.servicemodelgenerator.extension.model.ServiceInitModel;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Verifies the schema-driven source generator's listener-argument collection (CHOICE + GROUP_SECTION
 * aware) against the connector-agnostic codedata walk.
 *
 * @since 1.8.0
 */
public class SchemaDrivenSourceGeneratorTest {

    private final Gson gson = new Gson();

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

        String block = SchemaDrivenSourceGenerator.buildServiceBlockForTrigger(creation, null);
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

        String block = SchemaDrivenSourceGenerator.buildServiceBlockForTrigger(creation, null);
        Assert.assertFalse(block.contains("listener hubspot:Listener"),
                "no new listener should be declared when attaching to an existing one, got:\n" + block);
        Assert.assertTrue(block.contains("service hubspot:ContactService on myHubspotListener {"),
                "service must attach to the selected existing listener, got:\n" + block);
    }

    @Test
    public void testListenerVarNameBallerinaTypeOverridesDefaultListenerTypeName() throws Exception {
        // MSSQL CDC's listener type is `mssql:CdcListener`, not `mssql:Listener` (the assumed default
        // for every other connector). The listenerVarName field's IDENTIFIER type carries the real
        // type via `ballerinaType`, and the generator must use it instead of `<protocol>:Listener`.
        Path creationPath = resource("connector_models/mssql_cdc/resources/service-creation.json");
        ServiceInitModel creation = gson.fromJson(
                Files.readString(creationPath, StandardCharsets.UTF_8), ServiceInitModel.class);

        String listener = SchemaDrivenSourceGenerator.buildListenerDeclaration(creation);
        Assert.assertTrue(listener.startsWith("listener mssql:CdcListener mssqlCdcListener = new ("),
                "listener type must come from listenerVarName's ballerinaType hint, got:\n" + listener);
    }

    @Test
    public void testTextSetFieldRendersAsArrayLiteralFromValues() throws Exception {
        // MSSQL CDC's `databaseNames` is a TEXT_SET: the UI submits its entries via `values` (a list),
        // leaving `value` empty. The generic leaf-rendering path must fall back to `values` and emit
        // an array literal, or the field silently drops out of the generated record.
        Path creationPath = resource("connector_models/mssql_cdc/resources/service-creation.json");
        ServiceInitModel creation = gson.fromJson(
                Files.readString(creationPath, StandardCharsets.UTF_8), ServiceInitModel.class);

        String listener = SchemaDrivenSourceGenerator.buildListenerDeclaration(creation);
        Assert.assertTrue(listener.contains("databaseNames: [\"db1\", \"db2\"]"),
                "TEXT_SET field must render as an array literal from `values`, got:\n" + listener);
    }

    private Path resource(String name) throws Exception {
        return Paths.get(getClass().getClassLoader().getResource(name).toURI());
    }
}
