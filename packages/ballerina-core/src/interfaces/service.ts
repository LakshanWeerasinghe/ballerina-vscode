/**
 * Copyright (c) 2025, WSO2 LLC. (https://www.wso2.com) All Rights Reserved.
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

import { DiagnosticMessage, Imports, PropertyTypeMemberInfo, InputType } from "./bi";
import { LineRange } from "./common";


export type ListenerModel = {
    id: number;
    name: string;
    type: string;
    displayName: string;
    documentation: string;
    moduleName: string;
    orgName: string;
    version: string;
    packageName: string;
    listenerProtocol: string;
    icon: string;
    properties?: ConfigProperties;
};


export interface ServiceModel {
    id: number;
    name: string;
    type: string;
    displayName?: string;
    documentation?: string;
    moduleName: string;
    orgName: string;
    version: string;
    packageName: string;
    listenerProtocol: string;
    icon: string;
    properties?: ConfigProperties;
    functions?: FunctionModel[];
    // Schema-driven triggers (unified TriggerModel) split the handlers in two: `functions` holds
    // what exists in the user's source, `schemaFunctions` the connector-shipped addable catalog
    // (one entry per handler variant; consumed variants are removed by the language server).
    schemaFunctions?: FunctionModel[];
    codedata?: CodeData;
}

export interface ServiceClassModel { // for Ballerina Service Classes
    id: number;
    name: string;
    type: string;
    properties?: ConfigProperties;
    functions?: FunctionModel[];
    codedata?: CodeData;
    documentation?: PropertyModel;
    fields?: FieldType[];
}


export interface FieldType extends ParameterModel {
    codedata: CodeData;
    isPrivate: boolean;
    isFinal: boolean;
}

/**
 * How a schema-driven trigger handler may be added to a service, and how consuming one affects the
 * addable catalog (mirrors the language server's `Repeatable`):
 * - FALSE: single instance — adding it removes only that handler from the catalog.
 * - TRUE: may be added repeatedly — never leaves the catalog (pairs with a name-editable handler).
 * - ONE_OF_GROUP: mutually exclusive within its `group` — adding any member removes every sibling
 *   (e.g. RabbitMQ onMessage / onRequest).
 * - ONE_EACH_PER_GROUP: each member of the `group` may be added once — adding one removes only that
 *   member, siblings stay (e.g. FTP per-file-format handlers).
 */
export enum RepeatBehavior {
    FALSE = "FALSE",
    TRUE = "TRUE",
    ONE_OF_GROUP = "ONE_OF_GROUP",
    ONE_EACH_PER_GROUP = "ONE_EACH_PER_GROUP",
}

export interface FunctionModel {
    metadata?: MetaData;
    kind: "REMOTE" | "RESOURCE" | "QUERY" | "MUTATION" | "SUBSCRIPTION" | "DEFAULT" | "INIT";
    enabled: boolean;
    optional: boolean;
    editable: boolean;
    codedata?: CodeData;

    // Handler-catalog fields carried by schema-driven triggers (unified TriggerModel): functions
    // sharing a `group` are format variants of one logical handler (labelled by `variantLabel`,
    // offered under `addLabel`); `repeatable` says whether/how the handler may be added more than
    // once (see RepeatBehavior) and `nameEditable: false` locks the emitted function name to the
    // variant's.
    group?: string;
    variantLabel?: string;
    addLabel?: string;
    repeatable?: RepeatBehavior;
    nameEditable?: boolean;

    canAddParameters?: boolean;

    // accessor will be used by resource functions
    accessor?: PropertyModel;

    properties?: ConfigProperties;
    name: PropertyModel;
    parameters: ParameterModel[];
    schema?: ConfigProperties;
    returnType: ReturnTypeModel;
    documentation?: PropertyModel;
    qualifiers?: string[];
}


