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

package io.ballerina.flowmodelgenerator.core.copilot.model;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a service method (remote or resource function).
 *
 * @since 1.7.0
 */
public class ServiceRemoteFunction {
    private String name;
    private String type;
    private String description;
    private List<Parameter> parameters;
    @SerializedName("return")
    private Return returnInfo;
    // Spec §5 `options[].presence`, tri-state. Boxed on purpose: the pipeline omits the key entirely under
    // `addMode: "many"`, where the document is not saying whether a handler is required, and a primitive
    // would silently turn that into `false` on the JSON round-trip — asserting "required" for a handler
    // nobody said anything about. Null means "not stated"; the renderer emits no marker for it.
    private Boolean optional;
    // Spec §5 resource extras. The accessor is resolved (AccessorPrecedencePolicy); the rest are the legal
    // vocabularies the document declares, which the renderer turns into placeholders and notes.
    private String accessor;
    private List<String> methodValues;
    private Boolean methodRequired;
    private List<String> pathForm;
    private Boolean pathRequired;
    private List<String> fieldNameForm;
    private Boolean fieldNameRequired;
    private String graphqlOperation;
    @SerializedName("isDeprecated")
    private Boolean deprecated;

    public ServiceRemoteFunction() {
        this.parameters = new ArrayList<>();
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<Parameter> getParameters() {
        return parameters;
    }

    public void setParameters(List<Parameter> parameters) {
        this.parameters = parameters;
    }

    public Return getReturnInfo() {
        return returnInfo;
    }

    public void setReturnInfo(Return returnInfo) {
        this.returnInfo = returnInfo;
    }

    public Boolean isOptional() {
        return optional;
    }

    public void setOptional(Boolean optional) {
        this.optional = optional;
    }

    public String getAccessor() {
        return accessor;
    }

    public void setAccessor(String accessor) {
        this.accessor = accessor;
    }

    public List<String> getMethodValues() {
        return methodValues;
    }

    public void setMethodValues(List<String> methodValues) {
        this.methodValues = methodValues;
    }

    public Boolean isMethodRequired() {
        return methodRequired;
    }

    public void setMethodRequired(Boolean methodRequired) {
        this.methodRequired = methodRequired;
    }

    public List<String> getPathForm() {
        return pathForm;
    }

    public void setPathForm(List<String> pathForm) {
        this.pathForm = pathForm;
    }

    public Boolean isPathRequired() {
        return pathRequired;
    }

    public void setPathRequired(Boolean pathRequired) {
        this.pathRequired = pathRequired;
    }

    public List<String> getFieldNameForm() {
        return fieldNameForm;
    }

    public void setFieldNameForm(List<String> fieldNameForm) {
        this.fieldNameForm = fieldNameForm;
    }

    public Boolean isFieldNameRequired() {
        return fieldNameRequired;
    }

    public void setFieldNameRequired(Boolean fieldNameRequired) {
        this.fieldNameRequired = fieldNameRequired;
    }

    public String getGraphqlOperation() {
        return graphqlOperation;
    }

    public void setGraphqlOperation(String graphqlOperation) {
        this.graphqlOperation = graphqlOperation;
    }

    public Boolean isDeprecated() {
        return deprecated;
    }

    public void setDeprecated(Boolean deprecated) {
        this.deprecated = deprecated;
    }
}
