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

import { FunctionModel, ParameterModel, PropertyModel, ServiceModel } from "@wso2/ballerina-core";

/**
 * Pure helpers for schema-driven trigger handlers (unified TriggerModel wire shape).
 *
 * The language server expands each handler variant into a self-contained wire FunctionModel whose
 * payload parameter carries its composition inputs on `type.codedata`:
 *
 *   element = codedata.boundType ?? codedata.defaultType
 *   base    = codedata.template applied to element      ({{type}} -> element)
 *   result  = an active PAYLOAD_MODIFIER property's template (value === true), else base
 *
 * so the UI can recompose the rendered type when the user toggles a modifier (e.g. stream) or binds
 * a custom schema — no connector-specific string surgery.
 */

const TYPE_PLACEHOLDER = "{{type}}";

export const CODEDATA_PAYLOAD_TYPE = "PAYLOAD_TYPE";
export const CODEDATA_PAYLOAD_TYPE_INCLUDED_RECORD = "PAYLOAD_TYPE_INCLUDED_RECORD";
export const CODEDATA_PAYLOAD_MODIFIER = "PAYLOAD_MODIFIER";
export const CODEDATA_METADATA_FLAG = "METADATA_FLAG";
export const CODEDATA_COMPLEX_ANNOTATION = "COMPLEX_FUNCTION_ANNOTATION";
export const CODEDATA_FIELD_VALUE_CHOICE = "FIELD_VALUE_CHOICE";

/** The group id linking a handler's format variants (falls back to its display label). */
export function handlerGroupId(fn: FunctionModel): string | undefined {
    return fn.group ?? fn.metadata?.label;
}

/** True when the service's functions carry the schema-driven handler-catalog markers. */
export function isSchemaTriggerFunction(fn: FunctionModel): boolean {
    return !!fn.group;
}

/** True when the service is a schema-driven trigger (marker functions or an addable catalog). */
export function isSchemaTriggerService(serviceModel?: ServiceModel): boolean {
    if (!serviceModel) {
        return false;
    }
    return (serviceModel.schemaFunctions?.length ?? 0) > 0
        || (serviceModel.functions?.some(isSchemaTriggerFunction) ?? false);
}

/**
 * The addable handler catalog. The language server ships it in `schemaFunctions` (source handlers
 * live in `functions`); templates that predate the split are recognised by their disabled
 * catalog-marker functions as a fallback.
 */
export function catalogFunctionsOf(serviceModel: ServiceModel): FunctionModel[] {
    if (serviceModel.schemaFunctions?.length) {
        return serviceModel.schemaFunctions;
    }
    return (serviceModel.functions ?? []).filter((fn) => isSchemaTriggerFunction(fn) && !fn.enabled);
}

/** The payload (data-binding) parameter of an expanded variant, if any. */
export function payloadParameterOf(fn: FunctionModel): ParameterModel | undefined {
    return fn.parameters?.find(
        (p) => p.kind === "DATA_BINDING"
            || p.type?.codedata?.type === CODEDATA_PAYLOAD_TYPE
            || p.type?.codedata?.type === CODEDATA_PAYLOAD_TYPE_INCLUDED_RECORD
    );
}

/** Properties of a given codedata role, keyed as shipped. */
export function propertiesOfRole(fn: FunctionModel, role: string): [string, PropertyModel][] {
    return Object.entries(fn.properties ?? {}).filter(
        ([, prop]) => (prop as PropertyModel).codedata?.type === role
    ) as [string, PropertyModel][];
}

/**
 * Whether a single handler variant has anything {@link TriggerHandlerForm} would let the user
 * configure: a bindable payload, composition flags, function annotations, or opt-in advanced
 * parameters. False for a handler like kafka's `onError`, whose only parameter is a fixed,
 * non-editable error — the form would render empty, so callers can skip it (add directly / hide
 * the edit affordance) instead of opening a blank panel.
 */
