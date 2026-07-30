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
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.ballerina.modelgenerator.commons.trigger.models.TriggerMetadataModel.ServiceType.HandlerOption;
import io.ballerina.modelgenerator.commons.trigger.models.TriggerMetadataModel.ServiceType.Param;
import io.ballerina.modelgenerator.commons.trigger.models.TriggerUISchemaModel;
import io.ballerina.modelgenerator.commons.trigger.models.TypeRef;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Tests for {@link TriggerSchemaServiceLoader}'s pure rendering logic: metadata {@code TypeRef} →
 * service-index-form signature strings, return-union joining, and the small string helpers.
 *
 * @since 1.7.0
 */
public class TriggerSchemaServiceLoaderTest {

    private static final Predicate<String> KAFKA_TYPES =
            Set.of("AnydataConsumerRecord", "BytesConsumerRecord", "Caller", "Error", "Listener")::contains;
    private static final Predicate<String> NONE = name -> false;

    // ---- renderTypeRef -----------------------------------------------------------

    @Test
    public void testRenderTypeRefNull() {
        Assert.assertEquals(TriggerSchemaServiceLoader.renderTypeRef(null, "kafka", KAFKA_TYPES), "");
        Assert.assertEquals(TriggerSchemaServiceLoader.renderTypeRef(
                new TypeRef(null, null), "kafka", KAFKA_TYPES), "");
    }

    @Test
    public void testRenderTypeRefBuiltinsStayBare() {
        Assert.assertEquals(render("json"), "json");
        Assert.assertEquals(render("string[][]"), "string[][]");
        Assert.assertEquals(render("record {}"), "record {}");
        Assert.assertEquals(render("stream<string[], error?>"), "stream<string[], error?>");
        Assert.assertEquals(render("()"), "()");
        Assert.assertEquals(render("error"), "error");
        Assert.assertEquals(render("anydata"), "anydata");
    }

    @Test
    public void testRenderTypeRefModuleDeclaredTypesGetAliasPrefix() {
        Assert.assertEquals(render("AnydataConsumerRecord[]"), "kafka:AnydataConsumerRecord[]");
        Assert.assertEquals(render("Caller"), "kafka:Caller");
        Assert.assertEquals(render("Error"), "kafka:Error");
    }

    @Test
    public void testRenderTypeRefSubmoduleUsesAlias() {
        Predicate<String> githubTypes = Set.of("ListenerConfig", "IssuesEvent")::contains;
        Assert.assertEquals(TriggerSchemaServiceLoader.renderTypeRef(
                new TypeRef("IssuesEvent", null), "trigger.github", githubTypes), "github:IssuesEvent");
    }

    @Test
    public void testRenderTypeRefForeignPackageInfoUsesItsModuleAlias() {
        TypeRef cdcError = new TypeRef("Error",
                new TypeRef.PackageInfo("ballerinax", "cdc", "cdc", "1.3.2"));
        // Foreign module: prefixed with the foreign alias; TypeResolver later leaves it unlinked.
        Assert.assertEquals(TriggerSchemaServiceLoader.renderTypeRef(cdcError, "mssql", NONE), "cdc:Error");
    }

    @Test
    public void testRenderTypeRefSamePackagePackageInfoUsesOwnAlias() {
        TypeRef ownType = new TypeRef("Caller",
                new TypeRef.PackageInfo("ballerinax", "kafka", "kafka", "4.5.0"));
        Assert.assertEquals(TriggerSchemaServiceLoader.renderTypeRef(ownType, "kafka", NONE), "kafka:Caller");
    }

    @Test
    public void testRenderTypeRefForeignSubmoduleAlias() {
        TypeRef driverType = new TypeRef("Config",
                new TypeRef.PackageInfo("ballerinax", "mssql.cdc.driver", "mssql.cdc.driver", "1.0.2"));
        Assert.assertEquals(TriggerSchemaServiceLoader.renderTypeRef(driverType, "mssql", NONE), "driver:Config");
    }

    private static String render(String name) {
        return TriggerSchemaServiceLoader.renderTypeRef(new TypeRef(name, null), "kafka", KAFKA_TYPES);
    }

    // ---- renderReturns -----------------------------------------------------------

