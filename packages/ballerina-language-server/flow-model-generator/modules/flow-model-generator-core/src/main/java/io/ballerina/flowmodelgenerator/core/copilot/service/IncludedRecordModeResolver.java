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

import io.ballerina.modelgenerator.commons.trigger.models.TriggerMetadataModel;
import io.ballerina.modelgenerator.commons.trigger.models.TypeRef;
import io.ballerina.modelgenerator.commons.trigger.utils.TypeRefResolver;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Owns <b>spec §9's {@code mode: "includedRecord"}</b>: "User record does {@code *EnvelopeType;}, overrides
 * only {@code bindableFields}; everything else stays fixed."
 *
 * <p>Two things this component gets right that the sibling consumer does not:
 * <ul>
 *   <li><b>The full {@code bindableFields} list is kept.</b> {@code TriggerModelSynthesizer:708-709}
 *       truncates it to {@code get(0)}. Both corpus rules happen to declare exactly one field, so the
 *       truncation is invisible today and would silently drop the second the moment a connector declares
 *       one.</li>
 *   <li><b>{@code fixedFields} is derived here, once.</b> Spec §9: "No {@code fixedFields} — always
 *       derivable as the envelope's fields minus {@code bindableFields}." Deriving it in the resolver is
 *       what makes the derivation testable and keeps every consumer from re-implementing a set
 *       subtraction.</li>
 * </ul>
 *
 * <p><b>The complement is the load-bearing half.</b> Naming the bindable field says which field a user
 * record may override; it does not say the others are <i>forbidden</i>, which is the whole content of
 * {@code bindableFields}. A consumer must state the prohibition, not just the permission.
 *
 * @since 1.7.0
 */
final class IncludedRecordModeResolver {

    private IncludedRecordModeResolver() {
        // Prevent instantiation
    }

    /**
     * The {@code includedRecord} mode of one data-binding rule.
     *
     * @param envelope       the record a user type includes with {@code *Envelope;}, as module-prefixed
     *                       signature text; {@code null} when the document names none
     * @param bindableFields the fields a user record may override, in document order; never truncated
     * @param fixedFields    the envelope's remaining fields, derived rather than restated (spec §9). Empty
     *                       when the envelope is not an introspectable record of the resolved package — in
     *                       which case a consumer must not claim to know which fields are pinned
     */
    record IncludedRecord(String envelope, List<String> bindableFields, List<String> fixedFields)
            implements DataBindingResolver.Mode {
    }

    /**
     * Resolves an {@code includedRecord} mode.
     *
     * @param mode           the {@code supportedModes[]} entry
     * @param packageName    the resolved package name, for rendering the envelope per spec §1
     * @param declaresType   whether the home module declares a type of a given name
     * @param envelopeFields the declared field names of a record, by bare type name
     * @return the resolved mode
     */
    static IncludedRecord resolve(TriggerMetadataModel.DataBindingRule.SupportedMode mode, String packageName,
                                  Predicate<String> declaresType,
                                  Function<String, List<String>> envelopeFields) {
        TypeRef includes = mode.includes();
        String envelope = includes == null ? null
                : TypeRefResolver.render(includes, packageName, declaresType);
        List<String> bindable = bindableFields(mode);
        return new IncludedRecord(blankToNull(envelope), bindable,
                fixedFields(includes, bindable, envelopeFields));
    }

    /**
     * Spec §9's derivation: the envelope's declared fields minus the bindable ones, in declaration order.
     *
     * <p>Uses the envelope's <b>bare</b> name, not its rendered signature: the lookup is against the
     * resolved package's own symbols, where a type is known by the name it was declared with.
     */
    private static List<String> fixedFields(TypeRef includes, List<String> bindableFields,
                                            Function<String, List<String>> envelopeFields) {
        if (includes == null || includes.name() == null || envelopeFields == null) {
            return List.of();
        }
        List<String> declared = envelopeFields.apply(TypeRefResolver.baseIdentifier(includes.name()));
        if (declared == null || declared.isEmpty()) {
            return List.of();
        }
        Set<String> bindable = new LinkedHashSet<>(bindableFields);
        List<String> fixed = new ArrayList<>();
        for (String field : declared) {
            if (!bindable.contains(field)) {
                fixed.add(field);
            }
        }
        return fixed;
    }

    private static List<String> bindableFields(TriggerMetadataModel.DataBindingRule.SupportedMode mode) {
        if (mode.bindableFields() == null) {
            return List.of();
        }
        List<String> fields = new ArrayList<>();
        for (String field : mode.bindableFields()) {
            if (field != null && !field.isBlank()) {
                fields.add(field);
            }
        }
        return fields;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
