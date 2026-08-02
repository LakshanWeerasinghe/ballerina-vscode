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
import io.ballerina.modelgenerator.commons.trigger.models.TypeRef;
import io.ballerina.modelgenerator.commons.trigger.utils.TypeRefResolver;

import java.util.ArrayList;
import java.util.List;

/**
 * Owns <b>spec §2 {@code listeners[].requiredImports}</b>: "Side-effect-only imports needed at runtime
 * (e.g. {@code import ballerinax/mssql.cdc.driver as _;}) that nothing references by name."
 *
 * <p>Because nothing references them by name, no other part of the pipeline can discover them — a CDC
 * driver registers itself at runtime and appears nowhere in the generated code's type references. Without
 * this component the generated code compiles and then fails at run time.
 *
 * @since 1.7.0
 */
final class RequiredImportResolver {

    /** The alias a side-effect-only import is written with: {@code import org/module as _;}. */
    static final String SIDE_EFFECT_IMPORT_ALIAS = "_";

    private RequiredImportResolver() {
        // Prevent instantiation
    }

    /**
     * One import the generated code needs for its side effect alone.
     *
     * @param module the {@code org/module} to import
     * @param alias  the binding, always {@link #SIDE_EFFECT_IMPORT_ALIAS}
     */
    record ImportDirective(String module, String alias) {
    }

    /**
     * Resolves a listener's declared side-effect imports, in document order.
     *
     * <p>{@code importType} is deliberately <b>not</b> filtered on. Spec §10 lists {@code driver} as the
     * only value today, but an unrecognised kind still needs its import emitted for the generated code to
     * work — so it degrades rather than disappearing.
     *
     * <p>The import path is the <b>module</b>, not the package: the two differ for a submodule, and it is
     * the module that is imported.
     *
     * @param listener the document's listener; may be {@code null}
     * @return one directive per entry carrying usable coordinates; empty when there are none
     */
    static List<ImportDirective> resolve(TriggerMetadataModel.Listener listener) {
        List<ImportDirective> directives = new ArrayList<>();
        if (listener == null || listener.requiredImports() == null) {
            return directives;
        }
        for (TriggerMetadataModel.RequiredImport required : listener.requiredImports()) {
            if (required == null || required.packageInfo() == null) {
                continue;
            }
            String org = required.packageInfo().org();
            String module = TypeRefResolver.moduleOf(new TypeRef(null, required.packageInfo()));
            if (org == null || org.isEmpty() || module == null) {
                continue;
            }
            directives.add(new ImportDirective(org + "/" + module, SIDE_EFFECT_IMPORT_ALIAS));
        }
        return directives;
    }
}
