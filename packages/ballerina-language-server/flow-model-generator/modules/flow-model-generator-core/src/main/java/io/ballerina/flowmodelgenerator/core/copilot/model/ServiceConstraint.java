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
 * Spec §6 {@code rules[]} — one exclusivity constraint a service type declares.
 *
 * <p>{@code kind} carries the spec's own vocabulary, {@code "oneOf"} (exactly one member) or
 * {@code "atMostOne"} (zero or one). The distinction is load-bearing and must not be collapsed: only
 * {@code oneOf} obliges the generated service to pick an alternative at all.
 *
 * @since 1.7.0
 */
public class ServiceConstraint {

    private String id;
    private String kind;
    private List<ConstraintMember> members;

    public ServiceConstraint() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public List<ConstraintMember> getMembers() {
        return members;
    }

    public void setMembers(List<ConstraintMember> members) {
        this.members = members;
    }
}
