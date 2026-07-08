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

import io.ballerina.servicemodelgenerator.extension.builder.function.SchemaDrivenFunctionBuilder;
import io.ballerina.servicemodelgenerator.extension.connector.adapter.TriggerServiceAdapter;
import io.ballerina.servicemodelgenerator.extension.connector.model.TriggerModel;
import io.ballerina.servicemodelgenerator.extension.model.Function;
import io.ballerina.servicemodelgenerator.extension.model.Parameter;
import io.ballerina.servicemodelgenerator.extension.model.Service;
import io.ballerina.servicemodelgenerator.extension.model.Value;
import io.ballerina.servicemodelgenerator.extension.util.Utils;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.net.URL;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;

/**
 * Unit test for {@code TriggerFunctionAdapter}'s variant expansion: a schemaFunction with a VARIANT
 * parameter (SMB/FTP's onFileChange file formats) fans out into one self-contained wire Function per
 * variant, carrying the handler-catalog fields ({@code group}/{@code variantLabel}/{@code addLabel}/
 * {@code repeatable}), the composed payload parameter, the composition flags (stream / metadata
 * markers) and the function-level annotation tree — the wire contract the generic front-end handler
 * form consumes. Also covers the save-side collapse of an edited COMPLEX_FUNCTION_ANNOTATION tree
 * into an emitted {@code @smb:FunctionConfig {...}} attachment.
 *
 * @since 1.9.0
 */
public class TriggerFunctionExpansionTest {

    private Service smbTemplate() throws Exception {
        URL fixture = getClass().getClassLoader().getResource("trigger_models/smb");
        Assert.assertNotNull(fixture, "smb fixture missing");
        TriggerModel model = ConnectorModelReader.getInstance()
                .readTriggerModelFromPackageRoot(Paths.get(fixture.toURI())).orElseThrow();
        return TriggerServiceAdapter.toServiceTemplate(model, "Service", "ballerina", "smb", "smb");
    }

    /** Looks a handler up across the template's present functions and its addable catalog. */
    private static Function byName(Service service, String name) {
        return java.util.stream.Stream.concat(
                        service.getFunctions().stream(),
                        service.getSchemaFunctions() == null ? java.util.stream.Stream.<Function>empty()
                                : service.getSchemaFunctions().stream())
                .filter(f -> name.equals(f.getName().getValue()))
                .findFirst().orElse(null);
    }

    @Test
    public void testVariantHandlerFansOutPerFormat() throws Exception {
        Service service = smbTemplate();
        // onFileChange (5 formats) + onError = 6 addable wire functions, all in the catalog
        // (schemaFunctions) — the template's `functions` only carries the model's present handlers.
        List<String> names = service.getSchemaFunctions().stream().map(f -> f.getName().getValue()).toList();
        for (String expected : List.of("onFileCsv", "onFileJson", "onFileXml", "onFileText", "onFile", "onError")) {
            Assert.assertTrue(names.contains(expected), expected + " missing from " + names);
        }

        Function csv = byName(service, "onFileCsv");
        Assert.assertEquals(csv.getGroup(), "onFileChange", "variants must share the schema group id");
        Assert.assertEquals(csv.getVariantLabel(), "CSV");
        Assert.assertEquals(csv.getAddLabel(), "Add On File Change Handler");
        Assert.assertEquals(csv.getRepeatable(), Boolean.TRUE);
        Assert.assertFalse(csv.isEnabled(), "schemaFunction templates ship disabled (addable)");

        Function raw = byName(service, "onFile");
        Assert.assertEquals(raw.getGroup(), "onFileChange");
        Assert.assertEquals(raw.getVariantLabel(), "Raw Bytes");

        Function onError = byName(service, "onError");
        Assert.assertEquals(onError.getGroup(), "onError", "a variant-less handler is its own group");
        Assert.assertEquals(onError.getVariantLabel(), "onError",
                "a variant-less handler keeps its model-declared variantLabel");
    }

