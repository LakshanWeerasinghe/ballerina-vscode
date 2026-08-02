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
import io.ballerina.compiler.api.symbols.ClassSymbol;
import io.ballerina.modelgenerator.commons.trigger.utils.TypeRefResolver;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Spec §2 {@code listeners[].type} — the listener a service attaches to, with its init parameters.
 *
 * <p>Spec §2 is explicit that "No listener init fields are ever modeled" in the document, so every
 * parameter here comes from the semantic model: names and types from the {@code init} signature,
 * descriptions from its doc comment, and declared defaults recovered from the syntax tree.
 *
 * <p>The built object is <b>cached and shared</b> by identity across every service entry of a library,
 * exactly as before. That sharing is load-bearing rather than incidental: a downstream enricher rewrites
 * {@code listener.name} in place for packages shipping a non-canonical listener class, and handing each
 * service its own copy would change how many times that rewrite is applied.
 *
 * @since 1.7.0
 */
final class ListenerAspect implements ServiceAspect {

    private static final String DEFAULT_LISTENER_NAME = "Listener";

    private final Map<ClassSymbol, JsonObject> built = new IdentityHashMap<>();

    @Override
    public String id() {
        return "listener";
    }

    @Override
    public String specSection() {
        return "§2";
    }

    @Override
    public void contribute(TriggerScope scope, ServiceDraft draft) {
        draft.setListener(built.computeIfAbsent(scope.listenerClass(), listenerClass -> build(scope)));
    }

    private static JsonObject build(TriggerScope scope) {
        ClassSymbol listenerClass = scope.listenerClass();
        String packageName = scope.packageName();
        String className = listenerClass.getName().orElse(DEFAULT_LISTENER_NAME);

        JsonObject listenerObj = new JsonObject();
        listenerObj.addProperty("name", TypeRefResolver.moduleAlias(packageName) + ":" + className);

        JsonArray parameters = new JsonArray();
        for (TriggerSemanticFacts.InitParam param : scope.facts().listenerInitParams(listenerClass)) {
            JsonObject paramObj = new JsonObject();
            paramObj.addProperty("name", param.name());
            paramObj.addProperty("description", param.description() != null ? param.description() : "");
            paramObj.add("type", TypeResolver.resolveTypeWithLinks(
                    param.typeSignature() != null ? param.typeSignature() : "", packageName));
            if (param.optional()) {
                paramObj.addProperty("optional", true);
            }
            if (param.defaultValue() != null && !param.defaultValue().isEmpty()) {
                paramObj.addProperty("default", param.defaultValue());
            }
            parameters.add(paramObj);
        }
        listenerObj.add("parameters", parameters);
        return listenerObj;
    }
}
