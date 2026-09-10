/**
 * Copyright (c) 2026, WSO2 LLC. (https://www.wso2.com) All Rights Reserved.
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import * as path from 'path';
import * as https from 'https';
import { Range, TextDocument, Uri, window, workspace, WorkspaceEdit } from 'vscode';
import { ConnectorUpgradeAdvice } from '@wso2/ballerina-core';
import { StateMachine } from '../../stateMachine';
import { notifyCurrentWebview } from '../../RPCLayer';
import { runCommandWithOutput } from '../../utils/runCommand';
import { buildOutputChannel } from '../../utils/logger';

/** {@code CommandConstants.ARG_KEY_DOC_URI} on the LS side -- see PullModuleExecutor.java. */
const ARG_KEY_DOC_URI = 'doc.uri';
const PULL_MODULE_COMMAND = 'PULL_MODULE';
const BALLERINA_TOML = 'Ballerina.toml';

/**
 * Checks the current project for connectors used as a `service ... on <module>:Listener` whose
 * resolved version predates schema-driven trigger support, and prompts for consent to update.
 *
 * Deliberately a single, generic notice regardless of how many connectors are affected or whether any
 * of the fixes are version-boundary-crossing: naming versions/connectors here just exposes plumbing the
 * user can't act on directly, and doesn't change what they need to do (click Update).
 */
export async function checkAndPromptConnectorUpgrades(projectPath: string): Promise<void> {
    if (!projectPath) {
        return;
    }
    let advice: ConnectorUpgradeAdvice[];
    try {
        const response = await StateMachine.langClient().getConnectorUpgradeAdvice({ filePath: projectPath });
        advice = response?.advice ?? [];
    } catch (error) {
        console.error('>>> Error fetching connector upgrade advice', error);
        return;
    }
    if (advice.length === 0) {
        return;
    }

    const update = 'Update';
    const notNow = 'Not Now';
    const selection = await window.showInformationMessage(
        'Using the Service Designer feature requires an update.',
        update,
        notNow
    );
    if (selection === update) {
        await pullAndBumpConnectors(advice, projectPath);
    }
}

/**
 * Updates every connector in {@code advice}, routing each one to whichever mechanism can actually move
 * its version:
 *
 * <ul>
 * <li>Connectors with no explicit {@code Ballerina.toml} pin: a single batched, non-sticky
 * {@code PULL_MODULE} re-resolution, which floats them to a compatible newer version.</li>
 * <li>Connectors pinned via an explicit {@code Ballerina.toml} {@code [[dependency]]} entry: a plain
 * re-resolution can never move these -- an explicit pin is a hard version constraint, not something
 * sticky/non-sticky resolution governs -- so the pin itself has to change. This looks up the latest
 * version Ballerina Central actually publishes, rewrites the pin in {@code Ballerina.toml}, then runs a
 * real {@code bal build} (a fresh process, so it reads the just-written manifest cold, unlike the
 * in-process resolution above) to regenerate {@code Dependencies.toml} and pull the new bala.</li>
 * </ul>
 *
 * Which bucket a connector falls into is detected directly from {@code Ballerina.toml}'s own text,
 * rather than trusted from the caller's {@code explicitlyPinned} flag -- callers that only know a single
 * connector (e.g. the Service Designer's own "Update Now") don't always know whether it's pinned.
 */
export async function pullAndBumpConnectors(
    advice: ConnectorUpgradeAdvice[],
    projectPath: string
): Promise<{ succeeded: ConnectorUpgradeAdvice[]; failed: ConnectorUpgradeAdvice[] }> {
    const tomlPath = path.join(projectPath, BALLERINA_TOML);
    const pinned: { item: ConnectorUpgradeAdvice; pin: DependencyPin }[] = [];
    const unpinned: ConnectorUpgradeAdvice[] = [];

    for (const item of advice) {
        const pin = await findDependencyPin(tomlPath, item.orgName, item.packageName);
        if (pin) {
            pinned.push({ item, pin });
        } else {
            unpinned.push(item);
        }
    }

    const succeeded: ConnectorUpgradeAdvice[] = [];
    const failed: ConnectorUpgradeAdvice[] = [];

    if (unpinned.length > 0) {
        const result = await pullViaResolution(unpinned, projectPath);
        succeeded.push(...result.succeeded);
        failed.push(...result.failed);
    }

    if (pinned.length > 0) {
        const result = await bumpPinnedDependencies(pinned, projectPath);
        succeeded.push(...result.succeeded);
        failed.push(...result.failed);
    }

    return { succeeded, failed };
}

