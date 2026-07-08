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

import io.ballerina.servicemodelgenerator.extension.connector.model.TriggerModel;
import io.ballerina.servicemodelgenerator.extension.connector.model.TriggerModel.FunctionModel;
import io.ballerina.servicemodelgenerator.extension.connector.model.TriggerModel.Parameter;
import io.ballerina.servicemodelgenerator.extension.connector.model.TriggerModel.Property;
import io.ballerina.servicemodelgenerator.extension.connector.model.TriggerModel.ServiceTypeModel;
import io.ballerina.servicemodelgenerator.extension.model.ServiceInitModel;
import io.ballerina.servicemodelgenerator.extension.model.Value;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Unit test for the single-file {@code trigger-model.json} reader on {@link ConnectorModelReader}:
 * deserializes the phase-6 worked examples (kafka / ftp / github) from a package-root layout
 * ({@code <root>/resources/trigger-model.json}) without spinning up the language server. Verifies the
 * distinctive phase-6 shapes survive Gson: the listener CHOICE, structured parameters (type/name as
 * {@code Property} sub-nodes), data-binding, format variants, and fully-derived multi-service-type
 * handler sets.
 *
 * @since 1.9.0
 */
public class TriggerModelReaderTest {

    private TriggerModel read(String connector) throws Exception {
        URL fixture = getClass().getClassLoader().getResource("trigger_models/" + connector);
        Assert.assertNotNull(fixture, connector + " trigger-model fixture is missing from test resources");
        Path packageRoot = Paths.get(fixture.toURI());
        Optional<TriggerModel> result =
                ConnectorModelReader.getInstance().readTriggerModelFromPackageRoot(packageRoot);
        Assert.assertTrue(result.isPresent(), "expected " + connector + " trigger-model to be read");
        return result.get();
    }

    private String listenerFieldType(TriggerModel model) {
        Property listener = model.initProperties().get("listener");
        Assert.assertNotNull(listener, "initProperties.listener should be present");
        Assert.assertNotNull(listener.codedata());
        Assert.assertEquals(listener.codedata().type(), "LISTENER_CONFIG",
                "the listener node carries codedata.type LISTENER_CONFIG");
        Assert.assertNotNull(listener.types());
        return listener.types().getFirst().fieldType();
    }

    private FunctionModel findFunction(ServiceTypeModel st, String name) {
        return java.util.stream.Stream.concat(
                        st.functions() == null ? java.util.stream.Stream.empty() : st.functions().stream(),
                        st.schemaFunctions() == null ? java.util.stream.Stream.empty()
                                : st.schemaFunctions().stream())
                .filter(f -> name.equals(f.name()))
                .findFirst().orElse(null);
    }

    @Test
    public void testReadKafka() throws Exception {
        TriggerModel model = read("kafka");
        Assert.assertEquals(model.schemaVersion(), "1.0");
        Assert.assertEquals(model.orgName(), "ballerinax");
        Assert.assertEquals(model.moduleName(), "kafka");
        // Kafka does not support attaching to an existing listener, so there is no create/reuse
        // CHOICE — `listenerConfig` is a plain GROUP_SECTION straight under initProperties, always
        // creating a new listener.
        Assert.assertFalse(model.initProperties().containsKey("listener"), "kafka has no listener CHOICE");
        Property listenerConfig = model.initProperties().get("listenerConfig");
        Assert.assertNotNull(listenerConfig, "listenerConfig should be present directly under initProperties");
        Assert.assertEquals(listenerConfig.types().getFirst().fieldType(), "GROUP_SECTION");
        Assert.assertTrue(listenerConfig.properties().containsKey("listenerVarName"));
        Assert.assertTrue(listenerConfig.properties().containsKey("bootstrapServers"));

        List<ServiceTypeModel> serviceTypes = model.serviceTypes();
        Assert.assertNotNull(serviceTypes);
        Assert.assertEquals(serviceTypes.size(), 1);
        ServiceTypeModel st = serviceTypes.getFirst();
        Assert.assertEquals(st.name(), "Service");
        // Kafka: no present handlers; onConsumerRecord/onError are addable templates.
        Assert.assertTrue(st.functions() == null || st.functions().isEmpty());

        FunctionModel onConsumerRecord = findFunction(st, "onConsumerRecord");
        Assert.assertNotNull(onConsumerRecord, "onConsumerRecord should be present (schemaFunctions)");
        Assert.assertEquals(onConsumerRecord.kind(), "REMOTE");

        // The records param carries its type/name as Property sub-nodes and is a data-binding param.
        Parameter records = onConsumerRecord.parameters().getFirst();
        Assert.assertEquals(records.kind(), "DATA_BINDING");
        Assert.assertNotNull(records.type(), "parameter type is a Property sub-node");
        Assert.assertNotNull(records.name(), "parameter name is a Property sub-node");
        Assert.assertEquals(records.name().value(), "records");
        Assert.assertEquals(records.type().types().getFirst().fieldType(), "DATA_BINDING");
    }

