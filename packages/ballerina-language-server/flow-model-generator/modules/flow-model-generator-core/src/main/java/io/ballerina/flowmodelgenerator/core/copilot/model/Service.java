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

import java.util.List;

/**
 * Represents a service definition.
 *
 * @since 1.7.0
 */
public class Service {
    private String type;
    @SerializedName("name")
    private String name;
    @SerializedName("instructions")
    private String instructions;
    private Listener listener;
    // Spec §1: the org/module a cross-module service type belongs to (ballerinax/cdc). Null for a
    // home-module type. The renderer derives the prefix and the provenance note from it.
    private String serviceTypeModule;
    // Spec §2: side-effect-only imports the listener requires; needed only by code using that listener.
    private List<RequiredImport> requiredImports;
    @SerializedName("methods")
    private List<ServiceRemoteFunction> methods;
    private String testGenerationInstruction;
    @SerializedName("isDeprecated")
    private Boolean deprecated;

    public Service() {
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getInstructions() {
        return instructions;
    }

    public void setInstructions(String instructions) {
        this.instructions = instructions;
    }

    public Listener getListener() {
        return listener;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public String getServiceTypeModule() {
        return serviceTypeModule;
    }

    public void setServiceTypeModule(String serviceTypeModule) {
        this.serviceTypeModule = serviceTypeModule;
    }

    public List<RequiredImport> getRequiredImports() {
        return requiredImports;
    }

    public void setRequiredImports(List<RequiredImport> requiredImports) {
        this.requiredImports = requiredImports;
    }

    public List<ServiceRemoteFunction> getMethods() {
        return methods;
    }

    public void setMethods(List<ServiceRemoteFunction> methods) {
        this.methods = methods;
    }

    public String getTestGenerationInstruction() {
        return testGenerationInstruction;
    }

    public void setTestGenerationInstruction(String testGenerationInstruction) {
        this.testGenerationInstruction = testGenerationInstruction;
    }

    public Boolean isDeprecated() {
        return deprecated;
    }

    public void setDeprecated(Boolean deprecated) {
        this.deprecated = deprecated;
    }
}