/** Ordinary re-resolution: PULL_MODULE's non-sticky resolve floats unconstrained deps forward. */
async function pullViaResolution(
    advice: ConnectorUpgradeAdvice[],
    projectPath: string
): Promise<{ succeeded: ConnectorUpgradeAdvice[]; failed: ConnectorUpgradeAdvice[] }> {
    const targetFile = advice.find((item) => item.usedInFile)?.usedInFile
        ?? path.join(projectPath, 'main.bal');
    const fileUri = Uri.file(path.isAbsolute(targetFile) ? targetFile : path.join(projectPath, targetFile))
        .toString();

    try {
        await StateMachine.langClient().executeCommand({
            command: PULL_MODULE_COMMAND,
            arguments: [{ key: ARG_KEY_DOC_URI, value: fileUri }]
        });
        notifyCurrentWebview();
        return { succeeded: advice, failed: [] };
    } catch (error) {
        console.error('>>> Connector upgrade pull failed', error);
        window.showErrorMessage(
            `Failed to update connector${advice.length > 1 ? 's' : ''}: ` +
            `${advice.map((item) => item.moduleName).join(', ')}.`
        );
        return { succeeded: [], failed: advice };
    }
}

/**
 * Bumps every pinned connector's {@code Ballerina.toml} entry to Central's latest published version in
 * one batched edit, then runs a single real {@code bal build} to regenerate {@code Dependencies.toml}
 * for all of them at once.
 */
async function bumpPinnedDependencies(
    pinned: { item: ConnectorUpgradeAdvice; pin: DependencyPin }[],
    projectPath: string
): Promise<{ succeeded: ConnectorUpgradeAdvice[]; failed: ConnectorUpgradeAdvice[] }> {
    const succeeded: ConnectorUpgradeAdvice[] = [];
    const failed: ConnectorUpgradeAdvice[] = [];
    const edit = new WorkspaceEdit();
    const toBuild: ConnectorUpgradeAdvice[] = [];

    for (const { item, pin } of pinned) {
        const latestVersion = await getLatestPackageVersion(item.orgName, item.packageName);
        if (!latestVersion) {
            console.error(`>>> Failed to look up the latest version of ${item.moduleName} on Central`);
            failed.push(item);
            continue;
        }
        edit.replace(pin.document.uri, pin.versionRange, latestVersion);
        toBuild.push(item);
    }

    if (toBuild.length === 0) {
        reportFailures(failed);
        return { succeeded, failed };
    }

    const applied = await workspace.applyEdit(edit);
    if (!applied) {
        console.error('>>> Failed to apply Ballerina.toml edits for connector upgrade');
        failed.push(...toBuild);
        reportFailures(failed);
        return { succeeded, failed };
    }
    // All edited documents are the same Ballerina.toml; saving any one of the pin's documents saves it.
    await pinned[0].pin.document.save();

    const rebuilt = await rebuildProject(projectPath);
    if (!rebuilt) {
        failed.push(...toBuild);
        reportFailures(failed);
        return { succeeded, failed };
    }

    await StateMachine.langClient().resolveMissingDependencies({
        documentIdentifier: { uri: Uri.file(projectPath).toString() }
    });
    notifyCurrentWebview();
    succeeded.push(...toBuild);
    return { succeeded, failed };
}

function reportFailures(failed: ConnectorUpgradeAdvice[]): void {
    if (failed.length === 0) {
        return;
    }
    window.showErrorMessage(
        `Failed to update connector${failed.length > 1 ? 's' : ''}: ` +
        `${failed.map((item) => item.moduleName).join(', ')}.`
    );
}

