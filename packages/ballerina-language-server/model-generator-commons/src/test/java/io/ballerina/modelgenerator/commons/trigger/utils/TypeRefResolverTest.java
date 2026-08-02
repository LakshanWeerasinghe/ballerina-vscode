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
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Conformance tests for <b>Ballerina Trigger Construct Spec §1 — {@code TypeRef}</b>, written against the
 * spec text rather than the implementation: each test names the spec statement it pins.
 *
 * <p>Spec statements pinned by this class:
 * <ul>
 *   <li>"Every type reference … uses one shape" — one resolver serves listener classes, service types,
 *       annotation types, parameter/return types and binding envelopes alike.</li>
 *   <li>"Cross-module (only when the type isn't from this file's own 'home' module)" — {@code packageInfo}
 *       is present exactly when the reference leaves the home module.</li>
 *   <li>"A bare {@code {"name": ...}} always means same module as this connector's own types."</li>
 *   <li>"<b>Unions</b> are an array of {@code TypeRef}, first element = codegen default."</li>
 *   <li>"<b>Nilable</b> ({@code T?} = {@code T|()}) is an explicit {@code ()} union member, not a separate
 *       flag."</li>
 * </ul>
 *
 * @since 1.10.0
 */
public class TypeRefResolverTest {

    private static final Predicate<String> KAFKA_TYPES =
            Set.of("AnydataConsumerRecord", "BytesConsumerRecord", "Caller", "Error", "Listener")::contains;
    private static final Predicate<String> NONE = name -> false;

    // ---- §1 — a bare TypeRef means "this connector's own module" -----------------------

    @Test
    public void testBareReferenceToADeclaredTypeTakesTheHomeAlias() {
        // A bare reference means same module, so it is written with the home module's own prefix.
        Assert.assertEquals(render("Caller"), "kafka:Caller");
        Assert.assertEquals(render("AnydataConsumerRecord[]"), "kafka:AnydataConsumerRecord[]");
    }

    @Test
    public void testBuiltInAndAnonymousShapesAreNeverPrefixed() {
        // These name no user-defined type, so there is nothing for a module prefix to refer to.
        for (String builtin : List.of("json", "string[][]", "record {}", "error", "anydata",
                "stream<string[], error?>", "()")) {
            Assert.assertEquals(render(builtin), builtin, builtin + " must stay bare");
        }
    }

    @Test
    public void testBareReferenceToAnUndeclaredTypeStaysBare() {
        // The home module does not declare it, so prefixing would assert a symbol that is not there.
        Assert.assertEquals(TypeRefResolver.render(new TypeRef("HubError", null), "websub", NONE),
                "HubError");
    }

    // ---- §1 — cross-module references carry their own module's alias --------------------

    @Test
    public void testCrossModuleReferenceUsesItsOwnModuleAlias() {
        // "Cross-module (only when the type isn't from this file's own 'home' module)".
        TypeRef cdcError = new TypeRef("Error",
                new TypeRef.PackageInfo("ballerinax", "cdc", "cdc", "1.3.2"));
        Assert.assertEquals(TypeRefResolver.render(cdcError, "mssql", NONE), "cdc:Error");
    }

    @Test
    public void testPackageInfoNamingTheHomeModuleIsStillHome() {
        // Spec §1 says packageInfo appears "only when" cross-module; a document that states it anyway
        // for its own module must not thereby become foreign.
        TypeRef ownType = new TypeRef("Caller",
                new TypeRef.PackageInfo("ballerinax", "kafka", "kafka", "4.5.0"));
        Assert.assertEquals(TypeRefResolver.render(ownType, "kafka", NONE), "kafka:Caller");
    }

    @Test
    public void testAliasIsTheModulesLastDotSegment() {
        // Ballerina binds a module's last dot-segment as its default import prefix.
        Assert.assertEquals(TypeRefResolver.moduleAlias("kafka"), "kafka");
        Assert.assertEquals(TypeRefResolver.moduleAlias("trigger.github"), "github");
        Assert.assertEquals(TypeRefResolver.moduleAlias("mssql.cdc.driver"), "driver");
        Assert.assertNull(TypeRefResolver.moduleAlias(null));
    }

