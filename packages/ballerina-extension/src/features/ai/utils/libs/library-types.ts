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
    // Whether this parameter may be omitted. The pipeline emits it only when true — from the init method's
    // DEFAULTABLE/INCLUDED_RECORD parameter kind on the metadata path, or the service index's own flag
    // otherwise — so absent means required. `renderFixedService` depends on it to decide whether a listener
    // argument may carry a default; declared here rather than read through a cast, so a producer that stops
    // sending it fails type-checking instead of silently dropping every listener default.
    optional?: boolean;
    annotations?: AnnotationAttachment[];
}

export interface ParameterDef {
    // Spec §7 `params[].name`: the authored name, or the deterministic one the pipeline generated for a slot
    // whose name the document leaves to the service author. Declared here rather than smuggled through a
    // cast at the one call site that needs it.
    name?: string;
    description: string;
    type: Type;
    default?: string;
    // Spec §7 `presence`. An optional handler parameter may be omitted from the signature entirely — it is
    // never rendered as `T?` or given a default, neither of which is what the spec means.
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
    // Spec §5 `options[].kind`. Drives the rendered keyword: `resource` needs an accessor and a path, and
    // rendering one as `remote function` does not compile.
    type: "remote" | "resource";
    description: string;
    parameters: ParameterDef[];
    return: Return;
    // Spec §5 `options[].presence`, tri-state: `true` optional, `false` required, **absent** when the document
    // is not answering the question (`addMode: "many"`, or a concrete type's declared method). Absent is not
    // the same as `false` — only `false` states an obligation.
    optional?: boolean;
    name: string;
    isDeprecated?: boolean;
    // Spec §5 resource extras. `accessor` is resolved by the Java-side AccessorPrecedencePolicy; the rest are
    // the legal vocabularies the document declares, rendered as placeholders and notes (spec §11.2: the
    // concrete values are intent-derived and must never be invented).
    accessor?: string;
    methodValues?: string[];
    methodRequired?: boolean;
    pathForm?: string[];
    pathRequired?: boolean;
    fieldNameForm?: string[];
    fieldNameRequired?: boolean;
    graphqlOperation?: string;
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

// Spec §3 `serviceTypes[].identifier`: the slot between `service` and `on new …`. Carries the document's own
// `form` tokens rather than a rendered placeholder — building `/basePath` from `basePath` is a syntax decision,
// and keeping the raw token means a form outside spec §10's vocabulary can still be named in the note.
export interface ServiceIdentifier {
    presence: "required" | "optional";
    form: string[];
}

// Spec §6 `rules[].members[]`: exactly one shape is populated per member.
export interface ConstraintMember {
    // The annotation's actual name (`ServiceConfig`), already resolved from the document's registry id by the
    // Java side — a reader has to write this, not the id.
    annotation?: string;
    // The `annotations[].id` the rule referenced (`serviceConfig`). Carried for traceability; never rendered,
    // because it names nothing that exists in Ballerina source.
    annotationId?: string;
    field?: string;
    part?: string;
    handler?: string;
    preferred?: boolean;
}

// Spec §6 `rules[]`: `oneOf` obliges the service to pick exactly one member; `atMostOne` permits none. The
// distinction is load-bearing and must not be flattened when rendering.
export interface ServiceConstraint {
    id?: string;
    kind: "oneOf" | "atMostOne";
    members: ConstraintMember[];
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
    // Spec §3: the identifier slot, absent when the connector does not consult it.
    identifier?: ServiceIdentifier;
    // Spec §6: the exclusivity constraints this service type declares.
    constraints?: ServiceConstraint[];
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
