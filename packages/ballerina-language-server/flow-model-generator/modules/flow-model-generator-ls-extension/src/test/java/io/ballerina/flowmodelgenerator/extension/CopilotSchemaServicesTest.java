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

package io.ballerina.flowmodelgenerator.extension;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.ballerina.compiler.api.SemanticModel;
import io.ballerina.compiler.api.symbols.ClassSymbol;
import io.ballerina.compiler.api.symbols.Symbol;
import io.ballerina.compiler.api.symbols.TypeDefinitionSymbol;
import io.ballerina.flowmodelgenerator.core.copilot.CopilotLibraryManager;
import io.ballerina.flowmodelgenerator.core.copilot.model.Annotation;
import io.ballerina.flowmodelgenerator.core.copilot.model.Library;
import io.ballerina.flowmodelgenerator.core.copilot.model.Type;
import io.ballerina.flowmodelgenerator.core.copilot.model.TypeLink;
import io.ballerina.flowmodelgenerator.core.copilot.service.ServiceLoader;
import io.ballerina.modelgenerator.commons.PackageUtil;
import io.ballerina.projects.Package;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * End-to-end tests for the schema-driven Copilot service loader: trigger metadata (structure) +
 * semantic model (listener params, concrete methods + their doc comments, validation), entered
 * through the public
 * {@link ServiceLoader#loadAllServices(String, Package, SemanticModel)} overload — exactly the call
 * {@code CopilotLibraryManager} makes.
 *
 * <p>Each library resolves its latest cached/central package, so assertions pin only
 * version-stable facts (handler vocabulary, param names, doc presence, type link shapes) rather
 * than full golden documents.
 *
 * @since 1.7.0
 */
public class CopilotSchemaServicesTest {

    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().create();
    private static final Path OUTPUT_DIR = Path.of("build", "services-comparison");
    private static final String MCP = "ballerina/mcp";
    private static final String MCP_PREFIX = "mcp:";
    // The last mcp release before StreamableHttpService / StreamableHttpAdvancedService /
    // StreamableHttpListener existed. Pinned so the loader's skip + listener-fallback guards stay
    // covered once Central catches up with the metadata (see the pinned test below).
    private static final String MCP_PRE_STREAMABLE_HTTP_VERSION = "1.1.0";

    private final Map<String, JsonArray> cache = new HashMap<>();
    private final Map<String, SemanticModel> semanticModels = new HashMap<>();

    private JsonArray load(String libraryName) {
        return cache.computeIfAbsent(libraryName, lib -> {
            String[] parts = lib.split("/");
            Optional<Package> pkgOpt = PackageUtil.getModulePackage(
                    PackageUtil.getSampleProject(), parts[0], parts[1]);
            if (pkgOpt.isEmpty()) {
                throw new SkipException("Could not resolve package for " + lib);
            }
            Package pkg = pkgOpt.get();
            SemanticModel semanticModel = PackageUtil.getCompilation(pkg)
                    .getSemanticModel(pkg.getDefaultModule().moduleId());
            semanticModels.put(lib, semanticModel);
            JsonArray services = ServiceLoader.loadAllServices(lib, pkg, semanticModel);
            dump(lib, services);
            return services;
        });
    }

    /**
     * Loads services against one explicitly pinned package version instead of whatever Central
     * currently serves, so a test can assert behaviour that depends on which symbols that version
     * ships.
     */
    private JsonArray loadPinned(String libraryName, String version) {
        String[] parts = libraryName.split("/");
        Optional<Package> pkgOpt = PackageUtil.getModulePackage(
                PackageUtil.getSampleProject(), parts[0], parts[1], version);
        if (pkgOpt.isEmpty()) {
            throw new SkipException("Could not resolve " + libraryName + ":" + version);
        }
        Package pkg = pkgOpt.get();
        SemanticModel semanticModel = PackageUtil.getCompilation(pkg)
                .getSemanticModel(pkg.getDefaultModule().moduleId());
        JsonArray services = ServiceLoader.loadAllServices(libraryName, pkg, semanticModel);
        dump(libraryName + "_" + version, services);
        return services;
    }

    /**
     * Names of the module-level type definitions and classes the resolved package version declares.
     * Every emitted service type and listener must come from this vocabulary, whichever version
     * Central happens to serve — that is the loader's contract, stated without pinning a release.
     */
    private Set<String> declaredNames(String libraryName) {
        load(libraryName);
        SemanticModel semanticModel = semanticModels.get(libraryName);
        Assert.assertNotNull(semanticModel, "No semantic model cached for " + libraryName);
        Set<String> names = new HashSet<>();
        for (Symbol symbol : semanticModel.moduleSymbols()) {
            if (symbol instanceof TypeDefinitionSymbol || symbol instanceof ClassSymbol) {
                symbol.getName().ifPresent(names::add);
            }
        }
        return names;
    }

    private void dump(String label, JsonArray services) {
        try {
            Path dir = OUTPUT_DIR.resolve(label.replaceAll("[^A-Za-z0-9]", "_") + "_schema");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("services.json"), PRETTY.toJson(services));
        } catch (IOException e) {
            // Dumps are for manual review only; never fail the test on IO.
        }
    }

    // ---- helpers -------------------------------------------------------------------

    private static JsonObject serviceNamed(JsonArray services, String name) {
        for (JsonElement element : services) {
            JsonObject svc = element.getAsJsonObject();
            if (svc.has("name") && name.equals(svc.get("name").getAsString())) {
                return svc;
            }
        }
        Assert.fail("No service named " + name + " in " + services);
        return null;
    }

    private static JsonObject methodNamed(JsonObject service, String name) {
        for (JsonElement element : service.getAsJsonArray("methods")) {
            JsonObject method = element.getAsJsonObject();
            if (name.equals(method.get("name").getAsString())) {
                return method;
            }
        }
        Assert.fail("No method named " + name + " in " + service);
        return null;
    }

    private static List<String> methodNames(JsonObject service) {
        List<String> names = new ArrayList<>();
        if (!service.has("methods")) {
            return names;
        }
        service.getAsJsonArray("methods").forEach(m ->
                names.add(m.getAsJsonObject().get("name").getAsString()));
        return names;
    }

    private static JsonObject paramNamed(JsonObject method, String name) {
        for (JsonElement element : method.getAsJsonArray("parameters")) {
            JsonObject param = element.getAsJsonObject();
            if (name.equals(param.get("name").getAsString())) {
                return param;
            }
        }
        Assert.fail("No parameter named " + name + " in " + method);
        return null;
    }

    private static List<String> paramNames(JsonObject method) {
        List<String> names = new ArrayList<>();
        if (!method.has("parameters")) {
            return names;
        }
        method.getAsJsonArray("parameters").forEach(p ->
                names.add(p.getAsJsonObject().get("name").getAsString()));
        return names;
    }

    private static void assertInternalLink(JsonObject typed, String recordName) {
        JsonObject type = typed.getAsJsonObject("type");
        Assert.assertTrue(type.has("links"), "Expected links on type " + type);
        JsonObject link = type.getAsJsonArray("links").get(0).getAsJsonObject();
        Assert.assertEquals(link.get("category").getAsString(), "internal");
        Assert.assertEquals(link.get("recordName").getAsString(), recordName);
    }

    // ---- kafka -----------------------------------------------------------------------

    @Test
    public void testKafkaSchemaServices() {
        JsonArray services = load("ballerinax/kafka");
        Assert.assertEquals(services.size(), 1);

        JsonObject service = serviceNamed(services, "Service");
        Assert.assertEquals(service.get("type").getAsString(), "fixed");

        JsonObject listener = service.getAsJsonObject("listener");
        Assert.assertEquals(listener.get("name").getAsString(), "kafka:Listener");
        List<String> initParams = new ArrayList<>();
        listener.getAsJsonArray("parameters").forEach(p ->
                initParams.add(p.getAsJsonObject().get("name").getAsString()));
        Assert.assertTrue(initParams.contains("bootstrapServers"),
                "Expected bootstrapServers in " + initParams);
        Assert.assertTrue(initParams.contains("config"), "Expected config in " + initParams);
        JsonObject bootstrapServers = null;
        for (JsonElement p : listener.getAsJsonArray("parameters")) {
            if ("bootstrapServers".equals(p.getAsJsonObject().get("name").getAsString())) {
                bootstrapServers = p.getAsJsonObject();
            }
        }
        Assert.assertNotNull(bootstrapServers);
        Assert.assertFalse(bootstrapServers.get("description").getAsString().isEmpty(),
                "Listener param docs must come from the init method's parameterMap");

        Assert.assertEquals(methodNames(service), List.of("onConsumerRecord", "onError"));

        JsonObject onConsumerRecord = methodNamed(service, "onConsumerRecord");
        Assert.assertEquals(onConsumerRecord.get("type").getAsString(), "remote");
        // FLAG: a marker service type declares no methods, so neither the metadata document nor the
        // library carries a handler description — the key is omitted, never fabricated.
        Assert.assertFalse(onConsumerRecord.has("description"),
                "Marker-type handlers have no description source");
        // Spec §5 scopes `presence` to `addMode: "subset"`, which is kafka's mode — so the document's
        // `presence: "required"` must reach the wire. It used to be dropped, which left a mandatory handler
        // indistinguishable from a skippable one; `onError` below is the optional counterpart.
        Assert.assertTrue(onConsumerRecord.has("optional"),
                "A subset option's presence must be stated, not dropped");
        Assert.assertFalse(onConsumerRecord.get("optional").getAsBoolean(),
                "kafka's onConsumerRecord declares presence: \"required\"");

        // The metadata document states no names for these slots (a handler param name is the service
        // author's choice), so they are generated: the AnydataX|BytesX union collapses to one stable
        // name, and the first union member supplies the type.
        Assert.assertEquals(paramNames(onConsumerRecord), List.of("consumerRecords", "caller"));
        JsonObject records = paramNamed(onConsumerRecord, "consumerRecords");
        Assert.assertEquals(records.getAsJsonObject("type").get("name").getAsString(),
                "AnydataConsumerRecord[]");
        assertInternalLink(records, "AnydataConsumerRecord[]");
        JsonObject caller = paramNamed(onConsumerRecord, "caller");
        Assert.assertTrue(caller.get("optional").getAsBoolean(),
                "presence: optional must map to the param optional flag");

        Assert.assertEquals(onConsumerRecord.getAsJsonObject("return")
                .getAsJsonObject("type").get("name").getAsString(), "error?");

        JsonObject onError = methodNamed(service, "onError");
        // A bare `Error` slot is generated as <alias>Error — never the keyword `error`.
        Assert.assertEquals(paramNames(onError), List.of("kafkaError"));
        Assert.assertEquals(paramNamed(onError, "kafkaError").getAsJsonObject("type")
                .get("name").getAsString(), "Error");
        // The optional counterpart of onConsumerRecord above: both states are expressible, which is the
        // whole point of stating presence at all.
        Assert.assertTrue(onError.get("optional").getAsBoolean(),
                "kafka's onError declares presence: \"optional\"");
    }

    // ---- rabbitmq ----------------------------------------------------------------------

    @Test
    public void testRabbitmqSchemaServices() {
        JsonArray services = load("ballerinax/rabbitmq");
        JsonObject service = serviceNamed(services, "Service");

        Assert.assertEquals(methodNames(service), List.of("onMessage", "onRequest", "onError"));

        JsonObject onRequest = methodNamed(service, "onRequest");
        // Generated names, which here reproduce exactly what the retired service-index carried.
        Assert.assertEquals(paramNames(onRequest), List.of("message", "caller"));
        Assert.assertEquals(onRequest.getAsJsonObject("return")
                .getAsJsonObject("type").get("name").getAsString(), "anydata|error");
        Assert.assertFalse(onRequest.has("description"), "Marker-type handler: no description source");

        JsonObject onError = methodNamed(service, "onError");
        Assert.assertEquals(paramNames(onError), List.of("message", "rabbitmqError"));
    }

    // ---- ftp ---------------------------------------------------------------------------

    @Test
    public void testFtpSchemaServices() {
        JsonArray services = load("ballerina/ftp");
        JsonObject service = serviceNamed(services, "Service");

        // The metadata's handler vocabulary, including onFileChange (absent from the old index).
        Assert.assertEquals(methodNames(service), List.of("onFileCsv", "onFileJson", "onFileXml",
                "onFileText", "onFile", "onFileDelete", "onError", "onFileChange"));

        // Metadata structure wins: onFileJson has no caller; names come from the metadata file.
        JsonObject onFileJson = methodNamed(service, "onFileJson");
        Assert.assertEquals(paramNames(onFileJson), List.of("content", "fileInfo"));
        Assert.assertEquals(paramNamed(onFileJson, "content").getAsJsonObject("type")
                .get("name").getAsString(), "json");
        assertInternalLink(paramNamed(onFileJson, "fileInfo"), "FileInfo");

        // First union member is the codegen default for the CSV content type.
        JsonObject onFileCsv = methodNamed(service, "onFileCsv");
        Assert.assertEquals(paramNamed(onFileCsv, "contents").getAsJsonObject("type")
                .get("name").getAsString(), "string[][]");
        Assert.assertTrue(paramNamed(onFileCsv, "caller").get("optional").getAsBoolean());
    }

    // ---- mssql (metadata keyed as mssql.cdc) ---------------------------------------------

    @Test
    public void testMssqlSchemaServices() {
        JsonArray services = load("ballerinax/mssql");
        JsonObject service = serviceNamed(services, "Service");

        JsonObject listener = service.getAsJsonObject("listener");
        Assert.assertEquals(listener.get("name").getAsString(), "mssql:CdcListener",
                "The metadata-declared CdcListener must validate against the resolved package");

        // Spec §1: the service type belongs to ballerinax/cdc, not to the home module, so it is
        // written with its own module's alias — `service cdc:Service on new mssql:CdcListener(...)`.
        // `mssql:Service` would not compile.
        Assert.assertEquals(service.get("serviceTypeModule").getAsString(), "ballerinax/cdc");

        // Spec §2: the listener's side-effect import travels with the service that uses it. The
        // foreign service type itself is NOT imported - provenance is carried by the renderer's
        // Special Agent Note, the same mechanism every other cross-module reference uses.
        List<String> imports = new ArrayList<>();
        for (JsonElement element : service.getAsJsonArray("requiredImports")) {
            JsonObject entry = element.getAsJsonObject();
            imports.add(entry.get("module").getAsString()
                    + (entry.has("alias") ? " as " + entry.get("alias").getAsString() : ""));
        }
        Assert.assertTrue(imports.contains("ballerinax/mssql.cdc.driver as _"),
                "Spec §2 side-effect import missing, got " + imports);
        Assert.assertFalse(imports.contains("ballerinax/cdc"),
                "A foreign service type must not be imported, got " + imports);

        Assert.assertEquals(methodNames(service),
                List.of("onRead", "onCreate", "onUpdate", "onDelete", "onError"));

        JsonObject onUpdate = methodNamed(service, "onUpdate");
        Assert.assertEquals(paramNames(onUpdate), List.of("beforeEntry", "afterEntry", "tableName"));
        Assert.assertEquals(paramNamed(onUpdate, "beforeEntry").getAsJsonObject("type")
                .get("name").getAsString(), "record {}");

        // Cross-module TypeRef: prefixed with the foreign alias and never linked.
        JsonObject onError = methodNamed(service, "onError");
        JsonObject cdcError = paramNamed(onError, "cdcError");
        Assert.assertEquals(cdcError.getAsJsonObject("type").get("name").getAsString(), "cdc:Error");
        Assert.assertFalse(cdcError.getAsJsonObject("type").has("links"));

        // Metadata declares returns: () — a nil return carries no information and must be omitted.
        Assert.assertFalse(onError.has("return"));
    }

    @Test
    public void testMssqlCrossModuleServiceAnnotationCarriesItsConstraint() {
        // Spec §8 across a module boundary — the case the whole annotation phase exists for. mssql's
        // document declares a REQUIRED annotation that belongs to ballerinax/cdc, and generated CDC code
        // without it does not work.
        JsonObject service = serviceNamed(load("ballerinax/mssql"), "Service");

        Assert.assertTrue(service.has("annotations"), "the required annotation must reach the catalog");
        JsonArray annotations = service.getAsJsonArray("annotations");
        Assert.assertEquals(annotations.size(), 1);
        JsonObject annotation = annotations.get(0).getAsJsonObject();

        Assert.assertEquals(annotation.get("name").getAsString(), "ServiceConfig");
        Assert.assertEquals(annotation.get("presence").getAsString(), "required");
        Assert.assertEquals(annotation.get("attachPoint").getAsString(), "service");
        // Spec §1: it belongs to another module, so it states that module and renders `@cdc:ServiceConfig`.
        Assert.assertEquals(annotation.get("module").getAsString(), "ballerinax/cdc");

        // The document names the annotation TAG (`ServiceConfig`); its constraining record is called
        // something else entirely (`CdcServiceConfig`) and is declared in a different package. It is
        // introspected from the compiler — the foreign module's symbols are already in this compilation,
        // because a module whose annotation the generated code must attach is necessarily a dependency.
        JsonObject constraint = annotation.getAsJsonObject("typeConstraint");
        Assert.assertNotNull(constraint, "a required annotation with no field source is unusable");
        Assert.assertEquals(constraint.get("name").getAsString(), "CdcServiceConfig");

        // An EXTERNAL link is what carries the record's definition into the prompt, via the same
        // reachability mechanism every other cross-package type reference already uses.
        JsonObject link = constraint.getAsJsonArray("links").get(0).getAsJsonObject();
        Assert.assertEquals(link.get("category").getAsString(), "external");
        Assert.assertEquals(link.get("recordName").getAsString(), "CdcServiceConfig");
        Assert.assertEquals(link.get("libraryName").getAsString(), "ballerinax/cdc");
    }

    @Test
    public void testHomeModuleServiceAnnotationConstraintIsIntrospectedNotGuessed() {
        // ballerina/ftp's document says `type: {"name": "ServiceConfig"}` while the package declares
        // `public annotation ServiceConfiguration ServiceConfig on service;`. Reading the document's name
        // as a type name would emit an attachment constrained by a record that does not exist.
        JsonObject annotation = serviceNamed(load("ballerina/ftp"), "Service")
                .getAsJsonArray("annotations").get(0).getAsJsonObject();

        Assert.assertEquals(annotation.get("name").getAsString(), "ServiceConfig", "the tag");
        Assert.assertEquals(annotation.get("presence").getAsString(), "required");
        Assert.assertFalse(annotation.has("module"), "a home-module annotation states no module");
        Assert.assertEquals(annotation.getAsJsonObject("typeConstraint").get("name").getAsString(),
                "ServiceConfiguration", "the constraint differs from the tag and comes from the compiler");
        Assert.assertEquals(annotation.getAsJsonObject("typeConstraint").getAsJsonArray("links")
                .get(0).getAsJsonObject().get("category").getAsString(), "internal");
    }

    // ---- mcp ---------------------------------------------------------------------------

    @Test
    public void testMcpSchemaServices() {
        JsonArray services = load(MCP);
        Set<String> declared = declaredNames(MCP);

        List<String> names = new ArrayList<>();
        services.forEach(s -> names.add(s.getAsJsonObject().get("name").getAsString()));

        // Present in every mcp release the metadata targets.
        Assert.assertTrue(names.contains("Service"), "Expected Service in " + names);
        Assert.assertTrue(names.contains("AdvancedService"), "Expected AdvancedService in " + names);

        // The validation guard as an invariant rather than as one release's snapshot: the metadata
        // may name service types and a listener authored ahead of a release, and anything the
        // resolved version does not declare must never reach the prompt. Which types a given mcp
        // ships is deliberately not asserted here — see the pinned test for that.
        for (String name : names) {
            Assert.assertTrue(declared.contains(name),
                    "Emitted service type " + name + " is not declared by the resolved package: " + declared);
        }

        for (JsonElement element : services) {
            JsonObject svc = element.getAsJsonObject();
            assertListenerIsDeclared(svc.getAsJsonObject("listener").get("name").getAsString(), declared);
            if ("Service".equals(svc.get("name").getAsString())) {
                Assert.assertFalse(svc.has("methods"),
                        "Wildcard (addMode: many) handlers must not surface as literal methods");
            }
            if ("AdvancedService".equals(svc.get("name").getAsString())) {
                Assert.assertEquals(methodNames(svc), List.of("onListTools", "onCallTool"),
                        "Concrete service types must introspect their declared methods");
            }
        }
    }

    /**
     * A listener must be module-qualified and name a class the resolved package actually declares —
     * otherwise the generated {@code on new mcp:X(...)} would not compile.
     */
    private static void assertListenerIsDeclared(String listenerName, Set<String> declared) {
        Assert.assertTrue(listenerName.startsWith(MCP_PREFIX),
                "Listener must be module-qualified, got: " + listenerName);
        Assert.assertTrue(declared.contains(listenerName.substring(MCP_PREFIX.length())),
                "Listener " + listenerName + " is not a class the resolved package declares: " + declared);
    }

    /**
     * Pins the last mcp release before {@code StreamableHttpService},
     * {@code StreamableHttpAdvancedService} and {@code StreamableHttpListener} existed, so the two
     * guards the schema loader exists for stay exercised deterministically rather than only while
     * Central lags the metadata:
     * <ul>
     *   <li>a metadata-declared service type the resolved version does not ship is skipped;</li>
     *   <li>a metadata-declared listener it does not ship falls back to the conventional class.</li>
     * </ul>
     */
    @Test
    public void testMcpSchemaServicesSkipsSymbolsAbsentFromPinnedVersion() {
        JsonArray services = loadPinned(MCP, MCP_PRE_STREAMABLE_HTTP_VERSION);

        List<String> names = new ArrayList<>();
        services.forEach(s -> names.add(s.getAsJsonObject().get("name").getAsString()));

        Assert.assertTrue(names.contains("Service"), "Expected Service in " + names);
        Assert.assertTrue(names.contains("AdvancedService"), "Expected AdvancedService in " + names);
        Assert.assertFalse(names.contains("StreamableHttpService"),
                "mcp " + MCP_PRE_STREAMABLE_HTTP_VERSION + " does not declare StreamableHttpService, so the"
                        + " metadata's entry must be skipped: " + names);
        Assert.assertFalse(names.contains("StreamableHttpAdvancedService"),
                "mcp " + MCP_PRE_STREAMABLE_HTTP_VERSION + " does not declare StreamableHttpAdvancedService,"
                        + " so the metadata's entry must be skipped: " + names);

        for (JsonElement element : services) {
            Assert.assertEquals(element.getAsJsonObject().getAsJsonObject("listener")
                            .get("name").getAsString(), "mcp:Listener",
                    "The metadata's StreamableHttpListener is absent from mcp "
                            + MCP_PRE_STREAMABLE_HTTP_VERSION + " and must fall back to the real class");
        }
    }

    // ---- trigger.github ---------------------------------------------------------------

    @Test
    public void testTriggerGithubSchemaServices() {
        JsonArray services = load("ballerinax/trigger.github");
        Assert.assertEquals(services.size(), 10);

        JsonObject issues = serviceNamed(services, "IssuesService");
        Assert.assertEquals(issues.getAsJsonObject("listener").get("name").getAsString(),
                "github:Listener");
        Assert.assertTrue(methodNames(issues).contains("onOpened"));

        JsonObject onOpened = methodNamed(issues, "onOpened");
        Assert.assertEquals(onOpened.get("type").getAsString(), "remote");
        // FLAG: github's declared handlers carry no doc comments, so no description is available
        // (matching what the retired service-index served for this library).
        Assert.assertFalse(onOpened.has("description"),
                "No doc comment in source means no description is emitted");
        Assert.assertEquals(paramNames(onOpened), List.of("payload"));
        JsonObject payload = paramNamed(onOpened, "payload");
        Assert.assertEquals(payload.getAsJsonObject("type").get("name").getAsString(), "IssuesEvent");
        assertInternalLink(payload, "IssuesEvent");
        Assert.assertEquals(onOpened.getAsJsonObject("return")
                .getAsJsonObject("type").get("name").getAsString(), "error?");
    }

    @Test
    public void testMcpServiceModelRoundTrip() {
        // CopilotLibraryManager Gson-round-trips every service through the Service model class.
        // mcp's marker Service legitimately has no methods: the model keeps methods == null and the
        // re-serialized JSON omits the key — the shape the TS renderer's `?? []` guards handle.
        JsonArray services = load(MCP);
        Set<String> declared = declaredNames(MCP);
        Gson gson = new Gson();
        for (JsonElement element : services) {
            io.ballerina.flowmodelgenerator.core.copilot.model.Service service =
                    gson.fromJson(element, io.ballerina.flowmodelgenerator.core.copilot.model.Service.class);
            JsonObject reSerialized = gson.toJsonTree(service).getAsJsonObject();
            if ("Service".equals(service.getName())) {
                Assert.assertNull(service.getMethods());
                Assert.assertFalse(reSerialized.has("methods"),
                        "A method-less fixed service must omit the methods key after the round trip");
            }
            if ("AdvancedService".equals(service.getName())) {
                Assert.assertEquals(service.getMethods().size(), 2);
                Assert.assertTrue(reSerialized.has("methods"));
            }
            // The listener must survive the round trip intact and still name a real class; which
            // class that is depends on the resolved version, so it is checked, not hard-coded.
            assertListenerIsDeclared(
                    reSerialized.getAsJsonObject("listener").get("name").getAsString(), declared);
        }
    }

    // ---- net-new libraries (never in the SQLite index) -----------------------------------

    @Test
    public void testSmbSchemaServices() {
        JsonArray services = load("ballerina/smb");
        JsonObject service = serviceNamed(services, "Service");

        JsonObject listener = service.getAsJsonObject("listener");
        Assert.assertEquals(listener.get("name").getAsString(), "smb:Listener");
        Assert.assertTrue(listener.getAsJsonArray("parameters").size() > 0,
                "Listener init params must be introspected");

        Assert.assertEquals(methodNames(service), List.of("onFileChange", "onFileText", "onFileJson",
                "onFileXml", "onFileCsv", "onFile"));

        JsonObject onFileJson = methodNamed(service, "onFileJson");
        Assert.assertEquals(paramNames(onFileJson), List.of("content", "caller", "fileInfo"));
        Assert.assertTrue(paramNamed(onFileJson, "caller").get("optional").getAsBoolean());
        assertInternalLink(paramNamed(onFileJson, "fileInfo"), "FileInfo");
        // FLAG: no description source exists for smb's marker handlers — no fabricated text.
        Assert.assertFalse(onFileJson.has("description"));
    }

    @Test
    public void testWebsubSchemaServices() {
        JsonArray services = load("ballerina/websub");
        JsonObject service = serviceNamed(services, "SubscriberService");

        Assert.assertEquals(service.getAsJsonObject("listener").get("name").getAsString(),
                "websub:Listener");
        // onHubError is now emitted. It used to be silently dropped because the metadata declared its
        // param as "HubError", which the package does not declare — the veto that protected the prompt
        // from an uncompilable signature also made a real handler invisible. The document now says
        // "InternalHubError", which is what websub actually declares: verified by compiling a subscriber
        // service with `onHubError(websub:InternalHubError err)` (builds) against the same handler typed
        // `websub:HubError` ("unknown type 'HubError'"). The handler itself is genuinely part of the
        // contract — websub's plugin rejects an invented handler name but accepts this one.
        Assert.assertEquals(methodNames(service), List.of("onEventNotification",
                "onSubscriptionVerification", "onUnsubscriptionVerification",
                "onSubscriptionValidationDenied", "onHubError"));
        assertInternalLink(paramNamed(methodNamed(service, "onHubError"), "internalHubError"),
                "InternalHubError");

        // The metadata deliberately leaves these params unnamed: names are generated from the
        // declared type — idiomatic, compilable Ballerina.
        JsonObject onEventNotification = methodNamed(service, "onEventNotification");
        Assert.assertEquals(paramNames(onEventNotification), List.of("contentDistributionMessage"));
        assertInternalLink(paramNamed(onEventNotification, "contentDistributionMessage"),
                "ContentDistributionMessage");

        JsonObject onSubscriptionVerification = methodNamed(service, "onSubscriptionVerification");
        Assert.assertEquals(onSubscriptionVerification.getAsJsonObject("return")
                        .getAsJsonObject("type").get("name").getAsString(),
                "SubscriptionVerificationSuccess|SubscriptionVerificationError");
    }

    @Test
    public void testGoogleCalendarSchemaServices() {
        JsonArray services = load("ballerinax/trigger.google.calendar");
        JsonObject service = serviceNamed(services, "CalendarService");

        Assert.assertEquals(service.getAsJsonObject("listener").get("name").getAsString(),
                "calendar:Listener");
        Assert.assertEquals(methodNames(service),
                List.of("onNewEvent", "onEventUpdate", "onEventDelete"));

        JsonObject onNewEvent = methodNamed(service, "onNewEvent");
        Assert.assertEquals(onNewEvent.get("type").getAsString(), "remote");
        Assert.assertEquals(paramNames(onNewEvent), List.of("payload"));
        assertInternalLink(paramNamed(onNewEvent, "payload"), "Event");
        Assert.assertEquals(onNewEvent.getAsJsonObject("return")
                .getAsJsonObject("type").get("name").getAsString(), "error?");
    }

    /**
     * smb and websub were never in the service-index {@code Annotation} table. They are no longer
     * a special case: every library's catalog is built from the Semantic Model, so these assert
     * the same path every other library takes, through {@code CopilotLibraryManager}.
     */
    @Test
    public void testNetNewLibraryAnnotationsReachTheCatalog() {
        List<Annotation> smb = libraryAnnotations("ballerina/smb");

        Annotation serviceConfig = annotationNamed(smb, "ServiceConfig", "SERVICE");
        Assert.assertNotNull(serviceConfig.getDescription(),
                "Semantic-Model annotations carry the library's doc comment");
        Assert.assertFalse(serviceConfig.getDescription().isEmpty(),
                "Semantic-Model annotations carry the library's doc comment");
        assertInternalTypeConstraint(serviceConfig, "SmbServiceConfig");

        // smb declares FunctionConfig `on function`. The compiler reports FUNCTION and it is
        // emitted verbatim — it is no longer reclassified as a service method.
        annotationNamed(smb, "FunctionConfig", "FUNCTION");

        annotationNamed(libraryAnnotations("ballerina/websub"), "SubscriberServiceConfig", "SERVICE");

        // kafka likewise has no curated rows. Its `on parameter` annotation now reaches the
        // catalog instead of being dropped for sitting outside SERVICE/OBJECT_METHOD.
        annotationNamed(libraryAnnotations("ballerinax/kafka"), "Payload", "PARAMETER");
    }

    private List<Annotation> libraryAnnotations(String libraryName) {
        List<Library> libraries =
                new CopilotLibraryManager().loadFilteredLibraries(new String[]{libraryName});
        if (libraries.isEmpty()) {
            throw new SkipException("Could not resolve package for " + libraryName);
        }
        List<Annotation> annotations = libraries.get(0).getAnnotations();
        Assert.assertNotNull(annotations, libraryName + " should expose an annotation catalog");
        return annotations;
    }

    private static Annotation annotationNamed(List<Annotation> annotations, String name,
                                              String attachmentPoint) {
        for (Annotation annotation : annotations) {
            if (name.equals(annotation.getName())
                    && attachmentPoint.equals(annotation.getAttachmentPoint())) {
                return annotation;
            }
        }
        StringBuilder present = new StringBuilder();
        for (Annotation annotation : annotations) {
            present.append(" @").append(annotation.getName())
                    .append('/').append(annotation.getAttachmentPoint());
        }
        Assert.fail("No @" + name + " on " + attachmentPoint + " in:" + present);
        return null;
    }

    private static void assertInternalTypeConstraint(Annotation annotation, String recordName) {
        Type typeConstraint = annotation.getTypeConstraint();
        Assert.assertNotNull(typeConstraint, "@" + annotation.getName() + " must carry a type constraint");
        Assert.assertEquals(typeConstraint.getName(), recordName);
        List<TypeLink> links = typeConstraint.getLinks();
        Assert.assertNotNull(links, "@" + annotation.getName() + " type constraint must be linked");
        Assert.assertEquals(links.size(), 1, "Expected exactly one link, got: " + links.size());
        Assert.assertEquals(links.get(0).getCategory(), "internal");
        Assert.assertEquals(links.get(0).getRecordName(), recordName);
    }

    private static JsonObject mapTypeConstraint(JsonObject annotation) {
        // Adapts an annotation's typeConstraint to the shape assertInternalLink expects.
        JsonObject wrapper = new JsonObject();
        wrapper.add("type", annotation.getAsJsonObject("typeConstraint"));
        return wrapper;
    }

    // ---- fallback & pinning --------------------------------------------------------------

    @Test
    public void testNonSchemaDrivenLibraryStaysOnServiceIndex() {
        // asb is not schema-driven: the overload must produce exactly the SQLite-path output.
        String library = "ballerinax/asb";
        JsonArray viaOverload = load(library);
        JsonArray viaIndex = ServiceLoader.loadAllServices(library);
        Assert.assertEquals(viaOverload, viaIndex);
    }

    @Test
    public void testTriggerSourcePropertyPinsToIndex() {
        System.setProperty("ballerina.copilot.triggerSource", "index");
        try {
            String library = "ballerinax/kafka";
            // Bypass the cache: this assertion needs the pinned-property behavior.
            String[] parts = library.split("/");
            Optional<Package> pkgOpt = PackageUtil.getModulePackage(
                    PackageUtil.getSampleProject(), parts[0], parts[1]);
            if (pkgOpt.isEmpty()) {
                throw new SkipException("Could not resolve package for " + library);
            }
            Package pkg = pkgOpt.get();
            SemanticModel semanticModel = PackageUtil.getCompilation(pkg)
                    .getSemanticModel(pkg.getDefaultModule().moduleId());
            JsonArray pinned = ServiceLoader.loadAllServices(library, pkg, semanticModel);
            JsonArray viaIndex = ServiceLoader.loadAllServices(library);
            Assert.assertEquals(pinned, viaIndex,
                    "triggerSource=index must force the SQLite path even for schema-driven libraries");
        } finally {
            System.clearProperty("ballerina.copilot.triggerSource");
        }
    }
}
