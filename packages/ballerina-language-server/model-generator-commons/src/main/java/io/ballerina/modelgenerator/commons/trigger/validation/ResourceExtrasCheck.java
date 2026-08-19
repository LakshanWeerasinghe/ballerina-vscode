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

import io.ballerina.modelgenerator.commons.trigger.models.TriggerMetadataModel;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>Spec §5 resource extras</b>: {@code method}, {@code path}, {@code accessor}, {@code fieldName} and
 * {@code graphqlOperation} are "resource-kind extras", so they say nothing on a remote handler.
 *
 * <p>One check for both protocol families rather than one per protocol: the invariant is the same
 * sentence of the spec, and a document mixing HTTP's {@code method} with GraphQL's {@code accessor} on one
 * option is a defect neither protocol-specific check would own.
 *
 * <h2>Two severities, because §5's grouping is looser than its wording</h2>
 *
 * <p>{@code method}, {@code path} and {@code accessor} on a non-resource handler are an <b>ERROR</b>: the
 * pipeline renders them into a resource signature's accessor and path, and a remote handler has neither,
 * so the document describes syntax that cannot exist.
 *
 * <p>{@code fieldName} and {@code graphqlOperation} are only a <b>WARN</b>, and that is a deliberate
 * departure from a literal reading of §5. {@code graphql}'s mutation option is {@code kind: "remote"} —
 * which is <i>correct</i>, because a Ballerina GraphQL mutation is written as a remote method — and it
 * carries both keys. For such a handler the method name <i>is</i> the GraphQL field name, so
 * {@code fieldName} states something real, and {@code graphqlOperation} is explicitly "informational" in
 * the spec and classifies the handler rather than shaping its syntax. Making either an ERROR would force
 * a document edit that deletes true information to satisfy a grouping the spec did not think through.
 * Recorded for the spec author instead.
 *
 * @since 1.10.0
 */
final class ResourceExtrasCheck implements DocumentCheck {

    private static final String RESOURCE = TriggerMetadataModel.ServiceType.HandlerOption.KIND_RESOURCE;

    @Override
    public String id() {
        return "resourceExtras";
    }

    @Override
    public String specSection() {
        return "§5";
    }

    @Override
    public List<Finding> check(TriggerMetadataModel document) {
        List<Finding> findings = new ArrayList<>();
        List<TriggerMetadataModel.ServiceType> serviceTypes = DocumentWalk.safe(document.serviceTypes());
        for (int i = 0; i < serviceTypes.size(); i++) {
            List<TriggerMetadataModel.ServiceType.HandlerOption> options =
                    DocumentWalk.options(serviceTypes.get(i));
            for (int j = 0; j < options.size(); j++) {
                TriggerMetadataModel.ServiceType.HandlerOption option = options.get(j);
                if (option == null || RESOURCE.equals(option.kind())) {
                    continue;
                }
                String path = DocumentWalk.optionPath(i, j);
                // Renders into resource syntax a remote handler has no room for.
                syntactic(findings, option.method() != null, path, "method");
                syntactic(findings, option.path() != null, path, "path");
                syntactic(findings, option.accessor() != null, path, "accessor");
                // Describes naming/classification, which stays meaningful on a remote GraphQL handler.
                descriptive(findings, option.fieldName() != null, path, "fieldName");
                descriptive(findings, option.graphqlOperation() != null, path, "graphqlOperation");
            }
        }
        return findings;
    }

    private void syntactic(List<Finding> findings, boolean present, String path, String key) {
        if (present) {
            findings.add(Finding.error(this, path + "." + key,
                    "a resource-kind extra on a handler whose kind is not 'resource'; it renders into "
                            + "resource syntax that a remote handler cannot carry"));
        }
    }

    private void descriptive(List<Finding> findings, boolean present, String path, String key) {
        if (present) {
            findings.add(Finding.warn(this, path + "." + key,
                    "spec §5 lists this among the resource-kind extras, but the handler's kind is 'remote'."
                            + " Meaningful for a GraphQL mutation, whose method name is its field name — "
                            + "reported so the spec's grouping can be revisited"));
        }
    }
}
