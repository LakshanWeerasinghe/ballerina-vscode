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

/**
 * Spec §6 {@code rules[].members[]} — one alternative in a {@link ServiceConstraint}.
 *
 * <p>Exactly one of the three shapes is populated per member, matching the document: an annotation field
 * ({@code annotation} + {@code field}), this service type's identifier ({@code part: "identifier"}), or one of
 * its own handlers ({@code handler}). A flat POJO with nullable slots is used rather than a sealed hierarchy
 * because this type exists only to survive the Gson round-trip; the typed, exhaustively-switched form lives in
 * the resolver, where the semantics are decided.
 *
 * @since 1.7.0
 */
public class ConstraintMember {

    private String annotation;
    private String field;
    private String part;
    private String handler;
    private Boolean preferred;

    public ConstraintMember() {
    }

    public String getAnnotation() {
        return annotation;
    }

    public void setAnnotation(String annotation) {
        this.annotation = annotation;
    }

    public String getField() {
        return field;
    }

    public void setField(String field) {
        this.field = field;
    }

    public String getPart() {
        return part;
    }

    public void setPart(String part) {
        this.part = part;
    }

    public String getHandler() {
        return handler;
    }

    public void setHandler(String handler) {
        this.handler = handler;
    }

    public Boolean isPreferred() {
        return preferred;
    }

    public void setPreferred(Boolean preferred) {
        this.preferred = preferred;
    }
}
