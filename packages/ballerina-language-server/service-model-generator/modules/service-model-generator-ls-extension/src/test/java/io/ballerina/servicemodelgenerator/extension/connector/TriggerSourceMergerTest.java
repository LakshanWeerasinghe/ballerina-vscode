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

import io.ballerina.servicemodelgenerator.extension.connector.adapter.TriggerServiceAdapter;
import io.ballerina.servicemodelgenerator.extension.connector.adapter.TriggerSourceMerger;
import io.ballerina.servicemodelgenerator.extension.connector.model.TriggerModel;
import io.ballerina.servicemodelgenerator.extension.model.Function;
import io.ballerina.servicemodelgenerator.extension.model.Repeatable;
import io.ballerina.servicemodelgenerator.extension.model.Service;
import io.ballerina.servicemodelgenerator.extension.model.Value;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.net.URL;
import java.nio.file.Paths;
import java.util.List;

/**
 * Verifies {@link TriggerSourceMerger} preserves the schema-authored {@code optional} flag (whether the
 * designer's trash icon may remove a present handler) instead of forcing it to {@code false} for every
 * matched handler — a regression that silently disabled deletion for handlers the schema explicitly
 * marks optional (e.g. ftp's onFileDelete/onError, kafka's onError), while looking correct for
 * github/twilio purely because every one of their handlers is {@code optional: false} in the schema.
 *
 * @since 1.9.0
 */
public class TriggerSourceMergerTest {

    private TriggerModel model(String connector) throws Exception {
        URL fixture = getClass().getClassLoader().getResource("trigger_models/" + connector);
        Assert.assertNotNull(fixture, connector + " fixture missing");
        return ConnectorModelReader.getInstance()
                .readTriggerModelFromPackageRoot(Paths.get(fixture.toURI())).orElseThrow();
    }

    @Test
    public void testOptionalHandlerStaysOptionalOncePresentInSource() throws Exception {
        // ftp's onFileDelete is schema-optional (deletable) even once added to the source.
        Service service = TriggerServiceAdapter.toServiceTemplate(
                model("ftp"), "Service", "ballerina", "ftp", "ftp");
        TriggerSourceMerger.mergeSource(service, List.of(sourceFunction("onFileDelete", "REMOTE")));

        Function onFileDelete = findFunction(service, "onFileDelete");
        Assert.assertTrue(onFileDelete.isEnabled(), "the merged handler is present/enabled");
        Assert.assertTrue(onFileDelete.isOptional(),
                "a schema-optional handler must stay deletable once merged from source");
    }

    @Test
    public void testRequiredHandlerStaysNonOptionalOncePresentInSource() throws Exception {
        // kafka's onConsumerRecord is schema-required (optional=false, not deletable): the compiler
        // mandates it, so it must stay non-optional after merge too.
        Service service = TriggerServiceAdapter.toServiceTemplate(
                model("kafka"), "Service", "ballerinax", "kafka", "kafka");
        TriggerSourceMerger.mergeSource(service, List.of(sourceFunction("onConsumerRecord", "REMOTE")));

        Function onConsumerRecord = findFunction(service, "onConsumerRecord");
        Assert.assertTrue(onConsumerRecord.isEnabled(), "the merged handler is present/enabled");
        Assert.assertFalse(onConsumerRecord.isOptional(),
                "a schema-required handler must stay non-deletable once merged from source");
    }

    @Test
    public void testOneEachPerGroupConsumesOnlyMatchedVariant() throws Exception {
        // smb's file-format handlers share the onFileChange group as ONE_EACH_PER_GROUP: adding one
        // format consumes only that variant, leaving its siblings addable. (The old boolean model
        // treated any grouped handler as mutually exclusive, wrongly clearing the whole group.)
        Service service = TriggerServiceAdapter.toServiceTemplate(
                model("smb"), "Service", "ballerinax", "smb", "smb");
        TriggerSourceMerger.mergeSource(service, List.of(sourceFunction("onFileCsv", "REMOTE")));

        Assert.assertNotNull(findFunction(service, "onFileCsv"), "the added variant is present");
        List<String> addable = catalogNames(service);
        Assert.assertFalse(addable.contains("onFileCsv"), "the consumed variant leaves the catalog");
        Assert.assertTrue(addable.contains("onFileJson"), "sibling variants stay addable");
        Assert.assertTrue(addable.contains("onFileXml"), "sibling variants stay addable");
    }

    @Test
    public void testOneOfGroupConsumesEntireGroup() throws Exception {
        // Re-tag smb's file-format group as ONE_OF_GROUP (RabbitMQ's onMessage/onRequest shape):
        // adding any one member must clear every sibling from the addable catalog.
        Service service = TriggerServiceAdapter.toServiceTemplate(
                model("smb"), "Service", "ballerinax", "smb", "smb");
        service.getSchemaFunctions().stream()
                .filter(fn -> "onFileChange".equals(fn.getGroup()))
                .forEach(fn -> fn.setRepeatable(Repeatable.ONE_OF_GROUP));
        TriggerSourceMerger.mergeSource(service, List.of(sourceFunction("onFileCsv", "REMOTE")));

        List<String> addable = catalogNames(service);
        Assert.assertFalse(addable.contains("onFileJson"),
                "a mutually-exclusive group is fully consumed once one member is added");
        Assert.assertFalse(addable.contains("onFileXml"), "no sibling of the exclusive group remains");
    }

    private static List<String> catalogNames(Service service) {
        return service.getSchemaFunctions() == null ? List.of()
                : service.getSchemaFunctions().stream().map(fn -> fn.getName().getValue()).toList();
    }

    private static Function findFunction(Service service, String name) {
        return service.getFunctions().stream()
                .filter(f -> name.equals(f.getName().getValue()))
                .findFirst().orElseThrow(() -> new AssertionError(name + " not found in merged functions"));
    }

    /** A minimal parsed-from-source Function: just enough for {@code findTemplate} to match by name/kind. */
    private static Function sourceFunction(String name, String kind) {
        return new Function.FunctionBuilder()
                .kind(kind)
                .name(new Value.ValueBuilder().value(name).build())
                .accessor(new Value.ValueBuilder().value("").build())
                .build();
    }
}
