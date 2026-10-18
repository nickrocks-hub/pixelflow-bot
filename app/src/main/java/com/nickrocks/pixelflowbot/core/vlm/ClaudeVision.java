package com.nickrocks.pixelflowbot.core.vlm;

import com.nickrocks.pixelflowbot.learn.Json;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Anthropic Claude Messages API (POST https://api.anthropic.com/v1/messages) with one base64 JPEG
 * screenshot per request. The API key is the user's own, typed into the app and kept in the app's
 * private storage — never in the source code.
 *
 * Request shape follows the Claude API vision docs: headers x-api-key, anthropic-version 2023-06-01,
 * content-type application/json; an image block (base64, image/jpeg) before the text block; low
 * effort via output_config.effort to keep replies fast.
 */
public final class ClaudeVision implements VisionModel {
    public static final String URL = "https://api.anthropic.com/v1/messages";
    public static final String VERSION = "2023-06-01";

    private final String apiKey;
    private final Pricing.Model model;
    public String effort = "low";
    public int timeoutMs = 30_000;

    public ClaudeVision(String apiKey, Pricing.Model model) {
        if (apiKey == null || apiKey.trim().isEmpty()) throw new IllegalArgumentException("no API key");
        this.apiKey = apiKey.trim();
        this.model = model;
    }

    @Override public String name() { return model.label; }

    /** The JSON body sent to the API (also used by tests to check the shape). */
    public String body(String system, byte[] jpeg, String prompt, int maxTokens) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"model\":").append(Json.q(model.id));
        sb.append(",\"max_tokens\":").append(maxTokens);
        sb.append(",\"output_config\":{\"effort\":").append(Json.q(effort)).append('}');
        sb.append(",\"system\":").append(Json.q(system));
        sb.append(",\"messages\":[{\"role\":\"user\",\"content\":[");
        if (jpeg != null) {
            sb.append("{\"type\":\"image\",\"source\":{\"type\":\"base64\",\"media_type\":\"image/jpeg\",\"data\":\"")
                    .append(Base64.getEncoder().encodeToString(jpeg)).append("\"}},");
        }
        sb.append("{\"type\":\"text\",\"text\":").append(Json.q(prompt)).append("}]}]}");
        return sb.toString();
    }

    @Override
    public Reply ask(String system, byte[] jpeg, String prompt, int maxTokens) throws IOException {
        long t0 = System.currentTimeMillis();
        byte[] out = body(system, jpeg, prompt, maxTokens).getBytes(StandardCharsets.UTF_8);
        HttpURLConnection c = (HttpURLConnection) java.net.URI.create(URL).toURL().openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        c.setDoOutput(true);
        c.setRequestProperty("x-api-key", apiKey);
        c.setRequestProperty("anthropic-version", VERSION);
        c.setRequestProperty("content-type", "application/json");
        try (OutputStream os = c.getOutputStream()) { os.write(out); }
        int code = c.getResponseCode();
        String resp = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
        c.disconnect();
        if (code >= 400) throw new IOException("Claude API " + code + ": " + errorMessage(resp));
        Reply r = parse(resp);
        r.ms = System.currentTimeMillis() - t0;
        r.costUsd = model.cost(r.inputTokens, r.outputTokens);
        r.model = model.id;
        return r;
    }

    /** Text blocks and token usage from a Messages API response. */
    public static Reply parse(String resp) throws IOException {
        Reply r = new Reply();
        try {
            Map<String, Object> m = Json.obj(Json.parse(resp));
            Object content = m.get("content");
            StringBuilder sb = new StringBuilder();
            if (content instanceof List) {
                for (Object o : (List<?>) content) {
                    Map<String, Object> b = Json.obj(o);
                    if ("text".equals(b.get("type"))) sb.append(Json.str(b, "text", ""));
                }
            }
            r.text = sb.toString();
            Object u = m.get("usage");
            if (u instanceof Map) {
                r.inputTokens = (int) Json.num(Json.obj(u), "input_tokens", 0);
                r.outputTokens = (int) Json.num(Json.obj(u), "output_tokens", 0);
            }
        } catch (RuntimeException e) {
            throw new IOException("couldn't read the API reply: " + e.getMessage());
        }
        return r;
    }

    static String errorMessage(String resp) {
        try {
            Map<String, Object> m = Json.obj(Json.parse(resp));
            Object e = m.get("error");
            if (e instanceof Map) return Json.str(Json.obj(e), "message", resp);
        } catch (RuntimeException ignored) { }
        return resp.length() > 200 ? resp.substring(0, 200) : resp;
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return new String(bo.toByteArray(), StandardCharsets.UTF_8);
    }
}
