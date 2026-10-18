package com.nickrocks.pixelflowbot.core.vlm;

import java.io.IOException;

/**
 * A vision-language model that looks at one screenshot plus text and answers in text.
 * Implementations: {@link ClaudeVision} (Anthropic Claude API, opt-in, user's own key) and fakes for tests.
 */
public interface VisionModel {
    final class Reply {
        public String text = "";
        public int inputTokens, outputTokens;
        public double costUsd;
        public long ms;
        public String model = "";
    }

    /**
     * @param system   instructions for the model
     * @param jpeg     the screenshot (already shrunk), or null for text-only
     * @param prompt   the request for this step
     */
    Reply ask(String system, byte[] jpeg, String prompt, int maxTokens) throws IOException;

    String name();
}
