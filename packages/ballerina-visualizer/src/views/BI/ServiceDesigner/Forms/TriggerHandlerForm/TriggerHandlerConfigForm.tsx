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

import React from "react";
import { SidePanelBody } from "@wso2/ui-toolkit";
import { FunctionModel, ServiceModel } from "@wso2/ballerina-core";
import { cloneDeep } from "lodash";
import ButtonCard from "../../../../../components/ButtonCard";

import { EditorContentColumn } from "../../styles";
import { addableCatalogOf, handlerGroupId, hasConfigurableFields } from "./payloadComposer";

interface TriggerHandlerConfigFormProps {
    serviceModel: ServiceModel;
    isSaving: boolean;
    onSubmit: (group: string) => void;
    /** Adds a handler straight away, bypassing the config form (nothing in it to configure). */
    onQuickAdd: (functionModel: FunctionModel) => void;
    onBack?: () => void;
}

interface HandlerGroup {
    id: string;
    label: string;
    description: string;
    /** False when the group has exactly one variant and nothing configurable on it. */
    needsForm: boolean;
    /** The variant to add directly when {@code needsForm} is false. */
    quickAddFunction?: FunctionModel;
}

/**
 * The add-handler catalog of a schema-driven trigger: one card per handler group (a group's
 * functions are its format variants). The language server ships the still-addable variants in the
 * service's `schemaFunctions` — consumed ones are already removed — so every catalog group is
 * offerable. Fully driven by the wire model's `group`/`addLabel` fields, no per-connector code.
 *
 * A group with a single variant and nothing configurable on it (e.g. kafka's `onError`, whose only
 * parameter is a fixed, non-editable error) is added straight away instead of opening an empty form.
 */
export function TriggerHandlerConfigForm(props: TriggerHandlerConfigFormProps) {
    const { serviceModel, isSaving, onSubmit, onQuickAdd } = props;

    const handlerGroups: HandlerGroup[] = React.useMemo(() => {
        const catalog = addableCatalogOf(serviceModel) as FunctionModel[];
        const groups = new Map<string, HandlerGroup>();
        const membersByGroup = new Map<string, FunctionModel[]>();
        for (const fn of catalog) {
            const id = handlerGroupId(fn);
            if (!id) {
                continue;
            }
            if (!groups.has(id)) {
                groups.set(id, {
                    id,
                    label: fn.metadata?.label || id,
                    description: fn.metadata?.description || "",
                    needsForm: true,
                });
            }
            if (!membersByGroup.has(id)) {
                membersByGroup.set(id, []);
            }
            membersByGroup.get(id).push(fn);
        }
        for (const group of groups.values()) {
            const members = membersByGroup.get(group.id) ?? [];
            group.needsForm = members.length > 1 || members.some(hasConfigurableFields);
            if (!group.needsForm) {
                group.quickAddFunction = members[0];
            }
        }
        return Array.from(groups.values());
    }, [serviceModel]);

    const handleCardClick = (group: HandlerGroup) => {
        if (group.needsForm) {
            onSubmit(group.id);
            return;
        }
        onQuickAdd({ ...cloneDeep(group.quickAddFunction), enabled: true });
    };

    return (
        <SidePanelBody>
            <EditorContentColumn>
                {handlerGroups.map((group, index) => (
                    <ButtonCard
                        key={group.id}
                        id={`handler-group-card-${index}`}
                        title={group.label}
                        tooltip={group.description}
                        onClick={() => handleCardClick(group)}
                        disabled={isSaving}
                    />
                ))}
                {handlerGroups.length === 0 && <div>No handlers available to add.</div>}
            </EditorContentColumn>
        </SidePanelBody>
    );
}

export default TriggerHandlerConfigForm;
