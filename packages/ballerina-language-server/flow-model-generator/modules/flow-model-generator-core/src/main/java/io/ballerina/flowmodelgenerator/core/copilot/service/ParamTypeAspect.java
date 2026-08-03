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
import io.ballerina.modelgenerator.commons.trigger.models.TriggerMetadataModel;
import io.ballerina.modelgenerator.commons.trigger.utils.TypeRefResolver;

/**
 * Spec §7 {@code params[]} — one parameter slot's name, type and optionality.
 *
 * <p>A declared parameter supplies all three itself. A metadata slot supplies its type and presence, but
 * its name only sometimes: §7 calls {@code name} an "optional domain-meaningful name", so where the
 * document omits it a deterministic one is generated — the only place in the pipeline where a name is
 * synthesized rather than read.
 *
 * @since 1.7.0
 */
final class ParamTypeAspect implements ParamAspect {

    @Override
    public String id() {
        return "paramType";
    }

    @Override
    public String specSection() {
        return "§7";
    }

    @Override
    public void contribute(ParamScope scope, ParamDraft draft) {
        TriggerScope service = scope.handler().service();
        String packageName = service.packageName();

        if (scope.declared() != null) {
            TriggerSemanticFacts.DeclaredParam declared = scope.declared();
            draft.setName(declared.name());
            draft.setDescription(declared.description());
            draft.setType(TypeResolver.resolveTypeWithLinks(
                    declared.typeSignature() != null ? declared.typeSignature() : "", packageName));
            draft.setOptional(declared.optional());
            return;
        }

        TriggerMetadataModel.ServiceType.Param param = scope.param();
        // Spec §7: a repeatable slot's occurrences are "each independently named" by the author, so there
        // is no single name for it and none is synthesized. An authored name is still a real fact and is
        // kept. Reading ParamRepeatResolver's predicate rather than the raw key leaves §7's `addMode` with
        // exactly one owner.
        String name = ParamRepeatResolver.isRepeatable(param)
                ? param.name()
                : ParamTypeResolver.resolveName(param, scope.position(),
                        TypeRefResolver.moduleAlias(packageName), scope.siblingNames());
        if (name != null) {
            scope.siblingNames().add(name);
        }

        ParamTypeResolver.ParamType type = ParamTypeResolver.resolveType(param, packageName,
                service.declaresType());

        draft.setName(name);
        // No description: a marker-type handler's parameters have no documented source.
        draft.setType(TypeResolver.resolveTypeWithLinks(type.signature(), packageName));
        draft.setOptional(ParamTypeResolver.isOptional(param));

        // Spec §7's other legal types, as link-carrying pairs so the type closure reaches their
        // definitions. Never joined with `|` — see ParamTypeResolver.ParamType.
        JsonArray alternatives = new JsonArray();
        for (String alternative : type.alternatives()) {
            alternatives.add(TypeResolver.resolveTypeWithLinks(alternative, packageName));
        }
        draft.setAlternatives(alternatives);

        for (String undeclared : type.dropped()) {
            draft.drop(id(), specSection(), undeclared,
                    "an alternative type the resolved package version does not declare");
        }
    }
}
