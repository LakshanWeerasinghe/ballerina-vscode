// Copyright (c) 2026, WSO2 LLC. (https://www.wso2.com/) All Rights Reserved.

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

import * as assert from "assert";
import * as path from "path";
import * as fs from "fs";
import { Library } from "../../../../src/features/ai/utils/libs/library-types";
import { toSyntaxString, deriveModulePrefix } from "../../../../src/features/ai/utils/libs/to-syntax-string";

const RESOURCES_DIR = path.join(__dirname, "resources");

function loadLibraries(filename: string): Library[] {
    const filePath = path.join(RESOURCES_DIR, filename);
    const raw = fs.readFileSync(filePath, "utf-8");
    return JSON.parse(raw) as Library[];
}

/**
 * Helper: render a single library by name from the fixture.
 */
function renderLibrary(allLibs: Library[], name: string): string {
    const lib = allLibs.find((l) => l.name === name);
    assert.ok(lib, `Library ${name} not found in fixture`);
    return toSyntaxString([lib!]);
}

suite("toSyntaxString", () => {
    let allLibraries: Library[];
    let fullResult: string;

    suiteSetup(() => {
        allLibraries = loadLibraries("sample-libraries.json");
        fullResult = toSyntaxString(allLibraries);
    });

    // ----------------------------------------------------------------
    // Design Doc: Implementation Notes — Module prefix derivation
    // ----------------------------------------------------------------
    suite("deriveModulePrefix", () => {
        test("should derive correct module prefixes from the design doc table", () => {
            assert.strictEqual(deriveModulePrefix("ballerina/http"), "http");
            assert.strictEqual(deriveModulePrefix("ballerinax/salesforce"), "salesforce");
            assert.strictEqual(deriveModulePrefix("ballerinax/client.config"), "config");
            assert.strictEqual(deriveModulePrefix("ballerinax/docusign.dsesign"), "dsesign");
            assert.strictEqual(deriveModulePrefix("ballerina/oauth2"), "oauth2");
        });
    });

    // ----------------------------------------------------------------
    // Design Doc §13: Library (top-level structure)
    // ----------------------------------------------------------------
    suite("§13 Library top-level structure", () => {
        test("should render library header with separator, name, description, and import", () => {
            const result = renderLibrary(allLibraries, "ballerina/http");
            assert.ok(result.includes("// ============================================================"));
            assert.ok(result.includes("// Library: ballerina/http"));
            assert.ok(result.includes("// This module provides APIs for connecting and interacting with HTTP and HTTP2 endpoints."));
            assert.ok(result.includes("import ballerina/http;"));
        });

        test("should render section headers only when section is non-empty", () => {
            // ballerina/http has types, functions, services — but no clients
            const httpResult = renderLibrary(allLibraries, "ballerina/http");
            assert.ok(httpResult.includes("// --- Types ---"), "Should have Types section");
            assert.ok(httpResult.includes("// --- Functions ---"), "Should have Functions section");
            assert.ok(httpResult.includes("// --- Service ---"), "Should have Service section");
            assert.ok(!httpResult.includes("// --- Client ---"), "Should NOT have Client section (empty)");

            // ballerina/io has only functions — no types, clients, services
            const ioResult = renderLibrary(allLibraries, "ballerina/io");
            assert.ok(!ioResult.includes("// --- Types ---"), "io should NOT have Types section");
            assert.ok(!ioResult.includes("// --- Client ---"), "io should NOT have Client section");
            assert.ok(ioResult.includes("// --- Functions ---"), "io should have Functions section");
            assert.ok(!ioResult.includes("// --- Service ---"), "io should NOT have Service section");
        });

        test("should prepend library instructions before everything when present", () => {
            const result = renderLibrary(allLibraries, "ballerinax/custom.integration");
            const importIdx = result.indexOf("import ballerinax/custom.integration;");
            const instructionsIdx = result.indexOf("// Use this library for custom integrations.");
            const typesIdx = result.indexOf("// --- Types ---");
            assert.ok(instructionsIdx > importIdx, "Instructions should come after import");
            assert.ok(instructionsIdx < typesIdx, "Instructions should come before Types section");
        });
    });

    // ----------------------------------------------------------------
    // Design Doc §1: RecordTypeDefinition
    // ----------------------------------------------------------------
    suite("§1 RecordTypeDefinition", () => {
        test("should render record with internal links only (CacheConfig from ballerina/http)", () => {
            const result = renderLibrary(allLibraries, "ballerina/http");
            // Record-level description as # comment
            assert.ok(result.includes("# Provides a set of configurations for controlling the caching behaviour of the endpoint."));
            assert.ok(result.includes("type CacheConfig record {"));
            // Field-level descriptions
            assert.ok(result.includes("    # Specifies whether HTTP caching is enabled. Caching is enabled by default."));
            // Optional fields with ?
            assert.ok(result.includes("boolean enabled?;"));
            assert.ok(result.includes("boolean isShared?;"));
            assert.ok(result.includes("int capacity?;"));
            assert.ok(result.includes("float evictionFactor?;"));
            // Internal link — no prefix, no Special Agent Note
            assert.ok(result.includes("CachingPolicy policy?;"));
            assert.ok(!result.includes("CachingPolicy policy?; //"), "Internal link should have no agent note");
            assert.ok(result.includes("};"));
        });

        test("should render record with external links and Special Agent Note (ConnectionConfig from ballerinax/salesforce)", () => {
            const result = renderLibrary(allLibraries, "ballerinax/salesforce");
            // No description → no # comment before record
            assert.ok(result.includes("type ConnectionConfig record {"));
            // No field descriptions → no # comments on fields
            assert.ok(result.includes("    string baseUrl;"));
            // External links: prefix + Special Agent Note
            assert.ok(
                result.includes("http:BearerTokenConfig|http:OAuth2RefreshTokenGrantConfig|OAuth2PasswordGrantConfig|OAuth2ClientCredentialsGrantConfig auth;"),
                "Should prefix external types and leave non-external types unprefixed"
            );
            assert.ok(
                result.includes("// Special Agent Note: BearerTokenConfig, OAuth2RefreshTokenGrantConfig FROM ballerina/http package"),
                "Should add grouped Special Agent Note"
            );
        });

        test("should render per-field external notes (ClientHttp1Settings from ballerinax/docusign.dsesign)", () => {
            const result = renderLibrary(allLibraries, "ballerinax/docusign.dsesign");
            assert.ok(result.includes("type ClientHttp1Settings record {"));
            // Each external field gets its own note
            assert.ok(
                result.includes("http:KeepAlive keepAlive?; // Special Agent Note: KeepAlive FROM ballerina/http package"),
                "keepAlive should have its own agent note"
            );
            assert.ok(
                result.includes("http:Chunking chunking?; // Special Agent Note: Chunking FROM ballerina/http package"),
                "chunking should have its own agent note"
            );
            // Internal link — no prefix, no note
            assert.ok(result.includes("ProxyConfig proxy?;"));
            const proxyLine = result.split("\n").find((l) => l.includes("ProxyConfig proxy?;"));
            assert.ok(proxyLine && !proxyLine.includes("Special Agent Note"), "Internal link should have no agent note");
        });

        test("should render record field with default value (RecordWithDefault from ballerinax/custom.integration)", () => {
            const result = renderLibrary(allLibraries, "ballerinax/custom.integration");
            assert.ok(result.includes("type RecordWithDefault record {"));
            assert.ok(
                result.includes("int timeout? = 60;"),
                "Should render field with optional + default"
            );
            assert.ok(
                result.includes("int retryCount?;"),
                "Should render optional field without default"
            );
        });
    });

    // ----------------------------------------------------------------
    // Design Doc §2: EnumTypeDefinition
    // ----------------------------------------------------------------
    suite("§2 EnumTypeDefinition", () => {
        test("should render enum with members, skip member descriptions (HttpVersion from ballerina/http)", () => {
            const result = renderLibrary(allLibraries, "ballerina/http");
            assert.ok(result.includes("# Defines the supported HTTP protocols."));
            assert.ok(result.includes("enum HttpVersion {"));
            assert.ok(result.includes("HTTP_2_0"));
            assert.ok(result.includes("HTTP_1_1"));
            assert.ok(result.includes("HTTP_1_0"));
            // Member descriptions should be skipped
            assert.ok(!result.includes("Represents HTTP/2.0 protocol"), "Should skip enum member descriptions");
        });
    });

    // ----------------------------------------------------------------
    // Design Doc §3: UnionTypeDefinition
    // ----------------------------------------------------------------
    suite("§3 UnionTypeDefinition", () => {
        test("should render union with members (Compression from ballerina/http)", () => {
            const result = renderLibrary(allLibraries, "ballerina/http");
            // Multi-line description
            assert.ok(result.includes("# Options to compress using gzip or deflate."));
            assert.ok(result.includes("# AUTO: When service behaves as a HTTP gateway..."));
            assert.ok(result.includes("type Compression COMPRESSION_AUTO|COMPRESSION_ALWAYS|COMPRESSION_NEVER;"));
        });

        test("should render union without members as bare type declaration (StatusCode from ballerina/http)", () => {
            const result = renderLibrary(allLibraries, "ballerina/http");
            assert.ok(result.includes("# Represents an HTTP status code type."));
            assert.ok(result.includes("type StatusCode;"));
        });
    });

    // ----------------------------------------------------------------
    // Design Doc §4: ConstantTypeDefinition
    // ----------------------------------------------------------------
    suite("§4 ConstantTypeDefinition", () => {
        test("should render string constant with quoted value (AUTH_HEADER from ballerina/http)", () => {
            const result = renderLibrary(allLibraries, "ballerina/http");
            assert.ok(result.includes("# Represents the Authorization header name."));
            assert.ok(result.includes('const string AUTH_HEADER = "Authorization";'));
        });

        test("should render numeric constant without quotes (DEFAULT_PORT from ballerina/http)", () => {
            const result = renderLibrary(allLibraries, "ballerina/http");
            assert.ok(result.includes("# Default HTTP listener port."));
            assert.ok(result.includes("const int DEFAULT_PORT = 9090;"));
            // Should NOT have quotes around numeric value
            assert.ok(!result.includes('"9090"'), "Numeric constant should not be quoted");
        });
    });

    // ----------------------------------------------------------------
    // Design Doc §5: ClassTypeDefinition
    // ----------------------------------------------------------------
    suite("§5 ClassTypeDefinition", () => {
        test("should render class with description and empty body (PersistentCookieHandler from ballerina/http)", () => {
            const result = renderLibrary(allLibraries, "ballerina/http");
            assert.ok(result.includes("# Provides persistence for cookies."));
            assert.ok(result.includes("class PersistentCookieHandler {"));
            // Should NOT be `client class`
            assert.ok(!result.includes("client class PersistentCookieHandler"), "Regular class should not be client class");
        });
    });

    // ----------------------------------------------------------------
    // Design Doc §6: Client — Constructor
    // ----------------------------------------------------------------
    suite("§6 Client Constructor", () => {
        test("should render constructor with internal links only (salesforce)", () => {
            const result = renderLibrary(allLibraries, "ballerinax/salesforce");
            assert.ok(result.includes("client class Client {"));
            assert.ok(
                result.includes("function init(ConnectionConfig config) returns error?;"),
                "Constructor should use function init(...), no remote keyword, no description"
            );
        });

        test("should render constructor with external links and defaults (postgresql)", () => {
            const result = renderLibrary(allLibraries, "ballerinax/postgresql");
            // Constructor with many params, defaults, and external link
            assert.ok(
                result.includes('function init(string host = "localhost", string|() username = "postgres", string|() password = (), string|() database = (), int port = 5432, Options|() options = (), sql:ConnectionPool|() connectionPool = ()) returns ballerina/sql:1.16.0:Error?;'),
                "Should render constructor with all params, defaults, external prefix"
            );
            assert.ok(
                result.includes("// Special Agent Note: ConnectionPool FROM ballerina/sql package"),
                "Constructor should have Special Agent Note for external param"
            );
        });
    });

    // ----------------------------------------------------------------
    // Design Doc §7: Client — Remote Function
    // ----------------------------------------------------------------
    suite("§7 Client Remote Function", () => {
        test("should render remote function without external links (salesforce query)", () => {
            const result = renderLibrary(allLibraries, "ballerinax/salesforce");
            assert.ok(result.includes("    # Executes the specified SOQL query."));
            assert.ok(
                result.includes("remote function query(string soql, record {|anydata...;|} returnType = record {|anydata...;|}) returns stream<returnType, error?>|error;"),
                "Should render remote function with default param"
            );
        });

        test("should render remote function with external links on param and return (postgresql queryRow)", () => {
            const result = renderLibrary(allLibraries, "ballerinax/postgresql");
            assert.ok(result.includes("    # Executes the query, which is expected to return at most one row of the result."));
            assert.ok(result.includes("    # If the query does not return any results, an `sql:NoRowsError` is returned."));
            assert.ok(
                result.includes("remote function queryRow(sql:ParameterizedQuery sqlQuery, anydata returnType = anydata) returns returnType|sql:Error;"),
                "Should prefix external types in both param and return"
            );
            assert.ok(
                result.includes("// Special Agent Note: ParameterizedQuery, Error FROM ballerina/sql package"),
                "Should collect external links from both params and return in one note"
            );
        });
    });

    // ----------------------------------------------------------------
    // Design Doc §8: Client — Resource Function
    // ----------------------------------------------------------------
    suite("§8 Client Resource Function", () => {
        test("should render resource function with path segments and path-param exclusion (docusign post envelopes)", () => {
            const result = renderLibrary(allLibraries, "ballerinax/docusign.dsesign");
            assert.ok(result.includes("    # Creates an envelope."));
            // Path: accounts/[string accountId]/envelopes
            assert.ok(
                result.includes("resource function post accounts/[string accountId]/envelopes("),
                "Should render path with static segments and path parameter brackets"
            );
            // accountId should NOT appear in parenthesized params (it's in the path)
            const resourceLine = result.split("\n").find((l) => l.includes("resource function post accounts"));
            assert.ok(resourceLine, "Resource function line should exist");
            const paramsSection = resourceLine!.substring(resourceLine!.indexOf("("));
            assert.ok(!paramsSection.includes("string accountId"), "Path param should be excluded from parenthesized params");
            // Non-path params should be present
            assert.ok(paramsSection.includes("EnvelopeDefinition payload"));
            assert.ok(paramsSection.includes("string|() cdse_mode = ()"));
            assert.ok(paramsSection.includes("string|() change_routing_order = ()"));
            // Return type
            assert.ok(paramsSection.includes("returns EnvelopeSummary|error;"));
        });
    });

    // ----------------------------------------------------------------
    // Design Doc §9: Client (full composition)
    // ----------------------------------------------------------------
    suite("§9 Client full composition", () => {
        test("should render client class with constructor + remote functions (salesforce)", () => {
            const result = renderLibrary(allLibraries, "ballerinax/salesforce");
            assert.ok(result.includes("# Ballerina Salesforce connector provides the capability to access Salesforce REST API."));
            assert.ok(result.includes("client class Client {"));
            assert.ok(result.includes("function init(ConnectionConfig config) returns error?;"));
            assert.ok(result.includes("remote function query("));
            assert.ok(result.includes("}"));
        });

        test("should render client class with constructor + resource functions (docusign)", () => {
            const result = renderLibrary(allLibraries, "ballerinax/docusign.dsesign");
            assert.ok(result.includes("client class Client {"));
            assert.ok(result.includes("function init(ConnectionConfig config) returns error?;"));
            assert.ok(result.includes("resource function post accounts/[string accountId]/envelopes("));
        });

        test("should render client class with constructor + remote functions with external links (postgresql)", () => {
            const result = renderLibrary(allLibraries, "ballerinax/postgresql");
            assert.ok(result.includes("# Represents a PostgreSQL database client."));
            assert.ok(result.includes("client class Client {"));
            assert.ok(result.includes("function init("));
            assert.ok(result.includes("remote function queryRow("));
        });
    });

    // ----------------------------------------------------------------
    // Design Doc §10: Standalone Functions (library-level)
    // ----------------------------------------------------------------
    suite("§10 Standalone Functions", () => {
        test("should render standalone function with # + param and # + return docs (io fileWriteBytes)", () => {
            const result = renderLibrary(allLibraries, "ballerina/io");
            assert.ok(result.includes("# Write a set of bytes to a file."));
            assert.ok(result.includes("# + path - The path of the file"));
            assert.ok(result.includes("# + content - Byte content to write"));
            assert.ok(result.includes("# + option - To indicate whether to overwrite or append the given content"));
            assert.ok(result.includes("# + return - An `io:Error` or else `()`"));
            assert.ok(
                result.includes("function fileWriteBytes(string path, byte[] content, FileWriteOption option = OVERWRITE) returns Error|();"),
                "Should render function with params and default"
            );
        });

        test("should render standalone function without param descriptions (http authenticateResource)", () => {
            const result = renderLibrary(allLibraries, "ballerina/http");
            assert.ok(result.includes("# Uses for declarative auth design."));
            assert.ok(
                result.includes("function authenticateResource(Service serviceRef, string methodName, string[] resourcePath) returns ();"),
                "Should render function with no param docs when descriptions are empty"
            );
            // Should NOT have # + param lines for params with empty descriptions
            const funcLines = result.split("\n");
            const authFuncIdx = funcLines.findIndex((l) => l.includes("function authenticateResource("));
            // The line before should be the description, not a # + param line
            assert.ok(
                funcLines[authFuncIdx - 1].includes("# Uses for declarative auth design."),
                "No # + param lines for empty descriptions"
            );
        });

        test("should render standalone function with multi-package external links (custom.integration process)", () => {
            const result = renderLibrary(allLibraries, "ballerinax/custom.integration");
            assert.ok(
                result.includes("function process(http:Request req, kafka:Message msg) returns error?;"),
                "Should prefix types from different packages"
            );
            assert.ok(
                result.includes("// Special Agent Note: Request FROM ballerina/http package, Message FROM ballerinax/kafka package"),
                "Should group by package in Special Agent Note with comma separation"
            );
        });
    });

    // ----------------------------------------------------------------
    // Design Doc §11: Service — GenericService
    // ----------------------------------------------------------------
    suite("§11 GenericService", () => {
        test("should render generic service with listener signature and instructions passthrough (ballerina/http)", () => {
            const result = renderLibrary(allLibraries, "ballerina/http");
            assert.ok(result.includes("// --- Service (generic) ---"));
            assert.ok(result.includes("// Listener: Listener(int port)"));
            assert.ok(result.includes("// Instructions:"));
            // Instructions passed through verbatim
            assert.ok(result.includes("# Service writing instructions"));
            assert.ok(result.includes("- HTTP Service always requires a http listener to be attached to it."));
        });
    });

    // ----------------------------------------------------------------
    // Design Doc §12: Service — FixedService
    // ----------------------------------------------------------------
    suite("§12 FixedService", () => {
        test("should render fixed service with listener and remote methods (salesforce)", () => {
            const result = renderLibrary(allLibraries, "ballerinax/salesforce");
            assert.ok(
                result.includes("service on new salesforce:Listener(salesforce:ListenerConfig listenerConfig"),
                "Should render service on new Listener(...)"
            );
            // Method names from the name field
            assert.ok(result.includes("    # The `onCreate` method is triggered when a new record create event is received from Salesforce."));
            assert.ok(result.includes("remote function onCreate(salesforce:EventData payload) returns error?;"));
            assert.ok(result.includes("remote function onUpdate(salesforce:EventData payload) returns error?;"));
            assert.ok(result.includes("remote function onDelete(salesforce:EventData payload) returns error?;"));
        });

        test("should mark optional methods with // optional comment", () => {
            const result = renderLibrary(allLibraries, "ballerinax/salesforce");
            // onCreate and onUpdate are optional: false
            const onCreateLine = result.split("\n").find((l) => l.includes("remote function onCreate("));
            assert.ok(onCreateLine && !onCreateLine.includes("// optional"), "Required method should not have // optional");
            // onDelete is optional: true
            const onDeleteLine = result.split("\n").find((l) => l.includes("remote function onDelete("));
            assert.ok(onDeleteLine && onDeleteLine.includes("// optional"), "Optional method should have // optional comment");
        });
    });

    // ----------------------------------------------------------------
    // Design Doc: External Type References — Dual Approach
    // ----------------------------------------------------------------
    suite("External Type References — Dual Approach", () => {
        test("Strategy 1: should apply module-qualified prefix to external type names", () => {
            // salesforce ConnectionConfig auth field
            const result = renderLibrary(allLibraries, "ballerinax/salesforce");
            assert.ok(result.includes("http:BearerTokenConfig"), "Should prefix with http:");
            assert.ok(result.includes("http:OAuth2RefreshTokenGrantConfig"), "Should prefix with http:");
            // Non-external types left unprefixed
            assert.ok(result.includes("|OAuth2PasswordGrantConfig|"), "Non-linked types should stay unprefixed");
        });

        test("Strategy 2: should emit Special Agent Note only for external links", () => {
            // CacheConfig has only internal links → no note
            const httpResult = renderLibrary(allLibraries, "ballerina/http");
            const policyLine = httpResult.split("\n").find((l) => l.includes("CachingPolicy policy?;"));
            assert.ok(policyLine && !policyLine.includes("Special Agent Note"), "Internal-only field should have no agent note");

            // ConnectionConfig auth has external links → note
            const sfResult = renderLibrary(allLibraries, "ballerinax/salesforce");
            assert.ok(sfResult.includes("// Special Agent Note: BearerTokenConfig, OAuth2RefreshTokenGrantConfig FROM ballerina/http package"));
        });

        test("should handle multi-package external links on a single function line", () => {
            const result = renderLibrary(allLibraries, "ballerinax/custom.integration");
            assert.ok(
                result.includes("// Special Agent Note: Request FROM ballerina/http package, Message FROM ballerinax/kafka package"),
                "Multi-package note should separate packages with comma"
            );
        });

        test("should collect external links from both params and return type on function", () => {
            const result = renderLibrary(allLibraries, "ballerinax/postgresql");
            // queryRow has ParameterizedQuery in param and Error in return, both from ballerina/sql
            assert.ok(
                result.includes("// Special Agent Note: ParameterizedQuery, Error FROM ballerina/sql package"),
                "Should collect from both param and return in one note"
            );
        });
    });

    // "Error" and "Other" carry no fields or members — the model sends the compiler's own
    // signature in `baseType` instead, and it is emitted as the declaration's right-hand side.
    suite("§13 Member-less type definitions (Error / Other)", () => {
        function render(typeDef: Record<string, unknown>): string {
            const lib = {
                name: "ballerinax/kafka",
                description: "",
                typeDefs: [typeDef],
            } as unknown as Library;
            return toSyntaxString([lib]);
        }

        test("should render an error type from its baseType", () => {
            const result = render({
                name: "Error",
                description: "Defines the common error type for the module.",
                type: "Error",
                baseType: "error",
            });
            assert.ok(result.includes("# Defines the common error type for the module."),
                "Description must survive; it used to be discarded with the type");
            assert.ok(result.includes("type Error error;"), `Expected error declaration, got:\n${result}`);
            assert.ok(!result.includes("// Unknown type"), "Must no longer fall through to the comment");
        });

        test("should render an error type carrying a detail record", () => {
            const result = render({
                name: "PayloadBindingError",
                description: "Represents an error, which occurred due to payload binding.",
                type: "Error",
                baseType: "error<record {|TopicPartition partition; int offset;|}>",
            });
            assert.ok(
                result.includes("type PayloadBindingError error<record {|TopicPartition partition; int offset;|}>;"),
                `Detail record must be preserved verbatim, got:\n${result}`
            );
        });

        test("should render an Other type such as a tuple", () => {
            const result = render({
                name: "TopicPartitionTimestamp",
                description: "Represents a topic partition and a timestamp.",
                type: "Other",
                baseType: "[TopicPartition, int]",
            });
            assert.ok(result.includes("# Represents a topic partition and a timestamp."));
            assert.ok(result.includes("type TopicPartitionTimestamp [TopicPartition, int];"),
                `Expected tuple declaration, got:\n${result}`);
        });

        test("should keep the previous comment when baseType is absent", () => {
            const result = render({
                name: "Mystery",
                description: "No signature available.",
                type: "Other",
            });
            assert.ok(result.includes("// Unknown type: Mystery"),
                `Missing baseType must degrade to the old output, got:\n${result}`);
            assert.ok(!result.includes("type Mystery ;"), "Must never emit an empty right-hand side");
        });

        test("should still render deprecation for a member-less type", () => {
            const result = render({
                name: "OldError",
                description: "Legacy error.",
                type: "Error",
                baseType: "error",
                isDeprecated: true,
            });
            assert.ok(result.includes("@deprecated"), `Expected @deprecated, got:\n${result}`);
            assert.ok(result.includes("type OldError error;"));
        });

        test("should leave genuinely unknown type categories on the comment path", () => {
            const result = render({ name: "Weird", description: "", type: "SomethingElse" });
            assert.ok(result.includes("// Unknown type: Weird"),
                `Unrecognised categories must be unaffected, got:\n${result}`);
        });
    });

    // A class declaration and an object type definition both arrive as type "Class". Their methods
    // must render, and each method with the qualifier it was actually declared with.
    suite("§14 Class / object type members", () => {
        function renderTypeDef(typeDef: Record<string, unknown>): string {
            const lib = { name: "ballerina/sql", description: "", typeDefs: [typeDef] } as unknown as Library;
            return toSyntaxString([lib]);
        }

        function fn(name: string, type: string, extra: Record<string, unknown> = {}) {
            return { name, type, description: "", parameters: [], ...extra };
        }

        test("should render a plain method as `function`, never `remote function`", () => {
            const result = renderTypeDef({
                name: "ResultIterator", description: "The iterator.", type: "Class",
                functions: [fn("next", "Normal Function"), fn("close", "Normal Function")],
            });
            assert.ok(result.includes("class ResultIterator {"), `got:\n${result}`);
            assert.ok(result.includes("    function next();"), `Expected plain function, got:\n${result}`);
            assert.ok(!result.includes("remote function next"),
                `A Normal Function must not be labelled remote, got:\n${result}`);
            assert.ok(!result.includes("// Unknown type"), "Must not fall through to the comment path");
        });

        test("should render a remote method with the remote qualifier", () => {
            const result = renderTypeDef({
                name: "Holder", description: "", type: "Class",
                functions: [fn("query", "Remote Function")],
            });
            assert.ok(result.includes("    remote function query();"), `got:\n${result}`);
        });

        test("should render an object type carrying the client qualifier as `client class`", () => {
            const result = renderTypeDef({
                name: "Client", description: "Represents an SQL client.", type: "Class", isClient: true,
                functions: [fn("query", "Remote Function"), fn("close", "Normal Function")],
            });
            assert.ok(result.includes("client class Client {"),
                `A client-qualified object type must render as client class, got:\n${result}`);
            assert.ok(result.includes("    remote function query();"), `got:\n${result}`);
            assert.ok(result.includes("    function close();"), `got:\n${result}`);
        });

        test("should keep rendering an empty class body unchanged", () => {
            const result = renderTypeDef({ name: "Service", description: "Marker.", type: "Class" });
            assert.ok(result.includes("class Service {\n}"),
                `A member-less class must be unchanged, got:\n${result}`);
        });

        test("should treat an empty functions array the same as none", () => {
            const result = renderTypeDef({ name: "Empty", description: "", type: "Class", functions: [] });
            assert.ok(result.includes("class Empty {\n}"), `got:\n${result}`);
        });

        test("should render a constructor without a leading blank line", () => {
            const result = renderTypeDef({
                name: "Holder", description: "", type: "Class",
                functions: [fn("init", "Constructor"), fn("go", "Remote Function")],
            });
            assert.ok(result.includes("class Holder {\n    function init();"),
                `Constructor must follow the header directly, got:\n${result}`);
        });

        test("should render a resource method via the resource path", () => {
            const result = renderTypeDef({
                name: "Holder", description: "", type: "Class",
                functions: [{
                    name: "get", type: "Resource Function", description: "", parameters: [],
                    accessor: "get", paths: [{ kind: "literal", value: "items" }],
                }],
            });
            assert.ok(result.includes("resource function get"), `got:\n${result}`);
        });

        test("should carry deprecation and description onto a populated class", () => {
            const result = renderTypeDef({
                name: "Old", description: "Legacy holder.", type: "Class", isDeprecated: true,
                functions: [fn("go", "Remote Function")],
            });
            assert.ok(result.includes("# Legacy holder."), `got:\n${result}`);
            assert.ok(result.includes("@deprecated"), `got:\n${result}`);
            assert.ok(result.includes("class Old {"), `got:\n${result}`);
        });
    });

    // ----------------------------------------------------------------
    // Ballerina Trigger Construct Spec v1 — rendering conformance.
    // Each test names the spec section it pins and asserts what that section mandates, so a change
    // that breaks a spec guarantee fails here even if the implementation stays self-consistent.
    // ----------------------------------------------------------------
    suite("Trigger spec §1/§2 — service type module and required imports", () => {
        function renderService(service: Record<string, unknown>): string {
            const lib = {
                name: "ballerinax/mssql",
                description: "",
                typeDefs: [],
                clients: [],
                services: [service],
            } as unknown as Library;
            return toSyntaxString([lib]);
        }

        const listener = { name: "mssql:CdcListener", parameters: [] };

        test("§1: a cross-module service type is written with its own module alias", () => {
            // Spec §1: `packageInfo` appears "only when the type isn't from this file's own home
            // module", and the home module is the listener's. mssql.cdc's service type belongs to
            // ballerinax/cdc, so `mssql:Service` would not compile.
            const result = renderService({
                type: "fixed", name: "Service", serviceTypeModule: "ballerinax/cdc", listener, methods: [],
            });
            assert.ok(result.includes("service cdc:Service on new mssql:CdcListener("),
                `Expected the foreign module alias, got:\n${result}`);
            assert.ok(!result.includes("service mssql:Service"),
                "Must not borrow the listener's alias for a foreign service type");
        });

        test("§1: a home-module service type still borrows the listener's alias", () => {
            // No `serviceTypeModule` means the type is the connector's own, so the existing
            // listener-alias behaviour must be preserved exactly.
            const result = renderService({
                type: "fixed", name: "Service", listener: { name: "kafka:Listener", parameters: [] },
                methods: [],
            });
            assert.ok(result.includes("service kafka:Service on new kafka:Listener("), `got:\n${result}`);
        });

        test("§2: a side-effect-only import is stated on the service that requires it", () => {
            // Spec §2's own example: `import ballerinax/mssql.cdc.driver as _;`
            const result = renderService({
                type: "fixed", name: "Service", listener, methods: [],
                requiredImports: [{ module: "ballerinax/mssql.cdc.driver", alias: "_" }],
            });
            assert.ok(result.includes("# Requires: import ballerinax/mssql.cdc.driver as _;"),
                `Required import must be stated on the service that needs it, got:\n${result}`);
            assert.ok(!result.split("\n").some((l) => l === "import ballerinax/mssql.cdc.driver as _;"),
                "A listener-scoped import must not be hoisted to the library header");
        });

        test("§2: an import required by several services is stated on each, never hoisted", () => {
            // Spec §2 declares `requiredImports` on the *listener*, so the requirement belongs to each
            // service that attaches to it — one `# Requires:` line per service, and never a bare
            // `import ...;` at the library header. Repetition is correct here, not duplication: each
            // service states its own dependency, and a reader of one service must not have to look at
            // another to discover it.
            //
            // This test previously asserted the opposite — that a single *hoisted* `import ...;` line
            // appears — which directly contradicted its own sibling above ("must not be hoisted to the
            // library header"). It had never passed: it was red in the same commit that introduced it
            // (27f7eab8), so it pinned a design that was abandoned before it shipped.
            const service = (name: string) => ({
                type: "fixed", name, listener, methods: [],
                requiredImports: [{ module: "ballerinax/mssql.cdc.driver", alias: "_" }],
            });
            const lib = {
                name: "ballerinax/mssql", description: "", typeDefs: [], clients: [],
                services: [service("A"), service("B")],
            } as unknown as Library;
            const lines = toSyntaxString([lib]).split("\n");

            assert.strictEqual(
                lines.filter((l) => l === "# Requires: import ballerinax/mssql.cdc.driver as _;").length,
                2, "each service states the import it requires");
            assert.strictEqual(
                lines.filter((l) => l === "import ballerinax/mssql.cdc.driver as _;").length,
                0, "a listener-scoped import is never hoisted to the library header");
            assert.deepStrictEqual(lines.filter((l) => l.startsWith("import ")),
                ["import ballerinax/mssql;"], "only the library's own import is hoisted");
        });

        test("§2: an entry with no alias renders as a plain import", () => {
            const result = renderService({
                type: "fixed", name: "Service", listener, methods: [],
                requiredImports: [{ module: "ballerinax/somepkg" }],
            });
            assert.ok(result.includes("import ballerinax/somepkg;"), `got:\n${result}`);
            assert.ok(!result.includes("as _;"), "No alias means no `as` clause");
        });

        test("general rule: absent optional keys add nothing", () => {
            // "A field that would be empty, unused, or fully derivable ... is left out" — an absent
            // requiredImports/serviceTypeModule must leave output byte-identical to before.
            const result = renderService({ type: "fixed", name: "Service", listener, methods: [] });
            assert.ok(!result.includes(" as _;"), "No imports must be invented");
            const importLines = result.split("\n").filter((l) => l.startsWith("import "));
            assert.deepStrictEqual(importLines, ["import ballerinax/mssql;"]);
        });
    });

    suite("Trigger spec §8 — service-level annotation requirements", () => {
        function renderService(service: Record<string, unknown>, libName = "ballerina/ftp"): string {
            const lib = {
                name: libName,
                description: "",
                typeDefs: [],
                clients: [],
                services: [service],
            } as unknown as Library;
            return toSyntaxString([lib]);
        }

        const ftpListener = { name: "ftp:Listener", parameters: [] };

        function annotation(over: Record<string, unknown> = {}): Record<string, unknown> {
            return {
                name: "ServiceConfig", presence: "optional", attachPoint: "service", ...over,
            };
        }

        test("§8: a required annotation is attached above the service it is required on", () => {
            // ftp, smb and mssql.cdc declare `presence: "required"`; code generated without the
            // annotation does not work, so the obligation has to be unmissable and adjacent.
            const result = renderService({
                type: "fixed", name: "Service", listener: ftpListener, methods: [],
                annotations: [annotation({ presence: "required" })],
            });
            const lines = result.split("\n");
            const serviceLine = lines.findIndex((l) => l.startsWith("service ftp:Service on new"));
            const attachLine = lines.findIndex((l) => l.startsWith("@ftp:ServiceConfig"));

            assert.ok(attachLine >= 0, `Expected an attachment line, got:\n${result}`);
            assert.strictEqual(attachLine, serviceLine - 1, "The attachment must sit on the service");
            assert.ok(lines[attachLine - 1].startsWith("# Mandatory:"),
                `A required annotation must state the obligation, got: ${lines[attachLine - 1]}`);
        });

        test("§8: presence distinguishes a required annotation from an optional one", () => {
            // Both are attachments of identical shape, so the presence has to be legible on the line
            // that actually gets copied — an optional annotation whose record has mandatory fields
            // turns a harmless omission into a compile error when attached carelessly.
            const required = renderService({
                type: "fixed", name: "Service", listener: ftpListener, methods: [],
                annotations: [annotation({ presence: "required" })],
            });
            const optional = renderService({
                type: "fixed", name: "Service", listener: ftpListener, methods: [],
                annotations: [annotation({ presence: "optional" })],
            });

            assert.ok(required.includes("@ftp:ServiceConfig {...} // required"), `got:\n${required}`);
            assert.ok(optional.includes("@ftp:ServiceConfig {...} // optional"), `got:\n${optional}`);
            assert.ok(required.includes("# Mandatory: this service must carry"));
            assert.ok(optional.includes("# Optional: this service may carry"));
        });

        test("§1/§8: a cross-module annotation takes its own module's prefix and states provenance", () => {
            // mssql.cdc's annotation belongs to ballerinax/cdc, so `@mssql:ServiceConfig` would name
            // something that does not exist. Provenance travels in the same `Special Agent Note`
            // convention every other cross-module reference in this renderer uses.
            const result = renderService({
                type: "fixed", name: "Service", serviceTypeModule: "ballerinax/cdc",
                listener: { name: "mssql:CdcListener", parameters: [] }, methods: [],
                annotations: [annotation({ presence: "required", module: "ballerinax/cdc" })],
            }, "ballerinax/mssql");

            assert.ok(result.includes("@cdc:ServiceConfig {...}"), `got:\n${result}`);
            assert.ok(!result.includes("@mssql:ServiceConfig"),
                "A foreign annotation must not borrow the library's own alias");
            assert.ok(result.includes("Special Agent Note: ServiceConfig FROM ballerinax/cdc package"),
                `Provenance must be stated, got:\n${result}`);
        });

        test("§8: a home-module annotation takes the listener's alias, not the service type's", () => {
            // The service type may live in another module while the annotation is the library's own —
            // prefixing the annotation with `serviceTypeModule`'s alias would misname it.
            const result = renderService({
                type: "fixed", name: "Service", serviceTypeModule: "ballerinax/cdc",
                listener: { name: "mssql:CdcListener", parameters: [] }, methods: [],
                annotations: [annotation()],
            }, "ballerinax/mssql");
            assert.ok(result.includes("@mssql:ServiceConfig {...}"), `got:\n${result}`);
            assert.ok(!result.includes("@cdc:ServiceConfig"), "Home annotation must not go foreign");
        });

        test("§8: the constraining record is named so the placeholder can be filled", () => {
            // The document names the annotation tag, not its constraint: `@ftp:ServiceConfig` is
            // constrained by `ServiceConfiguration`. `{...}` is not valid Ballerina, so the model has
            // to be told both that it must be replaced and what supplies the fields.
            const result = renderService({
                type: "fixed", name: "Service", listener: ftpListener, methods: [],
                annotations: [annotation({
                    presence: "required",
                    typeConstraint: { name: "ServiceConfiguration" },
                })],
            });
            assert.ok(result.includes("Replace {...} with its fields, which are those of "
                + "ServiceConfiguration."), `got:\n${result}`);
        });

        test("§8: an unknown constraint still instructs the placeholder be replaced", () => {
            const result = renderService({
                type: "fixed", name: "Service", listener: ftpListener, methods: [],
                annotations: [annotation({ presence: "required" })],
            });
            assert.ok(result.includes("Replace {...} with its fields."), `got:\n${result}`);
        });

        test("§8: several annotations on one service keep document order", () => {
            // "Array order is meaningful" — mcp is the corpus case with more than one in play.
            const result = renderService({
                type: "fixed", name: "Service", listener: ftpListener, methods: [],
                annotations: [annotation({ name: "FirstConfig" }), annotation({ name: "SecondConfig" })],
            });
            assert.ok(result.indexOf("@ftp:FirstConfig") < result.indexOf("@ftp:SecondConfig"),
                `got:\n${result}`);
        });

        test("§8: documentation precedes every annotation, including @deprecated", () => {
            // Ballerina metadata order: all `#` documentation, then all annotations, then the
            // declaration. A deprecated service carrying an obligation must not sandwich the `#` line
            // between two annotations.
            const lines = renderService({
                type: "fixed", name: "Service", listener: ftpListener, methods: [], isDeprecated: true,
                annotations: [annotation({ presence: "required" })],
            }).split("\n");

            const doc = lines.findIndex((l) => l.startsWith("# Mandatory:"));
            const attach = lines.findIndex((l) => l.startsWith("@ftp:ServiceConfig"));
            const deprecated = lines.findIndex((l) => l === "@deprecated");
            const serviceLine = lines.findIndex((l) => l.startsWith("service ftp:Service on new"));

            assert.ok(doc >= 0 && attach >= 0 && deprecated >= 0 && serviceLine >= 0, lines.join("\n"));
            assert.ok(doc < attach, "documentation precedes the attachment");
            assert.ok(doc < deprecated, "documentation precedes @deprecated");
            assert.ok(attach < serviceLine && deprecated < serviceLine,
                "every annotation precedes the declaration");
        });

        test("general rule: a service with no annotations renders exactly as before", () => {
            // Most service types carry no obligation, and their output must be untouched.
            const withKeyAbsent = renderService({
                type: "fixed", name: "Service", listener: ftpListener, methods: [],
            });
            const withEmptyArray = renderService({
                type: "fixed", name: "Service", listener: ftpListener, methods: [], annotations: [],
            });
            assert.strictEqual(withKeyAbsent, withEmptyArray,
                "An empty array must render identically to an absent key");
            assert.ok(!withKeyAbsent.includes("must carry") && !withKeyAbsent.includes("may carry"),
                `No obligation may be invented, got:\n${withKeyAbsent}`);
        });

        test("§1/§8: a foreign constraint is named with its own module's prefix", () => {
            // `CdcServiceConfig` lives in ballerinax/cdc, so telling the model to use a bare
            // `CdcServiceConfig` would name something not in scope. The prefix comes from the external
            // link, exactly as it does for any other cross-package type reference in this renderer.
            const result = renderService({
                type: "fixed", name: "Service", serviceTypeModule: "ballerinax/cdc",
                listener: { name: "mssql:CdcListener", parameters: [] }, methods: [],
                annotations: [annotation({
                    presence: "required", module: "ballerinax/cdc",
                    typeConstraint: {
                        name: "CdcServiceConfig",
                        links: [{ category: "external", recordName: "CdcServiceConfig",
                                  libraryName: "ballerinax/cdc" }],
                    },
                })],
            }, "ballerinax/mssql");

            assert.ok(result.includes("which are those of cdc:CdcServiceConfig."), `got:\n${result}`);
        });

        test("§8: provenance names both the annotation and its record, in one comment", () => {
            // Both live in the foreign package, and both are what the model has to go and find. Two
            // separate `//` comments on one line would compete; the renderer's grouping convention is
            // `X, Y FROM <lib> package`.
            const result = renderService({
                type: "fixed", name: "Service", listener: { name: "mssql:CdcListener", parameters: [] },
                methods: [],
                annotations: [annotation({
                    presence: "required", module: "ballerinax/cdc",
                    typeConstraint: {
                        name: "CdcServiceConfig",
                        links: [{ category: "external", recordName: "CdcServiceConfig",
                                  libraryName: "ballerinax/cdc" }],
                    },
                })],
            }, "ballerinax/mssql");

            const line = result.split("\n").find((l) => l.startsWith("@cdc:ServiceConfig"))!;
            assert.strictEqual(line,
                "@cdc:ServiceConfig {...} // required; Special Agent Note: ServiceConfig, "
                + "CdcServiceConfig FROM ballerinax/cdc package");
            assert.strictEqual(line.split("//").length - 1, 1, "exactly one trailing comment");
        });

        test("§8: a home-module constraint stays unprefixed by the external mechanism", () => {
            // An internal link records the type without re-qualifying it, so the sentence reads with the
            // bare record name the same file already declares.
            const result = renderService({
                type: "fixed", name: "Service", listener: ftpListener, methods: [],
                annotations: [annotation({
                    presence: "required",
                    typeConstraint: {
                        name: "ServiceConfiguration",
                        links: [{ category: "internal", recordName: "ServiceConfiguration" }],
                    },
                })],
            });
            assert.ok(result.includes("which are those of ServiceConfiguration."), `got:\n${result}`);
            assert.ok(!result.includes("ftp:ServiceConfiguration"), "internal links are not re-qualified");
        });

        test("§8: a nameless entry is skipped rather than rendered as a bare @", () => {
            const result = renderService({
                type: "fixed", name: "Service", listener: ftpListener, methods: [],
                annotations: [annotation({ name: undefined }), annotation({ name: "Sound" })],
            });
            assert.ok(!result.split("\n").some((l) => l.trim() === "@ftp: {...} // optional"),
                `got:\n${result}`);
            assert.ok(result.includes("@ftp:Sound {...}"), "the sound entry beside it still renders");
        });
    });

    // ----------------------------------------------------------------
    // Trigger spec §2 — listener arguments
    // ----------------------------------------------------------------
    suite("Trigger spec §2 — listener argument defaults", () => {
        function renderListener(parameters: Record<string, unknown>[]): string {
            const lib = {
                name: "ballerinax/kafka", description: "", typeDefs: [], clients: [],
                services: [{
                    type: "fixed", name: "Service",
                    listener: { name: "kafka:Listener", parameters },
                    methods: [],
                }],
            } as unknown as Library;
            return toSyntaxString([lib]);
        }

        test("§2: a required listener parameter never carries a default", () => {
            // Spec §2 models no listener init fields — they come from the init signature, where a parameter
            // is required exactly when it is neither defaultable nor an included record. kafka's
            // `bootstrapServers` is required, and rendering `= ""` told the model a mandatory value was
            // already supplied.
            const result = renderListener([
                { name: "bootstrapServers", description: "", type: { name: "string|string[]" }, default: '""' },
            ]);
            assert.ok(result.includes("on new kafka:Listener(string|string[] bootstrapServers)"),
                `got:\n${result}`);
            assert.ok(!result.includes('bootstrapServers = ""'), "a required parameter has no default");
        });

        test("§2: an optional listener parameter keeps its default", () => {
            // The other half of the rule: an included-record or defaultable parameter genuinely may be left
            // out, and its default is the value the connector will use.
            const result = renderListener([
                { name: "config", description: "", type: { name: "ConsumerConfiguration" },
                  optional: true, default: "{}" },
            ]);
            assert.ok(result.includes("on new kafka:Listener(ConsumerConfiguration config = {})"),
                `got:\n${result}`);
        });

        test("§2: required and optional parameters are distinguished within one signature", () => {
            // kafka's real shape, and the one that proves the flag is consulted per parameter rather than
            // per service.
            const result = renderListener([
                { name: "bootstrapServers", description: "", type: { name: "string|string[]" }, default: '""' },
                { name: "config", description: "", type: { name: "ConsumerConfiguration" },
                  optional: true, default: "{}" },
            ]);
            assert.ok(result.includes(
                "on new kafka:Listener(string|string[] bootstrapServers, ConsumerConfiguration config = {})"),
                `got:\n${result}`);
        });

        test("§2: an optional parameter with no default stays bare", () => {
            const result = renderListener([
                { name: "config", description: "", type: { name: "Config" }, optional: true },
            ]);
            assert.ok(result.includes("on new kafka:Listener(Config config)"), `got:\n${result}`);
        });
    });

    // ----------------------------------------------------------------
    // Trigger spec §3/§5/§6/§7 — handler shape, presence, identifier, constraints
    // ----------------------------------------------------------------
    suite("Trigger spec §3/§5/§6/§7 — handler shape, identifier and constraints", () => {
        function renderService(service: Record<string, unknown>, libName = "ballerina/websocket"): string {
            const lib = {
                name: libName, description: "", typeDefs: [], clients: [], services: [service],
            } as unknown as Library;
            return toSyntaxString([lib]);
        }

        const wsListener = { name: "websocket:Listener", parameters: [] };

        function method(over: Record<string, unknown> = {}): Record<string, unknown> {
            return {
                name: "onMessage", type: "remote", description: "",
                parameters: [], return: { type: { name: "error?" } }, ...over,
            };
        }

        function line(result: string, needle: string): string {
            const found = result.split("\n").find((l) => l.includes(needle));
            assert.ok(found, `no line containing "${needle}" in:\n${result}`);
            return found!;
        }

        // ---- §5 kind ----

        test("§5: a resource handler renders `resource function <accessor> <path>`, not `remote`", () => {
            // Corpus: websocket's upgradeService declares {"name": "get", "kind": "resource"}. It used to
            // render `remote function get(...)`, which does not compile — a resource method needs an
            // accessor and a path.
            const result = renderService({
                type: "fixed", name: "UpgradeService", listener: wsListener,
                methods: [method({
                    name: "get", type: "resource", accessor: "get",
                    methodValues: ["get"], methodRequired: true,
                    pathForm: ["stringLiteralSegment"], pathRequired: true,
                    parameters: [{ name: "request", description: "", type: { name: "http:Request" } }],
                    return: { type: { name: "Service|UpgradeError" } },
                })],
            });
            assert.ok(result.includes("resource function get pathSegment(http:Request request)"),
                `got:\n${result}`);
            assert.ok(!result.includes("remote function get"), "the remote keyword must be gone");
        });

        test("§11.2: the resource path is a placeholder and the legal forms are stated verbatim", () => {
            // Plan §11.2: which verb and which path segments is intent-derived, so the renderer may only
            // place a fillable placeholder and quote the document's vocabulary.
            const result = renderService({
                type: "fixed", name: "UpgradeService", listener: wsListener,
                methods: [method({
                    name: "get", type: "resource", accessor: "get",
                    methodValues: ["get"], methodRequired: true,
                    pathForm: ["stringLiteralSegment"], pathRequired: true,
                })],
            });
            const note = line(result, "# Resource:");
            assert.ok(note.includes("the accessor must be one of `get`"), note);
            assert.ok(note.includes("stringLiteralSegment"), note);
            assert.ok(note.includes("replace `pathSegment`"), note);
        });

        test("§5: a resource handler with no accessor degrades to remote rather than emitting broken syntax", () => {
            // Defensive path, no corpus instance: inventing `get` would be inventing API, and
            // `resource function  pathSegment(...)` would not compile.
            const result = renderService({
                type: "fixed", name: "Service", listener: wsListener,
                methods: [method({ name: "onEvent", type: "resource", pathForm: ["identifierSegments"] })],
            });
            assert.ok(result.includes("remote function onEvent("), `got:\n${result}`);
            assert.ok(result.includes("# Resource:"), "the resource nature is still stated");
        });

        test("§5: graphqlOperation renders as prose only, never as syntax", () => {
            // Spec §5 marks it informational.
            const result = renderService({
                type: "fixed", name: "Service", listener: wsListener,
                methods: [method({ name: "onEvent", graphqlOperation: "mutation",
                                   fieldNameForm: ["identifierSegment"] })],
            });
            assert.ok(line(result, "# Resource:").includes("this is a GraphQL mutation"));
            assert.ok(result.includes("remote function onEvent("), "a mutation is a remote method");
        });

        // ---- §5 presence ----

        test("§5: a required handler is marked `// required` and an optional one `// optional`", () => {
            // Corpus: kafka's onConsumerRecord is required and onError optional. Before this, both rendered
            // identically and the obligation was invisible.
            const result = renderService({
                type: "fixed", name: "Service", listener: wsListener,
                methods: [method({ name: "onConsumerRecord", optional: false }),
                          method({ name: "onError", optional: true })],
            });
            assert.ok(line(result, "onConsumerRecord").endsWith("// required"), line(result, "onConsumerRecord"));
            assert.ok(line(result, "onError").endsWith("// optional"), line(result, "onError"));
        });

        test("§5: a handler whose presence the document does not state carries no marker", () => {
            // Spec §5: presence is meaningful "Only under `addMode: subset`". grpc's four options are under
            // `many`, so neither marker may appear — saying "required" there would invent an obligation.
            const result = renderService({
                type: "fixed", name: "Service", listener: wsListener,
                methods: [method({ name: "unary" })],
            });
            const unary = line(result, "unary");
            assert.ok(!unary.includes("// required"), unary);
            assert.ok(!unary.includes("// optional"), unary);
            assert.ok(unary.trim().endsWith(";"), unary);
        });

        // ---- §7 param presence ----

        test("§7: an optional parameter is named on a `#` line, not marked inside the signature", () => {
            // A `//` comment inside a parameter list would comment out the closing paren and return type;
            // `Caller caller?` is not a Ballerina parameter form, and `= ()` needs a nilable type and would
            // turn "may be omitted" into "has a default".
            const result = renderService({
                type: "fixed", name: "Service", listener: wsListener,
                methods: [method({
                    name: "onFileCsv",
                    parameters: [
                        { name: "contents", description: "", type: { name: "string[][]" } },
                        { name: "caller", description: "", type: { name: "Caller" }, optional: true },
                    ],
                })],
            });
            assert.ok(result.includes("    # Optional parameters (may be omitted): caller"), `got:\n${result}`);
            const signature = line(result, "remote function onFileCsv");
            assert.strictEqual(signature,
                "    remote function onFileCsv(string[][] contents, Caller caller) returns error?;");
        });

        test("§7: several optional parameters are listed together in document order", () => {
            const result = renderService({
                type: "fixed", name: "Service", listener: wsListener,
                methods: [method({
                    parameters: [
                        { name: "caller", description: "", type: { name: "Caller" }, optional: true },
                        { name: "data", description: "", type: { name: "string" } },
                        { name: "extra", description: "", type: { name: "int" }, optional: true },
                    ],
                })],
            });
            assert.ok(result.includes("# Optional parameters (may be omitted): caller, extra"), `got:\n${result}`);
        });

        test("§7: a handler with only required parameters gets no note", () => {
            const result = renderService({
                type: "fixed", name: "Service", listener: wsListener,
                methods: [method({
                    parameters: [{ name: "data", description: "", type: { name: "string" } }],
                })],
            });
            assert.ok(!result.includes("# Optional parameters"), `got:\n${result}`);
        });

        test("both markers coexist without colliding when a handler and its parameter are optional", () => {
            // The reason param optionality is a `#` line and handler presence a trailing comment: they are
            // two different facts and must stay separately readable.
            const result = renderService({
                type: "fixed", name: "Service", listener: wsListener,
                methods: [method({
                    optional: true,
                    parameters: [{ name: "caller", description: "", type: { name: "Caller" }, optional: true }],
                })],
            });
            assert.ok(result.includes("# Optional parameters (may be omitted): caller"), `got:\n${result}`);
            assert.ok(line(result, "remote function onMessage").endsWith("// optional"));
        });

        // ---- §3 identifier ----

        test("§3: a required base path renders a fillable placeholder and says what to replace", () => {
            // Corpus: websocket's upgradeService, graphql and http declare {presence: required,
            // form: [basePath]}. None of it reached the prompt before.
            const result = renderService({
                type: "fixed", name: "UpgradeService", listener: wsListener, methods: [],
                identifier: { presence: "required", form: ["basePath"] },
            });
            assert.ok(result.includes("service websocket:UpgradeService /basePath on new websocket:Listener()"),
                `got:\n${result}`);
            assert.ok(line(result, "# The service identifier").includes("requires a base path"));
            assert.ok(line(result, "# The service identifier").includes("replace `/basePath`"));
        });

        test("§3: an optional identifier is described but not placeheld", () => {
            // Corpus: rabbitmq, smb, websub, mcp declare `optional`. Writing a placeholder would push the
            // model to fill a slot the connector does not need; the note states the option instead.
            const result = renderService({
                type: "fixed", name: "Service", listener: wsListener, methods: [],
                identifier: { presence: "optional", form: ["stringLiteral"] },
            });
            assert.ok(result.includes("service websocket:Service on new websocket:Listener()"),
                `no placeholder expected, got:\n${result}`);
            const note = line(result, "# The service identifier");
            assert.ok(note.includes("accepts a quoted string literal"), note);
            assert.ok(note.includes("may be omitted"), note);
        });

        test("§3: a required string literal renders a quoted placeholder", () => {
            const result = renderService({
                type: "fixed", name: "Service", listener: wsListener, methods: [],
                identifier: { presence: "required", form: ["stringLiteral"] },
            });
            assert.ok(result.includes(`service websocket:Service "identifier" on new`), `got:\n${result}`);
        });

        test("§3: a form outside spec §10's vocabulary is named, not placeheld", () => {
            // §10 enumerates only basePath and stringLiteral. Inventing syntax for an unknown shape would be
            // worse than describing it, and the raw token is kept so the reader can look it up.
            const result = renderService({
                type: "fixed", name: "Service", listener: wsListener, methods: [],
                identifier: { presence: "required", form: ["regexPattern"] },
            });
            assert.ok(line(result, "# The service identifier").includes("form `regexPattern`"));
            assert.ok(result.includes("service websocket:Service on new"), "no invented placeholder");
        });

        test("§3: a service with no identifier renders exactly as before", () => {
            const result = renderService({
                type: "fixed", name: "Service", listener: wsListener, methods: [],
            });
            assert.ok(!result.includes("# The service identifier"), `got:\n${result}`);
            assert.ok(result.includes("service websocket:Service on new websocket:Listener() {"));
        });

        // ---- §6 constraints ----

        test("§6: `oneOf` states an obligation and `atMostOne` states a limit", () => {
            // Spec §6: oneOf is "Exactly one member — not zero"; atMostOne is "zero or one ... but zero is
            // fine". Corpus: rabbitmq's messageHandlerChoice vs websocket's textMessageVsGeneric. Wording
            // them the same would invent an obligation websocket does not impose.
            const oneOf = renderService({
                type: "fixed", name: "Service", listener: wsListener, methods: [],
                constraints: [{ id: "messageHandlerChoice", kind: "oneOf",
                                members: [{ handler: "onMessage" }, { handler: "onRequest" }] }],
            });
            assert.ok(oneOf.includes(
                "# Exactly one of the following is required: `onMessage` | `onRequest`."), `got:\n${oneOf}`);

            const atMostOne = renderService({
                type: "fixed", name: "Service", listener: wsListener, methods: [],
                constraints: [{ id: "textMessageVsGeneric", kind: "atMostOne",
                                members: [{ handler: "onMessage" }, { handler: "onTextMessage" }] }],
            });
            assert.ok(atMostOne.includes(
                "# At most one of the following may be used: `onMessage` | `onTextMessage`."),
                `got:\n${atMostOne}`);
        });

        test("§6: the annotation-field and identifier member shapes both render, with `preferred` marked", () => {
            // Corpus: rabbitmq's queueNameSource — the queueName field of @rabbitmq:ServiceConfig (preferred)
            // versus the service identifier.
            const result = renderService({
                type: "fixed", name: "Service", listener: { name: "rabbitmq:Listener", parameters: [] },
                methods: [],
                constraints: [{ id: "queueNameSource", kind: "oneOf", members: [
                    // `annotation` is the resolved name; `annotationId` is the registry reference and must
                    // never be what the reader is told to write.
                    { annotation: "ServiceConfig", annotationId: "serviceConfig",
                      field: "queueName", preferred: true },
                    { part: "identifier" },
                ] }],
            }, "ballerinax/rabbitmq");
            const note = line(result, "# Exactly one of the following");
            assert.ok(note.includes("the `queueName` field of @rabbitmq:ServiceConfig (preferred)"), note);
            assert.ok(!note.includes("serviceConfig"), `the registry id must not be rendered: ${note}`);
            assert.ok(note.includes("the service identifier"), note);
        });

        test("§6: constraint lines precede the service declaration and the identifier note precedes them", () => {
            // A constraint may name the identifier as one of its alternatives, so the slot has to be
            // described first for the constraint line to make sense.
            const result = renderService({
                type: "fixed", name: "Service", listener: wsListener, methods: [],
                identifier: { presence: "optional", form: ["stringLiteral"] },
                constraints: [{ kind: "oneOf", members: [
                    { annotation: "ServiceConfig", field: "queueName" }, { part: "identifier" }] }],
            });
            const lines = result.split("\n");
            const identifierAt = lines.findIndex((l) => l.startsWith("# The service identifier"));
            const constraintAt = lines.findIndex((l) => l.startsWith("# Exactly one of"));
            const serviceAt = lines.findIndex((l) => l.startsWith("service websocket:Service"));
            assert.ok(identifierAt >= 0 && constraintAt > identifierAt && serviceAt > constraintAt,
                `order was ${identifierAt}/${constraintAt}/${serviceAt}:\n${result}`);
        });

        test("§6: a member populating none of the three shapes is skipped, and an empty rule renders nothing", () => {
            const result = renderService({
                type: "fixed", name: "Service", listener: wsListener, methods: [],
                constraints: [{ kind: "oneOf", members: [{}, { handler: "onMessage" }] },
                              { kind: "oneOf", members: [] }],
            });
            const notes = result.split("\n").filter((l) => l.startsWith("# Exactly one of"));
            assert.strictEqual(notes.length, 1, `got:\n${result}`);
            assert.ok(notes[0].endsWith("required: `onMessage`."), notes[0]);
        });

        test("general rule: a service declaring none of the new constructs renders exactly as before", () => {
            const result = renderService({
                type: "fixed", name: "Service", listener: wsListener,
                methods: [method({ name: "onOpen", parameters: [] })],
            });
            assert.strictEqual(result.split("\n").filter((l) => l.startsWith("#")).length, 0,
                `no notes expected:\n${result}`);
            assert.ok(result.includes("    remote function onOpen() returns error?;"), `got:\n${result}`);
        });
    });

    suite("Trigger spec §7/§8/§9 — alternatives, non-service annotations and data binding", () => {
        function renderService(service: Record<string, unknown>, libName = "ballerinax/kafka"): string {
            const lib = {
                name: libName, description: "", typeDefs: [], clients: [], services: [service],
            } as unknown as Library;
            return toSyntaxString([lib]);
        }

        const kafkaListener = { name: "kafka:Listener", parameters: [] };

        function service(methods: Record<string, unknown>[],
                         over: Record<string, unknown> = {}): Record<string, unknown> {
            return { type: "fixed", name: "Service", listener: kafkaListener, methods, ...over };
        }

        function method(over: Record<string, unknown> = {}): Record<string, unknown> {
            return {
                name: "onConsumerRecord", type: "remote",
                parameters: [], return: { type: { name: "error?" } }, ...over,
            };
        }

        function line(result: string, needle: string): string {
            const found = result.split("\n").find((l) => l.includes(needle));
            assert.ok(found, `no line containing "${needle}" in:\n${result}`);
            return found!;
        }

        function noLine(result: string, needle: string): void {
            assert.ok(!result.includes(needle),
                `unexpected line containing "${needle}" in:\n${result}`);
        }

        // ---- §7 alternatives ----

        test("§7: a union slot renders its other members as a note, never joined with `|`", () => {
            // §1: "Unions are an array of TypeRef, first element = codegen default"; §7: `type` "restates
            // the full static surface for this slot". A `|`-joined type would declare a union-typed
            // parameter, which is a different contract.
            // Corpus: kafka's onConsumerRecord — AnydataConsumerRecord[] then BytesConsumerRecord[].
            const result = renderService(service([method({
                parameters: [{
                    name: "consumerRecords", description: "",
                    type: { name: "AnydataConsumerRecord[]" },
                    alternatives: [{ name: "BytesConsumerRecord[]" }],
                }],
            })]));

            assert.ok(line(result, "may also be").includes(
                "# `consumerRecords` may also be: BytesConsumerRecord[]"), `got:\n${result}`);
            assert.ok(result.includes(
                "remote function onConsumerRecord(AnydataConsumerRecord[] consumerRecords)"),
                "the signature keeps the codegen default alone");
            noLine(result, "AnydataConsumerRecord[]|BytesConsumerRecord[]");
        });

        test("§7: several alternatives are listed in document order, comma-separated", () => {
            const result = renderService(service([method({
                parameters: [{
                    name: "content", description: "", type: { name: "string[][]" },
                    alternatives: [{ name: "record {}[]" }, { name: "stream<string[], error?>" }],
                }],
            })]));
            assert.strictEqual(line(result, "may also be").trim(),
                "# `content` may also be: record {}[], stream<string[], error?>");
        });

        test("§7: a cross-module alternative carries its own prefix", () => {
            const result = renderService(service([method({
                parameters: [{
                    name: "data", description: "", type: { name: "Request" },
                    alternatives: [{
                        name: "Headers",
                        links: [{ category: "external", recordName: "Headers",
                            libraryName: "ballerina/http" }],
                    }],
                }],
            })]));
            assert.ok(line(result, "may also be").includes("http:Headers"), `got:\n${result}`);
        });

        test("§7: a scalar slot states no alternatives", () => {
            const result = renderService(service([method({
                parameters: [{ name: "err", description: "", type: { name: "Error" } }],
            })]));
            noLine(result, "may also be");
        });

        // ---- §9 data binding ----

        test("§9: `direct` states the legal targets and `excludes` states the prohibition", () => {
            // §9: `direct` | "Param type directly *is* the target type — no wrapping." `excludes` is a
            // negative constraint, derivable from nothing else.
            // Corpus: kafka binds any anydata EXCEPT its own envelope.
            const result = renderService(service([method({
                parameters: [{
                    name: "consumerRecords", description: "",
                    type: { name: "AnydataConsumerRecord[]" },
                    binding: {
                        modes: [{
                            mode: "direct",
                            typeConstraint: [{ name: "anydata" }],
                            excludes: [{ name: "AnydataConsumerRecord" }],
                        }],
                    },
                }],
            })]));

            assert.strictEqual(line(result, "may bind directly").trim(),
                "# `consumerRecords` may bind directly to: anydata — but never AnydataConsumerRecord");
        });

        test("§9: `includedRecord` names the envelope and states which fields may be overridden", () => {
            // §9: `includedRecord` | "User record does `*EnvelopeType;`, overrides only `bindableFields`;
            // everything else stays fixed." The prohibition is the load-bearing half: naming the bindable
            // field does not by itself say the others are pinned.
            const result = renderService(service([method({
                parameters: [{
                    name: "consumerRecords", description: "",
                    type: { name: "AnydataConsumerRecord[]" },
                    binding: {
                        modes: [{
                            mode: "includedRecord",
                            includes: { name: "AnydataConsumerRecord" },
                            bindableFields: ["value"],
                            fixedFields: ["key", "timestamp", "offset", "headers"],
                        }],
                    },
                }],
            })]));

            assert.strictEqual(line(result, "includes").trim(),
                "# `consumerRecords` may bind to a record that includes "
                + "`*kafka:AnydataConsumerRecord;` and overrides only `value`");
        });

        test("§9: the envelope inclusion carries the module alias, because the user writes it", () => {
            // `*AnydataConsumerRecord;` in a user's own module does not resolve. The same rule the §8
            // attachment lines follow: syntax the reader writes is qualified.
            const result = renderService(service([method({
                parameters: [{
                    name: "message", description: "", type: { name: "AnydataMessage" },
                    binding: {
                        modes: [{ mode: "includedRecord", includes: { name: "AnydataMessage" },
                            bindableFields: ["content"] }],
                    },
                }],
            })], { listener: { name: "rabbitmq:Listener", parameters: [] } }), "ballerinax/rabbitmq");
            assert.ok(result.includes("`*rabbitmq:AnydataMessage;`"), `got:\n${result}`);
        });

        test("§9: `streamable` reads its own types and does not wrap them a second time", () => {
            // The declared members are already whole stream types; wrapping would emit
            // `stream<stream<...>>`.
            const result = renderService(service([method({
                parameters: [{
                    name: "content", description: "", type: { name: "string[][]" },
                    binding: {
                        modes: [{
                            mode: "streamable",
                            typeConstraint: [{ name: "stream<string[], error?>" },
                                { name: "stream<record {}, error?>" }],
                        }],
                    },
                }],
            })]));
            assert.strictEqual(line(result, "may bind to a stream").trim(),
                "# `content` may bind to a stream: stream<string[], error?>, stream<record {}, error?>");
            noLine(result, "stream<stream<");
        });

        test("§9: `cardinality: array` is stated, never applied — the type is not pluralized twice", () => {
            // §9: "the bound value is a batch; a mode's type is the array *element* type, not the whole
            // param type." kafka's parameter is already `AnydataConsumerRecord[]`.
            const result = renderService(service([method({
                parameters: [{
                    name: "consumerRecords", description: "",
                    type: { name: "AnydataConsumerRecord[]" },
                    binding: {
                        array: true,
                        modes: [{ mode: "direct", typeConstraint: [{ name: "anydata" }] }],
                    },
                }],
            })]));

            assert.ok(line(result, "binds a batch").includes(
                "# `consumerRecords` binds a batch; the types below are element types."),
                `got:\n${result}`);
            assert.ok(line(result, "may bind directly").endsWith("anydata"),
                "the element type is stated as declared");
            noLine(result, "anydata[]");
        });

        test("§9: under `array`, the includedRecord recipe says an array of records", () => {
            // The one line where leaving the batch disclaimer to the reader costs a compile error: the
            // parameter takes `MyRecord[]`, not `MyRecord`. The English pluralizes; the type name does not,
            // which is what would double-count against a signature that is already an array.
            const result = renderService(service([method({
                parameters: [{
                    name: "consumerRecords", description: "",
                    type: { name: "AnydataConsumerRecord[]" },
                    binding: {
                        array: true,
                        modes: [{ mode: "includedRecord", includes: { name: "AnydataConsumerRecord" },
                            bindableFields: ["value"] }],
                    },
                }],
            })]));
            assert.strictEqual(line(result, "may bind to an array").trim(),
                "# `consumerRecords` may bind to an array of records that include "
                + "`*kafka:AnydataConsumerRecord;` and override only `value`");
            noLine(result, "AnydataConsumerRecord[];`");
        });

        test("§9: a type already visible in the signature or alternatives is not repeated", () => {
            // The suppression rule. §7 makes the document restate the surface in `params[].type` "even
            // where `dataBindingRules` also says it" — deliberate in the document, noise in the prompt.
            // Corpus: ftp/smb's onFileCsv declares the same four types in both places.
            const result = renderService(service([method({
                parameters: [{
                    name: "content", description: "", type: { name: "string[][]" },
                    alternatives: [{ name: "record {}[]" }, { name: "stream<string[], error?>" }],
                    binding: {
                        modes: [
                            { mode: "direct",
                                typeConstraint: [{ name: "string[][]" }, { name: "record {}[]" }] },
                            { mode: "streamable",
                                typeConstraint: [{ name: "stream<string[], error?>" }] },
                        ],
                    },
                }],
            })]));

            assert.ok(line(result, "may also be").includes("record {}[], stream<string[], error?>"));
            noLine(result, "may bind directly");
            noLine(result, "may bind to a stream");
        });

        test("§9: suppression never hides `excludes`", () => {
            // A negative constraint is derivable from nothing else, so it survives even when every
            // positive member is already visible.
            const result = renderService(service([method({
                parameters: [{
                    name: "message", description: "", type: { name: "anydata" },
                    binding: {
                        modes: [{
                            mode: "direct",
                            typeConstraint: [{ name: "anydata" }],
                            excludes: [{ name: "AnydataMessage" }],
                        }],
                    },
                }],
            })]));
            assert.strictEqual(line(result, "may bind directly").trim(),
                "# `message` may bind directly to any type shown above — but never AnydataMessage");
        });

        test("§9: a mode with nothing left to say contributes no line", () => {
            // Corpus: mssql.cdc's rowState binds `record {}` for a parameter already typed `record {}`.
            const result = renderService(service([method({
                parameters: [{
                    name: "afterEntry", description: "", type: { name: "record {}" },
                    binding: { modes: [{ mode: "direct", typeConstraint: [{ name: "record {}" }] }] },
                }],
            })]));
            noLine(result, "may bind");
            noLine(result, "binds a batch");
        });

        // ---- §8 non-service attach points ----

        test("§8 function: a required handler annotation states the obligation and the attachment", () => {
            // Corpus: smb's functionConfig is `presence: "required"` — generated smb handlers may not work
            // without it, and it reached the prompt nowhere before.
            const result = renderService(service([method({
                name: "onFileChange",
                annotationRefs: [{
                    name: "FunctionConfig", presence: "required", attachPoint: "function",
                    typeConstraint: { name: "FunctionConfiguration" },
                }],
            })], { listener: { name: "smb:Listener", parameters: [] } }), "ballerina/smb");

            assert.ok(result.includes(
                "    # Mandatory: this handler must carry the @smb:FunctionConfig annotation."
                + " Replace {...} with its fields, which are those of FunctionConfiguration."),
                `got:\n${result}`);
            assert.ok(result.includes("    @smb:FunctionConfig {...} // required"), `got:\n${result}`);
        });

        test("§8 function: an optional handler annotation is marked optional", () => {
            // Corpus: ftp declares the optional counterpart on all eight of its handlers.
            const result = renderService(service([method({
                annotationRefs: [{ name: "FunctionConfig", presence: "optional",
                    attachPoint: "function" }],
            })], { listener: { name: "ftp:Listener", parameters: [] } }), "ballerina/ftp");
            assert.ok(result.includes("    @ftp:FunctionConfig {...} // optional"), `got:\n${result}`);
            assert.ok(result.includes("may carry the @ftp:FunctionConfig"), `got:\n${result}`);
        });

        test("§8: two annotations at one scope emit both notes before either attachment", () => {
            // Ballerina metadata requires every `#` line to precede every annotation. Emitting
            // note-then-attachment per annotation puts a `#` line after an `@` as soon as a construct
            // carries two, which the compiler rejects with "missing close bracket token". No corpus
            // document does this today; the hazard is one document away.
            const result = renderService(service([method({
                annotationRefs: [
                    { name: "First", presence: "required", attachPoint: "function" },
                    { name: "Second", presence: "optional", attachPoint: "function" },
                ],
            })]));
            const lines = result.split("\n").map((l) => l.trim());
            const lastNote = Math.max(lines.findIndex((l) => l.startsWith("# Mandatory: this handler")),
                lines.findIndex((l) => l.startsWith("# Optional: this handler")));
            const firstAttachment = Math.min(lines.findIndex((l) => l.startsWith("@kafka:First")),
                lines.findIndex((l) => l.startsWith("@kafka:Second")));
            assert.ok(lastNote < firstAttachment,
                `every # line must precede every @ line:\n${result}`);
        });

        test("§8 function: the obligation block sits after the notes and before @deprecated", () => {
            // Ballerina metadata puts every `#` line ahead of every annotation.
            const result = renderService(service([method({
                isDeprecated: true,
                parameters: [{ name: "x", description: "", type: { name: "int" },
                    alternatives: [{ name: "string" }] }],
                annotationRefs: [{ name: "FunctionConfig", presence: "optional",
                    attachPoint: "function" }],
            })]));
            const lines = result.split("\n").map((l) => l.trim());
            const note = lines.findIndex((l) => l.startsWith("# `x` may also be"));
            const obligation = lines.findIndex((l) => l.startsWith("# Optional: this handler"));
            const attachment = lines.findIndex((l) => l.startsWith("@kafka:FunctionConfig"));
            const deprecated = lines.findIndex((l) => l === "@deprecated");
            const signature = lines.findIndex((l) => l.startsWith("remote function"));

            assert.ok(note < obligation && obligation < attachment, `got:\n${result}`);
            assert.ok(attachment < deprecated && deprecated < signature, `got:\n${result}`);
        });

        test("§8 parameter: an OPTIONAL annotation is described, never written into the signature", () => {
            // The signature is copied as one unit, and an inline attachment cannot carry a `// optional`
            // marker — a comment inside a parameter list would comment out the closing paren. Writing an
            // optional annotation there would therefore read as mandatory. Same policy renderIdentifierSlot
            // applies to an optional identifier.
            // Corpus: rabbitmq's payload parameter, the only observable instance.
            const result = renderService(service([method({
                name: "onMessage",
                parameters: [{
                    name: "message", description: "", type: { name: "AnydataMessage" },
                    annotationRefs: [{
                        name: "Payload", presence: "optional", attachPoint: "parameter",
                        typeConstraint: { name: "RabbitmqPayload" },
                    }],
                }],
            })], { listener: { name: "rabbitmq:Listener", parameters: [] } }), "ballerinax/rabbitmq");

            assert.ok(result.includes("remote function onMessage(AnydataMessage message)"),
                `the signature stays copyable:\n${result}`);
            assert.ok(result.includes(
                "    # The `message` parameter may carry @rabbitmq:Payload, written"
                + " `@rabbitmq:Payload {}` before its type. Its fields are those of RabbitmqPayload."),
                `got:\n${result}`);
            const signature = line(result, "remote function onMessage");
            assert.ok(!signature.includes("//"),
                `a comment inside a parameter list breaks the line: ${signature}`);
        });

        test("§8 parameter: a REQUIRED annotation is written inline as `{}`, never `{...}`", () => {
            // Verified against the compiler: `@X {}` compiles; `@X {...}` fails with "incompatible types:
            // expected a map or a record, found 'other'" plus "missing expression". `{...}` is a template
            // marker, and a signature is not a place a template marker can survive.
            const result = renderService(service([method({
                name: "onMessage",
                parameters: [{
                    name: "message", description: "", type: { name: "AnydataMessage" },
                    annotationRefs: [{
                        name: "Payload", presence: "required", attachPoint: "parameter",
                        typeConstraint: { name: "RabbitmqPayload" },
                    }],
                }],
            })], { listener: { name: "rabbitmq:Listener", parameters: [] } }), "ballerinax/rabbitmq");

            assert.ok(result.includes(
                "remote function onMessage(@rabbitmq:Payload {} AnydataMessage message)"),
                `got:\n${result}`);
            assert.ok(!result.includes("{...} AnydataMessage"), `never the template marker:\n${result}`);
            assert.ok(line(result, "parameter must carry").includes("fill the `{}`"), `got:\n${result}`);
        });

        test("§8 parameter: a cross-module annotation carries its own prefix and provenance", () => {
            // Corpus: mcp's httpHeader names ballerina/http's Header.
            const result = renderService(service([method({
                parameters: [{
                    name: "header", description: "", type: { name: "string" },
                    annotationRefs: [{ name: "Header", module: "ballerina/http", presence: "optional",
                        attachPoint: "parameter" }],
                }],
            })]));
            assert.ok(line(result, "parameter may carry").includes("@http:Header"), `got:\n${result}`);
            assert.ok(line(result, "parameter may carry").includes("FROM ballerina/http package"),
                `got:\n${result}`);
        });

        test("§8 return: only a required annotation is written into the return slot", () => {
            // `returns @X {} T` compiles; `returns @X {...} T` does not. The corpus's only return-scope
            // annotation (http's `cache`) is optional AND http never reaches this pipeline, so the
            // required branch is exercised here or nowhere.
            const optional = renderService(service([method({
                return: {
                    type: { name: "error?" },
                    annotationRefs: [{ name: "Cache", presence: "optional", attachPoint: "return" }],
                },
            })]));
            assert.ok(optional.includes("returns error?;"), `got:\n${optional}`);
            assert.ok(!optional.includes("returns @kafka:Cache"),
                `an optional one is not applied:\n${optional}`);
            // ...but it must still be stated somewhere, or the attach point is advertised and silent.
            assert.ok(optional.includes(
                "    # The return may carry @kafka:Cache, written `@kafka:Cache {}` in the `returns`"
                + " clause."), `got:\n${optional}`);

            const required = renderService(service([method({
                return: {
                    type: { name: "error?" },
                    annotationRefs: [{ name: "Cache", presence: "required", attachPoint: "return" }],
                },
            })]));
            assert.ok(required.includes("returns @kafka:Cache {} error?;"), `got:\n${required}`);
        });

        test("general rule: a handler declaring none of the new constructs renders exactly as before", () => {
            const result = renderService(service([method({
                name: "onError",
                parameters: [{ name: "kafkaError", description: "", type: { name: "Error" } }],
            })]));
            assert.ok(result.includes("    remote function onError(Error kafkaError) returns error?;"),
                `got:\n${result}`);
            for (const marker of ["may also be", "may bind", "binds a batch", "must carry", "may carry"]) {
                noLine(result, marker);
            }
        });
    });
});
