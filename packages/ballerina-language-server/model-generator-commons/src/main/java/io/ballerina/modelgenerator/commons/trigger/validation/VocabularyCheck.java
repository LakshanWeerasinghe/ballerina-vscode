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
import java.util.Set;

/**
 * <b>Spec §10's vocabulary tables</b>: every closed enumeration the spec defines, checked in one place.
 *
 * <p>This subsumes the plan's separately-named {@code HandlerKindCheck} and {@code IdentifierFormCheck},
 * and that is deliberate: both are pure membership tests against a §10 row, and splitting them would give
 * three classes that differ only in which row they read. The plan's own granularity note allows it —
 * checks are per invariant, and "a value outside its §10 vocabulary" is one invariant. What is <i>not</i>
 * folded in is {@link PresenceScopeCheck}, because scope is a different question from membership: it is
 * about where a legal value may appear, not whether the value is legal.
 *
 * <p>Every unknown value is an ERROR rather than a warning. The consuming pipeline degrades each of these
 * to a default — an unknown {@code kind} reads as {@code remote}, an unknown {@code addMode} as "at most
 * one", an unknown {@code form} yields a note and no placeholder — so a typo does not crash anything; it
 * quietly produces the wrong API guidance, which is worse.
 *
 * @since 1.10.0
 */
final class VocabularyCheck implements DocumentCheck {

    private static final Set<String> PRESENCE = Set.of("required", "optional");
    private static final Set<String> IDENTIFIER_FORM = Set.of("basePath", "stringLiteral");
    private static final Set<String> HANDLER_ADD_MODE = Set.of("subset", "many");
    private static final Set<String> PARAM_ADD_MODE = Set.of("many");
    private static final Set<String> KIND = Set.of("remote", "resource");
    private static final Set<String> BINDING_MODE = Set.of("direct", "includedRecord", "streamable");
    private static final Set<String> ATTACH_POINT = Set.of("service", "function", "parameter", "return");
    private static final Set<String> RULE_TYPE = Set.of("oneOf", "atMostOne");
    private static final Set<String> IMPORT_TYPE = Set.of("driver");
    private static final Set<String> GRAPHQL_OPERATION = Set.of("query", "mutation", "subscription");
    private static final Set<String> CARDINALITY = Set.of("array");

    @Override
    public String id() {
        return "vocabulary";
    }

    @Override
    public String specSection() {
        return "§10";
    }

    @Override
    public List<Finding> check(TriggerMetadataModel document) {
        List<Finding> findings = new ArrayList<>();

        List<TriggerMetadataModel.Listener> listeners = DocumentWalk.safe(document.listeners());
        for (int i = 0; i < listeners.size(); i++) {
            TriggerMetadataModel.Listener listener = listeners.get(i);
            if (listener == null) {
                continue;
            }
            List<TriggerMetadataModel.RequiredImport> imports = DocumentWalk.safe(listener.requiredImports());
            for (int j = 0; j < imports.size(); j++) {
                if (imports.get(j) != null) {
                    member(findings, imports.get(j).importType(), IMPORT_TYPE,
                            "listeners[" + i + "].requiredImports[" + j + "].importType");
                }
            }
        }

        List<TriggerMetadataModel.ServiceType> serviceTypes = DocumentWalk.safe(document.serviceTypes());
        for (int i = 0; i < serviceTypes.size(); i++) {
            TriggerMetadataModel.ServiceType serviceType = serviceTypes.get(i);
            if (serviceType == null) {
                continue;
            }
            checkServiceType(findings, serviceType, i);
        }

        for (TriggerMetadataModel.Annotation annotation : DocumentWalk.safe(document.annotations())) {
            if (annotation == null) {
                continue;
            }
            String path = "annotations[" + annotation.id() + "]";
            member(findings, annotation.attachPoint(), ATTACH_POINT, path + ".attachPoint");
            member(findings, annotation.presence(), PRESENCE, path + ".presence");
        }

        for (TriggerMetadataModel.DataBindingRule rule : DocumentWalk.safe(document.dataBindingRules())) {
            if (rule == null) {
                continue;
            }
            String path = "dataBindingRules[" + rule.id() + "]";
            optionalMember(findings, rule.cardinality(), CARDINALITY, path + ".cardinality");
            for (TriggerMetadataModel.DataBindingRule.SupportedMode mode
                    : DocumentWalk.safe(rule.supportedModes())) {
                if (mode != null) {
                    member(findings, mode.mode(), BINDING_MODE, path + ".supportedModes[].mode");
                }
            }
        }
        return findings;
    }

    private void checkServiceType(List<Finding> findings, TriggerMetadataModel.ServiceType serviceType,
                                  int index) {
        String path = DocumentWalk.serviceTypePath(index);
        if (serviceType.identifier() != null) {
            member(findings, serviceType.identifier().presence(), PRESENCE, path + ".identifier.presence");
            for (String form : DocumentWalk.safe(serviceType.identifier().form())) {
                member(findings, form, IDENTIFIER_FORM, path + ".identifier.form");
            }
        }
        if (serviceType.handlers() != null) {
            optionalMember(findings, serviceType.handlers().addMode(), HANDLER_ADD_MODE,
                    path + ".handlers.addMode");
        }
        for (TriggerMetadataModel.ServiceType.Rule rule : DocumentWalk.safe(serviceType.rules())) {
            if (rule != null) {
                member(findings, rule.type(), RULE_TYPE, path + ".rules[" + rule.id() + "].type");
            }
        }

        List<TriggerMetadataModel.ServiceType.HandlerOption> options = DocumentWalk.options(serviceType);
        for (int j = 0; j < options.size(); j++) {
            TriggerMetadataModel.ServiceType.HandlerOption option = options.get(j);
            if (option == null) {
                continue;
            }
            String optionPath = DocumentWalk.optionPath(index, j);
            member(findings, option.kind(), KIND, optionPath + ".kind");
            optionalMember(findings, option.presence(), PRESENCE, optionPath + ".presence");
            optionalMember(findings, option.graphqlOperation(), GRAPHQL_OPERATION,
                    optionPath + ".graphqlOperation");
            List<TriggerMetadataModel.ServiceType.Param> params = DocumentWalk.safe(option.params());
            for (int k = 0; k < params.size(); k++) {
                TriggerMetadataModel.ServiceType.Param param = params.get(k);
                if (param == null) {
                    continue;
                }
                String paramPath = DocumentWalk.paramPath(index, j, k);
                member(findings, param.presence(), PRESENCE, paramPath + ".presence");
                optionalMember(findings, param.addMode(), PARAM_ADD_MODE, paramPath + ".addMode");
            }
        }
    }

    /** A value the spec requires: absent is a defect, and so is a value outside the table. */
    private void member(List<Finding> findings, String value, Set<String> allowed, String path) {
        if (value == null) {
            findings.add(Finding.error(this, path, "missing; spec §10 allows " + sorted(allowed)));
            return;
        }
        optionalMember(findings, value, allowed, path);
    }

    /** A value the spec allows to be absent: only a present-but-unknown value is a defect. */
    private void optionalMember(List<Finding> findings, String value, Set<String> allowed, String path) {
        if (value != null && !allowed.contains(value)) {
            findings.add(Finding.error(this, path,
                    "'" + value + "' is outside spec §10's vocabulary " + sorted(allowed)));
        }
    }

    private static String sorted(Set<String> allowed) {
        return allowed.stream().sorted().toList().toString();
    }
}