export function hasConfigurableFields(fn: FunctionModel): boolean {
    if (!fn) {
        return false;
    }
    const payloadParam = payloadParameterOf(fn);
    const isPayloadBindable = payloadParam?.type?.codedata?.bindable === true;
    const hasMetadataFlags = propertiesOfRole(fn, CODEDATA_METADATA_FLAG).length > 0;
    const hasModifierFlags = propertiesOfRole(fn, CODEDATA_PAYLOAD_MODIFIER).length > 0;
    const hasAnnotations = propertiesOfRole(fn, CODEDATA_COMPLEX_ANNOTATION).length > 0;
    const hasAdvancedParams = fn.parameters?.some((p) => p.advanced === true) ?? false;
    return isPayloadBindable || hasMetadataFlags || hasModifierFlags || hasAnnotations || hasAdvancedParams;
}

export function applyTypeTemplate(template: string | undefined, element: string): string {
    if (!template) {
        return element;
    }
    return template.includes(TYPE_PLACEHOLDER) ? template.split(TYPE_PLACEHOLDER).join(element) : template;
}

/** Whether a PAYLOAD_MODIFIER flag is currently active (its value is the checked state). */
export function isModifierActive(prop: PropertyModel): boolean {
    const value = prop.value as unknown;
    return value === true || value === "true";
}

/**
 * Composes the rendered payload type from a parameter's codedata and the function's modifier flags.
 * An active modifier's template supersedes the base template (matching the LS PayloadComposer).
 */
export function composePayloadType(fn: FunctionModel, param: ParameterModel): string {
    const codedata = param.type?.codedata;
    if (!codedata) {
        return param.type?.value ?? "";
    }
    const element = codedata.boundType || codedata.defaultType || "";
    const activeModifier = propertiesOfRole(fn, CODEDATA_PAYLOAD_MODIFIER)
        .map(([, prop]) => prop)
        .find((prop) => isModifierActive(prop) && !!prop.codedata?.template);
    if (activeModifier) {
        return applyTypeTemplate(activeModifier.codedata.template, element);
    }
    const base = applyTypeTemplate(codedata.template, element);
    return base || element;
}

/** Whether the payload still renders its shipped default (no user-bound schema). */
export function hasDefaultPayload(param: ParameterModel): boolean {
    return !param.type?.codedata?.boundType;
}

/**
 * Converts a bound type name to a parameter name — camelCased, pluralized when the base template
 * produces an array (e.g. CSV rows).
 */
export function typeNameToParamName(typeName: string, pluralize: boolean): string {
    if (!typeName) {
        return "content";
    }
    let baseName = typeName.trim();
    if (baseName.includes(":")) {
        baseName = baseName.split(":").pop() || baseName;
    }
    while (baseName.endsWith("[]")) {
        baseName = baseName.slice(0, -2);
    }
    baseName = baseName.replace(/[^A-Za-z0-9_]/g, "");
    if (!baseName || /^\d/.test(baseName)) {
        return "content";
    }
    const camelCase = baseName.charAt(0).toLowerCase() + baseName.slice(1);
    if (!pluralize) {
        return camelCase;
    }
    const lastChar = camelCase.slice(-1);
    const lastTwoChars = camelCase.slice(-2);
    if (lastTwoChars === "ss" || lastTwoChars === "sh" || lastTwoChars === "ch" || lastChar === "x" || lastChar === "z") {
        return camelCase + "es";
    }
    if (lastChar === "y" && !["a", "e", "i", "o", "u"].includes(camelCase.slice(-2, -1))) {
        return camelCase.slice(0, -1) + "ies";
    }
    if (lastChar === "s") {
        return camelCase;
    }
    return camelCase + "s";
}

/**
 * A stable key of the function's generated signature — two models sharing this key regenerate the
 * same handler signature; a diff means saving will rewrite it (and may break body code).
 */
export function functionSignatureKey(fn: FunctionModel): string {
    const params = (fn.parameters ?? []).map((p) =>
        [p.kind ?? "", p.name?.value ?? "", p.type?.value ?? "", p.enabled ?? false].join("|")
    );
    return [fn.name?.value ?? "", ...params].join(";");
}
