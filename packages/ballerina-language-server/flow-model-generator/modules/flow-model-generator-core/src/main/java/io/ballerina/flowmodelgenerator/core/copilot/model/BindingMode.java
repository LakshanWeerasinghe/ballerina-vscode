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

import java.util.List;

/**
 * One way a handler parameter's raw value may be projected into a user-defined type — spec §9's
 * {@code supportedModes[]}.
 *
 * <p>One class covers all three modes rather than a hierarchy, because this type exists only to survive the
 * Gson round-trip between the pipeline and the renderer: {@code mode} is the discriminator both sides switch
 * on, and every other field is populated by exactly one mode. Types are carried as {@link Type} rather than
 * plain strings so the renderer's type closure can reach their definitions through the links.
 *
 * @since 1.7.0
 */
public class BindingMode {
    private String mode;
    // direct, streamable: every legal target type. Never truncated to the first member.
    private List<Type> typeConstraint;
    // direct: the types explicitly disallowed within typeConstraint's category. A negative constraint —
    // derivable from nothing else, so it must never be dropped downstream.
    private List<Type> excludes;
    // includedRecord: the envelope a user record includes with `*Envelope;`.
    private Type includes;
    // includedRecord: the fields such a record may override.
    private List<String> bindableFields;
    // includedRecord: the envelope's remaining fields, derived rather than restated (spec §9).
    private List<String> fixedFields;

    public BindingMode() {
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public List<Type> getTypeConstraint() {
        return typeConstraint;
    }

    public void setTypeConstraint(List<Type> typeConstraint) {
        this.typeConstraint = typeConstraint;
    }

    public List<Type> getExcludes() {
        return excludes;
    }

    public void setExcludes(List<Type> excludes) {
        this.excludes = excludes;
    }

    public Type getIncludes() {
        return includes;
    }

    public void setIncludes(Type includes) {
        this.includes = includes;
    }

    public List<String> getBindableFields() {
        return bindableFields;
    }

    public void setBindableFields(List<String> bindableFields) {
        this.bindableFields = bindableFields;
    }

    public List<String> getFixedFields() {
        return fixedFields;
    }

    public void setFixedFields(List<String> fixedFields) {
        this.fixedFields = fixedFields;
    }
}
