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

import type { FunctionModel, HandlerLayoutSection, ParameterModel, PropertyModel } from "@wso2/ballerina-core";

import {
    CODEDATA_ANNOTATION_ATTACHMENT,
    CODEDATA_COMPLEX_ANNOTATION,
    CODEDATA_METADATA_FLAG,
    CODEDATA_PAYLOAD_MODIFIER,
    bindingGroupOf,
    bindingGroupSiblingsOf,
    groupedPayloadParametersOf,
    propertiesOfRole,
} from "./payloadComposer";

/**
 * Resolves a schema-driven handler's authored `layout` (see `HandlerLayoutSection`) into the ordered,
 * grouped sections TriggerHandlerForm renders.
 *
 * The whole feature is opt-in: a handler with no `layout` resolves to a single unlabeled section holding
 * every unit in the form's historical order, which is why adding this changed nothing for the connectors
 * that shipped before it existed. A partial layout only has to name the units it wants to move; whatever
 * it leaves alone lands in the `*rest` section, or after the declared ones when there is no `*rest`.
 *
 * Section chrome follows one rule: **an unlabeled section keeps each unit's default chrome** (the flags
 * column, the "Advanced Configurations" collapsible, the divider before annotations), **a labeled section
 * shows its units plainly under its heading**. So "leave it out and it stays exactly as it is today; put
 * it in a named group and it appears under that heading".
 *
 * A labeled section may additionally set `advanced`, which moves the whole group inside the collapsed
 * "Advanced Configurations" box — for a group the user only needs occasionally. Those sections render after
 * whatever loose advanced units the box already holds, in the order the layout declared them.
 *
 * Two id namespaces, kept apart so neither can shadow the other: `$`-prefixed ids name the form's own
 * built-in units, and the single `*`-prefixed id ({@link LAYOUT_ID_REST}) is a placement directive rather
 * than a name. Everything else is an author's own identifier, used bare.
 */

/** The variant/format dropdown. */
export const LAYOUT_ID_VARIANT = "$variant";
/** The handler's own documentation blurb. */
export const LAYOUT_ID_DESCRIPTION = "$description";
/** The renamable-handler name field. */
export const LAYOUT_ID_NAME = "$name";
/** The editable doc-comment field. */
export const LAYOUT_ID_DOCUMENTATION = "$documentation";
/** The addable-parameters manager. */
export const LAYOUT_ID_PARAMETERS = "$parameters";
/** The editable return type field. */
export const LAYOUT_ID_RETURN_TYPE = "$returnType";
/** The individually-bound HTTP header block. */
export const LAYOUT_ID_HEADERS = "$headers";
/**
 * Directive: every unit no section claimed, in default order.
 *
 * Prefixed `*` rather than `$` deliberately. It is not the name of a unit like `$name` or `$headers` are
 * — it is an instruction about placement — and it lives in its own namespace so that no author-chosen
 * name can ever be mistaken for it. Unlike a Ballerina identifier, a `properties` key is an arbitrary
 * schema-authored string, so a field literally called `$rest` is possible; nothing may be called `*rest`.
 */
export const LAYOUT_ID_REST = "*rest";

/**
 * Layout id -> ArtifactForm `FormField.key`. The `$` prefix is load-bearing, not cosmetic: real
 * connectors ship parameters literally named `headers` (mcp's `newTool`) and `parameters` (sap.jco's
 * `onCall`), so bare ids would silently address the wrong unit. No Ballerina identifier starts with
 * `$`, so the two namespaces provably never overlap.
 */
const ARTIFACT_FIELD_KEY_BY_ID: Record<string, string> = {
    [LAYOUT_ID_NAME]: "name",
    [LAYOUT_ID_DOCUMENTATION]: "documentation",
    [LAYOUT_ID_PARAMETERS]: "parameters",
    [LAYOUT_ID_RETURN_TYPE]: "returnType",
};

const ARTIFACT_ID_BY_FIELD_KEY: Record<string, string> = Object.fromEntries(
    Object.entries(ARTIFACT_FIELD_KEY_BY_ID).map(([id, key]) => [key, id])
);

/** The key of the single section a handler with no authored layout resolves to. */
export const DEFAULT_SECTION_KEY = "$default";

export type HandlerUnitKind =
    | "VARIANT"
    | "DESCRIPTION"
    | "ARTIFACT_FIELD"
    | "FLAG"
    | "MODIFIER"
    | "PAYLOAD"
    | "ANNOTATION"
    | "ADVANCED_PARAM"
    | "HEADERS";

/**
 * One addressable thing the handler form can render. Carries only what the renderer needs to look the
 * unit's data back up — the form keeps rendering from `functionModel`, so a unit never holds a stale copy.
 */
