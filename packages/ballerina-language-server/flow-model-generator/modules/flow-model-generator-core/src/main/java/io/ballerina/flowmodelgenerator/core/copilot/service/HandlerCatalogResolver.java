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

import io.ballerina.compiler.api.symbols.ObjectTypeSymbol;
import io.ballerina.modelgenerator.commons.trigger.models.TriggerMetadataModel;

import java.util.List;
import java.util.Optional;

/**
 * Owns <b>spec §4 {@code handlers}</b>: which of the two sources a service type's handlers come from.
 *
 * <p>Spec §4 states the rule directly — {@code backedByConcreteType} "{@code true} → {@code options: []},
 * nothing else to say. {@code false} → {@code options} is the only source of truth." This is the one
 * component that knows how many handlers exist, and therefore the one that drives the handler and
 * parameter tiers.
 *
 * @since 1.7.0
 */
final class HandlerCatalogResolver {

    private HandlerCatalogResolver() {
        // Prevent instantiation
    }

    /**
     * Where a service type's handlers come from.
     *
     * <p>{@code addMode: "many"} does not yet get its own variant: today a wildcard {@code "*"} option is
     * skipped by name during iteration, so a many-shaped document lands in {@link Options} and contributes
     * nothing. Turning that into a rendered template is a later phase; introducing the variant here before
     * anything renders it would change no output while adding an unread branch.
     */
    sealed interface HandlerCatalog permits HandlerCatalog.Concrete, HandlerCatalog.Options,
            HandlerCatalog.None {

        /**
         * The service type declares its own methods; the semantic model is authoritative.
         *
         * @param methods the type's declared remote/resource methods, in declaration order
         */
        record Concrete(List<TriggerSemanticFacts.DeclaredMethod> methods) implements HandlerCatalog {
        }

        /**
         * A marker type: the metadata document's {@code options} are the only source of truth.
         *
         * @param options the documented handler vocabulary, in document order
         */
        record Options(List<TriggerMetadataModel.ServiceType.HandlerOption> options)
                implements HandlerCatalog {
        }

        /**
         * No usable catalog; the reason is attributable to the document.
         *
         * @param reason why no catalog could be resolved, in terms a document author can act on
         */
        record None(String reason) implements HandlerCatalog {
        }
    }

    /**
     * Whether a service type's handlers are its own declared methods.
     *
     * <p>A missing {@code handlers} block is treated as concrete: with nothing to enumerate, the only
     * possible source of truth is the type itself.
     */
    static boolean isConcrete(TriggerMetadataModel.ServiceType serviceType) {
        TriggerMetadataModel.ServiceType.Handlers handlers = serviceType.handlers();
        return serviceType.concrete() || handlers == null || handlers.backedByConcreteType();
    }

    /**
     * Resolves the catalog for one service type.
     *
     * @param serviceType the service type
     * @param typeName    its declared type name
     * @param facts       the resolved package's symbols
     * @return the catalog, or {@link HandlerCatalog.None} when a concrete type cannot be introspected
     */
    static HandlerCatalog resolve(TriggerMetadataModel.ServiceType serviceType, String typeName,
                                  TriggerSemanticFacts facts) {
        if (!isConcrete(serviceType)) {
            return new HandlerCatalog.Options(serviceType.handlers().options());
        }
        Optional<ObjectTypeSymbol> objectType = facts.serviceObjectType(typeName);
        if (objectType.isEmpty()) {
            // Emitting a method-less service here would be a phantom: the document claims the type
            // declares its own handlers, but the resolved package has no such type to read them from.
            return new HandlerCatalog.None("no introspectable service object type");
        }
        return new HandlerCatalog.Concrete(facts.declaredMethods(objectType.get()));
    }
}
