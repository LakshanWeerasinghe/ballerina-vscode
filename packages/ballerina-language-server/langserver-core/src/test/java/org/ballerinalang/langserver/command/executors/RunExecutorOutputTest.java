/*
 * Copyright (c) 2026, WSO2 LLC. (https://www.wso2.com) All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.ballerinalang.langserver.command.executors;

import org.ballerinalang.langserver.commons.client.ExtendedLanguageClient;
import org.eclipse.lsp4j.LogTraceParams;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;

/**
 * Tests how the fast-run executor forwards a running program's output to the client.
 */
public class RunExecutorOutputTest {

    private static final String OUT_CHANNEL = "out";
    private static final String STOPPED_CHANNEL = "stopped";

    @Test(description = "Output is forwarded unchanged when a character spans two reads")
    public void testMultiByteCharactersSpanningReads() {
        // Each character here is three UTF-8 bytes, so characters land across the reader's buffer boundary.
        String programOutput = "日本語テキスト\n".repeat(500);
        Assert.assertTrue(programOutput.getBytes(StandardCharsets.UTF_8).length > 1024,
                "The output must be longer than one read for this test to be meaningful");

        Assert.assertEquals(forwardedOutput(programOutput), programOutput,
                "Program output must reach the client exactly as the program wrote it");
    }

    /**
     * Streams {@code programOutput} through the executor the way a running program's stdout is streamed, and returns
     * what the client was given, joined back together.
     *
     * @param programOutput text the program writes
     * @return text forwarded to the client on the out channel
     */
    private String forwardedOutput(String programOutput) {
        ExtendedLanguageClient client = Mockito.mock(ExtendedLanguageClient.class, Mockito.withSettings().stubOnly());
        ArgumentCaptor<LogTraceParams> logCaptor = ArgumentCaptor.forClass(LogTraceParams.class);
        Mockito.doNothing().when(client).logTrace(logCaptor.capture());

        InputStream stdout = new ByteArrayInputStream(programOutput.getBytes(StandardCharsets.UTF_8));
        new RunExecutor().listenOutputAsync(client, () -> stdout, OUT_CHANNEL);

        await().atMost(5, TimeUnit.SECONDS).until(() -> messagesOn(logCaptor, STOPPED_CHANNEL).size() == 1);
        return String.join("", messagesOn(logCaptor, OUT_CHANNEL));
    }

    private static List<String> messagesOn(ArgumentCaptor<LogTraceParams> logCaptor, String channel) {
        return List.copyOf(logCaptor.getAllValues()).stream()
                .filter(params -> params.getVerbose().equals(channel))
                .map(LogTraceParams::getMessage)
                .toList();
    }
}