export interface HandlerUnit {
    /** The primary id an author writes in `layout[].fields`. */
    id: string;
    /**
     * Extra ids that also address this unit. Only payload sections use these: a CDC `onUpdate` binds
     * `before`/`after` as one section under `bindingGroup: "rowState"`, so `rowState`, `before` and
     * `after` all name the same section.
     */
    altIds?: string[];
    kind: HandlerUnitKind;
    /** The `fn.properties` key, for FLAG / MODIFIER / ANNOTATION units. */
    propertyKey?: string;
    /** The property itself, for FLAG / MODIFIER / ANNOTATION units. */
    property?: PropertyModel;
    /** The parameter, for PAYLOAD / ADVANCED_PARAM units. */
    parameter?: ParameterModel;
    /** The ArtifactForm `FormField.key`, for ARTIFACT_FIELD units. */
    fieldKey?: string;
}

export interface ResolvedSection {
    /** Stable React key: the author's section `id`, else its index, else `DEFAULT_SECTION_KEY`. */
    key: string;
    /** Heading to render. Absent -> an ordered run with no heading and default per-unit chrome. */
    label?: string;
    /** Explanatory text under the heading. */
    description?: string;
    /** Render this group inside the collapsed "Advanced Configurations" box. Labeled sections only. */
    advanced?: boolean;
    units: HandlerUnit[];
}

/** Dev-only diagnostics. Authoring mistakes are reported, never thrown — a bad id must not blank a form. */
function warn(message: string): void {
    // eslint-disable-next-line no-console
    console.warn(`[TriggerHandlerForm layout] ${message}`);
}

/**
 * Every unit the handler form can render, in the order it rendered them before `layout` existed.
 *
 * `artifactFieldKeys` comes from the caller's already-built ArtifactForm fields rather than being
 * re-derived here, so the two can never disagree about which of name/documentation/parameters/returnType
 * a given handler actually offers.
 *
 * Units are emitted unconditionally where the form itself decides visibility at render time (the variant
 * dropdown, the description) — resolution only has to know the unit exists to be addressable.
 */
export function handlerUnitsOf(fn: FunctionModel, artifactFieldKeys: string[] = []): HandlerUnit[] {
    if (!fn) {
        return [];
    }
    const units: HandlerUnit[] = [
        { id: LAYOUT_ID_VARIANT, kind: "VARIANT" },
        { id: LAYOUT_ID_DESCRIPTION, kind: "DESCRIPTION" },
    ];

    for (const fieldKey of artifactFieldKeys) {
        units.push({
            id: ARTIFACT_ID_BY_FIELD_KEY[fieldKey] ?? fieldKey,
            kind: "ARTIFACT_FIELD",
            fieldKey,
        });
    }

    for (const [propertyKey, property] of propertiesOfRole(fn, CODEDATA_METADATA_FLAG)) {
        units.push({ id: propertyKey, kind: "FLAG", propertyKey, property });
    }
    for (const [propertyKey, property] of propertiesOfRole(fn, CODEDATA_PAYLOAD_MODIFIER)) {
        units.push({ id: propertyKey, kind: "MODIFIER", propertyKey, property });
    }

    // One unit per rendered payload section. Addressable by its binding group *or* by any member's name,
    // since an author reading the schema sees the member names, not the group they were folded into.
    for (const param of groupedPayloadParametersOf(fn)) {
        const group = bindingGroupOf(param);
        const memberNames = group
            ? bindingGroupSiblingsOf(fn, param)
                .map((p) => p.name?.value)
                .filter((name): name is string => !!name)
            : [];
        const id = group ?? param.name?.value ?? "";
        units.push({
            id,
            altIds: memberNames.filter((name) => name !== id),
            kind: "PAYLOAD",
            parameter: param,
        });
    }

    for (const [propertyKey, property] of [
        ...propertiesOfRole(fn, CODEDATA_COMPLEX_ANNOTATION),
        ...propertiesOfRole(fn, CODEDATA_ANNOTATION_ATTACHMENT),
    ]) {
        units.push({ id: propertyKey, kind: "ANNOTATION", propertyKey, property });
    }

    for (const param of fn.parameters?.filter((p) => p.advanced === true) ?? []) {
        units.push({ id: param.name?.value ?? "", kind: "ADVANCED_PARAM", parameter: param });
    }

    if (fn.schema?.header) {
        units.push({ id: LAYOUT_ID_HEADERS, kind: "HEADERS" });
    }

    return units.filter((unit) => !!unit.id);
}

/** Index every unit by its primary id, then by its alt ids. First registration wins. */
function indexUnits(units: HandlerUnit[]): Map<string, HandlerUnit> {
    const byId = new Map<string, HandlerUnit>();
    for (const unit of units) {
        if (!byId.has(unit.id)) {
            byId.set(unit.id, unit);
        }
    }
    for (const unit of units) {
        for (const altId of unit.altIds ?? []) {
            if (!byId.has(altId)) {
                byId.set(altId, unit);
            }
        }
    }
    return byId;
}

/**
 * Keeps the ArtifactForm block whole. Its four fields share one react-hook-form context, so they cannot
 * be split across sections or interleaved with other units — but they can be *ordered*, which is what an
 * author naming them is really asking for. Every ARTIFACT_FIELD unit therefore collapses to the position
 * of the first one, in the relative order the layout gave them.
 *
 * A consequence worth knowing when authoring: naming *one* artifact field moves the whole block to that
 * position, because the fields the layout left alone have nowhere else to go.
 *
 * `declaredKeys` are the sections the author actually wrote, so the "you split them" warning fires only
 * on a real authoring mistake — never merely because some fields stayed behind in the remainder.
 */
