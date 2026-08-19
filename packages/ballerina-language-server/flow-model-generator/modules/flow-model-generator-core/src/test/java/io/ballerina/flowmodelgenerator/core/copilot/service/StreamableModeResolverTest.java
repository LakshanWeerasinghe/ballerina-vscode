/*
 *  Copyright (c) 2026, WSO2 LLC. (http://www.wso2.com)
 *
 *  WSO2 LLC. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package io.ballerina.flowmodelgenerator.core.copilot.service;

import io.ballerina.modelgenerator.commons.trigger.models.TriggerMetadataModel;
import io.ballerina.modelgenerator.commons.trigger.models.TypeRef;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.List;
import java.util.function.Predicate;

/**
 * Pins spec §9's {@code streamable} mode, whose one real hazard is reading the wrong slot.
 *
 * @since 1.7.0
 */
public class StreamableModeResolverTest {

    private static final Predicate<String> DECLARES_NONE = name -> false;

    @Test
    public void testItReadsItsOwnTypeConstraintNotTheDirectModes() {
        // §9: `streamable` | fields `typeConstraint`. The sibling consumer detects the mode and then reuses
        // the `direct` mode's constraint, which for ftp/smb is a different list entirely — that would emit
        // a stream over the wrong element type.
        TriggerMetadataModel.DataBindingRule.SupportedMode streamable = mode(
                List.of(ref("stream<string[], error?>"), ref("stream<record {}, error?>")));

        Assert.assertEquals(StreamableModeResolver.resolve(streamable, "ftp", DECLARES_NONE)
                        .typeConstraint(),
                List.of("stream<string[], error?>", "stream<record {}, error?>"),
                "ftp's csvContent streamable constraint, not its direct one (string[][], record {}[])");
    }

    @Test
    public void testTheDeclaredTypesAreAlreadyStreamShapedAndAreNotWrappedAgain() {
        // §9 words the mode as "same as `direct`, but `stream<...>` over the target type", but every corpus
        // member is written as a whole stream type. A consumer that wrapped these in a second `stream<>`
        // would emit `stream<stream<string[], error?>, error?>`.
        List<String> resolved = StreamableModeResolver.resolve(
                mode(List.of(ref("stream<byte[], error?>"))), "ftp", DECLARES_NONE).typeConstraint();
        Assert.assertEquals(resolved, List.of("stream<byte[], error?>"));
        Assert.assertFalse(resolved.get(0).startsWith("stream<stream<"));
    }

    @Test
    public void testAnAbsentConstraintYieldsAnEmptyListRatherThanNull() {
        Assert.assertTrue(StreamableModeResolver.resolve(mode(null), "ftp", DECLARES_NONE)
                .typeConstraint().isEmpty());
    }

    // ---- fixtures --------------------------------------------------------------------

    private static TriggerMetadataModel.DataBindingRule.SupportedMode mode(List<TypeRef> typeConstraint) {
        return new TriggerMetadataModel.DataBindingRule.SupportedMode(
                TriggerMetadataModel.DataBindingRule.SupportedMode.MODE_STREAMABLE, typeConstraint, null,
                null, null);
    }

    private static TypeRef ref(String name) {
        return new TypeRef(name, null);
    }
}
