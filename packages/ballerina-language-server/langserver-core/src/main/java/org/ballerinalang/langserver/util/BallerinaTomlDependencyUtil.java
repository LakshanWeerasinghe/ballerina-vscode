/*
 * Copyright (c) 2026, WSO2 LLC. (http://wso2.com) All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.ballerinalang.langserver.util;

import io.ballerina.projects.BallerinaToml;
import io.ballerina.toml.syntax.tree.DocumentNode;
import io.ballerina.toml.syntax.tree.SyntaxKind;
import io.ballerina.toml.syntax.tree.TableNode;
import org.ballerinalang.langserver.commons.toml.common.TomlSyntaxTreeUtil;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;

/**
 * Builds a {@code [[dependency]]} block {@link TextEdit} for {@code Ballerina.toml} -- shared by
 * {@code AddModuleToBallerinaTomlCodeAction} (the user-triggered {@code MODULE_NOT_FOUND} quick-fix) and
 * any flow that needs to add a local-repository dependency proactively (e.g. the schema-driven
 * "add trigger from a local-repository search result" flow), so the insertion-position logic and TOML
 * block format live in exactly one place instead of being reimplemented per caller.
 *
 * @since 1.10.0
 */
public final class BallerinaTomlDependencyUtil {

    private BallerinaTomlDependencyUtil() {
    }

    /**
     * A {@link TextEdit} inserting a {@code [[dependency]]} block (with {@code repository = "local"})
     * right after the {@code [package]} table -- the same position and format
     * {@code AddModuleToBallerinaTomlCodeAction} already uses for its quick-fix.
     */
    public static TextEdit createLocalDependencyEdit(BallerinaToml toml, String org, String name, String version) {
        Position dependencyStart = new Position(getDependencyStartLine(toml), 0);
        String dependency = String.format("[[dependency]]%norg = \"%s\"%nname = \"%s\"%nversion = "
                + "\"%s\"%nrepository = \"local\"%n%n", org, name, version);
        return new TextEdit(new Range(dependencyStart, dependencyStart), dependency);
    }

    private static int getDependencyStartLine(BallerinaToml toml) {
        DocumentNode tomlSyntaxTree = toml.tomlDocument().syntaxTree().rootNode();
        return tomlSyntaxTree.members().stream()
                .filter(member -> member.kind().equals(SyntaxKind.TABLE)
                        && TomlSyntaxTreeUtil.toQualifiedName(((TableNode) member).identifier().value())
                                .equals("package"))
                .findFirst()
                .map(member -> member.lineRange().endLine().line() + 2)
                .orElse(0);
    }
}
