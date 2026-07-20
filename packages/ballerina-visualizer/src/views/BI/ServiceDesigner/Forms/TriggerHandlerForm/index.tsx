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

import { Fragment, useCallback, useEffect, useMemo, useRef, useState } from "react";
import styled from "@emotion/styled";
import {
    ActionButtons,
    CheckBox,
    CheckBoxGroup,
    Codicon,
    Divider,
    Dropdown,
    LinkButton,
    ProgressIndicator,
    SidePanelBody,
    Tooltip,
    Typography,
} from "@wso2/ui-toolkit";
import {
    Diagnostic,
    FunctionModel,
    GeneralPayloadContext,
    Imports,
    ParameterModel,
    PropertyModel,
    ServiceModel,
    Type,
} from "@wso2/ballerina-core";
import { cloneDeep } from "lodash";
import WarningPopup from "@wso2/ballerina-side-panel/lib/components/WarningPopup";

import { EntryPointTypeCreator } from "../../../../../components/EntryPointTypeCreator";
import { Parameters } from "../FileIntegrationForm/Parameters/Parameters";
import { TextExpressionFieldHandle } from "../FileIntegrationForm/TextExpressionField";
import { AnnotationConfigSection } from "./AnnotationConfigSection";
import {
    CODEDATA_COMPLEX_ANNOTATION,
    CODEDATA_METADATA_FLAG,
    CODEDATA_PAYLOAD_MODIFIER,
    CODEDATA_PAYLOAD_TYPE_INCLUDED_RECORD,
    catalogFunctionsOf,
    composePayloadType,
    functionSignatureKey,
    handlerGroupId,
    hasDefaultPayload,
    isModifierActive,
    isPayloadParameter,
    payloadParametersOf,
    propertiesOfRole,
} from "./payloadComposer";

const SIGNATURE_CHANGE_BODY_WARNING =
    "This edit will change the handler signature. Nodes in the function body may be broken due to this change. Continue?";

const EditorContentColumn = styled.div`
    display: flex;
    flex-direction: column;
    justify-content: space-between;
    padding-bottom: 20px;
    gap: 10px;
`;

const InfoBanner = styled.div`
    display: flex;
    gap: 8px;
    padding: 8px 12px;
    border-left: 3px solid var(--vscode-focusBorder);
    background: var(--vscode-inputValidation-infoBackground);
    border-radius: 4px;
    align-items: flex-start;
`;

const FlagsColumn = styled.div`
    margin-top: 12px;
    display: flex;
    flex-direction: column;
    gap: 12px;
`;

const AddButtonWrapper = styled.div`
    margin: 16px 0 8px;
`;

const CollapsibleHeader = styled.div`
    display: flex;
    align-items: center;
    padding: 8px 0;
    cursor: pointer;
    user-select: none;
    &:hover {
        opacity: 0.8;
    }
`;

const CollapsibleContent = styled.div<{ isExpanded: boolean }>`
    display: ${({ isExpanded }: { isExpanded: boolean }) => (isExpanded ? "block" : "none")};
    padding-left: 8px;
    margin-top: 8px;
`;

export interface TriggerHandlerFormProps {
    functionModel?: FunctionModel;
    serviceModel: ServiceModel;
    isSaving: boolean;
    onSave: (functionModel: FunctionModel, openDiagram?: boolean) => void;
    onClose: () => void;
    isNew?: boolean;
    filePath?: string;
    /** The handler group being added (set by the catalog picker in add mode). */
    selectedGroup?: string;
}

