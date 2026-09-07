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

// L1 regression test for issue #2307: switching a FLAG (boolean) field to
// Expression mode crashed because the raw JS boolean form value was handed to
// the CodeMirror-backed chip editor, whose doc/insert APIs require a string
// (EditorState.create({ doc: <boolean> }) throws). jsdom cannot instantiate the
// CodeMirror editor (see docs/TEST_GUIDE "jsdom limits"), so instead of driving
// the live editor we stub ChipExpressionEditorComponent and assert the
// normalization invariant at the funnel boundary: ExpressionField must hand the
// chip editor a string | null | undefined — never a boolean/number. This is the
// AWS SQS "Auto Delete Messages" flow (type: FLAG, value: false) in EXP mode.

import React from "react";
import { render } from "@testing-library/react";
import type { FormField } from "../components/Form/types";
import { InputMode } from "../components/editors/MultiModeExpressionEditor/ChipExpressionEditor/types";

// Capture whatever value ExpressionField passes to the chip editor.
const chipValues: unknown[] = [];
jest.mock(
    "../components/editors/MultiModeExpressionEditor/ChipExpressionEditor/components/ChipExpressionEditor",
    () => {
        // eslint-disable-next-line @typescript-eslint/no-var-requires
        const react = require("react");
        return {
            __esModule: true,
            ChipExpressionEditorComponent: (props: any) => {
                chipValues.push(props.value);
                return react.createElement("div", {
                    "data-testid": "ChipExpressionEditorComponent",
                    "data-value": String(props.value),
                });
            },
        };
    }
);

// eslint-disable-next-line @typescript-eslint/no-var-requires
const { ExpressionField } = require("../components/editors/ExpressionField");

const autoDeleteMessagesField = (value: any): FormField =>
    ({
        key: "autoDeleteMessages",
        label: "Auto Delete Messages",
        type: "FLAG",
        types: [{ fieldType: "FLAG" }, { fieldType: "EXPRESSION" }],
        value,
        optional: true,
        editable: true,
        enabled: true,
    } as unknown as FormField);

const renderExpressionField = (value: any) =>
    render(
        <ExpressionField
            field={autoDeleteMessagesField(value)}
            inputMode={InputMode.EXP}
            primaryMode={InputMode.BOOLEAN}
            name="autoDeleteMessages"
            value={value}
            completions={[]}
            onChange={() => {}}
            isHelperPaneOpen={false}
            changeHelperPaneState={() => {}}
            onToggleHelperPane={() => {}}
            exprRef={React.createRef()}
            anchorRef={React.createRef()}
        />
    );

describe("ExpressionField chip-editor value normalization (issue #2307)", () => {
    beforeEach(() => {
        chipValues.length = 0;
    });

    it.each([
        ["boolean false", false, "false"],
        ["boolean true", true, "true"],
    ])(
        "INVARIANT: a FLAG %s reaches the chip editor as a string, not a boolean",
        (_desc, value, expected) => {
            renderExpressionField(value);
            const received = chipValues.at(-1);
            expect(typeof received).toBe("string");
            expect(received).toBe(expected);
        }
    );

    it.each([
        ["a string expression", "check foo()"],
        ["an empty string", ""],
    ])("passes %s through unchanged", (_desc, value) => {
        renderExpressionField(value);
        expect(chipValues.at(-1)).toBe(value);
    });

    it.each([
        ["null", null],
        ["undefined", undefined],
    ])("leaves %s as-is (never coerced to a string)", (_desc, value) => {
        renderExpressionField(value);
        expect(chipValues.at(-1)).toBe(value);
    });
});
