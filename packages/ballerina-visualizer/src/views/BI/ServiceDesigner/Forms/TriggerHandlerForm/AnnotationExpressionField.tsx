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

import { forwardRef, useCallback, useEffect, useImperativeHandle, useMemo, useRef, useState } from "react";
import { useForm } from "react-hook-form";
import { debounce } from "lodash";
import { useRpcContext } from "@wso2/ballerina-rpc-client";
import {
    Diagnostic,
    ExpressionProperty,
    LineRange,
    PropertyModel,
    TriggerCharacter,
    TRIGGER_CHARACTERS,
} from "@wso2/ballerina-core";
import {
    DiagnosticsStoreProvider,
    FieldFactory,
    FormExpressionEditorProps,
    FormField,
    FormValues,
    Provider as FormContextProvider,
    evaluateClientRules,
} from "@wso2/ballerina-side-panel";
import { CompletionItem } from "@wso2/ui-toolkit";

import { getHelperPaneNew } from "../../../HelperPaneNew";
import { EXPRESSION_EXTRACTION_REGEX } from "../../../../../constants";
import { calculateExpressionOffsets, convertBalCompletion, removeDuplicateDiagnostics } from "../../../../../utils/bi";

/**
 * The react-hook-form field key every leaf binds to. Each leaf renders its own isolated
 * {@link useForm} instance, so a single fixed key is sufficient and keeps the value plumbing simple.
 */
const FIELD_KEY = "value";

const EMPTY_LINE_RANGE: LineRange = {
    startLine: { line: 0, offset: 0 },
    endLine: { line: 0, offset: 0 },
};

export interface AnnotationExpressionFieldProps {
    id?: string;
    value: string;
    property?: PropertyModel;
    filePath?: string;
    targetLineRange?: LineRange;
    required?: boolean;
    disabled?: boolean;
    onChange: (value: string) => void;
    onDiagnosticsChange?: (diagnostics: Diagnostic[]) => void;
    onValidationStateChange?: (state: { isValidating: boolean }) => void;
}

export interface AnnotationExpressionFieldHandle {
    /**
     * Synchronously re-runs diagnostics for the current value, bypassing the typing-time debounce.
     * Returns the resulting diagnostics (empty on LS failure — the same silent fallback used while
     * typing). Consumed by the save-time gate.
     */
    revalidate: () => Promise<Diagnostic[]>;
}

/**
 * A single Text/Expression annotation leaf, rendered through the shared side-panel editor stack
 * ({@link FieldFactory} → EditorFactory → the mode-aware ExpressionEditor). Replaces the bespoke
 * TextExpressionField: the Text/Expression toggle, string-literal quoting and required/diagnostic
 * validation are all handled by the shared components instead of hand-rolled here.
 *
 * Each leaf owns an isolated react-hook-form instance bound to {@link FIELD_KEY}; the bound value is
 * mirrored back out through {@link AnnotationExpressionFieldProps.onChange} so the caller's model
 * tree stays the source of truth for save. The value the editor emits (a quoted string literal in
 * Text mode) is stored verbatim — the language server's annotation emitter quotes string leaves
 * idempotently, so it round-trips without double-quoting.
 */
