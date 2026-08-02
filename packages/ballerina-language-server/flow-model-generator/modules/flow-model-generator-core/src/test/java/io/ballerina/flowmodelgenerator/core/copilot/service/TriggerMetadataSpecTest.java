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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.ballerina.modelgenerator.commons.trigger.models.TriggerMetadataModel;
import io.ballerina.modelgenerator.commons.trigger.models.TypeRef;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Optional;

/**
 * Conformance tests for the <b>Ballerina Trigger Construct Spec v1</b>, written against the spec text
 * rather than the implementation: each test names the spec statement it pins and asserts the behaviour
 * that statement mandates. A change that breaks a spec guarantee must fail here even if the
 * implementation remains internally consistent.
 *
 * <p>Spec statements pinned by this class:
 * <ul>
 *   <li><b>§1</b> "Cross-module (only when the type isn't from this file's own 'home' module)" and
 *       "'Home' module = whichever module the file's primary construct (its listener, usually)
 *       belongs to" — a foreign service type is written with <i>its own</i> module's alias, and
 *       cross-module-ness is judged per <i>module</i>, not per package.</li>
 *   <li><b>§2</b> {@code requiredImports}: "Side-effect-only imports needed at runtime (e.g.
 *       {@code import ballerinax/mssql.cdc.driver as _;}) that nothing references by name."</li>
 *   <li><b>General rule</b> "a field that would be empty, unused, or fully derivable from other
 *       fields is left out" — an absent optional key must not fabricate output.</li>
 *   <li><b>Array ordering is meaningful</b> — declaration order is preserved.</li>
 * </ul>
 *
 * @since 1.7.0
 */
public class TriggerMetadataSpecTest {

    private static final String HOME_MODULE = "mssql";

    // ---- §1 — home module resolution --------------------------------------------------

    @Test
    public void testHomeModuleDefaultsToTheResolvedPackage() {
        // A listener with a bare TypeRef declares no module of its own, so the library's own package
        // (its default module) is home.
        Assert.assertEquals(TriggerSchemaServiceLoader.homeModule(listener(), "mssql"), "mssql");
        Assert.assertEquals(TriggerSchemaServiceLoader.homeModule(null, "mssql"), "mssql");
    }

    @Test
    public void testHomeModuleComesFromTheListenerWhenItDeclaresOne() {
        // §1: "'Home' module = whichever module the file's primary construct (its listener, usually)
        // belongs to." A listener TypeRef carrying packageInfo therefore *defines* home; the library
        // name must not override it.
        TriggerMetadataModel.Listener declared = new TriggerMetadataModel.Listener(
                new TypeRef("Listener", new TypeRef.PackageInfo("ballerinax", "kafka", "kafka", "4.6.5")),
                List.of("service"), null);
        Assert.assertEquals(TriggerSchemaServiceLoader.homeModule(declared, "somethingelse"), "kafka");
    }

    // ---- §1 — cross-module service types ----------------------------------------------

    @Test
    public void testHomeModuleServiceTypeCarriesNoOwnAlias() {
        // §1: a bare `{"name": ...}` means "same module as this connector's own types", so the
        // renderer must fall back to the listener's alias — no override is emitted.
        Assert.assertEquals(
                TriggerSchemaServiceLoader.serviceTypeModule(serviceType(new TypeRef("Service", null)),
                        HOME_MODULE),
                Optional.empty());
        Assert.assertFalse(TriggerSchemaServiceLoader.isForeignServiceType(
                serviceType(new TypeRef("Service", null)), HOME_MODULE));
    }

    @Test
    public void testSameModulePackageInfoIsStillHome() {
        TypeRef sameModule = new TypeRef("Service",
                new TypeRef.PackageInfo("ballerinax", HOME_MODULE, HOME_MODULE, "1.19.0"));
        Assert.assertEquals(TriggerSchemaServiceLoader.serviceTypeModule(serviceType(sameModule),
                HOME_MODULE), Optional.empty());
    }

    @Test
    public void testCrossModuleServiceTypeUsesItsOwnModuleAlias() {
        // The mssql.cdc case: the service type belongs to ballerinax/cdc, so it must render as
        // `cdc:Service`. Rendering `mssql:Service` would not compile.
        Assert.assertEquals(
                TriggerSchemaServiceLoader.serviceTypeModule(serviceType(cdcService()), HOME_MODULE),
                Optional.of("ballerinax/cdc"));
        Assert.assertTrue(TriggerSchemaServiceLoader.isForeignServiceType(serviceType(cdcService()),
                HOME_MODULE));
    }

