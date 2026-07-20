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
import io.ballerina.servicemodelgenerator.extension.connector.model.TriggerModel;
import io.ballerina.servicemodelgenerator.extension.model.ServiceInitModel;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.net.URL;
import java.nio.file.Paths;

/**
 * Unit test for {@link SchemaDrivenSourceGenerator}'s unified {@code TriggerModel} path: given the
 * derived init form (the user's filled listener choices) + the connector's {@code TriggerModel}, it
 * emits the {@code service <descriptor> on <listener> { ... }} block. Pure (no LS): exercises service
 * descriptor resolution and function rendering from the unified model.
 *
 * @since 1.9.0
 */
public class TriggerSourceGenerationTest {

    private ServiceInitModel initForm(String connector) throws Exception {
        URL fixture = getClass().getClassLoader().getResource("trigger_models/" + connector);
        Assert.assertNotNull(fixture, connector + " fixture missing");
        return ConnectorModelReader.getInstance()
                .readServiceInitModelFromPackageRoot(Paths.get(fixture.toURI())).orElseThrow();
    }

    private TriggerModel triggerModel(String connector) throws Exception {
        URL fixture = getClass().getClassLoader().getResource("trigger_models/" + connector);
        Assert.assertNotNull(fixture, connector + " fixture missing");
        return ConnectorModelReader.getInstance()
                .readTriggerModelFromPackageRoot(Paths.get(fixture.toURI())).orElseThrow();
    }

    @Test
    public void testKafkaServiceBlock() throws Exception {
        // kafka: single fixed service type (descriptor on serviceTypes[0], not the init form) and no
        // present handlers (all in schemaFunctions) -> empty service body.
        String src = SchemaDrivenSourceGenerator.buildServiceBlockForTrigger(initForm("kafka"), triggerModel("kafka"));
        Assert.assertTrue(src.contains("service kafka:Service on "),
                "descriptor should resolve from serviceTypes[0]: " + src);
        Assert.assertFalse(src.contains("remote function"),
                "kafka has no present functions -> empty body: " + src);
    }

    @Test
    public void testGithubServiceBlockEmitsDescriptorAndHandlers() throws Exception {
        // github: multi-type; the init form's serviceType selection (already module-qualified) drives
        // the descriptor (no double prefix), and that type's present handlers are emitted.
        String src = SchemaDrivenSourceGenerator.buildServiceBlockForTrigger(initForm("github"), triggerModel("github"));
        Assert.assertTrue(src.contains("service github:IssuesService on "),
                "descriptor must not be double-prefixed: " + src);
        Assert.assertTrue(
                src.contains("remote function onOpened(github:IssuesEvent payload) returns error?"),
                "handler should render from the unified FunctionModel (param type/name as Property): " + src);
    }

    @Test
    public void testHubspotServiceBlockGroupsRecordListenerParam() throws Exception {
        // Regression test: HubSpot's `Listener(ListenerConfig config, int|http:Listener listenOn)`
        // must emit `new({clientSecret: ..., callbackURL: ...}, listenOn)` — the record-typed
        // `config` param assembled into ONE record literal at its own position (1), followed by the
        // scalar `listenOn` at position 2. Previously clientSecret/callbackURL both landed at
        // position 1 as duplicate top-level args and listenOn was wrongly bumped to position 2 as a
        // third argument.
        String src = SchemaDrivenSourceGenerator.buildServiceBlockForTrigger(
                initForm("hubspot"), triggerModel("hubspot"));
        Assert.assertTrue(
                src.contains("new ({clientSecret: \"clientSecretValue\", callbackURL: \"callbackUrlValue\"}, 8090)"),
                "record-typed config param and scalar listenOn should render as ordered positional args: " + src);
    }

    @Test
    public void testSmbListenerNestsChoiceScopedIncludedFields() throws Exception {
        // Regression test: SMB's `auth` is a nested CHOICE (Username & Password / Kerberos) inside
        // the `listenerConfig` GROUP_SECTION, itself inside the top-level "Create New Listener"
        // branch. Its selected branch's fields carry dotted paths (auth.credentials.username,
        // auth.credentials.password) that cross the CHOICE boundary. Previously these were
        // flattened into bogus top-level named args (`username = ..., password = ...`) instead of
        // nesting into `auth = {credentials: {username: ..., password: ...}}` -- which doesn't
        // compile against smb:ListenerConfiguration (no such flat fields) and is what made "fill
        // the form and save" produce no usable service.
        String src = SchemaDrivenSourceGenerator.buildListenerDeclaration(initForm("smb"));
        Assert.assertTrue(src.contains("auth = {credentials: {username: \"user\", password: \"password\"}}"),
                "auth's selected branch should nest into one record literal named arg: " + src);
        Assert.assertFalse(src.contains("username = \"user\""),
                "username must not leak out as a bogus top-level named arg: " + src);
        Assert.assertFalse(src.contains("password = \"password\""),
                "password must not leak out as a bogus top-level named arg: " + src);
    }

