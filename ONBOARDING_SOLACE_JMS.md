# Onboarding `ballerinax/solace.jms` — handoff notes from the `solace` migration

This is a handoff doc for a **fresh agent/session** picking up onboarding of the
`ballerinax/solace.jms` trigger UI. It captures what was learned the hard way
while onboarding `ballerinax/solace` onto the schema-driven trigger model in
this same branch (`phase6-trigger-model-solace-manifest`). Read this in
addition to (not instead of) the standard skills — use the
`creating-integrator-triggers` skill and/or the `wso2-trigger-onboarder` agent,
and `/generate-trigger-model` (or the `trigger:generate` skill) to actually
author the manifest. This doc exists because several steps below are **not**
covered by those generic skills and caused real back-and-forth this session.

## Reference implementation (use as a structural template, not a copy)

- `packages/ballerina-language-server/service-model-generator/modules/service-model-generator-ls-extension/src/main/resources/trigger-models/solace.json`

`solace.jms` is conceptually similar (broker URL, auth choices, queue/topic
destination, ack mode) but is built on the **JMS API** rather than JCSMP
(the `solace` module's underlying API) — property names, types, and defaults
will differ. Introspect `solace.jms`'s actual `.bal` sources independently;
don't assume field-for-field parity with `solace.json`.

## Connector source (local bala cache)

`/Users/gayaldassanayake/.ballerina/repositories/local/bala/ballerinax/solace.jms/0.1.0/java21/modules/solace.jms/`
— has `listener.bal`, `annotations.bal`, `types.bal`, `caller.bal`, `errors.bal`,
`client.bal`, `message_producer.bal`, `message_consumer.bal`, `validation.bal`.

- `orgName`: `ballerinax`
- `packageName` / `moduleName`: `solace.jms`
- `version`: `0.1.0`

## The `/generate-trigger-model` skill: where to run it, where its output lands

This is the step most likely to trip up a fresh agent, so it's called out on
its own before the step list below.

The `generate-trigger-model` skill's own hard rule is: **"Write only
`resources/trigger-model.json`; touch nothing else in the library."** — it
must be invoked with the shell's cwd set to the **connector's own separate git
repository** (its "library repository root"), not this `ballerina-vscode`
monorepo. It never writes into this repo itself.

For `solace.jms`, that repo is already cloned locally at:

```
/Users/gayaldassanayake/Documents/event-integration/solace-repos/module-ballerinax-solace.jms
```

(`origin` = `gayaldassanayake/module-ballerinax-solace.jms`, `upstream` =
`ballerina-platform/module-ballerinax-solace.jms`, currently on branch
`docs/examples-and-setup-guide` — check out/create whatever branch is
appropriate before running the skill, and check `git status` first in case
there's in-progress work there too.)

So the actual flow is:

1. `cd /Users/gayaldassanayake/Documents/event-integration/solace-repos/module-ballerinax-solace.jms`
2. Invoke `/generate-trigger-model` (or use the `trigger-agent introspect` /
   `scaffold` / `validate` CLI it wraps) from *there*. It writes/updates
   `resources/trigger-model.json` inside that repo — nowhere else.
3. Copy the result into *this* repo, renaming it in the process:
   ```
   cp /Users/gayaldassanayake/Documents/event-integration/solace-repos/module-ballerinax-solace.jms/resources/trigger-model.json \
      packages/ballerina-language-server/service-model-generator/modules/service-model-generator-ls-extension/src/main/resources/trigger-models/solace.jms.json
   ```
4. Continue with the "End-to-end steps" below (registering it, wiring the
   routers, etc.) back in this repo.

## End-to-end steps to wire a new schema-driven trigger into the LS

(Steps 2–4 are the part *not* fully covered by the generic onboarding skill.)

1. Author `trigger-models/solace.jms.json` per the section above (generate in
   the library repo, hand-curate per the gotchas below, then copy it in).

2. Register it in `bundled_trigger_models.json`:
   ```json
   "solace.jms": "trigger-models/solace.jms.json"
   ```
   This alone makes `ConnectorModelReader.hasBundledTriggerModel("solace.jms")`
   return `true`, which makes **both** `ServiceBuilderRouter` and
   `FunctionBuilderRouter` automatically prefer `SchemaDrivenServiceBuilder`/
   `SchemaDrivenFunctionBuilder` over any hardcoded builder — see
   `useSchemaDrivenPath(...)`, which both routers check *before* consulting
   their hardcoded `CONSTRUCTOR_MAP`.

3. Check whether `solace.jms` already has a legacy hardcoded builder (grep for
   it in `ServiceBuilderRouter.java` / `FunctionBuilderRouter.java` /
   `builder/service/` / `builder/function/`, and in their `CONSTRUCTOR_MAP`
   comment listing of "not yet schema-driven" modules). Based on `solace`'s
   precedent, this may well be a no-op (never onboarded before at all) — but
   if a legacy builder *does* exist:
   - Delete the builder class(es) and their `resources/services/*.json`.
   - Delete any test fixtures under
     `src/test/resources/{add_service_and_listener,get_service_init_model,get_sm_from_source}/config/`
     that assert the old builder's output shape. These suites are
     data-provider-driven off whatever `.json` files exist in each `config/`
     dir, so deleting the file removes the test case cleanly — don't try to
     hand-edit them to match the new schema-driven shape.
   - Also delete any solace.jms-only sample `.bal` fixture directories those
     configs pointed at (check first whether the sample dir is shared by other
     tests, e.g. `sample1`/`sample2` are shared scaffolding across many
     connectors — only delete a sample dir if it's exclusively referenced by
     the fixture you're removing).

4. Add a `trigger_properties.json` entry for the trigger picker, **with**
   `version`/`icon`/`kind` populated (see the `kafka` or `mysql` entries as the
   model). This lets `ServiceModelGeneratorService.getTriggerBasicInfoByName(TriggerProperty)`
   take the fast path (build `TriggerBasicInfo` straight from those scalars)
   instead of parsing the full `TriggerModel` just to render one picker row.
   - **Note:** this step was missed for `solace` itself in this session — its
     entry (id `"11"`) still lacks `version`/`icon`/`kind`. It still works
     today (falls through to `getTriggerBasicInfoByName(orgName, name)`, which
     checks the bundled model directly), just not on the fast path. Worth
     fixing for `solace` too while in this area, and worth doing correctly
     from the start for `solace.jms`.

5. Rebuild: from `packages/ballerina-language-server`, run
   `./gradlew pack -x test -x check` (or `clean pack` for a from-scratch build).

6. **Fast local iteration loop** (skips the vsix repackage/reinstall cycle):
   point VS Code's `ballerina.langServerPath` setting directly at
   `packages/ballerina-language-server/build/ballerina-language-server-1.8.0.m3.jar`,
   then just `./gradlew pack` + reload window between edits.

7. Only when you actually need to ship/install a vsix: run
   `pnpm run postbuild` from `packages/ballerina-extension` (runs `provisionLS`
   → `copyFonts` → `copyJSLibs` → `package` → `copyVSIX`). Rush's own build
   cache can skip re-running this even when `ballerina-language-server` really
   did rebuild — if a "restored from cache" result looks suspicious, just rerun
   `node scripts/copy-ls.js` directly, and verify with
   `unzip -p <jar-path> trigger-models/<name>.json | grep ...` that the
   packaged jar actually has your latest content before trusting it.

## Gotchas found the hard way this session — apply to `solace.jms` from the start

1. **Advanced/optional field defaults.** For an advanced, optional,
   defaultable listener param (e.g. a timeout with a library-side default),
   don't bake the default into `"value"` — leave `"value": ""` and put the
   default in `"placeholder"` instead (see `mysql.json`'s `livenessInterval`
   for the established convention). If you bake a real value into `"value"`,
   `SchemaDrivenSourceGenerator` treats the field as user-configured and
   *always* emits it into the generated `.bal`, even if the user never
   touched it — its per-field loop only skips a field
   `if (!rendered.isEmpty())`, i.e. an empty `"value"` means "not configured".

2. **A property's JSON key must be unique across its own ancestor chain.**
   This is the one that caused the most damage on `solace` (the `durability`
   field). The frontend's form values live in **one flat namespace** keyed by
   field name (react-hook-form), not a nested path. If a leaf property inside
   a CHOICE branch happens to share its JSON key with an ancestor CHOICE field
   — e.g. a `durability` CHOICE field (Temporary/Durable) whose "Durable"
   branch contains a leaf *also* named `durability`, used just to carry a
   fixed literal annotation value — both fields' `setValue()` calls collide on
   the same key. The choice's own selected-index value gets overwritten by the
   leaf's literal string value; `Number(...)` on that string is `NaN`; no
   branch ever matches as "selected" at submit time; and the generator falls
   back to the *first* choice, silently dropping every field in the branch you
   actually meant to submit (not just the colliding one).
   - **Rule:** give every property key a name that's unique across its whole
     ancestor chain, even if that means the JSON map key differs from the
     actual Ballerina field name. `codedata.path` (not the map key) is what
     determines the generated field name, so this costs nothing — e.g. the
     fix on `solace` was renaming the leaf's key to `durabilityValue` while
     keeping `codedata.path: "durability"`.
   - Deliberately check this for `solace.jms`: anywhere you model a CHOICE
     branch that needs to emit a fixed/computed literal to match the
     selection (the "recorded to match the selection above" pattern), diff
     the leaf's key against every ancestor CHOICE's key.

3. **A locked/computed field needs `"hidden": true` to disappear from the UI —
   not `"enabled": false`.** `"enabled": false` *also* makes the backend's
   `collectAnnotationFields`/`isEnabledWithValue()` skip the field for
   codegen, so it vanishes from both the form *and* the generated source. Use
   `"editable": false` + `"hidden": true`, and keep `"enabled": true`, for a
   field whose value is fixed but must still land in the generated annotation.
   - This needed real code changes (not JSON-only) to support end-to-end, and
     they're **already merged into this branch** — you should not need to
     touch this code again, just set `"hidden": true` on the JSON leaf:
     - `Value.java` (backend model) — added a `hidden` boolean field
       (getter/setter/copy-constructor/`ValueBuilder` support), mirroring how
       `Parameter.java` already models the same concept for function params.
     - `ServiceCreationView.tsx`'s `mapPropertiesToFormFields` — passes
       `hidden: property.hidden` through.
     - `ChoiceForm.tsx`'s `convertConfig` + its render filter — passes
       `hidden` through *and* excludes hidden fields from both
       `nonAdvancedFields`/`advancedFields` (it previously only filtered by
       `advanced`, so a hidden field would still render).
     - `DropdownChoiceForm.tsx` — same filter fix for the dropdown-style
       CHOICE sibling.

4. **Checkstyle**: any new/modified Java record needs a `@param` tag for every
   component, in declaration order, or the build fails checkstyle (see the fix
   to `TriggerProperty.java` in this session for the exact pattern).

5. **Known open issue, not chased to root cause (flag if reproduced, not
   necessarily something to fix as part of onboarding unless asked):**
   switching between CHOICE radio options (e.g. an `auth` choice with
   Basic/Kerberos/OAuth2-style branches) sometimes causes a visible
   "page scrolled to top" flash. Confirmed contributing factors:
   `ServiceCreationView.handleOnChange` rebuilds the *entire* `formFields`
   tree via `mapPropertiesToFormFields` on every CHOICE-type value change
   (not just the diff), and `ChoiceForm` applies `autoFocus={index === 0}` to
   a branch's first field every time the branch changes. The exact asymmetry
   (some branch switches flash, some don't) wasn't fully root-caused.

## Suggested first move for the new agent

`cd` into
`/Users/gayaldassanayake/Documents/event-integration/solace-repos/module-ballerinax-solace.jms`
and run `/generate-trigger-model` there to produce a first-draft
`resources/trigger-model.json`, copy it into this repo as
`trigger-models/solace.jms.json` (see the section above), then apply
"End-to-end steps" 2–4 and the gotchas above before testing in the extension.
