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

package io.ballerina.modelgenerator.commons.trigger.utils;

import io.ballerina.modelgenerator.commons.trigger.models.TypeRef;

import java.util.List;
import java.util.function.Predicate;

/**
 * The single implementation of <b>Ballerina Trigger Construct Spec §1 — {@code TypeRef}</b>: turning a
 * document's type reference into the module-prefixed signature text a consumer can render.
 *
 * <p>Spec §1 states the two rules this class encodes:
 * <ul>
 *   <li>"Cross-module (only when the type isn't from this file's own 'home' module)" — a {@link TypeRef}
 *       carrying {@code packageInfo} for a <i>different</i> module is written with that module's own
 *       alias; a bare {@code {"name": ...}} "always means same module as this connector's own types."</li>
 *   <li>"<b>Unions</b> are an array of {@code TypeRef}, first element = codegen default" — hence
 *       {@link #first(List)}, which every caller needing a single representative type must use rather
 *       than indexing the list itself.</li>
 * </ul>
 *
 * <p>Ballerina binds a module's <i>last dot-segment</i> as its default import prefix, so
 * {@code mssql.cdc.driver} aliases to {@code driver} and {@code trigger.github} to {@code github} —
 * see {@link #moduleAlias(String)}.
 *
 * <p><b>Why this lives in commons.</b> Spec §1 was implemented twice and divergently — once for the
 * Copilot catalog and once for the service-model trigger synthesizer. This is the extraction point for
 * that duplication; the Copilot consumer routes through it today, and the synthesizer can adopt it as a
 * separate, separately-tested change (the two implementations differ, so switching both at once could
 * not be behaviour-preserving for either).
 *
 * @since 1.10.0
 */
public final class TypeRefResolver {

    private TypeRefResolver() {
        // Prevent instantiation
    }

    /**
     * The import prefix Ballerina binds for a module: its last dot-segment.
     * {@code "trigger.github"} → {@code "github"}, {@code "kafka"} → {@code "kafka"}.
     *
     * @param moduleName the module name; may be {@code null}
     * @return the alias, or the input unchanged when it carries no dot
     */
    public static String moduleAlias(String moduleName) {
        if (moduleName != null && moduleName.contains(".")) {
            return moduleName.substring(moduleName.lastIndexOf('.') + 1);
        }
        return moduleName;
    }

    /**
     * The leading identifier of a type name, i.e. the part that could name a declared type:
     * {@code "AnydataConsumerRecord[]"} → {@code "AnydataConsumerRecord"}, {@code "record {}"} →
     * {@code "record"}. Returns {@code null} when the name starts with no identifier character at all
     * ({@code "()"}, {@code ""}), which is how built-in and anonymous shapes are told apart from
     * user-defined type references.
     *
     * @param typeName the type name; may be {@code null}
     * @return the leading identifier, or {@code null}
     */
    public static String baseIdentifier(String typeName) {
        if (typeName == null || typeName.isEmpty()) {
            return null;
        }
        int end = 0;
        while (end < typeName.length()
                && (Character.isLetterOrDigit(typeName.charAt(end)) || typeName.charAt(end) == '_')) {
            end++;
        }
        return end == 0 ? null : typeName.substring(0, end);
    }

    /**
     * Spec §1: "the first element is the codegen default." The single representative member of a
     * scalar-or-union slot.
     *
     * @param refs the slot's type members; may be {@code null} or empty
     * @return the first member, or {@code null}
     */
    public static TypeRef first(List<TypeRef> refs) {
        return refs == null || refs.isEmpty() ? null : refs.get(0);
    }

    /**
     * The module a {@link TypeRef} belongs to, or {@code null} for a bare reference (spec §1: a bare
     * {@code {"name": ...}} means same module as the connector's own types).
     *
     * <p>Prefers {@code moduleName} over {@code packageName}: a submodule such as {@code mssql.cdc}
     * shares its parent's package name but is a distinct module, and it is the <i>module</i> that
     * determines both the import path and the alias.
     *
     * @param ref the reference; may be {@code null}
     * @return the module name, or {@code null} for a bare or coordinate-less reference
     */
    public static String moduleOf(TypeRef ref) {
        if (ref == null || ref.packageInfo() == null) {
            return null;
        }
        TypeRef.PackageInfo info = ref.packageInfo();
        if (info.moduleName() != null && !info.moduleName().isEmpty()) {
            return info.moduleName();
        }
        return info.packageName() == null || info.packageName().isEmpty() ? null : info.packageName();
    }

    /**
     * Renders a {@link TypeRef} as module-prefixed signature text.
     *
     * <p>A cross-module reference gets its own module's alias ({@code cdc:Error}); a reference to a type
     * the home module declares gets the home alias ({@code kafka:Caller}), which a downstream link
     * resolver strips back off while recording the link; built-ins and anonymous shapes
     * ({@code json}, {@code record {}}, {@code ()}) stay bare.
     *
     * @param ref                  the reference; may be {@code null}
     * @param homePackageName      the resolved library's package name, whose alias prefixes home types
     * @param declaredByHomeModule whether the home module declares a type of the given base name
     * @return the signature text, or {@code ""} for a missing reference
     */
    public static String render(TypeRef ref, String homePackageName, Predicate<String> declaredByHomeModule) {
        if (ref == null || ref.name() == null) {
            return "";
        }
        String name = ref.name();
        if (ref.packageInfo() != null) {
            String refPackage = ref.packageInfo().packageName();
            String refModule = ref.packageInfo().moduleName() != null
                    ? ref.packageInfo().moduleName() : refPackage;
            if (refPackage != null && !refPackage.equals(homePackageName)) {
                return moduleAlias(refModule) + ":" + name;
            }
            return moduleAlias(homePackageName) + ":" + name;
        }
        String base = baseIdentifier(name);
        if (base != null && declaredByHomeModule.test(base)) {
            return moduleAlias(homePackageName) + ":" + name;
        }
        return name;
    }

    /**
     * Joins a union's members with {@code |} into one signature.
     *
     * <p>Correct for a slot whose value genuinely <i>is</i> the union — notably a handler's
     * {@code returns}, where spec §1's nilable rule ({@code T?} written as an explicit {@code ()}
     * member) makes {@code error|()} the intended text. It is <b>not</b> correct for a
     * {@code params[].type} union, which enumerates alternatives legal for the slot rather than a
     * union-typed parameter.
     *
     * @param refs                 the union members; may be {@code null} or empty
     * @param homePackageName      the resolved library's package name
     * @param declaredByHomeModule whether the home module declares a type of the given base name
     * @return the joined signature, or {@code ""} when there are no members
     */
    public static String renderUnion(List<TypeRef> refs, String homePackageName,
                                     Predicate<String> declaredByHomeModule) {
        if (refs == null || refs.isEmpty()) {
            return "";
        }
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < refs.size(); i++) {
            if (i > 0) {
                joined.append("|");
            }
            joined.append(render(refs.get(i), homePackageName, declaredByHomeModule));
        }
        return joined.toString();
    }
}