    @Test
    public void testFtpListenerNestsChoiceScopedIncludedFields() throws Exception {
        // Same class of bug as SMB, on the flagship fixture: FTP's protocol CHOICE selects "FTP",
        // whose `auth` is a GROUP_SECTION (not a CHOICE, but the same argType/dotted-path shape)
        // with dotted paths auth.credentials.username / auth.credentials.password. This path was
        // never exercised by a source-generation test before, so the same flattening bug applied
        // here too -- confirms the fix is connector-agnostic, not an SMB-only patch.
        String src = SchemaDrivenSourceGenerator.buildListenerDeclaration(initForm("ftp"));
        Assert.assertTrue(src.contains("auth = {credentials: {username: \"user\", password: \"password\"}}"),
                "auth should nest into one record literal named arg: " + src);
        Assert.assertFalse(src.contains("username = \"user\""),
                "username must not leak out as a bogus top-level named arg: " + src);
    }

    @Test
    public void testEnumValueQualifierFollowsTheAliasedModulePrefix() throws Exception {
        // Regression test for the reported FTP breakage: with `import ballerina/file as ftp;` already in
        // the file, `ballerina/ftp` must be imported as `ftp2` and EVERY reference has to follow —
        // including the enum literal carried by `codedata.valueQualifier` on the selected branch of the
        // protocol choice. Emitting the authored `protocol = ftp:FTP` resolves against ballerina/file,
        // which has no such member. Types and annotations were already handled; the qualifier was not.
        String baseline = SchemaDrivenSourceGenerator.buildListenerDeclaration(initForm("ftp"));
        Assert.assertTrue(baseline.contains("protocol = ftp:FTP"),
                "unaliased baseline: the natural prefix stands when nothing shadows it: " + baseline);

        // A fresh parse (the pre-pass mutates the model in place), generated for a file where the
        // natural `ftp` prefix is already bound to another module.
        String block = SchemaDrivenSourceGenerator.buildServiceBlockForTrigger(
                initForm("ftp"), triggerModel("ftp"), "ftp2");
        Assert.assertTrue(block.contains("protocol = ftp2:FTP"),
                "the enum literal's qualifier must follow the emitted alias: " + block);
        Assert.assertFalse(block.contains("ftp:FTP"),
                "no reference may keep the shadowed prefix: " + block);
        Assert.assertTrue(block.contains("listener ftp2:Listener"),
                "listener type must use the alias too: " + block);
    }

    @Test
    public void testMultipleExistingListenersAttach() throws Exception {
        // Use-existing branch with a MULTIPLE_SELECT_LISTENER holding several selected names ->
        // the service attaches to all of them and declares no new listener.
        String json = """
                { "moduleName":"kafka","orgName":"ballerinax","type":"kafka",
                  "properties":{
                    "listener":{"enabled":true,"editable":true,"optional":false,"advanced":false,
                      "types":[{"fieldType":"CHOICE","selected":true}],
                      "codedata":{"type":"LISTENER_CONFIG"},
                      "choices":[
                        {"enabled":false,"editable":true,"optional":false,"advanced":false,"properties":{}},
                        {"enabled":true,"editable":true,"optional":false,"advanced":false,
                         "properties":{"existingListener":{"enabled":true,"editable":true,"optional":false,
                           "advanced":false,"values":["kafkaListener1","kafkaListener2"],
                           "types":[{"fieldType":"MULTIPLE_SELECT_LISTENER","selected":true}],
                           "codedata":{"type":"KEY_EXISTING_LISTENER"}}}}
                      ]}}}""";
        ServiceInitModel filled = new Gson().fromJson(json, ServiceInitModel.class);
        String src = SchemaDrivenSourceGenerator.buildServiceBlockForTrigger(filled, triggerModel("kafka"));
        Assert.assertTrue(src.contains("service kafka:Service on kafkaListener1, kafkaListener2 {"),
                "multiple existing listeners should attach as `on l1, l2`: " + src);
        Assert.assertFalse(src.contains("listener kafka:Listener"),
                "no new listener declared when attaching to existing: " + src);
    }
}
