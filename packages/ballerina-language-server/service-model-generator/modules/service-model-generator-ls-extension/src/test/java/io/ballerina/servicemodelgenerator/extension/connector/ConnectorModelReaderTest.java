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

import io.ballerina.servicemodelgenerator.extension.connector.model.LibraryArtifact;
import io.ballerina.servicemodelgenerator.extension.model.Codedata;
import io.ballerina.servicemodelgenerator.extension.model.ServiceInitModel;
import io.ballerina.servicemodelgenerator.extension.model.Value;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/**
 * Unit test for {@link ConnectorModelReader}: deserializes a connector's two shipped models from a
 * package-root layout ({@code <root>/resources/*.json}) without spinning up the language server.
 * Fixtures are the phase-2 worked example for {@code ballerinax/kafka}.
 *
 * @since 1.8.0
 */
public class ConnectorModelReaderTest {

    @Test
    public void testReadKafkaConnectorModels() throws Exception {
        URL fixture = getClass().getClassLoader().getResource("connector_models/kafka");
        Assert.assertNotNull(fixture, "kafka connector-model fixture is missing from test resources");
        Path packageRoot = Paths.get(fixture.toURI());

        Optional<ConnectorModelReader.ConnectorModels> result =
                ConnectorModelReader.getInstance().readFromPackageRoot(packageRoot);
        Assert.assertTrue(result.isPresent(), "expected both connector models to be read");

        // --- Service Creation Model -> ServiceInitModel/Value (the add-trigger form) ---
        ServiceInitModel creation = result.get().creationModel();
        Assert.assertEquals(creation.getOrgName(), "ballerinax");
        Assert.assertEquals(creation.getModuleName(), "kafka");
        Assert.assertEquals(creation.getType(), "kafka");

        Value bootstrapServers = creation.getProperties().get("bootstrapServers");
        Assert.assertNotNull(bootstrapServers, "bootstrapServers form field should be present");
        Codedata bsCodedata = bootstrapServers.getCodedata();
        Assert.assertNotNull(bsCodedata);
        Assert.assertEquals(bsCodedata.getArgType(), "LISTENER_PARAM_REQUIRED");
        Assert.assertEquals(bsCodedata.getPosition(), Integer.valueOf(1),
                "position must survive deserialization (drives positional listener args)");
        Assert.assertNotNull(bootstrapServers.getValidations(), "validations[] must survive deserialization");
        Assert.assertEquals(bootstrapServers.getValidations().getFirst().getRule(), "common.validate.required");

        Assert.assertNotNull(creation.getProperties().get(ServiceInitModel.KEY_LISTENER_VAR_NAME),
                "listenerVarName form field should be present");

        // --- Service Metadata Model -> LibraryArtifact (drives source generation) ---
        LibraryArtifact metadata = result.get().metadataModel();
        Assert.assertEquals(metadata.schemaVersion(), "1.0");
        Assert.assertEquals(metadata.name(), "kafka");
        Assert.assertNotNull(metadata.serviceDeclaration());
        Assert.assertTrue(metadata.serviceTypes().containsKey("Service"),
                "expected the Kafka 'Service' service type");

        LibraryArtifact.ServiceType serviceType = metadata.serviceTypes().get("Service");
        LibraryArtifact.FunctionModel onConsumerRecord = serviceType.functions().stream()
                .filter(f -> "onConsumerRecord".equals(f.name()))
                .findFirst().orElse(null);
        Assert.assertNotNull(onConsumerRecord, "onConsumerRecord function should be present");
        Assert.assertEquals(onConsumerRecord.kind(), "REMOTE", "function kind should deserialize");
        Assert.assertFalse(onConsumerRecord.parameters().isEmpty(), "function parameters should deserialize");
    }

    @Test
    public void testMissingModelsReturnEmpty() {
        Path nonExistent = Paths.get(System.getProperty("java.io.tmpdir"), "no-such-connector-" + hashCode());
        Assert.assertTrue(ConnectorModelReader.getInstance().readFromPackageRoot(nonExistent).isEmpty(),
                "a package without the two models must yield empty (so the router falls back)");
    }
}