export interface ReturnTypeModel extends PropertyModel {
    responses?: StatusCodeResponse[];
    schema?: ConfigProperties;
}
export interface StatusCodeResponse extends PropertyModel {
    statusCode: PropertyModel;
    body: PropertyModel;
    name: PropertyModel;
    type: PropertyModel;
    headers: PropertyModel;
    mediaType: PropertyModel;
}

export enum Protocol {
    HTTP = "HTTP",
    MESSAGE_BROKER = "MESSAGE_BROKER",
    GRAPHQL = "GRAPHQL",
    FTP = "FTP",
    CDC = "CDC"
}

export interface HttpPayloadContext {
    protocol: Protocol.HTTP;
    serviceName: string;
    serviceBasePath: string;
    resourceBasePath?: string;
    resourceMethod?: string;
    resourceDocumentation?: string;
    paramDetails?: ParamDetails[];
}

export interface MessageQueuePayloadContext {
    protocol: Protocol.MESSAGE_BROKER | Protocol.CDC;
    serviceName: string;
    queueOrTopic?: string;
    messageDocumentation?: string;
}

export interface GeneralPayloadContext {
    protocol: Protocol | string;
    filterType?: string;
}

export type PayloadContext = HttpPayloadContext | MessageQueuePayloadContext | GeneralPayloadContext;

export interface ParamDetails {
    name: string;
    type: string;
    defaulValue?: string;
}

interface MetaData {
    label: string;
    description: string;
    notice?: string;
    groupNo?: number;
    groupName?: string;
    // A short category tag shown as a chip before the function name in the service designer
    // (e.g. "Event", "Tool", "GET", "FUNC", "INIT", or a trigger-specific value like "onCreate").
    // Optional; the designer falls back to "Event" for handlers when absent.
    badge?: string;
}

interface CodeData {
    lineRange?: LineRange;
    type?: string;
    argType?: string;
    originalName?: string;
    orgName?: string;
    packageName?: string;
    moduleName?: string;
    version?: string;
    position?: number;
    path?: string;
    valueQualifier?: string;
    // Payload-composition hints of schema-driven triggers: the rendered type is
    // template({{type}} -> boundType ?? defaultType), optionally superseded by an active
    // PAYLOAD_MODIFIER's own template (e.g. stream<{{type}}, error?>).
    template?: string;
    defaultType?: string;
    boundType?: string;
    bindable?: boolean;
    modifier?: string;
    targetParam?: string;
    // Annotation-tree hints (COMPLEX_FUNCTION_ANNOTATION -> MAPPING_FIELD leaves). A leaf's
    // rendered kind (e.g. string quoting) derives from the node's types[], not codedata.
    field?: string;
    optional?: boolean;
    // The literal an ENUM_LITERAL choice branch emits (qualified by valueQualifier).
    value?: string;
}

export interface PropertyModel {
    metadata?: MetaData;
    codedata?: CodeData;
    enabled?: boolean;
    editable?: boolean;
    isHttpResponseType?: boolean;
    value?: string;
    values?: string[];
    types?: InputType[];
    isType?: boolean;
    placeholder?: string;
    defaultValue?: string | PropertyModel;
    optional?: boolean;
    advanced?: boolean;
    items?: string[];
    choices?: PropertyModel[];
    properties?: ConfigProperties;
    addNewButton?: boolean;
    httpParamType?: "QUERY" | "HEADER" | "PAYLOAD";
    diagnostics?: DiagnosticMessage[];
    imports?: Imports;
    hidden?: boolean;
    isGraphqlId?: boolean;
}

export interface ParameterModel extends PropertyModel {
    kind?: "REQUIRED" | "OPTIONAL" | "DATA_BINDING";
    type?: PropertyModel;
    name?: PropertyModel;
    headerName?: PropertyModel;
    documentation?: PropertyModel;
}


export interface ConfigProperties {
    [key: string]: PropertyModel | ParameterModel;
}

export interface ServiceInitModel {
    id: string;
    displayName: string;
    description: string;
    orgName: string;
    packageName: string;
    moduleName: string;
    version: string;
    type: string;
    icon: string;
    properties: { [key: string]: PropertyModel };
}

