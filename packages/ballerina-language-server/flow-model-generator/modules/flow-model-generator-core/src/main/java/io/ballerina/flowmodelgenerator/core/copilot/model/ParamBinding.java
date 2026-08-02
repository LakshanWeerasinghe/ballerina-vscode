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
 * The data-binding rule a handler parameter's {@code dataBinding} id names — spec §9.
 *
 * @since 1.7.0
 */
public class ParamBinding {
    // Spec §9 `cardinality: "array"`: a mode's type is the array *element* type, not the whole parameter
    // type. Boxed and emitted only when true, so a renderer can tell "the document says batch" from "the
    // document says nothing" — and must not pluralize a parameter whose signature is already an array.
    private Boolean array;
    private List<BindingMode> modes;

    public ParamBinding() {
    }

    public Boolean isArray() {
        return array;
    }

    public void setArray(Boolean array) {
        this.array = array;
    }

    public List<BindingMode> getModes() {
        return modes;
    }

    public void setModes(List<BindingMode> modes) {
        this.modes = modes;
    }
}
