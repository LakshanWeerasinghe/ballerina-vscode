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

package io.ballerina.servicemodelgenerator.extension.connector.model;

import io.ballerina.servicemodelgenerator.extension.model.Repeatable;

import java.util.List;
import java.util.Map;

/**
 * Deserialization target for a connector-shipped <b>Trigger Model</b>
 * ({@code resources/trigger-model.json}). This is the single unified model that supersedes the
 * earlier two-model design ({@code service-creation.json} + {@code service-metadata.json}): one
 * document unifies the add-trigger init form ({@code initProperties}) with the service type(s) and
 * their handler functions ({@code serviceTypes}).
 *
 * <p>It mirrors the authoring spec ({@code spec.bal}) one-to-one. The core principle is that
 * everything configurable is a {@link Property} that is BOTH a UI widget ({@code types[].fieldType})
 * and a code-generation instruction ({@code codedata}); a {@link Parameter} carries its {@code type}
 * and {@code name} as {@code Property} sub-nodes.
 *
 * <p>These are plain Gson DTOs (records). Gson 2.10+ deserializes records via their canonical
 * constructor and ignores unknown JSON fields, which preserves the schema's forward-compatible
 * {@code additionalProperties: true} contract (unknown {@code fieldType}/{@code codedata} roles and
 * the leading {@code $comment} key degrade gracefully). Adapters translate these into the wire POJOs
 * ({@code Service}/{@code Function}/{@code Value}).
 *
 * <p>Open-vocabulary and dual-typed fields are kept as {@code String}/{@code Object}: {@code fieldType}
 * is an open string; {@code PropertyType.template} is a {@code Property}-or-{@code String} union;
 * {@code Property.value} is an arbitrary JSON scalar. These are resolved by the generator/adapters,
 * not here.
 *
 * @since 1.9.0
 */
