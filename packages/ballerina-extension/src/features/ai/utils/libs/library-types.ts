// Copyright (c) 2025, WSO2 LLC. (https://www.wso2.com/) All Rights Reserved.

// WSO2 LLC. licenses this file to you under the Apache License,
// Version 2.0 (the "License"); you may not use this file except
// in compliance with the License.
// You may obtain a copy of the License at

// http://www.apache.org/licenses/LICENSE-2.0

// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.

import { z } from 'zod';

export interface Type {
    name: string;
    links?: Link[];
}

export interface Link {
    category: Category;
    recordName: string;
    libraryName?: string;
}

export type Category = "internal" | "external";

export interface AnnotationAttachment {
    name: string;
    module?: string;
    value?: string;
}

export interface Parameter {
    name: string;
    description: string;
    type: Type;
    default?: string;
    annotations?: AnnotationAttachment[];
}

export interface ParameterDef {
    description: string;
    type: Type;
    default?: string;
    optional: boolean;
}

export interface Return {
    description?: string;
    type: Type;
}

export interface EnumValue {
    name: string;
    description: string;
}

export interface Field {
    name: string;
    description: string;
    type: Type;
    default?: string;
    isDeprecated?: boolean;
    annotations?: AnnotationAttachment[];
}

export interface UnionValue {
    name: string;
    type: Type;
}

export interface PathParameter {
    name: string;
    type: string;
}

export interface TypeDefinitionBase {
    name: string;
    description: string;
    type: string;
    isDeprecated?: boolean;
    annotations?: AnnotationAttachment[];
    // The compiler's signature for the type, sent only for definitions with no members to model
    // ("Error" and "Other" — tuples, maps, tables, streams, intersections). It is the right-hand
    // side of the declaration; every other category describes its shape through fields/members.
    baseType?: string;
}

export interface ConstantTypeDefinition extends TypeDefinitionBase {
    value: string;
    varType: Type;
}

export interface RecordTypeDefinition extends TypeDefinitionBase {
    fields: Field[];
}

export interface EnumTypeDefinition extends TypeDefinitionBase {
    members: EnumValue[];
}

export interface UnionTypeDefinition extends TypeDefinitionBase {
    members: UnionValue[];
}

export interface ClassTypeDefinition extends TypeDefinitionBase {
    functions: any[];
    // Set for an object type carrying the `client` qualifier (e.g. sql:Client), which renders as
    // `client class`. Class declarations with the qualifier are emitted as `clients` instead.
    isClient?: boolean;
}

export type TypeDefinition = 
    | RecordTypeDefinition 
    | EnumTypeDefinition 
    | UnionTypeDefinition 
    | ClassTypeDefinition 
    | TypeDefinitionBase
    | ConstantTypeDefinition;

export interface AbstractFunction {
    type: string;
    description: string;
    parameters: Parameter[];
    return: Return;
    isDeprecated?: boolean;
    annotations?: AnnotationAttachment[];
}

export interface ResourceFunction extends AbstractFunction {
    accessor: string;
    paths: (PathParameter | string)[];
}

export interface RemoteFunction extends AbstractFunction {
    name: string;
}

export interface ServiceRemoteFunction {
    type: "remote" | "resource";
    description: string;
    parameters: ParameterDef[];
    return: Return;
    optional: boolean;
    name: string;
    isDeprecated?: boolean;
}

export interface Client {
    name: string;
    description: string;
    functions: (RemoteFunction | ResourceFunction)[];
    isDeprecated?: boolean;
    annotations?: AnnotationAttachment[];
}

export interface Listener {
    name: string;
    parameters: Parameter[];
}

// Spec §2 `listeners[].requiredImports`: an import the generated code needs for its runtime side
// effect even though nothing references it by name (bound to `_`). Scoped to the service that uses
// the listener, not to the library.
export interface RequiredImport {
    module: string;
    alias?: string;
}

// Spec §8 `annotations[]` at `attachPoint: "service"`: an annotation the generated service must or may
// carry. Deliberately distinct from `AnnotationAttachment`, which is an annotation the library *already
// carries* and renders verbatim with its real value; this is an obligation on code that does not exist
// yet, so it renders as a requirement with a placeholder value and a presence marker.
export interface ServiceAnnotationRef {
    name: string;
    // The `org/module` a cross-module annotation belongs to (`ballerinax/cdc`). Absent for one declared
    // by the library itself, which takes the listener's alias instead — the same rule spec §1 applies to
    // a service type.
    module?: string;
    presence: "required" | "optional";
    attachPoint: string;
    // The constraining record, introspected from the compiler rather than the document: spec §8's `type`
    // names the annotation tag, not its constraint (`@ftp:ServiceConfig` is constrained by
    // `ServiceConfiguration`). Absent for a cross-module annotation, whose constraint lives in symbols
    // the library's own semantic model cannot see.
    typeConstraint?: Type;
}

export interface Service {
    listener: Listener;
    type: "generic" | "fixed";
    name?: string;
    isDeprecated?: boolean;
    // Spec §1: the `org/module` a cross-module service type belongs to (`ballerinax/cdc`). Absent
    // for a home-module type, which is prefixed with the listener's alias instead.
    serviceTypeModule?: string;
    requiredImports?: RequiredImport[];
    // Spec §8: the annotations this service type must or may carry.
    annotations?: ServiceAnnotationRef[];
}

export interface Annotation {
    name: string;
    attachmentPoint: string;
    displayName?: string;
    description?: string;
    typeConstraint?: Type;
}

export interface GenericService extends Service {
    instructions: string;
    type: "generic";
}

export interface FixedService extends Service {
    type: "fixed";
    // Absent for fixed services whose service type declares no methods (e.g. mcp's marker Service).
    methods?: ServiceRemoteFunction[];
}

export interface Library {
    name: string;
    description: string;
    typeDefs: TypeDefinition[];
    clients: Client[];
    functions?: RemoteFunction[];
    services?: Service[];
    annotations?: Annotation[];
    instructions?: string;
    readme?: string;
}


export interface LibraryWithUrl extends Library {
    library_link: string;
}

export interface MiniType {
    name: string;
    description: string;
}

export interface GetTypesRequest {
    name: string;
    description: string;
    types: MiniType[];

}

export interface GetTypeResponse {
    libName: string;
    types: MiniType[];
}

export interface GetTypesResponse {
    libraries: GetTypeResponse[];
}


const miniTypeSchema = z.object({
    name: z.string(),
    description: z.string(),
});

const getTypeResponseSchema = z.object({
    libName: z.string(),
    types: z.array(miniTypeSchema),
});

export const getTypesResponseSchema = z.object({
    libraries: z.array(getTypeResponseSchema),
});
