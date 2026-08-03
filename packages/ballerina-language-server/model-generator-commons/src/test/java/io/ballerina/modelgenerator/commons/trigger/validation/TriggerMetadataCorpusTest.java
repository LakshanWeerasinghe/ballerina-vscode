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

package io.ballerina.modelgenerator.commons.trigger.validation;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.ballerina.modelgenerator.commons.trigger.models.TriggerMetadataModel;
import io.ballerina.modelgenerator.commons.trigger.utils.TriggerMetadataGson;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runs the validator over every {@code trigger-metadata.json} this repo ships.
 *
 * <p>This is the test the whole validation tier exists for. The consuming pipeline degrades gracefully
 * around every defect these checks find — that is correct at request time and is exactly why the defects
 * were invisible. Here they fail the build.
 *
 * @since 1.10.0
 */
public class TriggerMetadataCorpusTest {

    /** Every bundled document, by module key. */
    private static final List<String> CORPUS = List.of(
            "ftp", "graphql", "grpc", "http", "kafka", "mcp", "mssql.cdc", "rabbitmq", "smb",
            "trigger.github", "trigger.google.calendar", "websocket", "websub");

    /**
     * The schema's declared top-level properties. Hand-maintained against
     * {@code resources/schemas/trigger-metadata.schema.json} because no JSON-schema implementation is on
     * this build's classpath and adding one would break every {@code --offline} build that has not cached
     * it. This covers the one schema clause that actually bites — {@code additionalProperties: false} —
     * which is what made a spec-conforming document carrying {@code version} fail the repo's own schema
     * before it was updated.
     */
    private static final Set<String> SCHEMA_TOP_LEVEL_PROPERTIES =
            Set.of("version", "listeners", "serviceTypes", "annotations", "dataBindingRules");

    private static final Set<String> SCHEMA_REQUIRED_TOP_LEVEL =
            Set.of("version", "listeners", "serviceTypes");

    @Test
    public void testEveryBundledDocumentIsFreeOfErrors() {
        Map<String, List<Finding>> errorsByDocument = new LinkedHashMap<>();
        for (String key : CORPUS) {
            List<Finding> errors =
                    TriggerMetadataValidator.validate(parse(key), Finding.Severity.ERROR);
            if (!errors.isEmpty()) {
                errorsByDocument.put(key, errors);
            }
        }
        Assert.assertTrue(errorsByDocument.isEmpty(), "Documents with ERROR findings:\n" + render(errorsByDocument));
    }

    @Test
    public void testEveryBundledDocumentParses() {
        // A document that fails to deserialize reaches the pipeline as "this library ships no metadata",
        // which is indistinguishable from the (very common) case of a library that genuinely ships none.
        for (String key : CORPUS) {
            TriggerMetadataModel document = parse(key);
            Assert.assertNotNull(document, key + " failed to parse");
            Assert.assertNotNull(document.listeners(), key + " parsed with no listeners");
            Assert.assertNotNull(document.serviceTypes(), key + " parsed with no serviceTypes");
        }
    }

    @Test
    public void testEveryBundledDocumentSatisfiesTheSchemaTopLevelContract() {
        // The `additionalProperties: false` half of §9.6's ask, hand-rolled. It is deliberately narrow:
        // it catches a key the schema would reject outright, which is the failure mode that silently
        // invalidates every document at once.
        for (String key : CORPUS) {
            JsonObject json = raw(key);
            for (String property : json.keySet()) {
                Assert.assertTrue(SCHEMA_TOP_LEVEL_PROPERTIES.contains(property),
                        key + " declares top-level key '" + property
                                + "', which the schema does not allow (additionalProperties: false)");
            }
            for (String required : SCHEMA_REQUIRED_TOP_LEVEL) {
                Assert.assertTrue(json.has(required),
                        key + " is missing the schema-required top-level key '" + required + "'");
            }
        }
    }

    @Test
    public void testEverySpecSectionHasAtLeastOneRegisteredCheck() {
        // The validator half of the plan's traceability guard: a construct cannot be half-covered, with a
        // resolver that reads it and no check that validates it.
        List<String> owned = TriggerMetadataValidator.checks().stream()
                .map(DocumentCheck::specSection).distinct().toList();
        for (String section : List.of("§1", "§2", "§3", "§4", "§5", "§6", "§8", "§9", "§10")) {
            Assert.assertTrue(owned.contains(section),
                    "no registered check owns spec " + section + "; owned: " + owned);
        }
    }

