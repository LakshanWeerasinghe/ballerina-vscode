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

import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Pins spec §5's {@code kind} vocabulary and §10's enumeration of it.
 *
 * @since 1.7.0
 */
public class HandlerKindResolverTest {

    @Test
    public void testSpec10VocabularyIsRemoteAndResource() {
        // §10: `handlers.options[].kind` | `remote`, `resource`.
        Assert.assertEquals(HandlerKindResolver.resolve("remote"), HandlerKindResolver.Kind.REMOTE);
        Assert.assertEquals(HandlerKindResolver.resolve("resource"), HandlerKindResolver.Kind.RESOURCE);
    }

    @Test
    public void testOnlyResourceIsAResource() {
        // The renderer's keyword choice hangs off this one predicate, and `resource function` without a
        // path does not compile — so nothing but an explicit "resource" may answer true.
        Assert.assertTrue(HandlerKindResolver.resolve("resource").isResource());
        Assert.assertFalse(HandlerKindResolver.resolve("remote").isResource());
        Assert.assertFalse(HandlerKindResolver.resolve(null).isResource());
        Assert.assertFalse(HandlerKindResolver.resolve("Resource").isResource(),
                "The vocabulary is case-sensitive; a near-miss must not be read as a resource");
    }

    @Test
    public void testAnAbsentOrUnknownKindDegradesToRemoteRatherThanFailing() {
        // Losing a whole handler over an unrecognised token would be worse than rendering it as the shape
        // every non-resource handler in the corpus takes.
        Assert.assertEquals(HandlerKindResolver.resolve(null), HandlerKindResolver.Kind.REMOTE);
        Assert.assertEquals(HandlerKindResolver.resolve(""), HandlerKindResolver.Kind.REMOTE);
        Assert.assertEquals(HandlerKindResolver.resolve("rpc"), HandlerKindResolver.Kind.REMOTE);
    }

    @Test
    public void testTheWireValueIsTheSpecsOwnToken() {
        // The wire carries the document's vocabulary, not a Java enum name, so the renderer dispatches on
        // what the spec says.
        Assert.assertEquals(HandlerKindResolver.Kind.REMOTE.wireValue(), "remote");
        Assert.assertEquals(HandlerKindResolver.Kind.RESOURCE.wireValue(), "resource");
    }

    @Test
    public void testADeclaredMethodsKindReadsTheSameVocabulary() {
        // A concrete service type's kind comes from the semantic model rather than the document, but
        // TriggerSemanticFacts reports the same two tokens — so one vocabulary, one resolver.
        Assert.assertEquals(HandlerKindResolver.resolveDeclared("resource"),
                HandlerKindResolver.Kind.RESOURCE);
        Assert.assertEquals(HandlerKindResolver.resolveDeclared("remote"), HandlerKindResolver.Kind.REMOTE);
        Assert.assertEquals(HandlerKindResolver.resolveDeclared(null), HandlerKindResolver.Kind.REMOTE);
    }
}