    @Test
    public void testRenderReturnsJoinsUnionMembers() {
        Assert.assertEquals(TriggerSchemaServiceLoader.renderReturns(
                List.of(new TypeRef("error", null), new TypeRef("()", null)), "kafka", NONE), "error|()");
        Assert.assertEquals(TriggerSchemaServiceLoader.renderReturns(
                List.of(new TypeRef("anydata", null), new TypeRef("error", null)), "rabbitmq", NONE),
                "anydata|error");
    }

    @Test
    public void testRenderReturnsScalarAndEmpty() {
        Assert.assertEquals(TriggerSchemaServiceLoader.renderReturns(
                List.of(new TypeRef("()", null)), "mssql", NONE), "()");
        Assert.assertEquals(TriggerSchemaServiceLoader.renderReturns(List.of(), "kafka", NONE), "");
        Assert.assertEquals(TriggerSchemaServiceLoader.renderReturns(null, "kafka", NONE), "");
    }

    // ---- helpers -------------------------------------------------------------------

    @Test
    public void testFirstTypeRefTakesCodegenDefault() {
        TypeRef first = new TypeRef("AnydataConsumerRecord[]", null);
        TypeRef second = new TypeRef("BytesConsumerRecord[]", null);
        Assert.assertSame(TriggerSchemaServiceLoader.firstTypeRef(List.of(first, second)), first);
        Assert.assertNull(TriggerSchemaServiceLoader.firstTypeRef(List.of()));
        Assert.assertNull(TriggerSchemaServiceLoader.firstTypeRef(null));
    }

    @Test
    public void testBaseIdentifier() {
        Assert.assertEquals(TriggerSchemaServiceLoader.baseIdentifier("AnydataConsumerRecord[]"),
                "AnydataConsumerRecord");
        Assert.assertEquals(TriggerSchemaServiceLoader.baseIdentifier("Caller"), "Caller");
        Assert.assertEquals(TriggerSchemaServiceLoader.baseIdentifier("record {}"), "record");
        Assert.assertNull(TriggerSchemaServiceLoader.baseIdentifier("()"));
        Assert.assertNull(TriggerSchemaServiceLoader.baseIdentifier(""));
        Assert.assertNull(TriggerSchemaServiceLoader.baseIdentifier(null));
    }

    @Test
    public void testGetAlias() {
        Assert.assertEquals(TriggerSchemaServiceLoader.getAlias("kafka"), "kafka");
        Assert.assertEquals(TriggerSchemaServiceLoader.getAlias("trigger.github"), "github");
        Assert.assertEquals(TriggerSchemaServiceLoader.getAlias("mssql.cdc.driver"), "driver");
    }

    @Test
    public void testIsSchemaDrivenCoversExactlyTheOnboardedLibraries() {
        Assert.assertTrue(TriggerSchemaServiceLoader.isSchemaDriven("ballerinax/kafka"));
        Assert.assertTrue(TriggerSchemaServiceLoader.isSchemaDriven("ballerinax/rabbitmq"));
        Assert.assertTrue(TriggerSchemaServiceLoader.isSchemaDriven("ballerina/ftp"));
        Assert.assertTrue(TriggerSchemaServiceLoader.isSchemaDriven("ballerina/mcp"));
        Assert.assertTrue(TriggerSchemaServiceLoader.isSchemaDriven("ballerinax/mssql"));
        Assert.assertTrue(TriggerSchemaServiceLoader.isSchemaDriven("ballerinax/trigger.github"));
        Assert.assertFalse(TriggerSchemaServiceLoader.isSchemaDriven("ballerinax/asb"));
        Assert.assertFalse(TriggerSchemaServiceLoader.isSchemaDriven("ballerina/http"));
    }

    @Test
    public void testLoadServicesMissingInputsYieldEmpty() {
        Assert.assertTrue(TriggerSchemaServiceLoader.loadServices("ballerinax/asb", null, null).isEmpty(),
                "Non-schema-driven library must yield empty");
        Assert.assertTrue(TriggerSchemaServiceLoader.loadServices("ballerinax/kafka", null, null).isEmpty(),
                "Missing package/semantic model must yield empty");
    }

    // ---- buildOptionMethods ----------------------------------------------------------

    private static final Gson GSON = new Gson();

    private static HandlerOption option(String name, String kind, String presence, List<Param> params,
                                        List<TypeRef> returns) {
        return new HandlerOption(name, kind, presence, null, params, returns, null, null, null, null, null);
    }

