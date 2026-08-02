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
 * Spec §1/§3 — the service entry's identity: its type name and, when cross-module, the module that owns
 * it.
 *
 * <p>Runs first among the service aspects, because it is also the component that can veto the entry
 * outright: a home-module service type the resolved package does not declare would render a service on a
 * type that does not exist in the version actually resolved.
 *
 * @since 1.7.0
 */
final class ServiceIdentityAspect implements ServiceAspect {

    /** The wire contract's discriminator; every metadata-derived entry is a fixed-shape service. */
    private static final String KIND_FIXED = "fixed";

    @Override
    public String id() {
        return "serviceIdentity";
    }

    @Override
    public String specSection() {
        return "§3";
    }

    @Override
    public void contribute(TriggerScope scope, ServiceDraft draft) {
        ServiceIdentityResolver.ServiceIdentity identity = ServiceIdentityResolver.resolve(
                scope.serviceType(), scope.homeModule(), scope.declaresType());

        if (identity.typeName() == null) {
            draft.veto(id(), specSection(), scope.libraryName(),
                    "the document names no type for this service type entry");
            return;
        }
        if (!identity.declaredByPackage()) {
            draft.veto(id(), specSection(), identity.typeName(),
                    "not declared by the resolved package version");
            return;
        }

        draft.setKind(KIND_FIXED);
        // For a cross-module type this is the bare type name; a downstream enricher's lookup against
        // this module's symbols is then a deliberate no-op unless the module declares the name itself.
        draft.setName(identity.typeName());
        draft.setServiceTypeModule(identity.serviceTypeModule());
    }
}
