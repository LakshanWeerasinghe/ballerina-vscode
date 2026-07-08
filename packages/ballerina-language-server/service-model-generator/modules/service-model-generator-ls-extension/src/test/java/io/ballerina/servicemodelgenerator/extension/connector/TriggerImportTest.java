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
import io.ballerina.compiler.syntax.tree.ModulePartNode;
import io.ballerina.compiler.syntax.tree.SyntaxTree;
import io.ballerina.servicemodelgenerator.extension.connector.model.TriggerModel;
import io.ballerina.servicemodelgenerator.extension.model.ServiceInitModel;
import io.ballerina.tools.text.TextDocuments;
import org.eclipse.lsp4j.TextEdit;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Map;

/**
 * Unit test for the import emission of {@link SchemaDrivenSourceGenerator}: the connector import plus
 * the model's {@code importStatements} (additional {@code org/module} imports a listener param or
 * handler payload needs, e.g. {@code ballerina/http}).
 *
 * @since 1.9.0
 */
public class TriggerImportTest {

    private final Gson gson = new Gson();

    private ModulePartNode emptyRoot() {
        return (ModulePartNode) SyntaxTree.from(TextDocuments.from("\n")).rootNode();
    }

    @Test
    public void testConnectorAndAdditionalImportsEmitted() {
        String initJson = """
                { "moduleName":"kafka","orgName":"ballerinax","type":"kafka",
                  "properties":{"listener":{"enabled":true,"editable":true,"optional":false,"advanced":false,
                    "types":[{"fieldType":"CHOICE","selected":true}],"codedata":{"type":"LISTENER_CONFIG"},
                    "choices":[{"enabled":true,"editable":true,"optional":false,"advanced":false,
                      "properties":{"listenerVarName":{"enabled":true,"editable":true,"optional":false,
                        "advanced":false,"value":"kafkaListener",
                        "types":[{"fieldType":"IDENTIFIER","selected":true}],
                        "codedata":{"type":"LISTENER_VAR_NAME"}}}}]}}}""";
        String triggerJson = """
                { "schemaVersion":"1.0","displayName":"Kafka","description":"d","orgName":"ballerinax",
                  "packageName":"kafka","moduleName":"kafka","version":"1.0.0","type":"kafka","icon":"i",
                  "importStatements":["ballerina/http"],
                  "serviceTypes":[{"name":"Service","enabled":true,"functions":[],"schemaFunctions":[],
                    "codedata":{"type":"SERVICE_TYPE_DESCRIPTOR","moduleName":"kafka","originalName":"Service"}}]}""";
        ServiceInitModel init = gson.fromJson(initJson, ServiceInitModel.class);
        TriggerModel trigger = gson.fromJson(triggerJson, TriggerModel.class);

        Map<String, List<TextEdit>> edits = SchemaDrivenSourceGenerator.buildAddServiceEditsForTrigger(
                init, trigger, emptyRoot(), "svc.bal");
        String allText = edits.get("svc.bal").stream().map(TextEdit::getNewText).reduce("", String::concat);

        Assert.assertTrue(allText.contains("import ballerinax/kafka;"),
                "connector import should be emitted: " + allText);
        Assert.assertTrue(allText.contains("import ballerina/http;"),
                "additional import from importStatements should be emitted: " + allText);
    }
}
