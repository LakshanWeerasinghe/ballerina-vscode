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
import ButtonCard from "../../../../../components/ButtonCard";

import { EditorContentColumn } from "../../styles";
import { catalogFunctionsOf, handlerGroupId } from "./payloadComposer";

interface TriggerHandlerConfigFormProps {
    serviceModel: ServiceModel;
    isSaving: boolean;
    onSubmit: (group: string) => void;
    onBack?: () => void;
}

interface HandlerGroup {
    id: string;
    label: string;
    description: string;
}

/**
 * The add-handler catalog of a schema-driven trigger: one card per handler group (a group's
 * functions are its format variants). The language server ships the still-addable variants in the
 * service's `schemaFunctions` — consumed ones are already removed — so every catalog group is
 * offerable. Fully driven by the wire model's `group`/`addLabel` fields, no per-connector code.
 */
export function TriggerHandlerConfigForm(props: TriggerHandlerConfigFormProps) {
    const { serviceModel, isSaving, onSubmit } = props;

    const handlerGroups: HandlerGroup[] = React.useMemo(() => {
        const groups = new Map<string, HandlerGroup>();
        for (const fn of catalogFunctionsOf(serviceModel) as FunctionModel[]) {
            const id = handlerGroupId(fn);
            if (!id || groups.has(id)) {
                continue;
            }
            groups.set(id, {
                id,
                label: fn.metadata?.label || id,
                description: fn.metadata?.description || "",
            });
        }
        return Array.from(groups.values());
    }, [serviceModel]);

    return (
        <SidePanelBody>
            <EditorContentColumn>
                {handlerGroups.map((group, index) => (
                    <ButtonCard
                        key={group.id}
                        id={`handler-group-card-${index}`}
                        title={group.label}
                        tooltip={group.description}
                        onClick={() => onSubmit(group.id)}
                        disabled={isSaving}
                    />
                ))}
                {handlerGroups.length === 0 && <div>No handlers available to add.</div>}
            </EditorContentColumn>
        </SidePanelBody>
    );
}

export default TriggerHandlerConfigForm;
