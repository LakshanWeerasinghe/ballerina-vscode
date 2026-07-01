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
import io.ballerina.servicemodelgenerator.extension.model.ServiceInitModel;
import io.ballerina.servicemodelgenerator.extension.model.Value;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Verifies {@link ExistingListenerResolver} resolves an existing listener's config from the model
 * (create-new params as the field template) + the parsed source args — the generic equivalent of the
 * FTP/RabbitMQ per-connector extraction. The source-parse itself needs the LS; here the parsed args are
 * supplied directly to exercise the (pure) template-building and mapping.
 *
 * @since 1.8.0
 */
public class ExistingListenerResolverTest {

    @Test
    public void testResolveConfigFromModelAndParsedArgs() throws Exception {
        // create-new branch = choices[0] of the HubSpot configureListener CHOICE.
        Value createNewBranch = loadHubspotCreationModel().getProperties()
                .get("configureListener").getChoices().get(0);

        ExistingListenerResolver.ListenerTemplate template =
                ExistingListenerResolver.collectTemplate(createNewBranch);

        // A parsed `new ({clientSecret: "s", callbackURL: "c"}, 8090)`:
        LinkedHashMap<String, String> record = new LinkedHashMap<>();
        record.put("clientSecret", "\"s\"");
        record.put("callbackURL", "\"c\"");
        ExistingListenerResolver.ParsedListener parsed = new ExistingListenerResolver.ParsedListener(
                List.of(ExistingListenerResolver.ParsedArg.record(record),
                        ExistingListenerResolver.ParsedArg.scalar("8090")),
                new LinkedHashMap<>());

        Map<String, Value> fields = ExistingListenerResolver.buildFieldsFromParsed(parsed, template);

        // Config-record fields (positional arg 1) resolved onto their templates, read-only.
        Assert.assertTrue(fields.containsKey("clientSecret"), "clientSecret resolved from the config record");
        Assert.assertEquals(fields.get("clientSecret").getValue(), "\"s\"");
        Assert.assertFalse(fields.get("clientSecret").isEditable(), "resolved config is read-only");
        Assert.assertEquals(fields.get("clientSecret").getMetadata().label(), "Client Secret",
                "label comes from the model template");
        Assert.assertTrue(fields.containsKey("callbackURL"));

        // Nested positional param (arg 2) resolved onto its template. It is `optional` in the model, but
        // resolved read-only fields must be optional=false or the UI (DropdownChoiceForm) hides them.
        Assert.assertTrue(fields.containsKey("listenOn"), "listenOn resolved from positional arg 2");
        Assert.assertEquals(fields.get("listenOn").getValue(), "8090");
        Assert.assertFalse(fields.get("listenOn").isEditable());
        Assert.assertFalse(fields.get("listenOn").isOptional(), "resolved fields must be non-optional to render");
        Assert.assertFalse(fields.get("clientSecret").isOptional());
    }

    @Test
    public void testSelectorHasNoOptionsSoNestedConfigRenders() {
        // Regression: the SINGLE_SELECT must expose choices via `items` and per-listener config via
        // `properties`, but must NOT carry `options` — otherwise the front end (isDropDownType) routes it
        // to the enum/expression editor and never renders the nested DropdownChoiceForm.
        Map<String, Value> perListener = new LinkedHashMap<>();
        perListener.put("a", new Value.ValueBuilder().value("a").build());
        perListener.put("b", new Value.ValueBuilder().value("b").build());

        Value selector = ExistingListenerResolver.assembleSelector(List.of("a", "b"), perListener, "hubspot");

        Assert.assertEquals(selector.getTypes().getFirst().fieldType(), Value.FieldType.SINGLE_SELECT);
        Assert.assertNull(selector.getTypes().getFirst().options(),
                "SINGLE_SELECT must have no options, else the nested config is hidden by the UI");
        Assert.assertEquals(selector.getItems(), List.of("a", "b"), "choices come from items");
        Assert.assertEquals(selector.getProperties().keySet(), perListener.keySet(),
                "per-listener config comes from properties");
        Assert.assertTrue(selector.isEditable(), "must be editable so DropdownChoiceForm is used");
    }

    private ServiceInitModel loadHubspotCreationModel() throws Exception {
        Path path = Paths.get(getClass().getClassLoader()
                .getResource("connector_models/hubspot/resources/service-creation.json").toURI());
        return new Gson().fromJson(Files.readString(path, StandardCharsets.UTF_8), ServiceInitModel.class);
    }
}
