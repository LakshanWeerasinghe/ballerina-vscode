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
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.ballerina.modelgenerator.commons.AuthoringAnnotation;
import io.ballerina.modelgenerator.commons.AuthoringDataBindingRule;
import io.ballerina.modelgenerator.commons.AuthoringServiceType;
import io.ballerina.modelgenerator.commons.PresenceForm;
import io.ballerina.modelgenerator.commons.TriggerAuthoringModel;
import io.ballerina.modelgenerator.commons.TriggerLibraryFacts;
import io.ballerina.modelgenerator.commons.TypeRef;
import io.ballerina.servicemodelgenerator.extension.connector.model.TriggerModel;
import io.ballerina.servicemodelgenerator.extension.model.ServiceInitModel;
import io.ballerina.servicemodelgenerator.extension.model.Value;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Unit test for {@link TriggerModelSynthesizer}: builds a {@link TriggerAuthoringModel} +
 * {@link TriggerLibraryFacts} by hand (mirroring the Phase-B fixture connector used to verify the
 * introspector -- a {@code Listener(host, port = 9092, *ConsumerConfig config)} and a {@code Service}
 * with two locked remote handlers), synthesizes a {@link TriggerModel}, and feeds it through the
 * *real* {@link SchemaDrivenSourceGenerator} to confirm the emitted listener declaration and service
 * block are syntactically sensible -- the same acceptance bar {@link TriggerSourceGenerationTest} uses
 * for hand-authored/bundled models.
 *
 * @since 1.10.0
 */
public class TriggerModelSynthesizerTest {

    private static final String MODULE = "triggerfixture";
    private static final Gson GSON = new Gson();

    private TriggerAuthoringModel authoringModel() {
        TypeRef listenerType = new TypeRef("Listener", null);
        TriggerAuthoringModel.Listener listener = new TriggerAuthoringModel.Listener(
                listenerType, List.of("service"), null);

        AuthoringServiceType.Handlers handlers = new AuthoringServiceType.Handlers(true, null, List.of());
        AuthoringServiceType serviceType = new AuthoringServiceType(
                "service", new TypeRef("Service", null), true, false, false, null, handlers, null);

        AuthoringAnnotation annotation = new AuthoringAnnotation(
                "serviceConfig", new TypeRef("ServiceConfigData", null), AuthoringAnnotation.ATTACH_POINT_SERVICE,
                null, AuthoringAnnotation.PRESENCE_OPTIONAL);

        return new TriggerAuthoringModel(
                List.of(listener), List.of(serviceType), List.of(annotation), null);
    }

    private TriggerLibraryFacts libraryFacts() {
        TriggerLibraryFacts.Param groupId = new TriggerLibraryFacts.Param(
                "groupId", "string", false, "RECORD_FIELD", "", List.of());
        TriggerLibraryFacts.Param pollingInterval = new TriggerLibraryFacts.Param(
                "pollingIntervalInMillis", "int", true, "RECORD_FIELD", "", List.of());
        TriggerLibraryFacts.Param config = new TriggerLibraryFacts.Param(
                "config", "triggerfixture:ConsumerConfig", true, "INCLUDED_RECORD", "",
                List.of(groupId, pollingInterval));
        TriggerLibraryFacts.Param host = new TriggerLibraryFacts.Param(
                "host", "string", false, "REQUIRED", "The listener host.", List.of());
        TriggerLibraryFacts.Param port = new TriggerLibraryFacts.Param(
                "port", "int", true, "DEFAULTABLE", "The listener port.", List.of());
        TriggerLibraryFacts.Listener listener = new TriggerLibraryFacts.Listener(
                "triggerfixture:Listener", List.of(host, port, config));

        TriggerLibraryFacts.Param payload = new TriggerLibraryFacts.Param(
                "payload", "record {}", false, "REQUIRED", "", List.of());
        TriggerLibraryFacts.Function onMessage = new TriggerLibraryFacts.Function(
                "onMessage", List.of("remote"), "REMOTE", "error?", true, "Handles an inbound message.",
                List.of(payload));
        TriggerLibraryFacts.Param errorParam = new TriggerLibraryFacts.Param(
                "e", "error", false, "REQUIRED", "", List.of());
        TriggerLibraryFacts.Function onError = new TriggerLibraryFacts.Function(
                "onError", List.of("remote"), "REMOTE", "error?", true, "Handles a processing error.",
                List.of(errorParam));
        TriggerLibraryFacts.ServiceType serviceType = new TriggerLibraryFacts.ServiceType(
                "Service", "", List.of(onMessage, onError));

        TriggerLibraryFacts.Annotation annotation = new TriggerLibraryFacts.Annotation(
                "ServiceConfig", MODULE, "triggerfixture:ServiceConfigData", List.of("SERVICE"), "");

        return new TriggerLibraryFacts(List.of(listener), List.of(serviceType), List.of(annotation));
    }