    @Test
    public void testVariantPayloadParameterComposition() throws Exception {
        Service service = smbTemplate();
        Function csv = byName(service, "onFileCsv");

        Parameter content = csv.getParameters().stream()
                .filter(p -> "content".equals(p.getName().getValue())).findFirst().orElseThrow();
        Assert.assertEquals(content.getKind(), "DATA_BINDING", "bindable payload -> DATA_BINDING");
        Assert.assertEquals(content.getType().getValue(), "string[][]",
                "CSV composes element(defaultType string[]) through template {{type}}[]");
        Assert.assertEquals(content.getType().getCodedata().getTemplate(), "{{type}}[]");
        Assert.assertEquals(content.getType().getCodedata().getBindable(), Boolean.TRUE);
        Assert.assertEquals(content.getType().getCodedata().getDefaultType(), "string[]");

        // Composition flags surface as wire properties for the UI, keyed as declared in the model.
        Value stream = csv.getProperty("stream");
        Assert.assertNotNull(stream, "PAYLOAD_MODIFIER flag must surface as a wire property");
        Assert.assertEquals(stream.getCodedata().getType(), "PAYLOAD_MODIFIER");
        Assert.assertEquals(stream.getCodedata().getTemplate(), "stream<{{type}}, error?>");
        Value rows = csv.getProperty("rows");
        Assert.assertNotNull(rows, "METADATA_FLAG marker must surface as a wire property");
        Assert.assertEquals(rows.getCodedata().getType(), "METADATA_FLAG");

        // Text is a locked (non-bindable) variant: plain REQUIRED string param, no data binding.
        Function text = byName(service, "onFileText");
        Parameter textContent = text.getParameters().stream()
                .filter(p -> "content".equals(p.getName().getValue())).findFirst().orElseThrow();
        Assert.assertEquals(textContent.getKind(), "REQUIRED");
        Assert.assertEquals(textContent.getType().getValue(), "string");

        // Framework params keep their include-checkbox contract: advanced + disabled until ticked.
        Parameter caller = csv.getParameters().stream()
                .filter(p -> "caller".equals(p.getName().getValue())).findFirst().orElseThrow();
        Assert.assertTrue(caller.isAdvanced(), "caller is an opt-in framework param");
        Assert.assertFalse(caller.isEnabled());
        Assert.assertEquals(caller.getType().getValue(), "smb:Caller");
    }

    @Test
    public void testComplexAnnotationCollapsesToAttachmentOnSave() throws Exception {
        Service service = smbTemplate();
        Function csv = byName(service, "onFileCsv");

        Value functionConfig = csv.getProperty("functionConfig");
        Assert.assertNotNull(functionConfig, "annotation tree must ride the wire function");
        Assert.assertEquals(functionConfig.getCodedata().getType(), "COMPLEX_FUNCTION_ANNOTATION");

        // Simulate the UI: tick the optional fileNamePattern mapping field and type a pattern
        // (a `string `...`` template, the shape the expression field produces).
        Value fileNamePattern = functionConfig.getProperties().get("fileNamePattern");
        fileNamePattern.setEnabled(true);
        fileNamePattern.setValue("string `(.*).csv`");

        SchemaDrivenFunctionBuilder.renderComplexAnnotations(csv);
        String source = Utils.generateFunctionDefSource(csv, List.of(),
                Utils.FunctionAddContext.TRIGGER_ADD, Utils.FunctionSignatureContext.FUNCTION_ADD, new HashMap<>());
        Assert.assertTrue(source.contains("@smb:FunctionConfig{fileNamePattern: \"(.*).csv\"}"),
                "annotation must render above the handler; got:\n" + source);
        Assert.assertTrue(source.contains("remote function onFileCsv(string[][] content, smb:FileInfo fileInfo)"),
                "variant signature must compose payload + required params; got:\n" + source);
    }

    @Test
    public void testUncheckedAnnotationEmitsNothing() throws Exception {
        Service service = smbTemplate();
        Function csv = byName(service, "onFileCsv");
        // fileNamePattern ships disabled -> the whole @FunctionConfig attachment is skipped.
        SchemaDrivenFunctionBuilder.renderComplexAnnotations(csv);
        String source = Utils.generateFunctionDefSource(csv, List.of(),
                Utils.FunctionAddContext.TRIGGER_ADD, Utils.FunctionSignatureContext.FUNCTION_ADD, new HashMap<>());
        Assert.assertFalse(source.contains("FunctionConfig"),
                "no enabled mapping fields -> no annotation; got:\n" + source);
    }
}