export const AnnotationExpressionField = forwardRef<AnnotationExpressionFieldHandle, AnnotationExpressionFieldProps>(
    (props, ref) => {
        // `disabled` is accepted for API parity with the former TextExpressionField but is not wired:
        // FieldFactory exposes no disabled pass-through, and the annotation panel only disables during
        // the brief save round-trip. `required` is folded into the field's `optional` below.
        const { id, value, property, filePath, targetLineRange, required, onChange,
            onDiagnosticsChange, onValidationStateChange } = props;
        const { rpcClient } = useRpcContext();

        const methods = useForm<FormValues>({ defaultValues: { [FIELD_KEY]: value ?? "" } });
        const { control, getValues, setValue, watch, register, unregister, setError, clearErrors, formState } = methods;

        const [completions, setCompletions] = useState<CompletionItem[]>([]);
        const [filteredCompletions, setFilteredCompletions] = useState<CompletionItem[]>([]);
        const prevCompletionFetchText = useRef<string>("");

        // Latest diagnostics for the field, kept so revalidate() can resolve synchronously and so the
        // typing-time debounce and the save-time gate agree on a single source.
        const diagnosticsRef = useRef<Diagnostic[]>([]);
        const onChangeRef = useRef(onChange);
        const onDiagnosticsChangeRef = useRef(onDiagnosticsChange);
        const onValidationStateChangeRef = useRef(onValidationStateChange);
        useEffect(() => { onChangeRef.current = onChange; }, [onChange]);
        useEffect(() => { onDiagnosticsChangeRef.current = onDiagnosticsChange; }, [onDiagnosticsChange]);
        useEffect(() => { onValidationStateChangeRef.current = onValidationStateChange; }, [onValidationStateChange]);

        // Withdraw this field's diagnostics when it goes away. A leaf unmounts as soon as it can no
        // longer reach the generated source — its choice branch was deselected (Move → Delete) or the
        // section was unchecked — and a rule failure about a value that will not be emitted must stop
        // blocking save. This mirrors the server's validation walk, which descends only the enabled
        // branch and skips nodes that cannot contribute.
        useEffect(() => () => {
            onDiagnosticsChangeRef.current?.([]);
            onValidationStateChangeRef.current?.({ isValidating: false });
        }, []);

        const effectiveTargetLineRange = targetLineRange ?? EMPTY_LINE_RANGE;

        // ----- mirror the bound value back to the model tree -----
        const watchedValue = watch(FIELD_KEY);
        useEffect(() => {
            onChangeRef.current(typeof watchedValue === "string" ? watchedValue : String(watchedValue ?? ""));
        }, [watchedValue]);

        // The field descriptor handed to the shared editor. `types` carries the connector-shipped
        // `validations[]`, so it is also what the client rule engine evaluates against below.
        const field: FormField = useMemo(() => ({
            key: FIELD_KEY,
            label: property?.metadata?.label || "",
            type: (property?.types?.find((t) => t.selected)?.fieldType
                ?? property?.types?.[0]?.fieldType
                ?? "EXPRESSION") as string,
            optional: required !== undefined ? !required : (property?.optional ?? false),
            editable: property?.editable !== false,
            enabled: true,
            documentation: property?.metadata?.description || "",
            value: value ?? "",
            placeholder: property?.placeholder,
            diagnostics: [],
            types: property?.types,
            metadata: property?.metadata,
            codedata: property?.codedata as any,
            imports: property?.imports,
        }) as FormField, [property, value, required]);

        // ----- diagnostics -----
        // Two independent producers feed the parent's save gate: compiler diagnostics for the
        // expression (async, below) and the connector's `validations[]` rules (synchronous). The
        // shared editor renders the rule failures itself, but it keeps them in its own store — the
        // parent never sees them — so they are re-evaluated here and merged into what we publish.
        const [lsDiagnostics, setLsDiagnostics] = useState<Diagnostic[]>([]);

        const clientDiagnostics: Diagnostic[] = useMemo(() => evaluateClientRules(field, watchedValue)
            .filter((failure) => failure.severity === "ERROR")
            .map((failure) => ({ message: failure.message, severity: 1 } as unknown as Diagnostic)),
            [field, watchedValue]);

        const mergedDiagnostics = useMemo(
            () => [...lsDiagnostics, ...clientDiagnostics],
            [lsDiagnostics, clientDiagnostics]
        );

        // Publish the merged view and mirror it into react-hook-form, so the field reads as invalid
        // and the handler form's Save button stays blocked while any rule fails.
        useEffect(() => {
            diagnosticsRef.current = mergedDiagnostics;
            onDiagnosticsChangeRef.current?.(mergedDiagnostics);
            if (mergedDiagnostics.length === 0) {
                clearErrors(FIELD_KEY);
            } else {
                setError(FIELD_KEY, {
                    type: "validate",
                    message: mergedDiagnostics.map((d) => d.message).join("\n"),
                });
            }
        }, [mergedDiagnostics, clearErrors, setError]);

        const applyDiagnostics = useCallback((diagnostics: Diagnostic[]) => {
            setLsDiagnostics(diagnostics);
        }, []);

        const runDiagnostics = useCallback(async (expression: string, property?: ExpressionProperty): Promise<Diagnostic[]> => {
            if (!rpcClient || !filePath) {
                onValidationStateChangeRef.current?.({ isValidating: false });
                return [];
            }
            try {
                const response = await rpcClient.getBIDiagramRpcClient().getExpressionDiagnostics({
                    filePath,
                    context: {
                        expression,
                        startLine: effectiveTargetLineRange.startLine,
                        lineOffset: 0,
                        offset: 0,
                        codedata: undefined,
                        property,
                    } as any,
                });
                const result = removeDuplicateDiagnostics(response.diagnostics || []);
                applyDiagnostics(result);
                return result;
            } catch (error) {
                // Silently ignore LS failures during typing; the save gate re-runs via revalidate().
                console.error(">>> Error getting annotation expression diagnostics", error);
                applyDiagnostics([]);
                return [];
            } finally {
                onValidationStateChangeRef.current?.({ isValidating: false });
            }
        }, [rpcClient, filePath, effectiveTargetLineRange, applyDiagnostics]);

        const debouncedDiagnostics = useMemo(
            () => debounce((showDiagnostics: boolean, expression: string, _key: string, property: ExpressionProperty) => {
                if (!showDiagnostics) {
                    applyDiagnostics([]);
                    onValidationStateChangeRef.current?.({ isValidating: false });
                    return;
                }
                onValidationStateChangeRef.current?.({ isValidating: true });
                void runDiagnostics(expression, property);
            }, 250),
            [runDiagnostics, applyDiagnostics]
        );
        useEffect(() => () => debouncedDiagnostics.cancel(), [debouncedDiagnostics]);

        // ----- completions -----
        const debouncedRetrieveCompletions = useMemo(
            () => debounce(async (expression: string, property: ExpressionProperty, offset: number, triggerCharacter?: string) => {
                if (!rpcClient || !filePath) {
                    setCompletions([]);
                    setFilteredCompletions([]);
                    return;
                }
                try {
                    let expressionCompletions: CompletionItem[] = [];
                    const { parentContent, currentContent } = expression
                        .slice(0, offset)
                        .match(EXPRESSION_EXTRACTION_REGEX)?.groups ?? {};
                    const currentContentLower = (currentContent ?? "").toLowerCase();

                    if (completions.length > 0 && !triggerCharacter && parentContent === prevCompletionFetchText.current) {
                        expressionCompletions = completions
                            .filter((c) => c.label.toLowerCase().includes(currentContentLower))
                            .sort((a, b) => a.sortText.localeCompare(b.sortText));
                    } else {
                        const { lineOffset, charOffset } = calculateExpressionOffsets(expression, offset);
                        const response = await rpcClient.getBIDiagramRpcClient().getExpressionCompletions({
                            filePath,
                            context: {
                                expression,
                                startLine: effectiveTargetLineRange.startLine,
                                lineOffset,
                                offset: charOffset,
                                codedata: undefined,
                                property,
                            },
                            completionContext: {
                                triggerKind: triggerCharacter ? 2 : 1,
                                triggerCharacter: triggerCharacter as TriggerCharacter,
                            },
                        } as any);

                        const converted: CompletionItem[] = [];
                        response?.forEach((completion: any) => {
                            if (completion.detail) {
                                converted.push(convertBalCompletion(completion));
                            }
                        });
                        setCompletions(converted);
                        expressionCompletions = triggerCharacter
                            ? converted
                            : converted
                                .filter((c) => c.label.toLowerCase().includes(currentContentLower))
                                .sort((a, b) => a.sortText.localeCompare(b.sortText));
                    }
                    prevCompletionFetchText.current = parentContent ?? "";
                    setFilteredCompletions(expressionCompletions);
                } catch (error) {
                    console.error(">>> Error getting annotation expression completions", error);
                    setCompletions([]);
                    setFilteredCompletions([]);
                }
            }, 250),
            [rpcClient, completions, filePath, effectiveTargetLineRange]
        );
        useEffect(() => () => debouncedRetrieveCompletions.cancel(), [debouncedRetrieveCompletions]);

        const handleRetrieveCompletions = useCallback(async (
            expression: string, property: ExpressionProperty, offset: number, triggerCharacter?: string
        ) => {
            await debouncedRetrieveCompletions(expression, property, offset, triggerCharacter);
            if (triggerCharacter) {
                await debouncedRetrieveCompletions.flush();
            }
        }, [debouncedRetrieveCompletions]);

        // ----- helper pane -----
        const handleGetHelperPane = useCallback((
            _fieldKey: string,
            _exprRef: any,
            anchorRef: any,
            _placeholder: string,
            currentValue: string,
            onHelperChange: (value: string, options?: any) => void,
            _changeHelperPaneState: (isOpen: boolean) => void,
            helperPaneHeight: any,
        ) => {
            if (!filePath) {
                return null;
            }
            return getHelperPaneNew({
                fieldKey: id ?? FIELD_KEY,
                fileName: filePath,
                targetLineRange: effectiveTargetLineRange,
                anchorRef,
                onClose: () => { },
                defaultValue: "",
                currentValue,
                onChange: onHelperChange,
                helperPaneHeight,
                recordTypeField: undefined,
                updateImports: () => { },
                completions: filteredCompletions,
                filteredCompletions,
                isInModal: true,
                types: property?.types as any,
                handleRetrieveCompletions,
            } as any);
        }, [filePath, id, effectiveTargetLineRange, filteredCompletions, property, handleRetrieveCompletions]);

        // ----- expression editor RPC bundle -----
        const expressionEditor = useMemo(() => ({
            completions: filteredCompletions,
            triggerCharacters: TRIGGER_CHARACTERS,
            retrieveCompletions: handleRetrieveCompletions,
            getExpressionEditorDiagnostics: debouncedDiagnostics,
            getHelperPane: handleGetHelperPane,
            rpcManager: {
                getExpressionTokens: (expression: string, fileName: string, position: any) =>
                    rpcClient.getBIDiagramRpcClient().getExpressionTokens({ expression, filePath: fileName, position }),
            },
            onCompletionItemSelect: () => { },
            onFocus: () => { },
            onBlur: () => { },
            onCancel: () => {
                setCompletions([]);
                setFilteredCompletions([]);
            },
        }) as unknown as FormExpressionEditorProps, [
            filteredCompletions, handleRetrieveCompletions, debouncedDiagnostics, handleGetHelperPane, rpcClient,
        ]);

        const formContextValue = useMemo(() => ({
            form: {
                control, getValues, setValue, watch, register, unregister, setError, clearErrors,
                formState: { isValidating: formState.isValidating, errors: formState.errors },
            },
            expressionEditor,
            targetLineRange: effectiveTargetLineRange,
            fileName: filePath ?? "",
            popupManager: { addPopup: () => { }, removeLastPopup: () => { }, closePopup: () => { } },
            nodeInfo: { kind: "FUNCTION" as any },
        }), [control, getValues, setValue, watch, register, unregister, setError, clearErrors,
            formState.isValidating, formState.errors, expressionEditor, effectiveTargetLineRange, filePath]);

        // Re-validate on mode switch (FieldFactory calls this) using the value it hands back.
        const handleFormValidation = useCallback(async (formData?: FormValues): Promise<boolean> => {
            const current = (formData?.[FIELD_KEY] ?? getValues(FIELD_KEY) ?? "") as string;
            if (!String(current).trim()) {
                applyDiagnostics([]);
                return true;
            }
            const diagnostics = await runDiagnostics(String(current));
            return !diagnostics.some((d) => d.severity === 1);
        }, [getValues, runDiagnostics, applyDiagnostics]);

        useImperativeHandle(ref, () => ({
            revalidate: async () => {
                debouncedDiagnostics.cancel();
                const current = String(getValues(FIELD_KEY) ?? "");
                // The rule failures are derived synchronously from the current value, so they hold
                // even when the value is empty and no compiler check is worth issuing.
                const ruleFailures = evaluateClientRules(field, current)
                    .filter((failure) => failure.severity === "ERROR")
                    .map((failure) => ({ message: failure.message, severity: 1 } as unknown as Diagnostic));
                if (!current.trim()) {
                    applyDiagnostics([]);
                    onValidationStateChangeRef.current?.({ isValidating: false });
                    return ruleFailures;
                }
                return [...(await runDiagnostics(current)), ...ruleFailures];
            },
        }), [debouncedDiagnostics, getValues, runDiagnostics, applyDiagnostics, field]);

        return (
            // The shared editor keeps its live rule failures in a diagnostics store and reads them
            // back through it — without this provider the store is absent, every lookup yields
            // nothing, and the messages never render.
            <DiagnosticsStoreProvider>
                <FormContextProvider {...(formContextValue as any)}>
                    <FieldFactory
                        field={field}
                        autoFocus={false}
                        handleFormValidation={handleFormValidation}
                    />
                </FormContextProvider>
            </DiagnosticsStoreProvider>
        );
    }
);

AnnotationExpressionField.displayName = "AnnotationExpressionField";