/** Runs a real `bal build` (a fresh process, unlike PULL_MODULE's in-process resolve) to pick up an
 * on-disk Ballerina.toml edit and regenerate Dependencies.toml from it. */
async function rebuildProject(projectPath: string): Promise<boolean> {
    let buildCommand = 'bal build';
    const config = workspace.getConfiguration('ballerina');
    const ballerinaHome = config.get<string>('home');
    if (ballerinaHome) {
        buildCommand = path.join(ballerinaHome, 'bin', buildCommand);
    }
    const result = await runCommandWithOutput(buildCommand, projectPath, buildOutputChannel);
    return result.success;
}

interface DependencyPin {
    document: TextDocument;
    versionRange: Range;
}

/**
 * Locates the {@code version} field of the {@code [[dependency]]} table matching
 * {@code orgName}/{@code packageName} in {@code Ballerina.toml}, or {@code undefined} if there is no
 * such {@code Ballerina.toml} or no matching pinned entry.
 */
async function findDependencyPin(
    tomlPath: string, orgName: string, packageName: string
): Promise<DependencyPin | undefined> {
    let document: TextDocument;
    try {
        document = await workspace.openTextDocument(Uri.file(tomlPath));
    } catch {
        return undefined;
    }
    const text = document.getText();

    // [[dependency]] tables run up to the next top-level table header (or EOF).
    const tableRegex = /\[\[dependency\]\][^[]*/g;
    let match: RegExpExecArray | null;
    while ((match = tableRegex.exec(text)) !== null) {
        const block = match[0];
        const orgMatch = block.match(/^\s*org\s*=\s*"([^"]+)"/m);
        const nameMatch = block.match(/^\s*name\s*=\s*"([^"]+)"/m);
        if (orgMatch?.[1] !== orgName || nameMatch?.[1] !== packageName) {
            continue;
        }
        const versionMatch = block.match(/^\s*version\s*=\s*"([^"]+)"/m);
        if (!versionMatch || versionMatch.index === undefined) {
            return undefined;
        }
        const valueStart = match.index + versionMatch.index + versionMatch[0].indexOf(versionMatch[1]);
        const valueEnd = valueStart + versionMatch[1].length;
        return {
            document,
            versionRange: new Range(document.positionAt(valueStart), document.positionAt(valueEnd))
        };
    }
    return undefined;
}

/** The latest version Ballerina Central publishes for `orgName/packageName`, or `undefined` on failure. */
function getLatestPackageVersion(orgName: string, packageName: string): Promise<string | undefined> {
    return new Promise((resolve) => {
        const req = https.request({
            hostname: 'api.central.ballerina.io',
            path: `/2.0/registry/packages/${encodeURIComponent(orgName)}/${encodeURIComponent(packageName)}`,
            method: 'GET',
            headers: { 'Content-Type': 'application/json' }
        }, (res) => {
            let body = '';
            res.on('data', (chunk) => { body += chunk; });
            res.on('end', () => {
                if (res.statusCode !== 200) {
                    resolve(undefined);
                    return;
                }
                try {
                    const versions: string[] = JSON.parse(body);
                    if (!Array.isArray(versions) || versions.length === 0) {
                        resolve(undefined);
                        return;
                    }
                    resolve(versions.reduce((latest, current) =>
                        compareSemver(current, latest) > 0 ? current : latest));
                } catch {
                    resolve(undefined);
                }
            });
        });
        req.on('error', () => resolve(undefined));
        req.end();
    });
}

/** Numeric (not lexical) semver comparison, so "1.10.0" correctly outranks "1.9.0". */
function compareSemver(a: string, b: string): number {
    const partsOf = (v: string) => v.split('.').map((part) => Number.parseInt(part, 10) || 0);
    const pa = partsOf(a);
    const pb = partsOf(b);
    for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
        const diff = (pa[i] ?? 0) - (pb[i] ?? 0);
        if (diff !== 0) {
            return diff;
        }
    }
    return 0;
}
