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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>Spec §8 {@code attachPoint} scoping</b>: an annotation must be referenced from the slot its attach
 * point names.
 *
 * <p>Spec §8 files each entry at exactly one point, and the two reference paths are point-specific:
 * {@code handlers.options[].annotations} is defined as "ids into {@code annotations[]},
 * {@code attachPoint: "function"}" and {@code params[].annotations} as the same for
 * {@code "parameter"}. A reference that crosses those wires is not a harmless mislabel — the consuming
 * pipeline drops it (it will not render a {@code service}-pointed annotation onto a handler), so the
 * obligation vanishes, and the Ballerina compiler would reject the attachment anyway:
 * "annotation 'X' is not allowed on service_remote, object_method, function".
 *
 * <p>Reported as ERROR rather than WARN because the document is stating something that cannot be true of
 * any generated program.
 *
 * @since 1.10.0
 */
final class AnnotationScopeCheck implements DocumentCheck {

    @Override
    public String id() {
        return "annotationScope";
    }

    @Override
    public String specSection() {
        return "§8";
    }

    @Override
    public List<Finding> check(TriggerMetadataModel document) {
        List<Finding> findings = new ArrayList<>();
        Map<String, String> pointById = new LinkedHashMap<>();
        for (TriggerMetadataModel.Annotation annotation : DocumentWalk.safe(document.annotations())) {
            if (annotation != null && annotation.id() != null && annotation.attachPoint() != null) {
                pointById.putIfAbsent(annotation.id(), annotation.attachPoint());
            }
        }
        if (pointById.isEmpty()) {
            return findings;
        }

        List<TriggerMetadataModel.ServiceType> serviceTypes = DocumentWalk.safe(document.serviceTypes());
        for (int i = 0; i < serviceTypes.size(); i++) {
            List<TriggerMetadataModel.ServiceType.HandlerOption> options =
                    DocumentWalk.options(serviceTypes.get(i));
            for (int j = 0; j < options.size(); j++) {
                TriggerMetadataModel.ServiceType.HandlerOption option = options.get(j);
                if (option == null) {
                    continue;
                }
                expect(findings, option.annotations(), pointById,
                        TriggerMetadataModel.Annotation.ATTACH_POINT_FUNCTION,
                        DocumentWalk.optionPath(i, j) + ".annotations");
                List<TriggerMetadataModel.ServiceType.Param> params = DocumentWalk.safe(option.params());
                for (int k = 0; k < params.size(); k++) {
                    if (params.get(k) != null) {
                        expect(findings, params.get(k).annotations(), pointById,
                                TriggerMetadataModel.Annotation.ATTACH_POINT_PARAMETER,
                                DocumentWalk.paramPath(i, j, k) + ".annotations");
                    }
                }
            }
        }
        return findings;
    }

    private void expect(List<Finding> findings, List<String> ids, Map<String, String> pointById,
                        String expected, String path) {
        for (String id : DocumentWalk.safe(ids)) {
            String actual = pointById.get(id);
            // A dangling id is AnnotationRefCheck's finding, not this one's — reporting it twice would
            // make one defect look like two.
            if (actual != null && !expected.equals(actual)) {
                findings.add(Finding.error(this, path,
                        "references '" + id + "', which is filed at attachPoint '" + actual
                                + "'; this slot takes '" + expected + "' entries"));
            }
        }
    }
}
