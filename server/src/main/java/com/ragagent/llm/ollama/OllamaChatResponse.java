package com.ragagent.llm.ollama;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Ollama {@code POST /api/chat} 响应（对照 ollamaapi.ChatResponse，
 * ollama@v0.23.2/api/types.go:534-560 + Metrics）。
 *
 * <p>流式时同一个结构体会来很多次，最后一次 {@code done=true} 并带上
 * {@code prompt_eval_count} / {@code eval_count}。</p>
 *
 * <p><b>注意两个用量的口径不一致</b>（Go 侧既有行为，见 ollama.go 的取用点）：
 * 非流式路径算 {@code eval_count - prompt_eval_count} 当补全量，
 * 流式路径直接用 {@code eval_count}。两边照抄，不要统一。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class OllamaChatResponse {

    @JsonProperty("model")
    private String model;
    @JsonProperty("created_at")
    private String createdAt;
    @JsonProperty("message")
    private OllamaMessage message;
    @JsonProperty("done")
    private boolean done;
    @JsonProperty("done_reason")
    private String doneReason;
    @JsonProperty("prompt_eval_count")
    private int promptEvalCount;
    @JsonProperty("eval_count")
    private int evalCount;

    public String getModel() { return model; }
    public String getCreatedAt() { return createdAt; }
    public OllamaMessage getMessage() { return message; }
    public boolean isDone() { return done; }
    public String getDoneReason() { return doneReason; }
    public int getPromptEvalCount() { return promptEvalCount; }
    public int getEvalCount() { return evalCount; }
}