    @Test
    public void testSubmoduleHomeUsesItsAliasNotItsFullName() {
        Predicate<String> githubTypes = Set.of("IssuesEvent")::contains;
        Assert.assertEquals(TypeRefResolver.render(new TypeRef("IssuesEvent", null), "trigger.github",
                githubTypes), "github:IssuesEvent");
    }

    @Test
    public void testModuleIsPreferredOverPackageForCrossModuleJudgement() {
        // A submodule shares its parent's package name but is a distinct module, and it is the module
        // that determines both the import path and the alias.
        TypeRef submodule = new TypeRef("Service",
                new TypeRef.PackageInfo("ballerinax", "mssql", "mssql.cdc", "1.19.0"));
        Assert.assertEquals(TypeRefResolver.moduleOf(submodule), "mssql.cdc");
    }

    @Test
    public void testModuleOfFallsBackToPackageNameAndIsNullForBareReferences() {
        Assert.assertEquals(TypeRefResolver.moduleOf(new TypeRef("Service",
                new TypeRef.PackageInfo("ballerinax", "cdc", null, "1.3.2"))), "cdc");
        Assert.assertNull(TypeRefResolver.moduleOf(new TypeRef("Service", null)),
                "A bare reference declares no module of its own");
        Assert.assertNull(TypeRefResolver.moduleOf(null));
    }

    // ---- §1 — unions -------------------------------------------------------------------

    @Test
    public void testFirstElementIsTheCodegenDefault() {
        // "Unions are an array of TypeRef, first element = codegen default."
        TypeRef anydata = new TypeRef("AnydataConsumerRecord[]", null);
        TypeRef bytes = new TypeRef("BytesConsumerRecord[]", null);
        Assert.assertSame(TypeRefResolver.first(List.of(anydata, bytes)), anydata);
        Assert.assertNull(TypeRefResolver.first(List.of()));
        Assert.assertNull(TypeRefResolver.first(null));
    }

    @Test
    public void testNilableIsAnExplicitUnionMemberNotAFlag() {
        // "Nilable (T? = T|()) is an explicit () union member, not a separate flag."
        Assert.assertEquals(TypeRefResolver.renderUnion(
                List.of(new TypeRef("error", null), new TypeRef("()", null)), "kafka", NONE), "error|()");
    }

    @Test
    public void testUnionMembersAreResolvedIndividually() {
        // Each member follows §1's own rule, so a union can mix home, foreign and built-in members.
        List<TypeRef> mixed = List.of(
                new TypeRef("Caller", null),
                new TypeRef("Error", new TypeRef.PackageInfo("ballerinax", "cdc", "cdc", "1.3.2")),
                new TypeRef("()", null));
        Assert.assertEquals(TypeRefResolver.renderUnion(mixed, "kafka", KAFKA_TYPES),
                "kafka:Caller|cdc:Error|()");
    }

    @Test
    public void testEmptyUnionRendersNothing() {
        Assert.assertEquals(TypeRefResolver.renderUnion(List.of(), "kafka", NONE), "");
        Assert.assertEquals(TypeRefResolver.renderUnion(null, "kafka", NONE), "");
    }

    // ---- §1 — degenerate references ----------------------------------------------------

    @Test
    public void testMissingReferenceRendersNothing() {
        Assert.assertEquals(TypeRefResolver.render(null, "kafka", KAFKA_TYPES), "");
        Assert.assertEquals(TypeRefResolver.render(new TypeRef(null, null), "kafka", KAFKA_TYPES), "");
    }

    @Test
    public void testBaseIdentifierIsolatesTheNameableLeadingIdentifier() {
        // The base identifier is what decides whether a bare reference could name a declared type.
        Assert.assertEquals(TypeRefResolver.baseIdentifier("AnydataConsumerRecord[]"),
                "AnydataConsumerRecord");
        Assert.assertEquals(TypeRefResolver.baseIdentifier("Caller"), "Caller");
        Assert.assertEquals(TypeRefResolver.baseIdentifier("record {}"), "record");
        Assert.assertNull(TypeRefResolver.baseIdentifier("()"));
        Assert.assertNull(TypeRefResolver.baseIdentifier(""));
        Assert.assertNull(TypeRefResolver.baseIdentifier(null));
    }

    private static String render(String name) {
        return TypeRefResolver.render(new TypeRef(name, null), "kafka", KAFKA_TYPES);
    }
}