function consolidateArtifactUnits(sections: ResolvedSection[], declaredKeys: Set<string>): ResolvedSection[] {
    const owners = sections.filter((section) => section.units.some((unit) => unit.kind === "ARTIFACT_FIELD"));
    if (owners.length === 0) {
        return sections;
    }
    const split = owners.filter((section) => declaredKeys.has(section.key));
    if (split.length > 1) {
        warn(
            "the name/documentation/parameters/returnType fields share one form context and cannot be " +
            `split across sections; rendering all of them in "${split[0].key}"`
        );
    }
    const artifactUnits = owners.flatMap((section) => section.units.filter((unit) => unit.kind === "ARTIFACT_FIELD"));
    const first = split[0] ?? owners[0];
    return sections.map((section) => {
        if (!owners.includes(section)) {
            return section;
        }
        const others = section.units.filter((unit) => unit.kind !== "ARTIFACT_FIELD");
        if (section !== first) {
            return { ...section, units: others };
        }
        const insertAt = section.units.findIndex((unit) => unit.kind === "ARTIFACT_FIELD");
        const before = section.units.slice(0, insertAt).filter((unit) => unit.kind !== "ARTIFACT_FIELD");
        return { ...section, units: [...before, ...artifactUnits, ...others.slice(before.length)] };
    });
}

/**
 * The handler form's sections, in render order.
 *
 * No authored layout -> one unlabeled section holding every unit in default order, i.e. exactly the form
 * the connector rendered before this feature existed.
 */
export function resolveHandlerLayout(fn: FunctionModel, artifactFieldKeys: string[] = []): ResolvedSection[] {
    const units = handlerUnitsOf(fn, artifactFieldKeys);
    const layout = fn?.layout;
    if (!layout || layout.length === 0) {
        return [{ key: DEFAULT_SECTION_KEY, units }];
    }

    const byId = indexUnits(units);
    const claimed = new Set<HandlerUnit>();
    let restAt = -1;

    const sections: ResolvedSection[] = layout.map((section: HandlerLayoutSection, index: number) => {
        const key = section.id?.trim() || `$section-${index}`;
        const picked: HandlerUnit[] = [];
        for (const field of section.fields ?? []) {
            if (field === LAYOUT_ID_REST) {
                if (restAt === -1) {
                    restAt = index;
                } else {
                    warn(`"${LAYOUT_ID_REST}" appears more than once; only the first placement is used`);
                }
                continue;
            }
            const unit = byId.get(field);
            if (!unit) {
                // Expected and harmless: a handler variant may not have every field its siblings do.
                warn(`section "${key}" names "${field}", which matches no field on this handler -- skipped`);
                continue;
            }
            if (claimed.has(unit)) {
                warn(`"${field}" is claimed by an earlier section; the later mention is ignored`);
                continue;
            }
            claimed.add(unit);
            picked.push(unit);
        }
        const label = section.label?.trim() || undefined;
        let advanced = section.advanced === true;
        if (advanced && !label) {
            warn(`section "${key}" is marked advanced but has no label; an advanced group needs a heading `
                + "to sit under, so it is rendered in place instead");
            advanced = false;
        }
        return {
            key,
            label,
            description: section.description?.trim() || undefined,
            advanced: advanced || undefined,
            units: picked,
        };
    });

    // Whatever the layout did not name keeps its default order and default chrome, so a partial layout is
    // always safe to write. It goes where `*rest` said, else after everything the author did name.
    const remainder = units.filter((unit) => !claimed.has(unit));
    let remainderKey: string | undefined;
    if (remainder.length > 0) {
        if (restAt === -1) {
            remainderKey = LAYOUT_ID_REST;
            sections.push({ key: remainderKey, units: remainder });
        } else {
            remainderKey = sections[restAt].key;
            sections[restAt] = { ...sections[restAt], units: [...sections[restAt].units, ...remainder] };
        }
    }

    const declaredKeys = new Set(
        sections.map((section) => section.key).filter((key) => key !== remainderKey)
    );
    return consolidateArtifactUnits(sections, declaredKeys).filter((section) => section.units.length > 0);
}

/**
 * Orders the caller's ArtifactForm fields to match the resolved layout. ArtifactForm renders with
 * `preserveFieldOrder`, so the array order is the display order. Field keys the layout never named keep
 * their original relative order, after the ones it did.
 */
export function orderArtifactFieldKeys(sections: ResolvedSection[], artifactFieldKeys: string[]): string[] {
    const declared = sections
        .flatMap((section) => section.units)
        .filter((unit) => unit.kind === "ARTIFACT_FIELD")
        .map((unit) => unit.fieldKey)
        .filter((key): key is string => !!key && artifactFieldKeys.includes(key));
    const seen = new Set(declared);
    return [...declared, ...artifactFieldKeys.filter((key) => !seen.has(key))];
}
