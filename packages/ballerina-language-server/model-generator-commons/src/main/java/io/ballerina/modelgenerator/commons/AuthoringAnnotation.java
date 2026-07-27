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

package io.ballerina.modelgenerator.commons;

import java.util.List;

/**
 * One entry in a {@code trigger-authoring.json} document's top-level {@code annotations[]} registry —
 * an annotation type referenced elsewhere in the document (from a handler's or parameter's
 * {@code annotations} list, or from a {@link AuthoringServiceType.Rule.RuleMember#annotation()}),
 * defined once here rather than restated at each attachment point.
 *
 * <p>The annotation record's own field names, types, defaults, and enums are never restated — they
 * are introspectable from {@link #type()} itself (the governing DRY principle). This entry carries
 * only what isn't: whether the annotation must be attached at all, where it attaches, and — only when
 * no more precise reference already exists — which service type(s) it belongs to.
 *
 * @param id         referenced from {@code params[].annotations}, {@code handlers.options[].annotations},
 *                  or {@code rules[].members[].annotation}
 * @param type       the annotation type (may be cross-module — see {@link TypeRef})
 * @param attachPoint one of {@code "service"}, {@code "function"}, {@code "parameter"}, {@code "return"}
 * @param appliesTo  ids into {@link TriggerAuthoringModel#serviceTypes()}; included only when nothing
 *                  else already links this annotation to a service type (no
 *                  {@code params[].annotations}, {@code handlers.options[].annotations}, or
 *                  {@code rules[].members[].annotation} reference exists) — such a reference is
 *                  strictly more precise, so {@code appliesTo} would be redundant alongside one.
 *                  {@code null}/absent when a more precise reference exists elsewhere
 * @param presence   {@code "required"} or {@code "optional"} — whether this annotation must be
 *                  attached at all
 * @since 1.10.0
 */
public record AuthoringAnnotation(String id, TypeRef type, String attachPoint, List<String> appliesTo,
                                  String presence) {

    public static final String ATTACH_POINT_SERVICE = "service";
    public static final String ATTACH_POINT_FUNCTION = "function";
    public static final String ATTACH_POINT_PARAMETER = "parameter";
    public static final String ATTACH_POINT_RETURN = "return";

    public static final String PRESENCE_REQUIRED = "required";
    public static final String PRESENCE_OPTIONAL = "optional";
}
