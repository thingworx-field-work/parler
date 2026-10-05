package com.thingworx.things.agent.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

class ChatCompletionsClientDiagnosticsTest {

    @Test
    void openAiAndAzureWarningSeamEmitsOneContentFreeWarningPerEntry() {
        SuffixClassificationDiagnostics diagnostics = SuffixClassificationDiagnostics.fromPlannedMessages(List.of(
                ChatMessage.system("stable"),
                ChatMessage.system("unclassified-secret"),
                ChatMessage.user("u")));
        List<String> calls = new ArrayList<>();
        Logger logger = recordingLogger(calls);

        SuffixClassificationWarningLogger.log(logger, "openai", diagnostics);
        SuffixClassificationWarningLogger.log(logger, "azure-openai", diagnostics);

        assertEquals(2, calls.size());
        assertTrue(calls.get(0).contains("openai"));
        assertTrue(calls.get(1).contains("azure-openai"));
        assertTrue(calls.get(0).contains("b645abfd0f5b985a"));
        assertFalse(calls.toString().contains("unclassified-secret"));
    }

    static Logger recordingLogger(List<String> calls) {
        return (Logger) Proxy.newProxyInstance(
                Logger.class.getClassLoader(),
                new Class<?>[] { Logger.class },
                (proxy, method, args) -> {
                    if ("warn".equals(method.getName())) {
                        calls.add(flatten(args));
                    }
                    Class<?> returnType = method.getReturnType();
                    if (returnType == boolean.class) {
                        return false;
                    }
                    if (returnType == int.class) {
                        return 0;
                    }
                    if (returnType == long.class) {
                        return 0L;
                    }
                    return null;
                });
    }

    private static String flatten(Object[] args) {
        if (args == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (Object arg : args) {
            if (arg instanceof Object[]) {
                for (Object nested : (Object[]) arg) {
                    out.append('|').append(nested);
                }
            } else {
                out.append('|').append(arg);
            }
        }
        return out.toString();
    }
}
