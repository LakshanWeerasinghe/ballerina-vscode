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
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    private final Map<String, JsonArray> cache = new HashMap<>();

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
            JsonArray services = ServiceLoader.loadAllServices(lib, pkg, semanticModel);
            dump(lib, services);
            return services;
        });
    }

    private void dump(String libraryName, JsonArray services) {
        try {
            Path dir = OUTPUT_DIR.resolve(libraryName.replace('/', '_').replace('.', '_') + "_schema");
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
        Assert.assertFalse(onConsumerRecord.has("optional"),
                "Function-level optional must never be emitted");

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

    // ---- mcp ---------------------------------------------------------------------------

    @Test
    public void testMcpSchemaServices() {
        JsonArray services = load("ballerina/mcp");

        List<String> names = new ArrayList<>();
        services.forEach(s -> names.add(s.getAsJsonObject().get("name").getAsString()));
        Assert.assertTrue(names.contains("Service"), "Expected Service in " + names);
        Assert.assertFalse(names.contains("StreamableHttpService"),
                "Service types absent from the resolved package version must be skipped: " + names);
        Assert.assertFalse(names.contains("StreamableHttpAdvancedService"),
                "Service types absent from the resolved package version must be skipped: " + names);

        for (JsonElement element : services) {
            JsonObject svc = element.getAsJsonObject();
            Assert.assertEquals(svc.getAsJsonObject("listener").get("name").getAsString(), "mcp:Listener",
                    "The metadata's unreleased StreamableHttpListener must fall back to the real class");
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
        JsonArray services = load("ballerina/mcp");
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
            Assert.assertEquals(reSerialized.getAsJsonObject("listener").get("name").getAsString(),
                    "mcp:Listener");
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
        // onHubError is skipped: the metadata declares param type "HubError", which websub 2.15.0
        // does not declare (the compiler plugin expects InternalHubError) — emitting it would
        // render an uncompilable prompt. Reported upstream; it reappears once the metadata is fixed.
        Assert.assertEquals(methodNames(service), List.of("onEventNotification",
                "onSubscriptionVerification", "onUnsubscriptionVerification",
                "onSubscriptionValidationDenied"));

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

    @Test
    public void testNetNewLibraryAnnotationsIntrospected() {
        // These libraries have no SQLite Annotation rows; the loader introspects the module instead.
        JsonArray smbAnnotations = loadAnnotations("ballerina/smb");
        Assert.assertEquals(smbAnnotations.size(), 2, "smb declares ServiceConfig + FunctionConfig");
        JsonObject serviceConfig = annotationNamed(smbAnnotations, "ServiceConfig");
        Assert.assertEquals(serviceConfig.get("attachmentPoint").getAsString(), "SERVICE");
        Assert.assertFalse(serviceConfig.get("description").getAsString().isEmpty(),
                "Introspected annotations carry the library's doc comment");
        assertInternalLink(mapTypeConstraint(serviceConfig), "SmbServiceConfig");
        JsonObject functionConfig = annotationNamed(smbAnnotations, "FunctionConfig");
        Assert.assertEquals(functionConfig.get("attachmentPoint").getAsString(), "OBJECT_METHOD",
                "A plain 'on function' attach point must surface as OBJECT_METHOD");

        JsonArray websubAnnotations = loadAnnotations("ballerina/websub");
        JsonObject subscriberConfig = annotationNamed(websubAnnotations, "SubscriberServiceConfig");
        Assert.assertEquals(subscriberConfig.get("attachmentPoint").getAsString(), "SERVICE");

        // The introspection fallback never overrides curated index rows nor fires for
        // schema-driven libraries whose module declares no SERVICE/OBJECT_METHOD annotations.
        Assert.assertTrue(loadAnnotations("ballerinax/kafka").isEmpty(),
                "kafka's Payload annotation (on parameter) must stay filtered out");
        JsonArray ftpAnnotations = loadAnnotations("ballerina/ftp");
        Assert.assertEquals(ftpAnnotations.size(), 2,
                "ftp's curated index rows must win over introspection");
        Assert.assertTrue(annotationNamed(ftpAnnotations, "ServiceConfig").has("displayName"),
                "index-sourced annotations keep their curated displayName");
    }

    private JsonArray loadAnnotations(String libraryName) {
        String[] parts = libraryName.split("/");
        Optional<Package> pkgOpt = PackageUtil.getModulePackage(
                PackageUtil.getSampleProject(), parts[0], parts[1]);
        if (pkgOpt.isEmpty()) {
            throw new SkipException("Could not resolve package for " + libraryName);
        }
        Package pkg = pkgOpt.get();
        SemanticModel semanticModel = PackageUtil.getCompilation(pkg)
                .getSemanticModel(pkg.getDefaultModule().moduleId());
        return io.ballerina.flowmodelgenerator.core.copilot.service.AnnotationLoader
                .loadAnnotations(libraryName, semanticModel);
    }

    private static JsonObject annotationNamed(JsonArray annotations, String name) {
        for (JsonElement element : annotations) {
            JsonObject annotation = element.getAsJsonObject();
            if (name.equals(annotation.get("name").getAsString())) {
                return annotation;
            }
        }
        Assert.fail("No annotation named " + name + " in " + annotations);
        return null;
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
