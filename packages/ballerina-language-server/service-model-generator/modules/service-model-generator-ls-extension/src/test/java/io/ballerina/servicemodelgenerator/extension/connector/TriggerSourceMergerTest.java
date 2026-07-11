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