    @Test
    public void testReadFtp() throws Exception {
        TriggerModel model = read("ftp");
        Assert.assertEquals(model.moduleName(), "ftp");
        Assert.assertEquals(model.orgName(), "ballerina");
        Assert.assertEquals(listenerFieldType(model), "CHOICE");

        ServiceTypeModel st = model.serviceTypes().getFirst();
        // A format-variant handler: COMPLEX_REMOTE_FUNCTION whose content param is a VARIANT with a
        // VARIATION_SELECTOR type (the CSV/JSON/XML/TEXT/RAW picker).
        FunctionModel onFileCsv = findFunction(st, "onFileCsv");
        Assert.assertNotNull(onFileCsv, "onFileCsv should be present");
        Assert.assertEquals(onFileCsv.kind(), "COMPLEX_REMOTE_FUNCTION");
        Parameter content = onFileCsv.parameters().getFirst();
        Assert.assertEquals(content.kind(), "VARIANT");
        Assert.assertEquals(content.type().types().getFirst().fieldType(), "VARIATION_SELECTOR");
        // The variant sub-forms live under the selector's nested properties.
        Assert.assertNotNull(content.type().properties(), "variant sub-forms live in type.properties");
        Assert.assertTrue(content.type().properties().containsKey("CSV"));
    }

    @Test
    public void testReadGithub() throws Exception {
        TriggerModel model = read("github");
        Assert.assertEquals(model.moduleName(), "github");
        Assert.assertEquals(listenerFieldType(model), "CHOICE");
        // Multi-service-type connector: a serviceType selector in the init form.
        Assert.assertTrue(model.initProperties().containsKey("serviceType"),
                "multi-type connectors carry a serviceType selector");

        Assert.assertTrue(model.serviceTypes().size() >= 2, "GitHub exposes several service types");
        ServiceTypeModel issues = model.serviceTypes().getFirst();
        Assert.assertEquals(issues.name(), "github:IssuesService");
        // Handlers are FULLY derived into functions[] (locked); nothing is added from a catalog.
        Assert.assertNotNull(issues.functions());
        Assert.assertFalse(issues.functions().isEmpty(), "IssuesService handlers are fully derived");
        Assert.assertTrue(issues.schemaFunctions() == null || issues.schemaFunctions().isEmpty(),
                "no schemaFunctions for a fully-derived multi-type connector");
        // Each handler param carries type/name as Property sub-nodes.
        Parameter payload = issues.functions().getFirst().parameters().getFirst();
        Assert.assertEquals(payload.name().value(), "payload");
        Assert.assertEquals(payload.type().types().getFirst().fieldType(), "TYPE");
    }

    @Test
    public void testKafkaInitFormAsServiceInitModel() throws Exception {
        // The add-trigger init form is derived from the unified model's initProperties subtree and
        // handed to the frontend as the wire ServiceInitModel (identity + Map<String,Value>).
        URL fixture = getClass().getClassLoader().getResource("trigger_models/kafka");
        Assert.assertNotNull(fixture);
        Path packageRoot = Paths.get(fixture.toURI());
        Optional<ServiceInitModel> result =
                ConnectorModelReader.getInstance().readServiceInitModelFromPackageRoot(packageRoot);
        Assert.assertTrue(result.isPresent(), "the init form should build from the unified model");
        ServiceInitModel init = result.get();
        Assert.assertEquals(init.getOrgName(), "ballerinax");
        Assert.assertEquals(init.getModuleName(), "kafka");
        Assert.assertEquals(init.getType(), "kafka");

        // Kafka does not support attaching to an existing listener, so there is no create/reuse
        // CHOICE — `listenerConfig` sits directly under initProperties as a plain GROUP_SECTION,
        // wrapping the WHOLE listener config (listenerVarName plus every init param) so it still
        // renders as a single titled box (not just a record-typed param's own fields).
        Assert.assertFalse(init.getProperties().containsKey("listener"), "kafka has no listener CHOICE");
        Value listenerConfig = init.getProperties().get("listenerConfig");
        Assert.assertNotNull(listenerConfig, "the whole listener should be wrapped in one listenerConfig group");
        Assert.assertEquals(listenerConfig.getTypes().getFirst().fieldType(), Value.FieldType.GROUP_SECTION);
        Map<String, Value> cfg = listenerConfig.getProperties();
        Assert.assertTrue(cfg.containsKey("listenerVarName"), "listenerVarName should be first field");

        Value bootstrapServers = cfg.get("bootstrapServers");
        Assert.assertNotNull(bootstrapServers);
        // codedata (argType/position) drives positional listener args; validations must survive.
        Assert.assertEquals(bootstrapServers.getCodedata().getArgType(), "LISTENER_PARAM_REQUIRED");
        Assert.assertEquals(bootstrapServers.getCodedata().getPosition(), Integer.valueOf(1));
        Assert.assertNotNull(bootstrapServers.getValidations());
        Assert.assertEquals(bootstrapServers.getValidations().getFirst().getRule(), "common.validate.required");
    }