public record TriggerModel(
        String schemaVersion,
        String id,
        String displayName,
        String description,
        String orgName,
        String packageName,
        String moduleName,
        String version,
        String type,
        String icon,
        String kind,
        // The listener property's widget in the designer (a Value.FieldType name, e.g.
        // SINGLE_SELECT_LISTENER / MULTIPLE_SELECT_LISTENER): whether a service may bind one or several
        // listeners of this connector's type. Defaults to SINGLE_SELECT_LISTENER when a model omits it.
        String listenerKind,
        Map<String, Property> initProperties,
        List<ServiceTypeModel> serviceTypes,
        List<ReadOnlyMetadata> readOnlyMetadata,
        List<String> importStatements) {

    /**
     * A service-object type and its handler functions. {@code functions} are present/locked
     * handlers; {@code schemaFunctions} are addable templates. {@code properties} carries
     * service-level config / annotations. For multi-type connectors {@code enabled} marks the
     * selected type.
     */
    public record ServiceTypeModel(
            Metadata metadata,
            String name,
            String description,
            Boolean enabled,
            Boolean editable,
            Map<String, Property> properties,
            List<FunctionModel> functions,
            List<FunctionModel> schemaFunctions,
            Codedata codedata) {
    }

    /**
     * The recursive building block of every form. The four boolean markers
     * ({@code enabled}/{@code editable}/{@code optional}/{@code advanced}) are required by the schema.
     * Leaves carry {@code types} + {@code value}; containers carry {@code properties}; choices carry
     * {@code choices}.
     */
    public record Property(
            Metadata metadata,
            boolean enabled,
            boolean editable,
            boolean optional,
            boolean advanced,
            String placeholder,
            Object value,
            List<PropertyType> types,
            List<String> items,
            List<Property> choices,
            Map<String, Property> properties,
            Codedata codedata,
            List<ValidationRule> validations) {
    }

    /**
     * A candidate rendering descriptor. The entry with {@code selected:true} is the active widget.
     * {@code template} is a {@code Property} (REPEATABLE_LIST element clone) OR a {@code String}
     * type-wrap (e.g. {@code "{{type}}[]"}), hence {@code Object}.
     */
    public record PropertyType(
            String fieldType,
            boolean selected,
            String ballerinaType,
            List<Option> options,
            List<TypeMember> typeMembers,
            Object template,
            List<PayloadFormat> formats,
            List<ValidationRule> validations) {
    }

    /**
     * A trigger handler / resource definition. Rich enough to render the add/edit-function dialog
     * and generate the Ballerina function source.
     */
    public record FunctionModel(
            Metadata metadata,
            String name,
            Boolean nameEditable,
            String kind,
            String accessor,
            List<String> qualifiers,
            String group,
            String variantLabel,
            boolean enabled,
            Boolean editable,
            Boolean optional,
            Boolean canAddParameters,
            Repeatable repeatable,
            String documentation,
            List<Parameter> parameters,
            Map<String, Property> properties,
            ReturnType returnType,
            Codedata codedata,
            List<ValidationRule> validations) {
    }

    /**
     * A function parameter whose {@code type} and {@code name} are {@link Property} sub-nodes, so a
     * parameter is rendered and generated with the same generic walk as any form field.
     */
    public record Parameter(
            Metadata metadata,
            String kind,
            Property type,
            Property name,
            Boolean enabled,
            Boolean editable,
            Boolean optional,
            Boolean advanced,
            Boolean hidden,
            Codedata codedata,
            List<ValidationRule> validations) {
    }

    /**
     * The return type of a handler. {@code enabled:false} = returns {@code ()}; {@code optional} =
     * nilable ({@code T?}); {@code hasError} = error union.
     */
    public record ReturnType(
            Metadata metadata,
            String type,
            Boolean typeEditable,
            String typeConstraint,
            boolean enabled,
            Boolean editable,
            Boolean optional,
            Boolean hasError,
            String importStatements,
            Codedata codedata,
            List<ValidationRule> validations) {
    }

    /**
     * Source-generation semantics for a node. Fields are used selectively per {@code type} role;
     * {@code type}/{@code argType} are open strings. {@code modifiers} is an open object. A leaf's
     * rendered value kind (e.g. string quoting) is derived from the node's {@code types[]}
     * ({@code fieldType}/{@code ballerinaType}), not carried here.
     */
    public record Codedata(
            String type,
            String argType,
            String originalName,
            String moduleName,
            String orgName,
            String packageName,
            Integer position,
            String path,
            String defaultType,
            String boundType,
            Boolean bindable,
            String bindingKind,
            String typeConstraint,
            String template,
            String modifier,
            List<String> supersedes,
            String targetParam,
            Object modifiers,
            String field,
            Boolean optional,
            String value,
            String valueQualifier,
            String group,
            String variantLabel) {
    }

    /**
     * An inline selectable option for SINGLE_SELECT / ENUM / CHOICE / VARIATION_SELECTOR.
     */
    public record Option(
            String label,
            String value,
            String helperText) {
    }

    /**
     * A selectable record/union member offered by a TYPE / RECORD_MAP_EXPRESSION field.
     */
    public record TypeMember(
            String type,
            String packageInfo,
            String packageName,
            String kind,
            Boolean selected) {
    }

    /**
     * How a data-binding type may be defined by the user (offered by a PAYLOAD_TYPE field).
     */
    public record PayloadFormat(
            List<String> supported,
            String defaultFormat) {
    }

    /**
     * A read-only summary chip in the service-card header (derived from source).
     */
    public record ReadOnlyMetadata(
            String key,
            String displayName,
            String kind,
            String paramKind,
            String path) {
    }

    /**
     * A reference to a named validation rule.
     */
    public record ValidationRule(
            String rule,
            Map<String, Object> args,
            String message,
            String severity) {
    }

    /**
     * Display metadata for any UI node.
     */
    public record Metadata(
            String label,
            String description,
            String notice,
            String icon,
            String subLabel,
            String addLabel,
            String groupName,
            String badge) {
    }
}
