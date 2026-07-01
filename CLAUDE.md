# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

> Also read **`AGENTS.md`** — it captures non-obvious agent pitfalls not repeated here.

## What this repo is

`ballerina-vscode` is a **Rush.js monorepo** containing:
- **`packages/ballerina-extension`** — the VS Code extension (TypeScript + webpack, entry point `src/extension.ts`)
- **`packages/ballerina-language-server`** — Gradle-built Java LSP server
- **`packages/ballerina-grammar`** — TextMate syntax grammar (YAML source, JSON output)
- **~18 diagram/webview packages** — React-based webviews (`bi-diagram`, `sequence-diagram`, `data-mapper`, `type-editor`, etc.)
- **`submodules/wso2-vscode-extensions/`** — shared `@wso2/*` libraries via git submodule (separate repo, separate git history)

## Prerequisites

- **Node.js** ≥20, <23 (LTS 22.x recommended)
- **Java JDK 21** on `JAVA_HOME` (required for the language server Gradle build)
- **GitHub PAT** with `read:packages` scope in `~/.gradle/gradle.properties`:
  ```
  packageUser=<github-username>
  packagePAT=<token>
  ```
- **VS Code** ≥1.100.0

Rush and pnpm are managed automatically — do not install them separately.

## Setup

```bash
git clone --recurse-submodules https://github.com/wso2/ballerina-vscode.git
cd ballerina-vscode
rush update
```

## Build commands

```bash
rush update                                        # install/sync all deps (run after any package.json change)
rush build                                         # full build: LS + all TS packages + VSIX
rush build --to ballerina-language-server          # LS only
rush build --to @wso2/ballerina-visualizer         # one package + its TS deps (skips LS and extension)
```

Without Java 21 + GitHub PAT, use the scoped form to skip the LS:
```bash
rush build --to @wso2/ballerina-visualizer
```

To reproduce CI failures exactly:
```bash
node common/scripts/install-run-rush.js build --to ballerina --verbose
```

## Extension-specific commands

Run from `packages/ballerina-extension/`:
```bash
pnpm run watch-ballerina      # webpack --watch (for active development)
pnpm run compile              # webpack production build
pnpm run test                 # compile + run Mocha tests
pnpm run test:ai              # compile + run AI-specific tests
pnpm run e2e-test             # Playwright E2E tests
pnpm run lint                 # tslint --fix on src/
```

## Debugging

Press **F5** in VS Code with the repo open — the root `.vscode/launch.json` has the "Ballerina Extension" debug config ready. Don't create custom launch configs; the existing ones cover extension, tests, and LS attach.

## Testing

- **Extension (Mocha)**: `pnpm run test` from `packages/ballerina-extension/`
- **Diagram packages (Jest)**: `pnpm test` from within a specific diagram package (e.g. `packages/bi-diagram/`)
- **E2E (Playwright)**: `pnpm run e2e-test` from `packages/ballerina-extension/`

## Architecture

### Extension entry point and feature modules

`packages/ballerina-extension/src/extension.ts` activates the extension and wires up feature modules:

- **`src/core/`** — language client setup, preferences, messaging bus
- **`src/features/`** — self-contained feature modules: `ai/`, `bi/`, `debugger/`, `editor-support/`, `testing/`, `tracing/`, `notebook/`, `performance/`, etc.
- **`src/views/`** — webview panel management (visualizer, AI panel, notebook, etc.)
- **`src/RPCLayer/`** — JSON-RPC bridge between the extension host and webview React apps

### Language server integration

The extension loads a Java LSP server from `packages/ballerina-language-server/`. The build step copies the jar to `packages/ballerina-extension/ls/`. The `copy-ls.js` script controls which jar is used:

- `BALLERINA_LS_SOURCE=download` — always download from GitHub releases
- `BALLERINA_LS_TAG=<tag>` — pin a specific release
- Default: prefer local build output, fall back to download

### Webview packages (diagram packages)

Each diagram package under `packages/` is a standalone React app. The pattern is:
1. TypeScript compiles to `lib/`
2. webpack (where applicable) bundles to `build/`
3. Post-build scripts copy JS artifacts into `packages/ballerina-extension/resources/jslibs/`

The extension loads these at runtime via webview panels managed in `src/views/`.

### Submodule (`submodules/wso2-vscode-extensions/`)

Shared `@wso2/*` packages (font, ui-toolkit, common-libs, etc.) live here. They are workspace projects Rush treats as first-class but they belong to a separate git repo. Edits there require a separate push to `wso2/vscode-extensions` and a submodule pointer update in this repo.

### RPC communication

Webviews communicate with the extension host via the `@wso2/ballerina-rpc-client` package. New capabilities require changes to both the RPC client (message types) and the extension-side handler in `src/RPCLayer/`.

## Dependency management

- Add deps by editing `package.json` then running `rush update`
- Cross-package references use `workspace:*` protocol (e.g. `"@wso2/ballerina-core": "workspace:*"`)
- Commit both `package.json` **and** `common/config/rush/pnpm-lock.yaml`
- All version pins must be exact (no `^` or `~`) — pre-commit hooks enforce this

## Generated artifacts — do not edit directly

`lib/`, `build/`, `dist/`, `out/`, and `packages/ballerina-extension/grammar/ballerina-grammar/` are build outputs. Edit source under `src/` or the canonical source package (`packages/ballerina-grammar/syntaxes/` for grammar changes).

## Key reference files

| Question | Where to look |
|---|---|
| What gets built and in what order | `rush.json` + each package's `scripts` block |
| What lands in the VSIX | `packages/ballerina-extension/.vscodeignore` |
| LS jar selection logic | `packages/ballerina-extension/scripts/copy-ls.js` |
| CI workflow logic | `.github/workflows/*.yml` + `.github/actions/*/action.yml` |
| pnpm/node/rush versions | `rush.json` (top) and `common/config/rush/pnpm-config.json` |
| Recent changes | `git log --oneline -30` |