    private TriggerModel synthesize() {
        return TriggerModelSynthesizer.synthesize(authoringModel(), libraryFacts(), "999", "Trigger Fixture",
                "https://example.test/icon.png", "event", "testorg", MODULE, MODULE, "0.1.0").orElseThrow();
    }

    /** Mirrors {@code ConnectorModelReader}'s private JSON-level {@code initProperties -> properties} remap. */
    private ServiceInitModel toServiceInitModel(TriggerModel model) {
        JsonObject root = GSON.toJsonTree(model).getAsJsonObject();
        JsonObject remapped = new JsonObject();
        for (String key : List.of("id", "displayName", "description", "orgName", "packageName", "moduleName",
                "version", "type", "icon")) {
            if (root.has(key)) {
                remapped.add(key, root.get(key));
            }
        }
        remapped.add("properties", root.get("initProperties"));
        return GSON.fromJson(remapped, ServiceInitModel.class);
    }

    @Test
    public void testSynthesizedModelShape() {
        TriggerModel model = synthesize();
        Assert.assertEquals(model.moduleName(), MODULE);
        Assert.assertEquals(model.listenerKind(), "SINGLE_SELECT_LISTENER");

        Assert.assertTrue(model.initProperties().containsKey("listener"), "listener CHOICE should be present");
        TriggerModel.Property listener = model.initProperties().get("listener");
        Assert.assertEquals(listener.codedata().type(), "LISTENER_CONFIG");
        Assert.assertEquals(listener.choices().size(), 2, "create-new + use-existing branches");

        Map<String, TriggerModel.Property> createNew = listener.choices().get(0).properties();
        Assert.assertTrue(createNew.containsKey("listenerVarName"));
        Assert.assertTrue(createNew.containsKey("host"), "REQUIRED init param rendered directly");
        Assert.assertEquals(createNew.get("host").codedata().argType(), "LISTENER_PARAM_REQUIRED");
        Assert.assertEquals(createNew.get("host").codedata().position(), Integer.valueOf(1));
        Assert.assertEquals(createNew.get("port").codedata().argType(), "LISTENER_PARAM_REQUIRED");
        Assert.assertEquals(createNew.get("port").codedata().position(), Integer.valueOf(2));
        Assert.assertTrue(createNew.get("port").optional(), "DEFAULTABLE param is optional");

        // The INCLUDED_RECORD `config` param is not itself rendered -- its fields are flattened.
        Assert.assertFalse(createNew.containsKey("config"));
        Assert.assertTrue(createNew.containsKey("groupId"));
        Assert.assertEquals(createNew.get("groupId").codedata().argType(), "LISTENER_PARAM_INCLUDED_FIELD");
        Assert.assertTrue(createNew.containsKey("pollingIntervalInMillis"));
        Assert.assertEquals(createNew.get("pollingIntervalInMillis").codedata().argType(),
                "LISTENER_PARAM_INCLUDED_DEFAULTABLE_FIELD");

        Assert.assertEquals(model.serviceTypes().size(), 1);
        TriggerModel.ServiceTypeModel serviceType = model.serviceTypes().get(0);
        Assert.assertEquals(serviceType.name(), "Service");
        Assert.assertEquals(serviceType.functions().size(), 2, "backedByConcreteType -> locked from introspection");
        Assert.assertTrue(serviceType.schemaFunctions().isEmpty());
        Assert.assertTrue(serviceType.properties().containsKey("serviceConfig"), "service annotation rendered");
        Assert.assertEquals(serviceType.properties().get("serviceConfig").codedata().type(), "ANNOTATION_ATTACHMENT");
    }

