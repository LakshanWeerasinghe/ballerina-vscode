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

    @Test
    public void testCdcOperationFlagsFoldIntoOptionsSkippedOperations() throws Exception {
        // MSSQL CDC's Insert/Update/Delete checkboxes are CDC_OPERATION_ENABLE flags: a deselected
        // one (value "false") contributes its op-code to `options.skippedOperations`, and an enabled
        // one contributes nothing. The fixture leaves Insert on, Update/Delete off, so only "u"/"d"
        // must appear — folded into a fresh `options` argument (the user left options empty).
        Path creationPath = resource("connector_models/mssql_cdc/resources/service-creation.json");
        ServiceInitModel creation = gson.fromJson(
                Files.readString(creationPath, StandardCharsets.UTF_8), ServiceInitModel.class);

        String listener = SchemaDrivenSourceGenerator.buildListenerDeclaration(creation);
        Assert.assertEquals(listener,
                "listener mssql:CdcListener mssqlCdcListener = new (database = {hostname: \"localhost\", "
                        + "port: 1433, username: \"sa\", password: \"pass\", databaseNames: [\"db1\", \"db2\"]}, "
                        + "options = {skippedOperations: [\"u\", \"d\"]});",
                "deselected CDC operations must fold into a trailing options.skippedOperations arg");
        Assert.assertFalse(listener.contains("enableCreate") || listener.contains("enableUpdate"),
                "CDC operation flags must not emit as their own listener args, got:\n" + listener);
    }

    @Test
    public void testMysqlCdcListenerDeclaration() throws Exception {
        // MySQL CDC: databases -> database.includedDatabases (a TEXT_SET, optional), no schemas/
        // databaseInstance, port 3306, listener type mysql:CdcListener, and Update/Delete deselected.
        Path creationPath = resource("connector_models/mysql_cdc/resources/service-creation.json");
        ServiceInitModel creation = gson.fromJson(
                Files.readString(creationPath, StandardCharsets.UTF_8), ServiceInitModel.class);

        String listener = SchemaDrivenSourceGenerator.buildListenerDeclaration(creation);
        Assert.assertEquals(listener,
                "listener mysql:CdcListener mysqlCdcListener = new (database = {hostname: \"localhost\", "
                        + "port: 3306, username: \"sa\", password: \"pass\", includedDatabases: [\"db1\"]}, "
                        + "options = {skippedOperations: [\"u\", \"d\"]});");
    }

    @Test
    public void testPostgresqlCdcListenerDeclaration() throws Exception {
        // PostgreSQL CDC: a single required databaseName (TEXT, not a set), schemas ->
        // database.includedSchemas, port 5432, listener type postgresql:CdcListener, and a fourth
        // Truncate operation flag (deselected here alongside Update/Delete).
        Path creationPath = resource("connector_models/postgresql_cdc/resources/service-creation.json");
        ServiceInitModel creation = gson.fromJson(
                Files.readString(creationPath, StandardCharsets.UTF_8), ServiceInitModel.class);

        String listener = SchemaDrivenSourceGenerator.buildListenerDeclaration(creation);
        Assert.assertEquals(listener,
                "listener postgresql:CdcListener postgresqlCdcListener = new (database = {hostname: "
                        + "\"localhost\", port: 5432, username: \"sa\", password: \"pass\", databaseName: "
                        + "\"mydb\", includedSchemas: [\"public\"]}, options = {skippedOperations: "
                        + "[\"u\", \"d\", \"t\"]});");
    }

    @Test
    public void testCdcFlagWithoutPathAndUnqualifiedListenerType() throws Exception {
        // The add-service submission for CDC uses a flat layout where the operation flags carry
        // CDC_OPERATION_ENABLE with only an originalName (no dotted path), and listenerVarName's type
        // hint is unqualified ("CdcListener"). The generator must (a) module-prefix the listener type
        // and (b) still fold the deselected flags into options.skippedOperations by convention.
        Path creationPath = resource("connector_models/mssql_cdc_flat/resources/service-creation.json");
        ServiceInitModel creation = gson.fromJson(
                Files.readString(creationPath, StandardCharsets.UTF_8), ServiceInitModel.class);

        String listener = SchemaDrivenSourceGenerator.buildListenerDeclaration(creation);
        Assert.assertTrue(listener.startsWith("listener mssql:CdcListener mssqlCdcListener = new ("),
                "unqualified listener type must be module-prefixed, got:\n" + listener);
        Assert.assertTrue(listener.contains("options = {skippedOperations: [\"u\"]}"),
                "a path-less CDC flag must still fold into options.skippedOperations, got:\n" + listener);
    }

    private Path resource(String name) throws Exception {
        return Paths.get(getClass().getClassLoader().getResource(name).toURI());
    }
}
