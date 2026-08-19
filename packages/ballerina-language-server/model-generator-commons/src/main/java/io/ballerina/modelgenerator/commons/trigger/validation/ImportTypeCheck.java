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
import io.ballerina.modelgenerator.commons.trigger.models.TypeRef;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>Spec §2 {@code requiredImports}</b>: a side-effect-only import must carry coordinates complete enough
 * to write the import statement.
 *
 * <p>These are the imports "needed at runtime … that nothing references by name", so nothing else in the
 * pipeline can rediscover them: a CDC driver registers itself and appears nowhere in the generated code's
 * type references. An entry with incomplete coordinates is dropped by the resolver, and the generated
 * program then compiles and fails at run time — the worst failure mode in the whole document, because it
 * survives every check a compiler could perform.
 *
 * <p>{@code importType} membership is {@link VocabularyCheck}'s; this owns the coordinates.
 *
 * @since 1.10.0
 */
final class ImportTypeCheck implements DocumentCheck {

    @Override
    public String id() {
        return "importType";
    }

    @Override
    public String specSection() {
        return "§2";
    }

    @Override
    public List<Finding> check(TriggerMetadataModel document) {
        List<Finding> findings = new ArrayList<>();
        List<TriggerMetadataModel.Listener> listeners = DocumentWalk.safe(document.listeners());
        for (int i = 0; i < listeners.size(); i++) {
            TriggerMetadataModel.Listener listener = listeners.get(i);
            if (listener == null) {
                continue;
            }
            List<TriggerMetadataModel.RequiredImport> imports = DocumentWalk.safe(listener.requiredImports());
            for (int j = 0; j < imports.size(); j++) {
                String path = "listeners[" + i + "].requiredImports[" + j + "]";
                TriggerMetadataModel.RequiredImport required = imports.get(j);
                if (required == null) {
                    findings.add(Finding.error(this, path, "a null entry"));
                    continue;
                }
                TypeRef.PackageInfo info = required.packageInfo();
                if (info == null) {
                    findings.add(Finding.error(this, path + ".packageInfo",
                            "required: there is nothing to import without it"));
                    continue;
                }
                if (info.org() == null || info.org().isBlank()) {
                    findings.add(Finding.error(this, path + ".packageInfo.org",
                            "required to write `import <org>/<module> as _;`"));
                }
                boolean hasModule = info.moduleName() != null && !info.moduleName().isBlank();
                boolean hasPackage = info.packageName() != null && !info.packageName().isBlank();
                if (!hasModule && !hasPackage) {
                    findings.add(Finding.error(this, path + ".packageInfo",
                            "needs `moduleName` (or at least `packageName`): the module is what is imported"));
                }
            }
        }
        return findings;
    }
}
