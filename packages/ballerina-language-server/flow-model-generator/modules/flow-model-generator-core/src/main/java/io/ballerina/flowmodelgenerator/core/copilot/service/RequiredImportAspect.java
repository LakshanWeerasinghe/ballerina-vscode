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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;

/**
 * Spec §2 {@code listeners[].requiredImports} — the side-effect-only imports the generated code needs.
 *
 * <p>Carried on the <b>service</b> rather than hoisted to the library: the spec declares these on the
 * listener, so only code that actually uses that listener needs them.
 *
 * @since 1.7.0
 */
final class RequiredImportAspect implements ServiceAspect {

    @Override
    public String id() {
        return "requiredImports";
    }

    @Override
    public String specSection() {
        return "§2";
    }

    @Override
    public void contribute(TriggerScope scope, ServiceDraft draft) {
        List<RequiredImportResolver.ImportDirective> directives =
                RequiredImportResolver.resolve(scope.listener());
        if (directives.isEmpty()) {
            return;
        }
        JsonArray imports = new JsonArray();
        for (RequiredImportResolver.ImportDirective directive : directives) {
            JsonObject entry = new JsonObject();
            entry.addProperty("module", directive.module());
            entry.addProperty("alias", directive.alias());
            imports.add(entry);
        }
        draft.setRequiredImports(imports);
    }
}