    @Test
    public void testEveryCheckIsAttributable() {
        List<String> ids = new ArrayList<>();
        for (DocumentCheck check : TriggerMetadataValidator.checks()) {
            Assert.assertNotNull(check.id());
            Assert.assertFalse(check.id().isBlank());
            Assert.assertTrue(check.specSection().startsWith("§"),
                    check.id() + " must name the spec section it owns, got: " + check.specSection());
            ids.add(check.id());
        }
        Assert.assertEquals(ids.stream().distinct().count(), ids.size(),
                "check ids must be unique: " + ids);
    }

    /**
     * Every warning the corpus is known to carry, as {@code document|checkId|path}.
     *
     * <p>All five are <b>spec limitations, not document defects</b>, which is why they are tolerated rather
     * than fixed:
     * <ul>
     *   <li>{@code grpc} and {@code graphql} both need "an open-ended catalog whose handlers take one of N
     *       shapes" — gRPC's four RPC shapes with proto-derived names, GraphQL's query/mutation/
     *       subscription. Spec §4 allows exactly one {@code "*"} entry, so neither can be expressed.
     *       Editing either document to conform would make the rendered output worse: gRPC's four shape
     *       names would become literal, copyable handler names.</li>
     *   <li>{@code graphql}'s mutation carries {@code fieldName}/{@code graphqlOperation} on a
     *       {@code remote} handler, which is correct Ballerina — see {@link ResourceExtrasCheck}.</li>
     *   <li>{@code websocket}'s {@code Service} is genuinely not listener-attachable.</li>
     * </ul>
     *
     * <p>Pinned exactly so a <i>new</i> warning cannot hide among the accepted ones.
     */
    private static final Set<String> ACCEPTED_WARNINGS = Set.of(
            "graphql|addMode|serviceTypes[0].handlers.options",
            "graphql|resourceExtras|serviceTypes[0].handlers.options[1].fieldName",
            "graphql|resourceExtras|serviceTypes[0].handlers.options[1].graphqlOperation",
            "grpc|addMode|serviceTypes[0].handlers",
            "websocket|listenerRef|serviceTypes[service]");

    @Test
    public void testTheCorpusCarriesExactlyTheKnownWarnings() {
        List<String> actual = new ArrayList<>();
        for (String key : CORPUS) {
            for (Finding finding : TriggerMetadataValidator.validate(parse(key), Finding.Severity.WARN)) {
                actual.add(key + "|" + finding.checkId() + "|" + finding.path());
            }
        }
        List<String> unexpected = actual.stream().filter(w -> !ACCEPTED_WARNINGS.contains(w)).sorted().toList();
        Assert.assertTrue(unexpected.isEmpty(), "new warnings the corpus did not carry before: " + unexpected);

        List<String> resolved = ACCEPTED_WARNINGS.stream().filter(w -> !actual.contains(w)).sorted().toList();
        Assert.assertTrue(resolved.isEmpty(),
                "these warnings no longer fire; remove them from ACCEPTED_WARNINGS: " + resolved);
    }

    @Test
    public void testTheCorpusIsTheExpectedSize() {
        // Pins the count so a document added without being validated fails here rather than silently
        // skipping every check.
        Assert.assertEquals(CORPUS.size(), 13);
    }

    // ---- helpers --------------------------------------------------------------------

    private static String resourcePath(String key) {
        return "trigger-metadata-models/" + key + "/trigger-metadata.json";
    }

    private static String read(String key) {
        try (InputStream is = TriggerMetadataCorpusTest.class.getClassLoader()
                .getResourceAsStream(resourcePath(key))) {
            Assert.assertNotNull(is, "missing bundled document: " + resourcePath(key));
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static TriggerMetadataModel parse(String key) {
        return TriggerMetadataGson.instance().fromJson(read(key), TriggerMetadataModel.class);
    }

    private static JsonObject raw(String key) {
        try (InputStreamReader reader = new InputStreamReader(
                TriggerMetadataCorpusTest.class.getClassLoader()
                        .getResourceAsStream(resourcePath(key)), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String render(Map<String, List<Finding>> errorsByDocument) {
        StringBuilder sb = new StringBuilder();
        errorsByDocument.forEach((key, findings) -> {
            sb.append("  ").append(key).append(":\n");
            findings.forEach(finding -> sb.append("    ").append(finding).append('\n'));
        });
        return sb.toString();
    }
}