    @Test
    public void testSubmoduleOfTheSamePackageIsCrossModule() {
        // §1 draws the line at the MODULE, not the package: `mssql.cdc` shares the `mssql` package
        // name but is a different module, so its type is `cdc:Service`. Judging by package would both
        // render an uncompilable `mssql:Service` and wrongly subject the type to the
        // declared-in-this-module veto.
        TypeRef submodule = new TypeRef("Service",
                new TypeRef.PackageInfo("ballerinax", "mssql", "mssql.cdc", "1.19.0"));
        Assert.assertTrue(TriggerSchemaServiceLoader.isForeignServiceType(serviceType(submodule),
                HOME_MODULE));
        Assert.assertEquals(TriggerSchemaServiceLoader.serviceTypeModule(serviceType(submodule),
                HOME_MODULE), Optional.of("ballerinax/mssql.cdc"));
    }

    @Test
    public void testCrossModuleDottedModuleUsesLastSegmentAsAlias() {
        // Ballerina binds a module's last dot-segment as the default prefix.
        TypeRef foreign = new TypeRef("Config",
                new TypeRef.PackageInfo("ballerinax", "mssql.cdc.driver", "mssql.cdc.driver", "1.0.2"));
        Assert.assertEquals(TriggerSchemaServiceLoader.serviceTypeModule(serviceType(foreign),
                HOME_MODULE), Optional.of("ballerinax/mssql.cdc.driver"));
    }

    @Test
    public void testCrossModuleFallsBackToPackageNameWhenModuleNameAbsent() {
        TypeRef foreign = new TypeRef("Service",
                new TypeRef.PackageInfo("ballerinax", "cdc", null, "1.3.2"));
        Assert.assertEquals(TriggerSchemaServiceLoader.serviceTypeModule(serviceType(foreign),
                HOME_MODULE), Optional.of("ballerinax/cdc"));
    }

    @Test
    public void testUnusableAliasIsNotEmitted() {
        // A blank alias must never reach the renderer: `service :Service` / `service Service` would
        // silently drop the service type, which is worse than not qualifying it at all.
        TypeRef trailingDot = new TypeRef("Service",
                new TypeRef.PackageInfo("ballerinax", "cdc.", "cdc.", "1.3.2"));
        Assert.assertEquals(TriggerSchemaServiceLoader.serviceTypeModule(serviceType(trailingDot),
                HOME_MODULE), Optional.empty());
        // ...but it is still foreign, so it must NOT be subjected to the same-module symbol veto.
        Assert.assertTrue(TriggerSchemaServiceLoader.isForeignServiceType(serviceType(trailingDot),
                HOME_MODULE));
    }

    @Test
    public void testMissingTypeYieldsNoAlias() {
        Assert.assertEquals(TriggerSchemaServiceLoader.serviceTypeModule(null, HOME_MODULE),
                Optional.empty());
        Assert.assertEquals(TriggerSchemaServiceLoader.serviceTypeModule(serviceType(null), HOME_MODULE),
                Optional.empty());
        Assert.assertFalse(TriggerSchemaServiceLoader.isForeignServiceType(null, HOME_MODULE));
    }

    // ---- §2 — listeners[].requiredImports ----------------------------------------------

    @Test
    public void testDriverImportIsEmittedWithSideEffectAlias() {
        // Spec §2's own example: `import ballerinax/mssql.cdc.driver as _;`
        JsonArray imports = TriggerSchemaServiceLoader.requiredImports(listener(
                new TriggerMetadataModel.RequiredImport(
                        TriggerMetadataModel.RequiredImport.IMPORT_TYPE_DRIVER,
                        new TypeRef.PackageInfo("ballerinax", "mssql.cdc.driver", "mssql.cdc.driver",
                                "1.0.2"))));
        Assert.assertEquals(imports.size(), 1);
        JsonObject entry = imports.get(0).getAsJsonObject();
        Assert.assertEquals(entry.get("module").getAsString(), "ballerinax/mssql.cdc.driver");
        Assert.assertEquals(entry.get("alias").getAsString(), "_",
                "A side-effect-only import must be bound to `_`");
    }

