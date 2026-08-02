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
import io.ballerina.modelgenerator.commons.trigger.utils.TypeRefResolver;

import java.util.Optional;
import java.util.function.Predicate;

/**
 * Owns <b>spec §1's home-module rule</b> and <b>§3's {@code serviceTypes[].type}</b>: who a service type
 * belongs to, and therefore what alias it is written with.
 *
 * <p>Spec §1 defines "home" as "whichever module the file's primary construct (its listener, usually)
 * belongs to", and says a {@code packageInfo} is present "only when the type isn't from this file's own
 * home module". Everything here follows from those two sentences:
 * <ul>
 *   <li>A <b>home-module</b> service type carries no alias of its own — the renderer prefixes it with
 *       the listener's alias, exactly as before.</li>
 *   <li>A <b>cross-module</b> service type is written with its <i>own</i> module's alias. This is the
 *       mssql case: its service type belongs to {@code ballerinax/cdc}, so it must render as
 *       {@code cdc:Service}; {@code mssql:Service} would not compile.</li>
 * </ul>
 *
 * <p>Cross-module-ness is judged at <b>module</b> granularity, not package: {@code mssql.cdc} shares the
 * {@code mssql} package name but is a distinct module. Judging by package would both render an
 * uncompilable {@code mssql:Service} and wrongly subject a foreign type to the same-module symbol veto.
 *
 * @since 1.7.0
 */
final class ServiceIdentityResolver {

    private ServiceIdentityResolver() {
        // Prevent instantiation
    }

    /**
     * The resolved identity of one service type.
     *
     * @param typeName          the declared type name; {@code null} when the document names none
     * @param serviceTypeModule the foreign {@code org/module}, or {@code null} for a home-module type
     * @param foreign           whether the type belongs to a module other than home
     * @param declaredByPackage whether the resolved package declares this type; always {@code true} for
     *                          a foreign type, which cannot be checked against this module's symbols
     */
    record ServiceIdentity(String typeName, String serviceTypeModule, boolean foreign,
                           boolean declaredByPackage) {
    }

    /**
     * Spec §1's home module: taken from the listener's own {@code packageInfo} when it declares one,
     * otherwise the resolved library's default module, which is its package name.
     *
     * @param listener    the document's listener; may be {@code null}
     * @param packageName the resolved library's package name — the default home
     * @return the home module name
     */
    static String homeModule(TriggerMetadataModel.Listener listener, String packageName) {
        String declared = TypeRefResolver.moduleOf(listener == null ? null : listener.type());
        return declared != null ? declared : packageName;
    }

    /**
     * Whether a service type belongs to a module other than the document's home module.
     *
     * <p>Kept separate from {@link #serviceTypeModule} because the two answer different questions: this
     * one also decides whether the type may be validated against the resolved package's symbols. A
     * foreign type cannot be, so it is trusted rather than vetoed.
     */
    static boolean isForeign(TriggerMetadataModel.ServiceType serviceType, String homeModule) {
        String module = serviceType == null ? null : TypeRefResolver.moduleOf(serviceType.type());
        return module != null && !module.equals(homeModule);
    }

    /**
     * The {@code org/module} a cross-module service type belongs to, e.g. {@code ballerinax/cdc}.
     *
     * <p>The <i>module</i> is emitted rather than the alias it renders with: the module is the fact the
     * document states, and deriving a prefix from it is a syntax decision belonging to the renderer —
     * which already derives module prefixes the same way for every other foreign type reference.
     * Emitting the full coordinate also lets the renderer name the owning package in the provenance note
     * it attaches.
     *
     * @return the foreign {@code org/module}, or empty for a home-module type or an unusable coordinate
     */
    static Optional<String> serviceTypeModule(TriggerMetadataModel.ServiceType serviceType,
                                              String homeModule) {
        // Spec §1's cross-module rule has one implementation, in commons: a service type's ownership is
        // the same question as any other type reference's, and answering it twice is how the two would
        // drift.
        return TypeRefResolver.foreignModulePath(
                serviceType == null ? null : serviceType.type(), homeModule);
    }

    /**
     * Resolves the full identity, including whether the resolved package backs a home-module type.
     *
     * @param serviceType  the service type
     * @param homeModule   the document's home module
     * @param declaresType whether the resolved package declares a type of the given name
     */
    static ServiceIdentity resolve(TriggerMetadataModel.ServiceType serviceType, String homeModule,
                                   Predicate<String> declaresType) {
        String typeName = serviceType == null || serviceType.type() == null
                ? null : serviceType.type().name();
        boolean foreign = isForeign(serviceType, homeModule);
        // A cross-module type is not declared by *this* module by definition, so the symbol check is
        // neither possible nor meaningful for it.
        boolean declared = foreign || (typeName != null && declaresType.test(typeName));
        return new ServiceIdentity(typeName, serviceTypeModule(serviceType, homeModule).orElse(null),
                foreign, declared);
    }
}
