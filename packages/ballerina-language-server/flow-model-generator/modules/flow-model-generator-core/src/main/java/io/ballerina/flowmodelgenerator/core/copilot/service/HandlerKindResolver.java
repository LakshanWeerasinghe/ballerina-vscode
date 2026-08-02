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

import io.ballerina.modelgenerator.commons.trigger.models.TriggerMetadataModel;

/**
 * Owns <b>spec §5 {@code options[].kind}</b>: whether a handler is a remote method or a resource method.
 *
 * <p>Trivial and alone on purpose. This is the one field the renderer's keyword choice depends on —
 * {@code remote function} versus {@code resource function} — and getting it wrong emits source that does
 * not compile, which is exactly what happens today for {@code websocket}'s {@code get}. Keeping it
 * uncoupled from a protocol's resource extras means a change to HTTP's {@code method}/{@code path} or
 * GraphQL's {@code accessor}/{@code fieldName} can never perturb the keyword.
 *
 * <p>An unrecognised or absent {@code kind} resolves to {@link Kind#REMOTE} rather than throwing: spec
 * §10 lists {@code remote} and {@code resource}, and remote is the shape every non-resource handler in the
 * corpus takes, so an unknown value degrades to the form that at least parses.
 *
 * @since 1.7.0
 */
final class HandlerKindResolver {

    /** The two handler shapes spec §10's {@code kind} vocabulary admits. */
    enum Kind {
        REMOTE(TriggerMetadataModel.ServiceType.HandlerOption.KIND_REMOTE),
        RESOURCE(TriggerMetadataModel.ServiceType.HandlerOption.KIND_RESOURCE);

        private final String wireValue;

        Kind(String wireValue) {
            this.wireValue = wireValue;
        }

        /** The value written to the wire, and the discriminator the renderer dispatches on. */
        String wireValue() {
            return wireValue;
        }

        /** Whether this handler renders as a resource method, which needs an accessor and a path. */
        boolean isResource() {
            return this == RESOURCE;
        }
    }

    private HandlerKindResolver() {
        // Prevent instantiation
    }

    /**
     * Resolves a document's {@code kind} string.
     *
     * @param kind the declared kind; may be {@code null}
     * @return the resolved kind, defaulting to {@link Kind#REMOTE}
     */
    static Kind resolve(String kind) {
        return TriggerMetadataModel.ServiceType.HandlerOption.KIND_RESOURCE.equals(kind)
                ? Kind.RESOURCE : Kind.REMOTE;
    }

    /**
     * Resolves the kind of a concrete service type's declared method, whose provenance is the semantic
     * model rather than the document.
     *
     * <p>{@link TriggerSemanticFacts#declaredMethods} already reports {@code "remote"}/{@code "resource"}
     * from the method's own qualifiers, so this reads the same vocabulary from a different source — which
     * is why it belongs here rather than being re-derived at the aspect.
     *
     * @param declaredKind the kind reported for a declared method; may be {@code null}
     * @return the resolved kind, defaulting to {@link Kind#REMOTE}
     */
    static Kind resolveDeclared(String declaredKind) {
        return resolve(declaredKind);
    }
}
