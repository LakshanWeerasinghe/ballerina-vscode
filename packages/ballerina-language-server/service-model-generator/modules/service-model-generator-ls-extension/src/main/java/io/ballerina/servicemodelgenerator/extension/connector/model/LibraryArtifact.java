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

import io.ballerina.servicemodelgenerator.extension.model.ValidationRule;

import java.util.List;
import java.util.Map;

/**
 * Deserialization target for a connector-shipped <b>Service Metadata Model</b>
 * ({@code resources/service-metadata.json}). This mirrors the phase-2 schema
 * ({@code service-metadata.bal}) one-to-one: a single {@code LibraryArtifact} per connector
 * whose {@code serviceTypes} carry the embedded function model.
 *
 * <p>These are plain Gson DTOs (records). Records keep them compact and immutable; Gson 2.10+
 * deserializes records via their canonical constructor and ignores unknown JSON fields, which
 * preserves the schema's forward-compatible {@code additionalProperties: true} contract.
 * Adapters translate these into the wire POJOs ({@code Service}/{@code Function}).
 *
 * @since 1.8.0
 */
public record LibraryArtifact(
        String schemaVersion,
        String name,
        String version,
        ServiceDeclaration serviceDeclaration,
        Map<String, ServiceType> serviceTypes,
        List<ReadOnlyMetadata> readOnlyMetadata,
        Map<String, AnnotationDefinition> annotations) {

    /**
     * How the {@code service} declaration is shaped and how listeners are selected.
     */
    public record ServiceDeclaration(
            String displayName,
            String description,
            boolean optionalTypeDescriptor,
            String typeDescriptorLabel,
            String typeDescriptorDescription,
            String typeDescriptorDefaultValue,
            boolean addDefaultTypeDescriptor,
            boolean optionalAbsoluteResourcePath,
            String absoluteResourcePathLabel,
            String absoluteResourcePathDescription,
            String absoluteResourcePathDefaultValue,
            boolean optionalStringLiteral,
            String stringLiteralLabel,
            String stringLiteralDescription,
            String stringLiteralDefaultValue,
            String listenerKind,
            String kind) {
    }

    /**
     * A service-object type and its trigger endpoints (each a {@link FunctionModel}).
     */
    public record ServiceType(
            String name,
            String description,
            List<FunctionModel> functions) {
    }

    /**
     * A trigger function / resource definition (the embedded function model).
     */
    public record FunctionModel(
            Metadata metadata,
            String name,
            Boolean nameEditable,
            String kind,
            String accessor,
            Boolean accessorEditable,
            List<String> qualifiers,
            String documentation,
            boolean enabled,
            Boolean optional,
            Boolean editable,
            Boolean canAddParameters,
            Boolean deprecated,
            String deprecatedMessage,
            List<ValidationRule> validations,
            List<Annotation> annotations,
            List<Parameter> parameters,
            Map<String, Parameter> schema,
            ReturnType returnType,
            Codedata codedata) {
    }

    /**
     * A function parameter. {@code name}/{@code type} are strings with {@code *Editable} flags.
     */
    public record Parameter(
            Metadata metadata,
            String name,
            Boolean nameEditable,
            String type,
            Boolean typeEditable,
            String typeConstraint,
            String typeTemplate,
            String kind,
            Boolean optional,
            String defaultValue,
            String documentation,
            String importStatements,
            String httpParamType,
            Boolean enabled,
            Boolean editable,
            Boolean advanced,
            Boolean hidden,
            Boolean deprecated,
            String deprecatedMessage,
            List<Annotation> annotations,
            DataBinding dataBinding,
            List<ValidationRule> validations) {
    }

    /**
     * Configurable data binding: binds a raw wrapper payload to a user type.
     */
    public record DataBinding(
            boolean enabled,
            Boolean editable,
            String boundType,
            String wrapperType,
            String payloadField,
            List<ValidationRule> validations) {
    }

    /**
     * The function return type. {@code optional} = nilable (T?); {@code hasError} = error union.
     */
    public record ReturnType(
            Metadata metadata,
            String type,
            Boolean typeEditable,
            String typeConstraint,
            String typeTemplate,
            boolean enabled,
            Boolean optional,
            Boolean hasError,
            String importStatements,
            Boolean editable,
            List<Annotation> annotations,
            List<ValidationRule> validations,
            List<Response> responses,
            Map<String, Response> schema) {
    }

    /**
     * One return response (e.g. an HTTP status-code response) or its addable template.
     */
    public record Response(
            Metadata metadata,
            String statusCode,
            String body,
            String name,
            String type,
            String mediaType,
            String headers,
            Boolean enabled,
            Boolean editable,
            Boolean advanced,
            List<ValidationRule> validations) {
    }

    /**
     * An annotation on a function, a parameter, or the return type.
     */
    public record Annotation(
            Metadata metadata,
            String name,
            String org,
            String module,
            String typeConstraint,
            String target,
            boolean enabled,
            Boolean editable,
            List<AnnotationField> fields,
            List<ValidationRule> validations) {
    }

    /**
     * One configurable field of an annotation's record value.
     */
    public record AnnotationField(
            String name,
            Metadata metadata,
            String type,
            String value,
            Boolean optional,
            Boolean editable,
            Boolean advanced,
            List<ValidationRule> validations) {
    }

    /**
     * A read-only summary chip in the service header.
     */
    public record ReadOnlyMetadata(
            String key,
            String displayName,
            String kind,
            String paramKind,
            String path) {
    }

    /**
     * A service/method annotation the library defines.
     */
    public record AnnotationDefinition(
            List<String> attachmentPoints,
            String displayName,
            String description,
            String typeConstraint,
            List<String> serviceTypes) {
    }

    /**
     * Display metadata.
     */
    public record Metadata(
            String label,
            String description) {
    }

    /**
     * Source-location coordinates for editing/generating the function.
     */
    public record Codedata(
            String orgName,
            String packageName,
            String moduleName,
            LineRange lineRange) {
    }

    /**
     * Source location of an existing function.
     */
    public record LineRange(
            String fileName,
            LinePosition startLine,
            LinePosition endLine) {
    }

    /**
     * A line/offset position.
     */
    public record LinePosition(
            int line,
            int offset) {
    }
}
