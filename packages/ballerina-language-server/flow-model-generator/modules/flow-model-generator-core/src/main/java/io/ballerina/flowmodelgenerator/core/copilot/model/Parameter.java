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
 * Represents a function or method parameter.
 *
 * @since 1.7.0
 */
public class Parameter {
    private String name;
    private String description;
    private Type type;
    private Boolean optional;
    @SerializedName("default")
    private String defaultValue;
    /**
     * Annotations the compiler reports as <b>already present</b> on this parameter — a fact about the
     * library, rendered verbatim with its real value.
     *
     * <p>Deliberately not the same field as {@link #annotationRefs}, and deliberately not renamed to match
     * it: the two are opposite in kind, and the asymmetry in their names is the thing that keeps them from
     * being "harmonised" into one. See {@link Service#getAnnotations()} for the same distinction at service
     * scope, where the key is historical.
     */
    private List<AnnotationAttachment> annotations;
    // Spec §7 — the slot's other legal types. Carried as Type (not String) so the renderer's type closure
    // can reach their definitions through the links; never joined into a union.
    private List<Type> alternatives;
    /**
     * Spec §8 at {@code attachPoint: "parameter"} — annotations code written against this library
     * <b>must or may attach</b> here. A requirement on code that does not exist yet, which is why it cannot
     * share {@link #annotations}: that field holds attachments the library already carries.
     */
    private List<ServiceAnnotationRef> annotationRefs;
    // Spec §9 — how this slot's raw value may be projected into a user-defined type.
    private ParamBinding binding;

    public Parameter() {
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Type getType() {
        return type;
    }

    public void setType(Type type) {
        this.type = type;
    }

    public Boolean isOptional() {
        return optional;
    }

    public void setOptional(Boolean optional) {
        this.optional = optional;
    }

    public String getDefaultValue() {
        return defaultValue;
    }

    public void setDefaultValue(String defaultValue) {
        this.defaultValue = defaultValue;
    }

    public List<AnnotationAttachment> getAnnotations() {
        return annotations;
    }

    public void setAnnotations(List<AnnotationAttachment> annotations) {
        this.annotations = annotations;
    }

    public List<Type> getAlternatives() {
        return alternatives;
    }

    public void setAlternatives(List<Type> alternatives) {
        this.alternatives = alternatives;
    }

    public List<ServiceAnnotationRef> getAnnotationRefs() {
        return annotationRefs;
    }

    public void setAnnotationRefs(List<ServiceAnnotationRef> annotationRefs) {
        this.annotationRefs = annotationRefs;
    }

    public ParamBinding getBinding() {
        return binding;
    }

    public void setBinding(ParamBinding binding) {
        this.binding = binding;
    }
}
