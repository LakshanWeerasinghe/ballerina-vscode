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
import org.testng.Assert;
import org.testng.annotations.Test;

import java.net.URL;
import java.nio.file.Paths;
import java.util.List;

/**
 * Unit test for {@link AnnotationEmitter} and the annotation/variant wiring in
 * {@link SchemaDrivenSourceGenerator#buildFunctionSource}: the granular {@code codedata} roles
 * (COMPLEX_FUNCTION_ANNOTATION -> MAPPING_FIELD -> FIELD_VALUE_CHOICE -> MAPPING_CONSTRUCTOR) emit a
 * well-formed {@code @ftp:FunctionConfig { ... }}, and a variant handler fans out to the selected
 * variant's name. Leaf rendering (string quoting) derives from the leaf's declared types[].
 *
 * @since 1.9.0
 */
public class AnnotationEmitterTest {

    private TriggerModel.FunctionModel onFileCsv() throws Exception {
        URL fixture = getClass().getClassLoader().getResource("trigger_models/ftp");
        Assert.assertNotNull(fixture, "ftp fixture missing");
        TriggerModel model = ConnectorModelReader.getInstance()
                .readTriggerModelFromPackageRoot(Paths.get(fixture.toURI())).orElseThrow();
        return model.serviceTypes().getFirst().schemaFunctions().stream()
                .filter(f -> "onFileCsv".equals(f.name())).findFirst().orElseThrow();
    }

    @Test
    public void testFunctionConfigAnnotationEmitsCorrectly() throws Exception {
        List<String> annotations = AnnotationEmitter.annotationsOf(onFileCsv().properties());
        Assert.assertEquals(annotations.size(), 1, "one @ftp:FunctionConfig annotation expected");
        // moveTo's string type quotes its value; optional fields (checked) emit; the selected
        // FIELD_VALUE_CHOICE branch (MOVE -> MAPPING_CONSTRUCTOR) is used.
        Assert.assertEquals(annotations.getFirst(),
                "@ftp:FunctionConfig {afterProcess: {moveTo: \"/home/processed\"}, "
                        + "afterError: {moveTo: \"/home/failed\"}}");
    }

    @Test
    public void testBuildFunctionSourceEmitsAnnotationVariantNameAndComposedType() throws Exception {
        String source = SchemaDrivenSourceGenerator.buildFunctionSource(onFileCsv());
        Assert.assertTrue(source.contains("@ftp:FunctionConfig {afterProcess:"),
                "annotation should sit above the function: " + source);
        // The VARIATION_SELECTOR (default CSV) fans out to the CSV variant's originalName.
        Assert.assertTrue(source.contains("function onFileCsv("),
                "variant handler name should resolve to the selected variant: " + source);
        // The content param's composed type (default CSV payload string[] wrapped by {{type}}[]).
        Assert.assertTrue(source.contains("string[][]"),
                "the variant payload should compose to string[][]: " + source);
    }
}