    @Test
    public void testRequiredImportUsesTheModulePathNotThePackage() {
        // packageInfo distinguishes package from module; it is the MODULE that is imported. A document
        // spelling the driver as module `mssql.cdc.driver` of package `mssql` must still import the
        // driver, not a second copy of the library's own module.
        JsonArray imports = TriggerSchemaServiceLoader.requiredImports(listener(
                new TriggerMetadataModel.RequiredImport("driver",
                        new TypeRef.PackageInfo("ballerinax", "mssql", "mssql.cdc.driver", "1.0.2"))));
        Assert.assertEquals(imports.get(0).getAsJsonObject().get("module").getAsString(),
                "ballerinax/mssql.cdc.driver");
    }

    @Test
    public void testUnknownImportTypeIsStillEmitted() {
        // §10 lists `driver` as the only value today. An unrecognised kind must NOT be dropped: the
        // import is still required for the generated code to work, so it degrades rather than
        // disappearing.
        JsonArray imports = TriggerSchemaServiceLoader.requiredImports(listener(
                new TriggerMetadataModel.RequiredImport("someFutureKind",
                        new TypeRef.PackageInfo("ballerinax", "future.pkg", "future.pkg", "1.0.0"))));
        Assert.assertEquals(imports.size(), 1, "An unknown importType must not silently drop the import");
        Assert.assertEquals(imports.get(0).getAsJsonObject().get("module").getAsString(),
                "ballerinax/future.pkg");
    }

    @Test
    public void testMultipleRequiredImportsPreserveDocumentOrder() {
        JsonArray imports = TriggerSchemaServiceLoader.requiredImports(listener(
                new TriggerMetadataModel.RequiredImport("driver",
                        new TypeRef.PackageInfo("ballerinax", "a.driver", "a.driver", "1.0.0")),
                new TriggerMetadataModel.RequiredImport("driver",
                        new TypeRef.PackageInfo("ballerinax", "b.driver", "b.driver", "1.0.0"))));
        Assert.assertEquals(imports.size(), 2);
        Assert.assertEquals(imports.get(0).getAsJsonObject().get("module").getAsString(),
                "ballerinax/a.driver");
        Assert.assertEquals(imports.get(1).getAsJsonObject().get("module").getAsString(),
                "ballerinax/b.driver");
    }

    @Test
    public void testAbsentRequiredImportsEmitNothing() {
        Assert.assertTrue(TriggerSchemaServiceLoader.requiredImports(listener()).isEmpty());
        Assert.assertTrue(TriggerSchemaServiceLoader.requiredImports(
                new TriggerMetadataModel.Listener(new TypeRef("Listener", null), List.of(), null))
                .isEmpty());
        Assert.assertTrue(TriggerSchemaServiceLoader.requiredImports(null).isEmpty());
    }

    @Test
    public void testUnusableRequiredImportEntriesAreSkipped() {
        JsonArray imports = TriggerSchemaServiceLoader.requiredImports(listener(
                new TriggerMetadataModel.RequiredImport("driver", null),
                new TriggerMetadataModel.RequiredImport("driver",
                        new TypeRef.PackageInfo(null, "orphan", "orphan", "1.0.0")),
                new TriggerMetadataModel.RequiredImport("driver",
                        new TypeRef.PackageInfo("ballerinax", null, null, "1.0.0"))));
        Assert.assertTrue(imports.isEmpty(), "Entries without org and a module path cannot be rendered");
    }

    // ---- fixtures --------------------------------------------------------------------

    private static TypeRef cdcService() {
        return new TypeRef("Service", new TypeRef.PackageInfo("ballerinax", "cdc", "cdc", "1.3.2"));
    }

    private static TriggerMetadataModel.ServiceType serviceType(TypeRef type) {
        return new TriggerMetadataModel.ServiceType("service", type, false, true, true, null,
                new TriggerMetadataModel.ServiceType.Handlers(false, "subset", List.of()), null);
    }

    private static TriggerMetadataModel.Listener listener(
            TriggerMetadataModel.RequiredImport... requiredImports) {
        return new TriggerMetadataModel.Listener(new TypeRef("Listener", null), List.of("service"),
                requiredImports.length == 0 ? List.of() : List.of(requiredImports));
    }
}
