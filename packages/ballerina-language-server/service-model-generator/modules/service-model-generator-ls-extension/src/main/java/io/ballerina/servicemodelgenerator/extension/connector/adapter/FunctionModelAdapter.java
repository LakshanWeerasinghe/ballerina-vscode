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

package io.ballerina.servicemodelgenerator.extension.connector.adapter;

import io.ballerina.servicemodelgenerator.extension.connector.model.LibraryArtifact;
import io.ballerina.servicemodelgenerator.extension.model.Function;
import io.ballerina.servicemodelgenerator.extension.model.FunctionReturnType;
import io.ballerina.servicemodelgenerator.extension.model.MetaData;
import io.ballerina.servicemodelgenerator.extension.model.Parameter;
import io.ballerina.servicemodelgenerator.extension.model.PropertyType;
import io.ballerina.servicemodelgenerator.extension.model.Value;

import java.util.ArrayList;
import java.util.List;

import static io.ballerina.servicemodelgenerator.extension.util.Constants.KIND_RESOURCE;

/**
 * Adapts a connector-shipped {@link LibraryArtifact.FunctionModel} (the clean phase-2 string+boolean
 * shape) into the wire {@link Function} POJO the Integrator already understands ({@code Value}-wrapped
 * name/type/return). This is the connector-model equivalent of
 * {@code ServiceModelUtils.getFunctionFromServiceTypeFunction} (which builds the same wire shape from
 * the SQLite index), so the designer/add-function UIs render identically whether a connector is
 * hardcoded or schema-driven.
 *
 * @since 1.8.0
 */
public final class FunctionModelAdapter {

    private FunctionModelAdapter() {
    }

    /**
     * Converts one connector function model into a wire {@link Function}.
     */
    public static Function toFunction(LibraryArtifact.FunctionModel model) {
        String label = label(model.metadata(), model.name());
        String description = description(model.metadata());

        Function.FunctionBuilder builder = new Function.FunctionBuilder()
                .setMetadata(new MetaData(label, description))
                .kind(model.kind())
                .name(identifierValue(model.name(), label, description))
                .parameters(toParameters(model.parameters()))
                .returnType(toReturnType(model.returnType()))
                .enabled(model.enabled())
                .optional(Boolean.TRUE.equals(model.optional()))
                .editable(model.editable() == null || model.editable());

        // NOTE: do NOT copy `qualifiers` — the generic source emitter derives the `remote`/`resource`
        // keyword from `kind`; copying it here would double it (`remote remote function ...`).
        if (KIND_RESOURCE.equalsIgnoreCase(model.kind()) && model.accessor() != null) {
            builder.accessor(identifierValue(model.accessor(), model.accessor(), description));
        }
        return builder.build();
    }

    private static List<Parameter> toParameters(List<LibraryArtifact.Parameter> parameters) {
        List<Parameter> result = new ArrayList<>();
        if (parameters == null) {
            return result;
        }
        for (LibraryArtifact.Parameter parameter : parameters) {
            result.add(toParameter(parameter));
        }
        return result;
    }

    private static Parameter toParameter(LibraryArtifact.Parameter model) {
        String label = label(model.metadata(), model.name());
        String description = description(model.metadata());

        Value name = identifierValue(model.name(), label, description);
        name.setEditable(model.nameEditable() == null || model.nameEditable());

        Value type = new Value.ValueBuilder()
                .setMetadata(new MetaData("Parameter Type", "The type of the parameter"))
                .value(model.type())
                .types(List.of(PropertyType.types(Value.FieldType.TYPE)))
                .setPlaceholder(model.type())
                .editable(model.typeEditable() == null || model.typeEditable())
                .enabled(true)
                .optional(true)
                .build();

        return new Parameter.Builder()
                .metadata(new MetaData(label, description))
                .kind(model.kind())
                .type(type)
                .name(name)
                .optional(Boolean.TRUE.equals(model.optional()))
                .enabled(model.enabled() == null || model.enabled())
                .editable(model.editable() == null || model.editable())
                .build();
    }

    private static FunctionReturnType toReturnType(LibraryArtifact.ReturnType model) {
        if (model == null) {
            return null;
        }
        // The wire return Value's text IS what the source emitter writes verbatim, so bake the
        // rendered type here (typeTemplate -> error union -> nilable), mirroring the connector schema's
        // separate type/hasError/optional fields into a single Ballerina type expression.
        String rendered = renderReturnType(model);
        Value returnValue = new Value.ValueBuilder()
                .setMetadata(new MetaData("Return Type", "The return type of the function."))
                .value(rendered)
                .types(List.of(PropertyType.types(Value.FieldType.TYPE)))
                .setPlaceholder(rendered)
                .editable(Boolean.TRUE.equals(model.typeEditable()))
                .enabled(model.enabled())
                .optional(Boolean.TRUE.equals(model.optional()))
                .build();
        FunctionReturnType returnType = new FunctionReturnType(returnValue);
        returnType.setHasError(Boolean.TRUE.equals(model.hasError()));
        return returnType;
    }

    private static String renderReturnType(LibraryArtifact.ReturnType model) {
        String type = model.type() == null ? "" : model.type();
        if (model.typeTemplate() != null && model.typeTemplate().contains("{{type}}")) {
            type = model.typeTemplate().replace("{{type}}", type);
        }
        if (Boolean.TRUE.equals(model.hasError()) && !type.contains("error")) {
            type = type.isEmpty() ? "error" : type + "|error";
        }
        if (Boolean.TRUE.equals(model.optional()) && !type.endsWith("?")) {
            type = type + "?";
        }
        return type;
    }

    private static Value identifierValue(String value, String label, String description) {
        return new Value.ValueBuilder()
                .metadata(label, description)
                .value(value)
                .types(List.of(PropertyType.types(Value.FieldType.IDENTIFIER)))
                .setPlaceholder(value)
                .enabled(true)
                .build();
    }

    private static String label(LibraryArtifact.Metadata metadata, String fallback) {
        if (metadata != null && metadata.label() != null && !metadata.label().isBlank()) {
            return metadata.label();
        }
        return fallback;
    }

    private static String description(LibraryArtifact.Metadata metadata) {
        return metadata == null || metadata.description() == null ? "" : metadata.description();
    }
}