/**
 * Generic add/edit form for a schema-driven trigger handler (unified TriggerModel wire shape) — the
 * connector-agnostic counterpart of the FTP-specific FileIntegrationForm. Every section is driven by
 * schema markers rather than connector names:
 *
 * - variant selection — sibling functions sharing `group`, labelled by `variantLabel`;
 * - read-only markers — properties with codedata.type METADATA_FLAG;
 * - stream/modifier toggles — properties with codedata.type PAYLOAD_MODIFIER, recomposing the
 *   payload type through the templates the connector shipped;
 * - payload schema — the DATA_BINDING parameter (codedata.bindable), bound via the type creator;
 * - opt-in framework params — parameters marked `advanced` (caller and friends);
 * - function annotations — properties with codedata.type COMPLEX_FUNCTION_ANNOTATION.
 */
export function TriggerHandlerForm(props: TriggerHandlerFormProps) {
    const { serviceModel, isSaving, onSave, onClose, isNew, selectedGroup } = props;

    const [functionModel, setFunctionModel] = useState<FunctionModel | null>(null);
    // The payload param (by name) the type-creator modal is open for — a handler can expose several
    // bindable payloads (e.g. CDC onUpdate's before/after), so we track which one is being defined.
    const [typeEditorParamName, setTypeEditorParamName] = useState<string | null>(null);
    const [isSignatureWarningOpen, setIsSignatureWarningOpen] = useState<boolean>(false);
    const [isAdvancedExpanded, setIsAdvancedExpanded] = useState<boolean>(false);
    const initialSignatureKeyRef = useRef<string | null>(null);

    const groupId = selectedGroup ?? (props.functionModel ? handlerGroupId(props.functionModel) : undefined);

    // Still-addable sibling variants of this handler group (e.g. CSV/JSON/XML of onFileChange),
    // from the service's addable catalog — the language server already removed consumed variants.
    const addableVariants = useMemo(() => {
        if (!groupId) {
            return [];
        }
        return catalogFunctionsOf(serviceModel).filter((fn) => handlerGroupId(fn) === groupId);
    }, [serviceModel, groupId]);

    // Add mode starts from the group's first addable variant; edit mode from the passed model.
    useEffect(() => {
        if (isNew) {
            const initial = props.functionModel ?? addableVariants[0];
            setFunctionModel(initial ? cloneDeep(initial) : null);
            initialSignatureKeyRef.current = null;
        } else {
            setFunctionModel(props.functionModel ? cloneDeep(props.functionModel) : null);
            initialSignatureKeyRef.current = props.functionModel
                ? functionSignatureKey(props.functionModel) : null;
        }
    }, [isNew, props.functionModel, addableVariants]);

    // ----- variant selection -----

    // Add mode: a real choice needs more than one addable variant. Edit mode: an enriched source
    // handler shows its (fixed) variant for context whenever it carries one.
    const hasVariants = isNew ? addableVariants.length > 1 : !!functionModel?.variantLabel;
    const selectedVariantLabel = functionModel?.variantLabel ?? functionModel?.name?.metadata?.label ?? "";

    const handleVariantChange = (label: string) => {
        const variant = addableVariants.find(
            (fn) => (fn.variantLabel ?? fn.name?.metadata?.label) === label
        );
        if (variant) {
            setFunctionModel(cloneDeep(variant));
        }
    };

    // ----- composition flags -----

    const metadataFlags = functionModel ? propertiesOfRole(functionModel, CODEDATA_METADATA_FLAG) : [];
    const modifierFlags = functionModel ? propertiesOfRole(functionModel, CODEDATA_PAYLOAD_MODIFIER) : [];
    // All bindable payload params — a handler may expose more than one (e.g. CDC onUpdate's
    // before/after), each configured independently below.
    const payloadParams = functionModel ? payloadParametersOf(functionModel) : [];
    // The first payload param still drives shared UI bits (e.g. the variant dropdown's label).
    const payloadParam = payloadParams[0];

    /** Recomposes every payload param's rendered type after a modifier/schema change. */
    const withRecomposedPayload = (fn: FunctionModel): FunctionModel => {
        const parameters = fn.parameters.map((p) =>
            isPayloadParameter(p) && p.type?.codedata
                ? { ...p, type: { ...p.type, value: composePayloadType(fn, p) } }
                : p
        );
        return { ...fn, parameters };
    };

    const handleModifierToggle = (key: string, prop: PropertyModel, checked: boolean) => {
        if (!functionModel) {
            return;
        }
        // Modifier flags store their checked state in `value` — "true"/"false" strings, accepted
        // alongside booleans by both this form's composer and the language server's.
        const updated: FunctionModel = {
            ...functionModel,
            properties: { ...functionModel.properties, [key]: { ...prop, value: String(checked) } },
        };
        setFunctionModel(withRecomposedPayload(updated));
    };

    // ----- payload schema binding -----

    const labelOfPayload = (param?: ParameterModel) => param?.metadata?.label || "Content Schema";
    // When a handler exposes more than one payload (e.g. CDC onUpdate's before/after), the shared
    // metadata label ("Database Entry") no longer tells them apart — append the param's own name
    // (the identifier used in the generated signature, e.g. `before`) so the user can tell which is
    // which. A single-payload handler keeps the plain label.
    const displayLabelOf = (param?: ParameterModel) => {
        const base = labelOfPayload(param);
        return payloadParams.length > 1 && param?.name?.value ? `${base} (${param.name.value})` : base;
    };
    // The payload param the type-creator modal is currently open for (by name).
    const typeEditorParam = payloadParams.find((p) => p.name?.value === typeEditorParamName);
    // An included-record databind (e.g. kafka's message shape) defaults the type creator to the
    // import tab — the schema's payload format is sample-driven (JSON) rather than built by hand.
    const typeCreatorDefaultTab =
        typeEditorParam?.type?.codedata?.type === CODEDATA_PAYLOAD_TYPE_INCLUDED_RECORD
            ? "import"
            : "create-from-scratch";

    const handleTypeCreated = (type: Type | string, imports?: Imports) => {
        const targetName = typeEditorParamName;
        setTypeEditorParamName(null);
        if (!functionModel || !targetName) {
            return;
        }
        const typeName = typeof type === "string" ? type : type.name;
        // The parameter keeps its schema-shipped name — only the bound shape changes.
        const parameters = functionModel.parameters.map((p) => {
            if (!isPayloadParameter(p) || p.name?.value !== targetName) {
                return p;
            }
            const updatedType: PropertyModel = {
                ...p.type,
                codedata: { ...p.type.codedata, boundType: typeName },
            };
            if (imports) {
                updatedType.imports = imports;
            }
            return { ...p, type: updatedType, enabled: true };
        });
        setFunctionModel(withRecomposedPayload({ ...functionModel, parameters }));
    };

    const handleDeletePayloadSchema = (target: ParameterModel) => {
        if (!functionModel) {
            return;
        }
        const parameters = functionModel.parameters.map((p) =>
            isPayloadParameter(p) && p.name?.value === target.name?.value
                ? { ...p, type: { ...p.type, codedata: { ...p.type.codedata, boundType: undefined } } }
                : p
        );
        setFunctionModel(withRecomposedPayload({ ...functionModel, parameters }));
    };

    // ----- opt-in framework params (advanced) -----

    const advancedParameters = functionModel?.parameters?.filter((p) => p.advanced === true) ?? [];

    const handleAdvancedParamToggle = (param: ParameterModel, checked: boolean) => {
        if (!functionModel) {
            return;
        }
        const parameters = functionModel.parameters.map((p) => (p === param ? { ...p, enabled: checked } : p));
        setFunctionModel({ ...functionModel, parameters });
    };

    // ----- annotations -----

    const annotations = functionModel ? propertiesOfRole(functionModel, CODEDATA_COMPLEX_ANNOTATION) : [];

    const handleAnnotationChange = (annotationKey: string, updated: PropertyModel) => {
        setFunctionModel((prev) => prev
            ? { ...prev, properties: { ...prev.properties, [annotationKey]: updated } }
            : prev);
    };

    // ----- expression diagnostics (annotation leaves) -----

    const fieldRefs = useRef<Record<string, TextExpressionFieldHandle | null>>({});
    const [diagnosticsByField, setDiagnosticsByField] = useState<Record<string, Diagnostic[]>>({});
    const [validationStateByField, setValidationStateByField] = useState<Record<string, { isValidating: boolean }>>({});

    useEffect(() => {
        setDiagnosticsByField({});
        setValidationStateByField({});
        fieldRefs.current = {};
    }, [functionModel?.name?.value]);

    const registerFieldRef = useCallback((key: string, handle: TextExpressionFieldHandle | null) => {
        if (handle) {
            fieldRefs.current[key] = handle;
        } else {
            delete fieldRefs.current[key];
        }
    }, []);

    const handleFieldDiagnostics = useCallback((key: string, diagnostics: Diagnostic[]) => {
        setDiagnosticsByField((prev) => ({ ...prev, [key]: diagnostics }));
    }, []);

    const handleFieldValidationState = useCallback((key: string, state: { isValidating: boolean }) => {
        setValidationStateByField((prev) => ({ ...prev, [key]: state }));
    }, []);

    const hasErrorDiagnostics = useMemo(
        () => Object.values(diagnosticsByField).some((diags) => diags?.some((d) => d.severity === 1)),
        [diagnosticsByField]
    );
    const hasPendingValidation = useMemo(
        () => Object.values(validationStateByField).some((s) => s?.isValidating),
        [validationStateByField]
    );

    // ----- save -----

    const hasSignatureChanged = (): boolean => {
        if (isNew || !functionModel || !initialSignatureKeyRef.current) {
            return false;
        }
        return functionSignatureKey(functionModel) !== initialSignatureKeyRef.current;
    };

    const handleSave = async () => {
        if (!functionModel) {
            return;
        }
        // Save-time revalidation of annotation expression fields — the authoritative gate, since
        // typing-time diagnostics are debounced and swallow LS errors silently.
        const liveRefs = Object.values(fieldRefs.current).filter(
            (handle): handle is TextExpressionFieldHandle => handle !== null && handle !== undefined
        );
        if (liveRefs.length > 0) {
            const allDiagnostics = await Promise.all(liveRefs.map((h) => h.revalidate()));
            if (allDiagnostics.some((diags) => diags.some((d) => d.severity === 1))) {
                return;
            }
        }
        if (hasSignatureChanged()) {
            setIsSignatureWarningOpen(true);
            return;
        }
        onSave({ ...functionModel, enabled: true }, isNew);
    };

    const confirmSignatureChangeSave = () => {
        setIsSignatureWarningOpen(false);
        if (functionModel) {
            onSave({ ...functionModel, enabled: true }, isNew);
        }
    };

    const isSaveDisabled = hasErrorDiagnostics || hasPendingValidation;
    const saveTooltip = useMemo(() => {
        if (isSaving) {
            return "Saving...";
        }
        if (hasPendingValidation) {
            return "Waiting for expression diagnostics...";
        }
        if (isSaveDisabled) {
            return "Fix validation errors";
        }
        return "Save";
    }, [isSaveDisabled, isSaving, hasPendingValidation]);

    const payloadContext: GeneralPayloadContext = {
        protocol: serviceModel.listenerProtocol || serviceModel.moduleName,
        filterType: functionModel?.metadata?.label || "",
    };

    if (!functionModel) {
        return null;
    }

    const infoBannerText = functionModel.metadata?.notice;
    const showAnnotationsDivider = hasVariants || metadataFlags.length > 0 || modifierFlags.length > 0;

    return (
        <>
            {isSaving && <ProgressIndicator id="trigger-handler-form-loading-bar" />}
            <SidePanelBody>
                <EditorContentColumn>
                    {infoBannerText && (
                        <InfoBanner>
                            <Codicon name="info" sx={{ marginTop: 2 }} />
                            <Typography variant="body3" sx={{ color: "var(--vscode-foreground)" }}>
                                {infoBannerText}
                            </Typography>
                        </InfoBanner>
                    )}

                    {/* Variant selection — sibling functions of the same group */}
                    {hasVariants && (
                        <Dropdown
                            id="trigger-handler-variant"
                            label={payloadParam?.type?.metadata?.label ? "Format" : "Variant"}
                            items={isNew
                                ? addableVariants.map((fn) => ({
                                    value: fn.variantLabel ?? fn.name?.metadata?.label ?? fn.name?.value ?? "",
                                }))
                                : [{ value: selectedVariantLabel }]}
                            value={selectedVariantLabel}
                            onValueChange={handleVariantChange}
                            disabled={!isNew}
                        />
                    )}

                    {/* Read-only markers + modifier toggles (e.g. Rows, Stream) */}
                    {(metadataFlags.length > 0 || modifierFlags.length > 0) && (
                        <FlagsColumn>
                            {metadataFlags.map(([key, prop]) => (
                                <CheckBoxGroup key={key} direction="vertical">
                                    <CheckBox
                                        label={prop.metadata?.label ?? key}
                                        checked={true}
                                        disabled={true}
                                        onChange={() => { }}
                                        sx={{ description: prop.metadata?.description ?? "" }}
                                    />
                                </CheckBoxGroup>
                            ))}
                            {modifierFlags.map(([key, prop]) => (
                                <CheckBoxGroup key={key} direction="vertical">
                                    <CheckBox
                                        label={prop.metadata?.label ?? key}
                                        checked={isModifierActive(prop)}
                                        disabled={prop.editable === false}
                                        onChange={(checked) => handleModifierToggle(key, prop, checked)}
                                        sx={{ description: prop.metadata?.description ?? "" }}
                                    />
                                </CheckBoxGroup>
                            ))}
                        </FlagsColumn>
                    )}

                    {/* Payload schema — one section per bindable DATA_BINDING param (a handler such
                        as CDC onUpdate exposes both a before- and an after-image). */}
                    {payloadParams
                        .filter((param) => param.type?.codedata?.bindable === true)
                        .map((param) => {
                            const label = displayLabelOf(param);
                            return (
                                <Fragment key={param.name?.value ?? label}>
                                    {hasDefaultPayload(param) ? (
                                        <AddButtonWrapper>
                                            <Tooltip
                                                content={`Define ${label} for easier access in the flow diagram`}
                                                position="bottom"
                                            >
                                                <LinkButton onClick={() => setTypeEditorParamName(param.name?.value ?? null)}>
                                                    <Codicon name="add" />
                                                    Define {label}
                                                </LinkButton>
                                            </Tooltip>
                                        </AddButtonWrapper>
                                    ) : (
                                        <div style={{ marginTop: 16 }}>
                                            <Typography variant="body2" sx={{ marginBottom: 8 }}>
                                                {label}
                                            </Typography>
                                            {/* The card presents the bound shape only (no name, no array/wrapper
                                                composition); an edit flows back as the new bound element and the
                                                stored composed type is derived from it. A bindable payload is
                                                always editable, so force it on regardless of the shipped flag. */}
                                            <Parameters
                                                parameters={[{
                                                    ...param,
                                                    editable: true,
                                                    type: {
                                                        ...param.type,
                                                        value: param.type?.codedata?.boundType
                                                            || param.type?.value,
                                                    },
                                                }]}
                                                hideName={true}
                                                onChange={(edited) => {
                                                    if (edited.length === 0) {
                                                        handleDeletePayloadSchema(param);
                                                        return;
                                                    }
                                                    const [editedPayload] = edited;
                                                    const editedElement = editedPayload.type?.value ?? "";
                                                    const parameters = functionModel.parameters.map((p) =>
                                                        isPayloadParameter(p) && p.name?.value === param.name?.value
                                                            ? {
                                                                ...p,
                                                                type: {
                                                                    ...p.type,
                                                                    imports: editedPayload.type?.imports ?? p.type?.imports,
                                                                    codedata: {
                                                                        ...p.type?.codedata,
                                                                        boundType: editedElement,
                                                                    },
                                                                },
                                                                enabled: true,
                                                            }
                                                            : p
                                                    );
                                                    setFunctionModel(
                                                        withRecomposedPayload({ ...functionModel, parameters }));
                                                }}
                                                showPayload={true}
                                                typeLabel={label}
                                            />
                                        </div>
                                    )}
                                </Fragment>
                            );
                        })}

                    {/* Function annotations — schema-shipped granular trees */}
                    {annotations.length > 0 && (
                        <>
                            {showAnnotationsDivider && <Divider />}
                            {annotations.map(([key, annotation]) => (
                                <AnnotationConfigSection
                                    key={`${functionModel.name?.value}-${key}`}
                                    annotationKey={key}
                                    annotation={annotation}
                                    filePath={props.filePath}
                                    targetLineRange={functionModel.codedata?.lineRange}
                                    disabled={isSaving}
                                    onChange={handleAnnotationChange}
                                    registerFieldRef={registerFieldRef}
                                    onDiagnosticsChange={handleFieldDiagnostics}
                                    onValidationStateChange={handleFieldValidationState}
                                />
                            ))}
                        </>
                    )}

                    {/* Opt-in framework params (caller and friends) */}
                    {advancedParameters.length > 0 && (
                        <>
                            <Divider />
                            <CollapsibleHeader onClick={() => setIsAdvancedExpanded(!isAdvancedExpanded)}>
                                <Codicon
                                    name={isAdvancedExpanded ? "chevron-down" : "chevron-right"}
                                    sx={{ marginRight: 4 }}
                                />
                                <Typography variant="body2">Advanced Parameters</Typography>
                            </CollapsibleHeader>
                            <CollapsibleContent isExpanded={isAdvancedExpanded}>
                                {advancedParameters.map((param, index) => (
                                    <CheckBoxGroup key={param.name?.value || index} direction="vertical">
                                        <CheckBox
                                            label={param.metadata?.label}
                                            checked={param.enabled}
                                            onChange={(checked) => handleAdvancedParamToggle(param, checked)}
                                            sx={{
                                                marginTop: index === 0 ? 0 : 8,
                                                description: param.metadata?.description,
                                            }}
                                        />
                                    </CheckBoxGroup>
                                ))}
                            </CollapsibleContent>
                        </>
                    )}
                </EditorContentColumn>
                <ActionButtons
                    primaryButton={{
                        text: isSaving ? "Saving..." : "Save",
                        onClick: handleSave,
                        tooltip: saveTooltip,
                        disabled: isSaving || isSaveDisabled,
                        loading: isSaving,
                    }}
                    secondaryButton={{
                        text: "Cancel",
                        onClick: onClose,
                        tooltip: "Cancel",
                        disabled: isSaving,
                    }}
                    sx={{ justifyContent: "flex-end" }}
                />
            </SidePanelBody>

            <WarningPopup
                isOpen={isSignatureWarningOpen}
                onContinue={confirmSignatureChangeSave}
                onCancel={() => setIsSignatureWarningOpen(false)}
                message={SIGNATURE_CHANGE_BODY_WARNING}
            />

            <EntryPointTypeCreator
                isOpen={!!typeEditorParam}
                onClose={() => setTypeEditorParamName(null)}
                onTypeCreate={handleTypeCreated}
                initialTypeName={"Content"}
                modalTitle={`Define ${displayLabelOf(typeEditorParam)}`}
                payloadContext={payloadContext}
                defaultTab={typeCreatorDefaultTab}
                modalWidth={650}
                modalHeight={600}
            />
        </>
    );
}

export default TriggerHandlerForm;
