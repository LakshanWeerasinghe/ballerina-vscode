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

import io.ballerina.compiler.api.symbols.ClassSymbol;
import io.ballerina.modelgenerator.commons.trigger.models.TriggerMetadataModel;

import java.util.function.Predicate;

/**
 * The immutable inputs every service-level component reads. One instance exists per
 * (service type × listener) pair being built, so a component never has to ask "which service type am I
 * working on" — it is handed one.
 *
 * <p>Components receive this read-only and contribute only through their {@link ServiceDraft}, which is
 * what keeps them independently testable: a resolver's behaviour is a pure function of this record.
 *
 * <p>Later spec phases extend this record rather than reaching around it — library introspection facts
 * (§9's derived {@code fixedFields}) become components here when the phases that consume them land.
 * Fields are added only once something reads them, so no component has to ignore a permanently-null slot.
 *
 * @param libraryName    the full library name, e.g. {@code "ballerinax/mssql"}
 * @param org            the organization, e.g. {@code "ballerinax"}
 * @param packageName    the resolved package name, e.g. {@code "mssql"}
 * @param homeModule     spec §1's "home" module — the module the document's listener belongs to, which
 *                       is what every cross-module judgement in the document is relative to
 * @param document       the whole trigger metadata document, for constructs that reference sibling
 *                       registries by id
 * @param annotations    spec §8's top-level {@code annotations[]} registry, built once per library
 *                       because it is shared by every service type and, in later phases, by every
 *                       attach point and the constraint resolver
 * @param serviceType    the service type this scope is building
 * @param listener       the listener paired with {@code serviceType}; never {@code null}
 * @param listenerClass  the listener class resolved from the semantic model; never {@code null}
 * @param facts          the resolved package's symbols, for validating metadata claims
 * @param declaresType   whether the resolved package declares a type of a given name. Carried
 *                       separately from {@code facts} because it is the only capability the metadata
 *                       path needs: a component that merely validates type names then depends on this
 *                       narrow predicate rather than on a whole compiled package, which is what lets
 *                       those components be exercised without one
 * @since 1.7.0
 */
record TriggerScope(
        String libraryName,
        String org,
        String packageName,
        String homeModule,
        TriggerMetadataModel document,
        AnnotationRegistry annotations,
        TriggerMetadataModel.ServiceType serviceType,
        TriggerMetadataModel.Listener listener,
        ClassSymbol listenerClass,
        TriggerSemanticFacts facts,
        Predicate<String> declaresType) {

    /** The service type's declared name, or {@code null} when the document names none. */
    String serviceTypeName() {
        return serviceType == null || serviceType.type() == null ? null : serviceType.type().name();
    }
}