    @Test
    public void testHubspotGroupedListenerParamAsServiceInitModel() throws Exception {
        // HubSpot's listener has a record-typed positional param (`config`, holding clientSecret /
        // callbackURL) alongside a scalar positional param (`listenOn`). Regression test for a bug
        // where clientSecret/callbackURL both ended up at position 1 (duplicated) and listenOn was
        // shifted to position 2 as if it were a THIRD arg, instead of clientSecret/callbackURL being
        // flattened as CONFIG_FIELD siblings sharing position 1 (config's own slot) with listenOn
        // correctly at position 2 — all nested inside ONE listenerConfig GROUP_SECTION so the whole
        // listener (not just the record fields) renders as a single titled box.
        URL fixture = getClass().getClassLoader().getResource("trigger_models/hubspot");
        Assert.assertNotNull(fixture, "hubspot fixture missing");
        Optional<ServiceInitModel> result = ConnectorModelReader.getInstance()
                .readServiceInitModelFromPackageRoot(Paths.get(fixture.toURI()));
        Assert.assertTrue(result.isPresent());
        ServiceInitModel init = result.get();

        Value listener = init.getProperties().get("listener");
        Value createNew = listener.getChoices().stream().filter(Value::isEnabled).findFirst().orElse(null);
        Assert.assertNotNull(createNew);
        Value listenerConfig = createNew.getProperties().get("listenerConfig");
        Assert.assertNotNull(listenerConfig, "the whole listener should be wrapped in one listenerConfig group");
        Assert.assertEquals(listenerConfig.getTypes().getFirst().fieldType(), Value.FieldType.GROUP_SECTION);
        // The group's OWN codedata carries the record param's position (1) so its assembled record
        // literal lands at the right slot.
        Assert.assertEquals(listenerConfig.getCodedata().getArgType(), "LISTENER_PARAM_REQUIRED");
        Assert.assertEquals(listenerConfig.getCodedata().getPosition(), Integer.valueOf(1));

        Map<String, Value> cfg = listenerConfig.getProperties();
        Assert.assertTrue(cfg.containsKey("listenerVarName"), "listenerVarName is inside the group, not a sibling");

        Value clientSecret = cfg.get("clientSecret");
        Value callbackURL = cfg.get("callbackURL");
        Assert.assertNotNull(clientSecret);
        Assert.assertNotNull(callbackURL);
        Assert.assertEquals(clientSecret.getCodedata().getArgType(), "LISTENER_PARAM_CONFIG_FIELD");
        Assert.assertEquals(callbackURL.getCodedata().getArgType(), "LISTENER_PARAM_CONFIG_FIELD");
        // Both config fields share the SAME position — the record param's own slot — never their own.
        Assert.assertEquals(clientSecret.getCodedata().getPosition(), Integer.valueOf(1));
        Assert.assertEquals(callbackURL.getCodedata().getPosition(), Integer.valueOf(1));

        Value listenOn = cfg.get("listenOn");
        Assert.assertNotNull(listenOn);
        Assert.assertEquals(listenOn.getCodedata().getArgType(), "LISTENER_PARAM_REQUIRED");
        Assert.assertEquals(listenOn.getCodedata().getPosition(), Integer.valueOf(2));
    }

    @Test
    public void testInitFormBuildsForAllExamples() throws Exception {
        // ftp and github init forms use only known wire fieldTypes, so they deserialize cleanly too.
        for (String connector : new String[] {"ftp", "github"}) {
            URL fixture = getClass().getClassLoader().getResource("trigger_models/" + connector);
            Assert.assertNotNull(fixture, connector + " fixture missing");
            Optional<ServiceInitModel> result = ConnectorModelReader.getInstance()
                    .readServiceInitModelFromPackageRoot(Paths.get(fixture.toURI()));
            Assert.assertTrue(result.isPresent(), connector + " init form should build");
            Value listener = result.get().getProperties().get("listener");
            Assert.assertNotNull(listener, connector + " listener present");
            Assert.assertEquals(listener.getTypes().getFirst().fieldType(), Value.FieldType.CHOICE);
        }
    }

    @Test
    public void testMissingModelReturnsEmpty() {
        Path nonExistent = Paths.get(System.getProperty("java.io.tmpdir"), "no-trigger-model-" + hashCode());
        Assert.assertTrue(
                ConnectorModelReader.getInstance().readTriggerModelFromPackageRoot(nonExistent).isEmpty(),
                "a package without trigger-model.json must yield empty (so the router falls back)");
    }
}
