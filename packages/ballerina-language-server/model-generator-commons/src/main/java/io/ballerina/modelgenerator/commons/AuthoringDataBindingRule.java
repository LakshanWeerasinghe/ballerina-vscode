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

package io.ballerina.modelgenerator.commons;

import java.util.List;

/**
 * One entry in a {@code trigger-authoring.json} document's top-level {@code dataBindingRules[]}
 * registry — the legal ways a handler parameter's raw value can be projected into a different,
 * user-defined type (e.g. RabbitMQ/Kafka's envelope-inclusion, FTP's JSON/XML/CSV variants).
 * Referenced from {@link AuthoringServiceType.Param#dataBinding()}.
 *
 * @param id             referenced from {@code params[].dataBinding}
 * @param envelopeType   the record being wrapped; present only when {@code supportedModes} includes an
 *                      {@link SupportedMode#MODE_INCLUDED_RECORD} entry — restating it on that entry
 *                      too would either duplicate {@link SupportedMode#includes()} or falsely imply a
 *                      partial-envelope relationship where there is none (e.g. a rule with only
 *                      {@code direct}/{@code streamable} modes)
 * @param cardinality    {@link #CARDINALITY_ARRAY} when the bound value is actually an array/batch of
 *                      the described type (i.e. whatever a mode resolves to is the array element
 *                      type, not the param's whole type); {@code null} for a scalar,
 *                      one-value-per-invocation binding
 * @param supportedModes the legal binding modes for this slot
 * @since 1.10.0
 */
public record AuthoringDataBindingRule(String id, TypeRef envelopeType, String cardinality,
                                       List<SupportedMode> supportedModes) {

    public static final String CARDINALITY_ARRAY = "array";

    /**
     * One binding mode a {@link AuthoringDataBindingRule} supports.
     *
     * @param mode          {@link #MODE_DIRECT}, {@link #MODE_INCLUDED_RECORD}, or
     *                     {@link #MODE_STREAMABLE}
     * @param typeConstraint the legal alternatives for {@link #MODE_DIRECT}/{@link #MODE_STREAMABLE};
     *                     {@code null} for {@link #MODE_INCLUDED_RECORD}
     * @param excludes      types that are members of {@code typeConstraint}'s general category but
     *                     explicitly disallowed (e.g. {@code anydata} excluding subtypes of the
     *                     envelope type); {@code null}/absent when nothing is excluded
     * @param includes      the base record being included via {@code *Type;}; set only for
     *                     {@link #MODE_INCLUDED_RECORD}
     * @param bindableFields the field names the user's record is free to override; set only for
     *                     {@link #MODE_INCLUDED_RECORD} — the complement (fields that stay pinned) is
     *                     always derivable from {@link #includes}'s own declared fields minus this
     *                     list, so it is never separately restated
     */
    public record SupportedMode(
            String mode,
            List<TypeRef> typeConstraint,
            List<TypeRef> excludes,
            TypeRef includes,
            List<String> bindableFields) {

        /** The param's type directly is the target type — no wrapping. */
        public static final String MODE_DIRECT = "direct";
        /** A user-defined record that does {@code *EnvelopeType;} plus overrides only {@code bindableFields}. */
        public static final String MODE_INCLUDED_RECORD = "includedRecord";
        /** Same as {@link #MODE_DIRECT}, but the param is a {@code stream<...>} over the target type. */
        public static final String MODE_STREAMABLE = "streamable";
    }
}
