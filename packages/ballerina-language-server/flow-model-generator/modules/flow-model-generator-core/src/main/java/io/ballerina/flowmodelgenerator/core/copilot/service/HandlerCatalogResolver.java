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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

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

    private static final Logger LOGGER = Logger.getLogger(HandlerCatalogResolver.class.getName());

    private HandlerCatalogResolver() {
        // Prevent instantiation
    }

    /**
     * Where a service type's handlers come from.
     *
     * <p>Sealed so a new source cannot be added without every consumer being forced to handle it: the
     * catalog decides whether a service body is built from the semantic model, from a fixed vocabulary, or
     * from an open-ended template, and a silently unhandled variant would emit an empty body.
     */
    sealed interface HandlerCatalog permits HandlerCatalog.Concrete, HandlerCatalog.Options,
            HandlerCatalog.Many, HandlerCatalog.None {

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
         * @param options     the documented handler vocabulary, in document order
         * @param authorNamed whether the document declared this catalog {@code addMode: "many"} — i.e.
         *                    open-ended and user-named — while supplying <i>named</i> options instead of
         *                    the single {@code "*"} entry spec §4 prescribes. The names are then shapes,
         *                    not handler names: {@code grpc}'s {@code unary}/{@code serverStreaming} appear
         *                    in no real program, because a gRPC handler is named after its proto RPC.
         *                    Carried so a consumer can say so; without it these are indistinguishable from
         *                    a genuinely fixed vocabulary like {@code salesforce}'s {@code onCreate}
         */
        record Options(List<TriggerMetadataModel.ServiceType.HandlerOption> options, boolean authorNamed)
                implements HandlerCatalog {
        }

        /**
         * An open-ended catalog: spec §4's {@code addMode: "many"}, "user-named (HTTP resource methods,
         * GraphQL fields, MCP tools); represented as one {@code options} entry named {@code "*"}".
         *
         * <p>Distinct from {@link Options} because the shape it describes is not a handler but the
         * <i>rule for writing</i> handlers: the author names each one, so there is no fixed signature to
         * emit and consequently no entry in {@code methods}.
         *
         * @param template the wildcard option, which states everything about such a handler except its
         *                 name
         */
        record Many(TriggerMetadataModel.ServiceType.HandlerOption template) implements HandlerCatalog {
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
            return openOrFixed(serviceType.handlers(), typeName);
        }
        Optional<ObjectTypeSymbol> objectType = facts.serviceObjectType(typeName);
        if (objectType.isEmpty()) {
            // Emitting a method-less service here would be a phantom: the document claims the type
            // declares its own handlers, but the resolved package has no such type to read them from.
            return new HandlerCatalog.None("no introspectable service object type");
        }
        return new HandlerCatalog.Concrete(facts.declaredMethods(objectType.get()));
    }

    /**
     * Splits a marker type's vocabulary into the open-ended and the fixed shape.
     *
     * <p><b>The wildcard, not {@code addMode}, is the discriminator.</b> Spec §4 ties the two together —
     * {@code "many"} is "represented as one options entry named {@code \"*\"}" — but two corpus documents
     * break that tie, and reading the pair rather than the flag is what lets both degrade sensibly instead
     * of rendering nothing:
     *
     * <ul>
     *   <li><b>{@code grpc}</b> declares {@code addMode: "many"} with four <i>named</i> options
     *       ({@code unary}, {@code serverStreaming}, {@code clientStreaming}, {@code bidiStreaming}) and no
     *       wildcard. Those four are real, fully-specified signatures; treating the type as open-ended
     *       because of the flag would discard all of them and emit a template instead.</li>
     *   <li><b>{@code graphql}</b> declares three {@code "*"} entries under one {@code options} list where
     *       spec §4 allows one. The first is taken and the rest reported — dropping the service type over a
     *       document defect would lose more than it protects.</li>
     * </ul>
     *
     * <p>Both are document defects belonging to the validator phase; this component's job is to tolerate
     * them visibly, never to fix them silently.
     */
    private static HandlerCatalog openOrFixed(TriggerMetadataModel.ServiceType.Handlers handlers,
                                              String typeName) {
        List<TriggerMetadataModel.ServiceType.HandlerOption> options = handlers.options();
        boolean declaresMany =
                TriggerMetadataModel.ServiceType.Handlers.ADD_MODE_MANY.equals(handlers.addMode());
        List<TriggerMetadataModel.ServiceType.HandlerOption> wildcards = wildcardsOf(options);

        if (wildcards.isEmpty()) {
            if (declaresMany && options != null && !options.isEmpty()) {
                LOGGER.warning("Service type '" + typeName + "' declares addMode: \"many\" with "
                        + options.size() + " named option(s) and no \"*\" entry (spec §4 represents an"
                        + " open-ended catalog as one option named \"*\"); treating the named options as a"
                        + " fixed vocabulary so their signatures are not lost");
            }
            return new HandlerCatalog.Options(options, declaresMany && options != null
                    && !options.isEmpty());
        }

        if (wildcards.size() > 1) {
            LOGGER.warning("Service type '" + typeName + "' declares " + wildcards.size()
                    + " \"*\" options where spec §4 allows one; taking the first");
        }
        if (options.size() > wildcards.size()) {
            LOGGER.warning("Service type '" + typeName + "' mixes a \"*\" option with "
                    + (options.size() - wildcards.size()) + " named option(s); spec §4 defines the two as"
                    + " alternative shapes, so the named options are not emitted");
        }
        if (!declaresMany) {
            LOGGER.warning("Service type '" + typeName + "' declares a \"*\" option under addMode: "
                    + handlers.addMode() + " (spec §4 pairs the wildcard with \"many\")");
        }
        return new HandlerCatalog.Many(wildcards.get(0));
    }

    private static List<TriggerMetadataModel.ServiceType.HandlerOption> wildcardsOf(
            List<TriggerMetadataModel.ServiceType.HandlerOption> options) {
        List<TriggerMetadataModel.ServiceType.HandlerOption> wildcards = new ArrayList<>();
        if (options == null) {
            return wildcards;
        }
        for (TriggerMetadataModel.ServiceType.HandlerOption option : options) {
            if (option != null && TriggerMetadataModel.ServiceType.HandlerOption.WILDCARD_NAME
                    .equals(option.name())) {
                wildcards.add(option);
            }
        }
        return wildcards;
    }
}
