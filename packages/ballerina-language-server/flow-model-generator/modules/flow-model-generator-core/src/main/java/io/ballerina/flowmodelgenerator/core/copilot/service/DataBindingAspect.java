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

package io.ballerina.flowmodelgenerator.core.copilot.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.ballerina.modelgenerator.commons.trigger.models.TriggerMetadataModel;

import java.util.List;
import java.util.Optional;

/**
 * Spec §9 {@code dataBindingRules[]} — how a parameter's raw value may be projected into a user-defined
 * type.
 *
 * <p>Every type name is written as a {@code {name, links}} pair rather than bare text, for one reason: the
 * type closure that decides which definitions reach the prompt walks links. A binding note naming
 * {@code AnydataConsumerRecord} with no way to reach its declaration would tell the model to include a
 * record the file never defines.
 *
 * @since 1.7.0
 */
final class DataBindingAspect implements ParamAspect {

    @Override
    public String id() {
        return "dataBinding";
    }

    @Override
    public String specSection() {
        return "§9";
    }

    @Override
    public void contribute(ParamScope scope, ParamDraft draft) {
        TriggerMetadataModel.ServiceType.Param param = scope.param();
        if (param == null || param.dataBinding() == null || param.dataBinding().isBlank()) {
            return;
        }
        TriggerScope service = scope.handler().service();
        String packageName = service.packageName();
        Optional<DataBindingResolver.BindingSpec> spec = DataBindingResolver.resolve(
                param.dataBinding(), service.document(), packageName, service.declaresType(),
                envelopeFields(service));

        if (spec.isEmpty()) {
            // Told apart so the diagnostic is actionable: a rule that does not exist is a broken reference,
            // whereas a rule whose every mode was unrecognised is a vocabulary problem.
            String reason = DataBindingResolver.declaresRule(service.document(), param.dataBinding())
                    ? "its dataBindingRules[] entry declares no mode this version understands"
                    : "no dataBindingRules[] entry declares the id '" + param.dataBinding() + "'";
            draft.drop(id(), specSection(), param.dataBinding(), reason);
            return;
        }
        draft.setBinding(toJson(spec.get(), packageName));
    }

    /**
     * The envelope-field lookup spec §9's derived {@code fixedFields} needs, or an empty one when no
     * compiled package is behind this scope — in which case {@code fixedFields} is simply not derived,
     * rather than guessed.
     */
    private static java.util.function.Function<String, List<String>> envelopeFields(TriggerScope scope) {
        TriggerSemanticFacts facts = scope.facts();
        return facts == null ? name -> List.of() : facts::recordFieldNames;
    }

    private static JsonObject toJson(DataBindingResolver.BindingSpec spec, String packageName) {
        JsonObject json = new JsonObject();
        if (spec.arrayCardinality()) {
            // Emitted only when true, per the omission rule. The renderer must read it as "a mode's type is
            // the array *element* type" — kafka's param is already `AnydataConsumerRecord[]`, so treating
            // this as "make it an array" would pluralize twice.
            json.addProperty("array", true);
        }
        JsonArray modes = new JsonArray();
        for (DataBindingResolver.Mode mode : spec.modes()) {
            modes.add(modeToJson(mode, packageName));
        }
        json.add("modes", modes);
        return json;
    }

    private static JsonObject modeToJson(DataBindingResolver.Mode mode, String packageName) {
        JsonObject json = new JsonObject();
        switch (mode) {
            case DirectModeResolver.Direct direct -> {
                json.addProperty("mode",
                        TriggerMetadataModel.DataBindingRule.SupportedMode.MODE_DIRECT);
                addTypes(json, "typeConstraint", direct.typeConstraint(), packageName);
                addTypes(json, "excludes", direct.excludes(), packageName);
            }
            case IncludedRecordModeResolver.IncludedRecord included -> {
                json.addProperty("mode",
                        TriggerMetadataModel.DataBindingRule.SupportedMode.MODE_INCLUDED_RECORD);
                if (included.envelope() != null) {
                    json.add("includes",
                            TypeResolver.resolveTypeWithLinks(included.envelope(), packageName));
                }
                addStrings(json, "bindableFields", included.bindableFields());
                addStrings(json, "fixedFields", included.fixedFields());
            }
            case StreamableModeResolver.Streamable streamable -> {
                json.addProperty("mode",
                        TriggerMetadataModel.DataBindingRule.SupportedMode.MODE_STREAMABLE);
                addTypes(json, "typeConstraint", streamable.typeConstraint(), packageName);
            }
        }
        return json;
    }

    private static void addTypes(JsonObject json, String key, List<String> signatures, String packageName) {
        if (signatures == null || signatures.isEmpty()) {
            return;
        }
        JsonArray types = new JsonArray();
        for (String signature : signatures) {
            types.add(TypeResolver.resolveTypeWithLinks(signature, packageName));
        }
        json.add(key, types);
    }

    private static void addStrings(JsonObject json, String key, List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        json.add(key, array);
    }
}
