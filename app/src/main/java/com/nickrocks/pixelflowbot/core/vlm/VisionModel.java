package com.nickrocks.pixelflowbot.core.vlm;

import java.io.IOException;

/**
 * A vision-language model that looks at one screenshot plus text and answers in text.
 * The app's implementation runs entirely on the phone (LocalVision, LiteRT-LM); tests use fakes.
 */
public interface VisionModel {
    final class Reply {
        public String text = "";
        public long ms;
        public String model = "";
    }

    /**
     * @param system   instructions for the model
     * @param jpeg     the screenshot (already shrunk, grid drawn on it), or null for text-only
     * @param prompt   the request for this step
     */
    Reply ask(String system, byte[] jpeg, String prompt, int maxTokens) throws IOException;

    String name();
}
