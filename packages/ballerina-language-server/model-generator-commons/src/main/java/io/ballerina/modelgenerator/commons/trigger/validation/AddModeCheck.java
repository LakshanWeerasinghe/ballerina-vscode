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

package io.ballerina.modelgenerator.commons.trigger.validation;

import io.ballerina.modelgenerator.commons.trigger.models.TriggerMetadataModel;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>Spec §4 {@code handlers.addMode}</b> and the wildcard it pairs with.
 *
 * <p>Spec §4 ties the two together in one sentence: {@code "many"} is "an open-ended, user-named set …
 * represented as one {@code options} entry named {@code "*"}". Three separate corpus documents break that
 * tie in three different ways, and each currently degrades silently behind a log line:
 *
 * <ul>
 *   <li><b>{@code grpc}</b> — {@code addMode: "many"} with four <i>named</i> options and no wildcard. The
 *       consuming pipeline treats the named options as a fixed vocabulary so their signatures are not
 *       lost, which is the least-bad reading of a document that cannot be read as written.</li>
 *   <li><b>{@code graphql}</b> — three {@code "*"} entries where §4 allows one. The first is taken; the
 *       other two (a mutation and a subscription) are dropped entirely.</li>
 *   <li>a wildcard under {@code addMode: "subset"} — the reverse mismatch, unseen in the corpus.</li>
 * </ul>
 *
 * <p>Both live instances are <b>reported as WARN, not ERROR</b>, because the underlying limitation is the
 * spec's, not the documents': §4 provides no way to say "an open-ended catalog whose handlers take one of
 * N shapes", which is exactly what gRPC (four RPC shapes, proto-derived names) and GraphQL
 * (query/mutation/subscription) are. Erroring would block the build on a defect no document author can
 * fix within the schema.
 *
 * <p><b>What this does NOT justify.</b> An earlier version of this note claimed that erroring "would force
 * a document edit that makes the rendered output worse — gRPC's four shape names would become literal,
 * copyable handler names". That was self-refuting: they are <i>already</i> rendered as literal, copyable
 * handler names, because the renderer reads the surviving {@code options} and ignores {@code addMode}.
 * A real gRPC service names its handlers after the proto's RPCs ({@code SayHello}), so {@code unary} and
 * its three siblings appear in no real program. The consumer therefore states the catalog is author-named
 * (see the {@code authorNamedHandlers} wire key) rather than letting four shape labels read as a fixed
 * vocabulary — which is what {@code salesforce}'s genuinely-fixed {@code onCreate}/{@code onUpdate} look
 * like, and an LLM could not tell the two apart.
 *
 * @since 1.10.0
 */
final class AddModeCheck implements DocumentCheck {

    private static final String WILDCARD = TriggerMetadataModel.ServiceType.HandlerOption.WILDCARD_NAME;
    private static final String MANY = TriggerMetadataModel.ServiceType.Handlers.ADD_MODE_MANY;

    @Override
    public String id() {
        return "addMode";
    }

    @Override
    public String specSection() {
        return "§4";
    }

    @Override
    public List<Finding> check(TriggerMetadataModel document) {
        List<Finding> findings = new ArrayList<>();
        List<TriggerMetadataModel.ServiceType> serviceTypes = DocumentWalk.safe(document.serviceTypes());
        for (int i = 0; i < serviceTypes.size(); i++) {
            TriggerMetadataModel.ServiceType serviceType = serviceTypes.get(i);
            if (serviceType == null || serviceType.handlers() == null) {
                continue;
            }
            TriggerMetadataModel.ServiceType.Handlers handlers = serviceType.handlers();
            String path = DocumentWalk.serviceTypePath(i) + ".handlers";
            List<TriggerMetadataModel.ServiceType.HandlerOption> options = DocumentWalk.options(serviceType);

            long wildcards = options.stream()
                    .filter(option -> option != null && WILDCARD.equals(option.name())).count();
            boolean declaresMany = MANY.equals(handlers.addMode());

            if (handlers.backedByConcreteType()) {
                // §4: "true -> options: [], nothing else to say."
                if (!options.isEmpty()) {
                    findings.add(Finding.error(this, path,
                            "backedByConcreteType is true, so the type's own methods are the handlers; "
                                    + options.size() + " option(s) here can never be read"));
                }
                if (handlers.addMode() != null) {
                    findings.add(Finding.error(this, path + ".addMode",
                            "absent when backedByConcreteType is true"));
                }
                continue;
            }

            if (handlers.addMode() == null) {
                findings.add(Finding.error(this, path + ".addMode",
                        "required when backedByConcreteType is false; options are the only source of truth"));
            }
            if (wildcards > 1) {
                findings.add(Finding.warn(this, path + ".options",
                        wildcards + " \"*\" entries where spec §4 allows one; only the first is rendered, "
                                + "the rest are dropped. The spec has no way to express an open-ended "
                                + "catalog with several handler shapes"));
            }
            if (wildcards > 0 && !declaresMany) {
                findings.add(Finding.error(this, path,
                        "a \"*\" option under addMode '" + handlers.addMode()
                                + "'; spec §4 pairs the wildcard with \"many\""));
            }
            if (wildcards == 0 && declaresMany && !options.isEmpty()) {
                findings.add(Finding.warn(this, path,
                        "addMode \"many\" with " + options.size() + " named option(s) and no \"*\" entry; "
                                + "read as a fixed vocabulary so the signatures are not lost. The spec has "
                                + "no way to express an open-ended catalog with named handler shapes"));
            }
            if (wildcards > 0 && options.size() > wildcards) {
                findings.add(Finding.warn(this, path + ".options",
                        "mixes a \"*\" option with " + (options.size() - wildcards)
                                + " named option(s); spec §4 defines the two as alternative shapes, so the "
                                + "named options are not emitted"));
            }
        }
        return findings;
    }
}
