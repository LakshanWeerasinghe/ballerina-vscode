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
    /**
     * Spec §8: the annotations this service type must or may carry, scoped by the document's
     * {@code appliesTo}.
     *
     * <p><b>The key is {@code annotations} here but {@code annotationRefs} at handler, parameter and return
     * scope, and that asymmetry is deliberate.</b> A {@code Service} has no competing field, so this one
     * shipped first under the shorter name; a {@code Parameter} already has an {@code annotations} field
     * holding the semantic model's real attachments, which are the opposite kind of thing — a fact about the
     * library rather than a requirement on generated code. Renaming this to match would churn the renderer
     * and P3's fixtures for no change in output, so it is left as-is and recorded as a cleanup. Do not
     * "harmonise" the two by pointing them at one field.
     */
    private List<ServiceAnnotationRef> annotations;
    // Spec §3: the identifier/base-path slot between `service` and `on new`. Null when the connector does
    // not consult it, which is what an absent `identifier` key means.
    private ServiceIdentifier identifier;
    // Spec §6: the exclusivity constraints this service type declares (`oneOf` / `atMostOne`).
    private List<ServiceConstraint> constraints;
    /**
     * Spec §3's array cardinality: the document declares more than one service type, so this one is
     * "individually optional" rather than mandatory. Boxed and emitted only when true.
     *
     * <p>Not a synonym for "mutually exclusive". Spec §3 leaves the choice "to whatever supplied the
     * generation intent" and imposes no "exactly one of N" rule — {@code websocket} declares two service
     * types where the first's handler <i>returns</i> the second, so both are routinely declared together.
     */
    private Boolean alternatives;
    /**
     * Spec §3 {@code multipleListenersAllowed: false} — this service type attaches to exactly one
     * listener. Present only when the connector forbids it; a permissive value states nothing, because the
     * one-service-one-listener shape a generator writes by default is legal either way.
     */
    private Boolean singleListenerOnly;
    /**
     * Spec §3 {@code multipleServicesPerListenerAllowed: false} — one listener hosts at most one service
     * of this type. Same presence rule as {@link #singleListenerOnly}.
     */
    private Boolean singleServicePerListenerOnly;
    /**
     * Spec §4 {@code addMode: "many"} — the shape every handler of this service type takes, for a catalog
     * whose handler names are the author's to choose.
     *
     * <p>Typed as {@link ServiceRemoteFunction} because it <i>is</i> one in every respect but its name:
     * same kind, parameters, return and annotation obligations. It is held apart from {@link #methods}
     * because it is not writable as-is — a consumer must render it as guidance, never as a signature.
     */
    private ServiceRemoteFunction handlerTemplate;
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

    public List<ServiceAnnotationRef> getAnnotations() {
        return annotations;
    }

    public void setAnnotations(List<ServiceAnnotationRef> annotations) {
        this.annotations = annotations;
    }

    public ServiceIdentifier getIdentifier() {
        return identifier;
    }

    public void setIdentifier(ServiceIdentifier identifier) {
        this.identifier = identifier;
    }

    public List<ServiceConstraint> getConstraints() {
        return constraints;
    }

    public void setConstraints(List<ServiceConstraint> constraints) {
        this.constraints = constraints;
    }

    public Boolean isAlternatives() {
        return alternatives;
    }

    public void setAlternatives(Boolean alternatives) {
        this.alternatives = alternatives;
    }

    public Boolean isSingleListenerOnly() {
        return singleListenerOnly;
    }

    public void setSingleListenerOnly(Boolean singleListenerOnly) {
        this.singleListenerOnly = singleListenerOnly;
    }

    public Boolean isSingleServicePerListenerOnly() {
        return singleServicePerListenerOnly;
    }

    public void setSingleServicePerListenerOnly(Boolean singleServicePerListenerOnly) {
        this.singleServicePerListenerOnly = singleServicePerListenerOnly;
    }

    public ServiceRemoteFunction getHandlerTemplate() {
        return handlerTemplate;
    }

    public void setHandlerTemplate(ServiceRemoteFunction handlerTemplate) {
        this.handlerTemplate = handlerTemplate;
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