    @Test
    public void testEmitsRealListenerDeclarationAndServiceBlock() throws Exception {
        TriggerModel model = synthesize();
        ServiceInitModel initModel = toServiceInitModel(model);

        Value listener = initModel.getProperties().get("listener");
        Value createNew = listener.getChoices().stream().filter(Value::isEnabled).findFirst().orElseThrow();
        createNew.getProperties().get("host").setValue("\"localhost\"");

        String block = SchemaDrivenSourceGenerator.buildServiceBlockForTrigger(initModel, model);
        Assert.assertTrue(block.contains("listener triggerfixture:Listener"), "listener decl emitted: " + block);
        Assert.assertTrue(block.contains("\"localhost\""), "host value should appear: " + block);
        Assert.assertTrue(block.contains("service triggerfixture:Service on "), "service descriptor: " + block);
        Assert.assertTrue(block.contains("remote function onMessage"), "onMessage handler emitted: " + block);
        Assert.assertTrue(block.contains("remote function onError"), "onError handler emitted: " + block);
    }

    @Test
    public void testDataBindingParamComposition() {
        TypeRef listenerType = new TypeRef("Listener", null);
        TriggerAuthoringModel.Listener listener = new TriggerAuthoringModel.Listener(
                listenerType, List.of("service"), null);

        AuthoringServiceType.Param recordsParam = new AuthoringServiceType.Param(
                "records", null, "required", null, "consumerRecordPayload", null);
        AuthoringServiceType.HandlerOption option = new AuthoringServiceType.HandlerOption(
                "onConsumerRecord", AuthoringServiceType.HandlerOption.KIND_REMOTE, "required", null,
                List.of(recordsParam), List.of(new TypeRef("error", null), new TypeRef("()", null)),
                null, null, null, null, null);
        AuthoringServiceType.Handlers handlers = new AuthoringServiceType.Handlers(
                false, AuthoringServiceType.Handlers.ADD_MODE_SUBSET, List.of(option));
        AuthoringServiceType serviceType = new AuthoringServiceType(
                "service", new TypeRef("Service", null), false, false, false, null, handlers, null);

        AuthoringDataBindingRule.SupportedMode includedRecord = new AuthoringDataBindingRule.SupportedMode(
                AuthoringDataBindingRule.SupportedMode.MODE_INCLUDED_RECORD, null, null,
                new TypeRef("AnydataConsumerRecord", null), List.of("value"));
        AuthoringDataBindingRule bindingRule = new AuthoringDataBindingRule(
                "consumerRecordPayload", null, "array", List.of(includedRecord));

        TriggerAuthoringModel authoring = new TriggerAuthoringModel(
                List.of(listener), List.of(serviceType), null, List.of(bindingRule));

        TriggerLibraryFacts.Listener listenerFacts = new TriggerLibraryFacts.Listener("kafka:Listener", List.of());
        TriggerLibraryFacts facts = new TriggerLibraryFacts(List.of(listenerFacts), List.of(), List.of());

        TriggerModel model = TriggerModelSynthesizer.synthesize(authoring, facts, "1", "Kafka", null, "event",
                "ballerinax", "kafka", "kafka", "4.5.0").orElseThrow();

        TriggerModel.FunctionModel fn = model.serviceTypes().get(0).schemaFunctions().get(0);
        TriggerModel.Parameter param = fn.parameters().get(0);
        Assert.assertEquals(param.kind(), "DATA_BINDING");
        TriggerModel.Codedata cd = param.type().codedata();
        Assert.assertEquals(cd.type(), "PAYLOAD_TYPE_INCLUDED_RECORD");
        Assert.assertEquals(cd.defaultType(), "AnydataConsumerRecord");
        Assert.assertEquals(cd.template(), "{{type}}[]", "array cardinality -> [] template");
        Assert.assertEquals(cd.field(), "value");
    }
}