    private static Param param(String name, String type, String presence, String addMode) {
        return new Param(name, List.of(new TypeRef(type, null)), presence, addMode, null, null);
    }

    private static TriggerUiDocs docsFor(String modelJson) {
        return TriggerUiDocs.index(GSON.fromJson(modelJson, TriggerUISchemaModel.class));
    }

    private static final TriggerUiDocs SERVICE_DOCS = docsFor("""
            {
              "serviceTypes": [ {
                "name": "Service",
                "schemaFunctions": [ {
                  "name": "onEvent", "kind": "REMOTE", "enabled": false,
                  "metadata": { "label": "onEvent", "description": "Handles the event." },
                  "parameters": [ {
                    "metadata": { "label": "event", "description": "The event payload." },
                    "kind": "REQUIRED",
                    "name": { "value": "event", "enabled": false, "editable": true,
                              "optional": false, "advanced": false },
                    "enabled": false, "editable": true, "optional": false, "advanced": false
                  } ]
                } ]
              } ]
            }
            """);

    private static final List<TypeRef> ERROR_NIL =
            List.of(new TypeRef("error", null), new TypeRef("()", null));

    @Test
    public void testBuildOptionMethodsSkipsWildcardNullAndNamelessOptions() {
        JsonArray methods = TriggerSchemaServiceLoader.buildOptionMethods(
                java.util.Arrays.asList(
                        option("*", "remote", "optional", null, ERROR_NIL),
                        null,
                        option(null, "remote", "optional", null, ERROR_NIL)),
                "Service", NONE, TriggerUiDocs.empty(), "testmod");
        Assert.assertTrue(methods.isEmpty(), "Wildcard, null, and name-less options must be skipped");
        Assert.assertTrue(TriggerSchemaServiceLoader.buildOptionMethods(
                null, "Service", NONE, TriggerUiDocs.empty(), "testmod").isEmpty());
    }

    @Test
    public void testBuildOptionMethodsResourceKindAndNoParams() {
        JsonArray methods = TriggerSchemaServiceLoader.buildOptionMethods(
                List.of(option("chat", "resource", "required", null, ERROR_NIL)),
                "Service", NONE, TriggerUiDocs.empty(), "testmod");
        JsonObject method = methods.get(0).getAsJsonObject();
        Assert.assertEquals(method.get("type").getAsString(), "resource");
        Assert.assertFalse(method.has("parameters"), "No parameters key for a param-less option");
        Assert.assertFalse(method.has("description"), "No description without UI docs");
        Assert.assertFalse(method.has("optional"), "Function-level optional must never be emitted");
        Assert.assertEquals(method.getAsJsonObject("return").getAsJsonObject("type")
                .get("name").getAsString(), "error?");
    }

    @Test
    public void testBuildOptionMethodsParamNamePrecedenceAndDocs() {
        // Metadata name wins; description comes from the UI docs by name/position.
        JsonArray withMetadataName = TriggerSchemaServiceLoader.buildOptionMethods(
                List.of(option("onEvent", "remote", "optional",
                        List.of(param("payload", "json", "optional", null)), ERROR_NIL)),
                "Service", NONE, SERVICE_DOCS, "testmod");
        JsonObject method = withMetadataName.get(0).getAsJsonObject();
        Assert.assertEquals(method.get("description").getAsString(), "Handles the event.");
        JsonObject p = method.getAsJsonArray("parameters").get(0).getAsJsonObject();
        Assert.assertEquals(p.get("name").getAsString(), "payload");
        Assert.assertEquals(p.get("description").getAsString(), "The event payload.");
        Assert.assertTrue(p.get("optional").getAsBoolean());

        // Name-less metadata param takes the UI name positionally.
        JsonArray withUiName = TriggerSchemaServiceLoader.buildOptionMethods(
                List.of(option("onEvent", "remote", "optional",
                        List.of(param(null, "json", "required", null)), null)),
                "Service", NONE, SERVICE_DOCS, "testmod");
        JsonObject uiNamed = withUiName.get(0).getAsJsonObject()
                .getAsJsonArray("parameters").get(0).getAsJsonObject();
        Assert.assertEquals(uiNamed.get("name").getAsString(), "event");
        Assert.assertFalse(uiNamed.has("optional"));

        // Name-less metadata param with no UI docs gets a synthetic positional name.
        JsonArray synthetic = TriggerSchemaServiceLoader.buildOptionMethods(
                List.of(option("onUnknown", "remote", "optional",
                        List.of(param(null, "json", "required", null)), null)),
                "Service", NONE, TriggerUiDocs.empty(), "testmod");
        Assert.assertEquals(synthetic.get(0).getAsJsonObject()
                .getAsJsonArray("parameters").get(0).getAsJsonObject().get("name").getAsString(), "param1");
    }

