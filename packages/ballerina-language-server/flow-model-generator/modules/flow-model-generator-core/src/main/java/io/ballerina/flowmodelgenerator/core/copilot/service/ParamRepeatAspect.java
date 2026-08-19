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

package io.ballerina.flowmodelgenerator.core.copilot.service;

/**
 * Spec §7 {@code params[].addMode} — the flag that takes a slot out of the fixed signature.
 *
 * <p>Applies to a metadata-described slot only. A concrete service type's parameters are read from the
 * semantic model, where a declared parameter either exists or does not; there is no notion of a slot that
 * repeats.
 *
 * @since 1.7.0
 */
final class ParamRepeatAspect implements ParamAspect {

    @Override
    public String id() {
        return "paramRepeat";
    }

    @Override
    public String specSection() {
        return "§7";
    }

    @Override
    public void contribute(ParamScope scope, ParamDraft draft) {
        if (ParamRepeatResolver.isRepeatable(scope.param())) {
            draft.setRepeatable(true);
        }
    }
}
