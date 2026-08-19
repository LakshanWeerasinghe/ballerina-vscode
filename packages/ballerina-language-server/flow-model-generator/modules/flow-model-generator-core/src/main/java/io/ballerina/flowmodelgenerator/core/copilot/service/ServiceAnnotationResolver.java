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

import java.util.List;

/**
 * Owns <b>spec §8 at {@code attachPoint: "service"}</b>: which annotations the generated service must or
 * may carry.
 *
 * <p>Spec §8 gives the registry two access paths — "by id" from a sibling construct's reference, and by
 * {@code appliesTo} for the scopes that have no more precise reference. Service scope is squarely the
 * second: §8's own <b>"Residual gap"</b> says "service-level/return-level annotations have no reference
 * mechanism as precise as {@code params[].annotations}, so they always rely on {@code appliesTo}". So
 * this resolver reads the registry by attach point and filters by {@code appliesTo}, never by id.
 *
 * <h2>Two decisions of ours, not the spec's</h2>
 *
 * <p><b>1. An absent {@code appliesTo} applies to every service type.</b> Spec §8 says to include
 * {@code appliesTo} "only when no other reference already links this annotation" — but a service-level
 * annotation has no other reference available, so an omission here is under-specification rather than a
 * signal, and the spec's Residual gap explicitly leaves the case open. Attaching to every service type is
 * the reading that cannot lose a <i>required</i> annotation: {@code ballerina/smb} declares one with
 * {@code presence: "required"} and no {@code appliesTo}, and dropping it would emit code that does not
 * work. The opposite reading — applies to none — makes a required annotation unreachable, which no
 * document author can have meant. Decided here and nowhere else, per plan §11.5.
 *
 * <p>The choice is unobservable in the current corpus: both documents that omit {@code appliesTo}
 * ({@code rabbitmq}, {@code smb}) declare exactly one service type, so "every" and "the only one"
 * coincide. It is stated and tested anyway, because the next document to omit the key while declaring
 * several service types would otherwise silently inherit whichever reading the code happened to have.
 *
 * <p><b>2. {@code annotations[].type} names the annotation, not its constraint.</b> Verified against the
 * corpus: {@code ballerina/ftp}'s document declares {@code type: {"name": "ServiceConfig"}} while the
 * package declares {@code public annotation ServiceConfiguration ServiceConfig on service;} — so the
 * document's name is the tag written after {@code @}, and the constraining record carries a different
 * name entirely ({@code smb}: {@code SmbServiceConfig}; {@code websub}:
 * {@code SubscriberServiceConfiguration}; {@code ballerinax/cdc}: {@code CdcServiceConfig}). Reading the
 * document's name as a type name would emit an attachment constrained by a record that does not exist.
 * The constraint is therefore introspected from the compiler ({@link TriggerSemanticFacts}), never read
 * from the document — which is also what the governing DRY principle requires of an introspectable fact.
 *
 * @since 1.7.0
 */
final class ServiceAnnotationResolver {

    /** Spec §8 {@code attachPoint}: the single point this resolver owns. */
    static final String ATTACH_POINT_SERVICE = TriggerMetadataModel.Annotation.ATTACH_POINT_SERVICE;

    private ServiceAnnotationResolver() {
        // Prevent instantiation
    }

    /**
     * Resolves the annotations one service type must or may carry.
     *
     * <p>An entry that names no annotation is skipped outright — there is nothing to emit and nothing to
     * report a name for. An entry naming a <i>home-module</i> annotation the resolved package does not
     * declare is dropped and reported: a document authored against a different release must not put an
     * unresolvable name in the prompt, which is the guard {@link ServiceIdentityAspect} applies to a
     * service type for the same reason. A cross-module entry cannot be checked against this module's
     * symbols, so it is trusted rather than dropped.
     *
     * @param registry      the document's annotation registry
     * @param serviceTypeId the {@code serviceTypes[].id} of the service type being built; {@code null}
     *                      when the document names none
     * @param homeModule    spec §1's home module, which decides whether an entry is cross-module
     * @param facts         the resolved package's symbols, for the constraint and the existence check;
     *                      {@code null} skips both, so nothing is dropped for want of a compiled package
     * @return the references to emit and the entries dropped
     */
    static AnnotationScopeResolver.Resolution resolve(AnnotationRegistry registry, String serviceTypeId,
                                                      String homeModule, TriggerSemanticFacts facts) {
        // Selection is this component's own (attach point + `appliesTo`, per the decisions above); the
        // mechanics of turning a selected entry into an AnnotationRef are shared with the other three
        // attach points, so they live in one place rather than four.
        return AnnotationScopeResolver.byAttachPoint(registry, serviceTypeId,
                AnnotationScopeResolver.Scope.SERVICE, homeModule,
                AnnotationScopeResolver.factsOf(facts));
    }

    /**
     * Spec §8 {@code appliesTo}: "{@code serviceTypes[].id} array". An absent or empty list applies to
     * every service type — decision 1 in the class javadoc.
     *
     * @param annotation    the registry entry
     * @param serviceTypeId the service type being built
     * @return whether this entry attaches to that service type
     */
    static boolean appliesTo(TriggerMetadataModel.Annotation annotation, String serviceTypeId) {
        List<String> appliesTo = annotation == null ? null : annotation.appliesTo();
        if (appliesTo == null || appliesTo.isEmpty()) {
            return true;
        }
        return serviceTypeId != null && appliesTo.contains(serviceTypeId);
    }

    /**
     * Spec §8 {@code presence}: {@code "required"} or {@code "optional"}. Anything else — including an
     * absent value — reads as optional, so an unrecognised vocabulary term cannot silently assert that
     * generated code is obliged to carry an annotation.
     *
     * @param annotation the registry entry
     * @return whether the annotation must be attached
     */
    static boolean isRequired(TriggerMetadataModel.Annotation annotation) {
        return annotation != null
                && TriggerMetadataModel.Annotation.PRESENCE_REQUIRED.equals(annotation.presence());
    }
}