    @Test
    public void testBuildOptionMethodsSkipsHandlersWithUndeclaredTypes() {
        // Metadata authored against a future release: "HubError" is not declared by the resolved
        // package — the handler must be skipped, not rendered uncompilable.
        JsonArray skipped = TriggerSchemaServiceLoader.buildOptionMethods(
                List.of(option("onHubError", "remote", "optional",
                        List.of(param(null, "HubError", "required", null)), ERROR_NIL)),
                "SubscriberService", NONE, TriggerUiDocs.empty(), "websub");
        Assert.assertTrue(skipped.isEmpty());

        // Same handler with the type declared: kept, with the type-derived param name.
        JsonArray kept = TriggerSchemaServiceLoader.buildOptionMethods(
                List.of(option("onHubError", "remote", "optional",
                        List.of(param(null, "HubError", "required", null)), ERROR_NIL)),
                "SubscriberService", Set.of("HubError")::contains, TriggerUiDocs.empty(), "websub");
        Assert.assertEquals(kept.get(0).getAsJsonObject().getAsJsonArray("parameters")
                .get(0).getAsJsonObject().get("name").getAsString(), "hubError");

        // Undeclared bare user types in the returns are equally disqualifying.
        Assert.assertTrue(TriggerSchemaServiceLoader.buildOptionMethods(
                List.of(option("onEvent", "remote", "optional", null,
                        List.of(new TypeRef("Acknowledgement", null)))),
                "Service", NONE, TriggerUiDocs.empty(), "websub").isEmpty());
    }

    @Test
    public void testDeriveParamNameReservedWordsAndCollisions() {
        // A declared type whose lower-camel form is a reserved word must not be derived.
        JsonArray reserved = TriggerSchemaServiceLoader.buildOptionMethods(
                List.of(option("onError", "remote", "optional",
                        List.of(param(null, "Error", "required", null)), null)),
                "Service", Set.of("Error")::contains, TriggerUiDocs.empty(), "testmod");
        Assert.assertEquals(reserved.get(0).getAsJsonObject().getAsJsonArray("parameters")
                .get(0).getAsJsonObject().get("name").getAsString(), "param1");

        // A derived name colliding with a sibling's explicit name falls back positionally.
        JsonArray collision = TriggerSchemaServiceLoader.buildOptionMethods(
                List.of(option("onTwo", "remote", "optional",
                        List.of(param("event", "Event", "required", null),
                                param(null, "Event", "optional", null)), null)),
                "Service", Set.of("Event")::contains, TriggerUiDocs.empty(), "testmod");
        JsonArray params = collision.get(0).getAsJsonObject().getAsJsonArray("parameters");
        Assert.assertEquals(params.get(0).getAsJsonObject().get("name").getAsString(), "event");
        Assert.assertEquals(params.get(1).getAsJsonObject().get("name").getAsString(), "param2");
    }

    @Test
    public void testBuildOptionMethodsSkipsRepeatableParamsAndNilReturns() {
        JsonArray methods = TriggerSchemaServiceLoader.buildOptionMethods(
                List.of(option("onTool", "remote", "optional",
                        List.of(param("meta", "ToolMeta", "required", null),
                                param(null, "string", "optional", "many")),
                        List.of(new TypeRef("()", null)))),
                "Service", Set.of("ToolMeta")::contains, TriggerUiDocs.empty(), "testmod");
        JsonObject method = methods.get(0).getAsJsonObject();
        Assert.assertEquals(method.getAsJsonArray("parameters").size(), 1,
                "addMode: many parameter slots must be skipped");
        Assert.assertEquals(method.getAsJsonArray("parameters").get(0).getAsJsonObject()
                .getAsJsonObject("type").get("name").getAsString(), "ToolMeta");
        Assert.assertFalse(method.has("return"), "A nil return carries no information");
    }
}
