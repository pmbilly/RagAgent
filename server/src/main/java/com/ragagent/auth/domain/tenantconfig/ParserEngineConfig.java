package com.ragagent.auth.domain.tenantconfig;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 解析引擎配置段。
 *
 * <p>字段序 = JSON 键序。仅 mineru_endpoint / mineru_api_key 恒输出
 * （零值 ""），其余空值省略。布尔三态用 {@link Boolean}。</p>
 *
 * <p>chat_parser_engine_rules 的元素类型不建强类型——
 * 规则由 agent 侧配置，租户级只是透传保留（合并的 legacy 分支），
 * 用 JsonNode 带过，键序与原文一致。</p>
 */
@JsonPropertyOrder({
        "chat_parser_engine_rules", "mineru_endpoint", "mineru_api_key",
        "mineru_model", "mineru_vlm_server_url", "mineru_enable_formula",
        "mineru_enable_table", "mineru_parse_method", "mineru_enable_ocr",
        "mineru_language", "mineru_cloud_model", "mineru_cloud_enable_formula",
        "mineru_cloud_enable_table", "mineru_cloud_enable_ocr", "mineru_cloud_language",
        "odl_hybrid", "odl_hybrid_url", "odl_hybrid_mode", "odl_hybrid_fallback",
        "odl_markdown_with_html", "paddleocr_vl_endpoint",
        "paddleocr_vl_use_seal_recognition", "paddleocr_vl_use_chart_recognition",
        "paddleocr_vl_cloud_token", "paddleocr_vl_cloud_model",
        "paddleocr_vl_cloud_use_seal_recognition", "paddleocr_vl_cloud_use_chart_recognition"
})
public class ParserEngineConfig {

    /** 列表空/缺失都省略键；元素透传 */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("chat_parser_engine_rules")
    private List<JsonNode> chatParserEngineRules;

    @JsonProperty("mineru_endpoint")
    private String mineruEndpoint = "";

    @JsonProperty("mineru_api_key")
    private String mineruApiKey = "";

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("mineru_model")
    private String mineruModel = "";

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("mineru_vlm_server_url")
    private String mineruVlmServerUrl = "";

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("mineru_enable_formula")
    private Boolean mineruEnableFormula;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("mineru_enable_table")
    private Boolean mineruEnableTable;

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("mineru_parse_method")
    private String mineruParseMethod = "";

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("mineru_enable_ocr")
    private Boolean mineruEnableOcr;

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("mineru_language")
    private String mineruLanguage = "";

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("mineru_cloud_model")
    private String mineruCloudModel = "";

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("mineru_cloud_enable_formula")
    private Boolean mineruCloudEnableFormula;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("mineru_cloud_enable_table")
    private Boolean mineruCloudEnableTable;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("mineru_cloud_enable_ocr")
    private Boolean mineruCloudEnableOcr;

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("mineru_cloud_language")
    private String mineruCloudLanguage = "";

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("odl_hybrid")
    private String odlHybrid = "";

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("odl_hybrid_url")
    private String odlHybridUrl = "";

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("odl_hybrid_mode")
    private String odlHybridMode = "";

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("odl_hybrid_fallback")
    private Boolean odlHybridFallback;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("odl_markdown_with_html")
    private Boolean odlMarkdownWithHtml;

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("paddleocr_vl_endpoint")
    private String paddleOcrVlEndpoint = "";

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("paddleocr_vl_use_seal_recognition")
    private Boolean paddleOcrVlUseSealRecognition;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("paddleocr_vl_use_chart_recognition")
    private Boolean paddleOcrVlUseChartRecognition;

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("paddleocr_vl_cloud_token")
    private String paddleOcrVlCloudToken = "";

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("paddleocr_vl_cloud_model")
    private String paddleOcrVlCloudModel = "";

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("paddleocr_vl_cloud_use_seal_recognition")
    private Boolean paddleOcrVlCloudUseSealRecognition;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("paddleocr_vl_cloud_use_chart_recognition")
    private Boolean paddleOcrVlCloudUseChartRecognition;

    public List<JsonNode> getChatParserEngineRules() { return chatParserEngineRules; }
    public void setChatParserEngineRules(List<JsonNode> v) { chatParserEngineRules = v; }
    public String getMineruEndpoint() { return mineruEndpoint; }
    public void setMineruEndpoint(String v) { mineruEndpoint = v == null ? "" : v; }
    public String getMineruApiKey() { return mineruApiKey; }
    public void setMineruApiKey(String v) { mineruApiKey = v == null ? "" : v; }
    public String getMineruModel() { return mineruModel; }
    public void setMineruModel(String v) { mineruModel = v == null ? "" : v; }
    public String getMineruVlmServerUrl() { return mineruVlmServerUrl; }
    public void setMineruVlmServerUrl(String v) { mineruVlmServerUrl = v == null ? "" : v; }
    public Boolean getMineruEnableFormula() { return mineruEnableFormula; }
    public void setMineruEnableFormula(Boolean v) { mineruEnableFormula = v; }
    public Boolean getMineruEnableTable() { return mineruEnableTable; }
    public void setMineruEnableTable(Boolean v) { mineruEnableTable = v; }
    public String getMineruParseMethod() { return mineruParseMethod; }
    public void setMineruParseMethod(String v) { mineruParseMethod = v == null ? "" : v; }
    public Boolean getMineruEnableOcr() { return mineruEnableOcr; }
    public void setMineruEnableOcr(Boolean v) { mineruEnableOcr = v; }
    public String getMineruLanguage() { return mineruLanguage; }
    public void setMineruLanguage(String v) { mineruLanguage = v == null ? "" : v; }
    public String getMineruCloudModel() { return mineruCloudModel; }
    public void setMineruCloudModel(String v) { mineruCloudModel = v == null ? "" : v; }
    public Boolean getMineruCloudEnableFormula() { return mineruCloudEnableFormula; }
    public void setMineruCloudEnableFormula(Boolean v) { mineruCloudEnableFormula = v; }
    public Boolean getMineruCloudEnableTable() { return mineruCloudEnableTable; }
    public void setMineruCloudEnableTable(Boolean v) { mineruCloudEnableTable = v; }
    public Boolean getMineruCloudEnableOcr() { return mineruCloudEnableOcr; }
    public void setMineruCloudEnableOcr(Boolean v) { mineruCloudEnableOcr = v; }
    public String getMineruCloudLanguage() { return mineruCloudLanguage; }
    public void setMineruCloudLanguage(String v) { mineruCloudLanguage = v == null ? "" : v; }
    public String getOdlHybrid() { return odlHybrid; }
    public void setOdlHybrid(String v) { odlHybrid = v == null ? "" : v; }
    public String getOdlHybridUrl() { return odlHybridUrl; }
    public void setOdlHybridUrl(String v) { odlHybridUrl = v == null ? "" : v; }
    public String getOdlHybridMode() { return odlHybridMode; }
    public void setOdlHybridMode(String v) { odlHybridMode = v == null ? "" : v; }
    public Boolean getOdlHybridFallback() { return odlHybridFallback; }
    public void setOdlHybridFallback(Boolean v) { odlHybridFallback = v; }
    public Boolean getOdlMarkdownWithHtml() { return odlMarkdownWithHtml; }
    public void setOdlMarkdownWithHtml(Boolean v) { odlMarkdownWithHtml = v; }
    public String getPaddleOcrVlEndpoint() { return paddleOcrVlEndpoint; }
    public void setPaddleOcrVlEndpoint(String v) { paddleOcrVlEndpoint = v == null ? "" : v; }
    public Boolean getPaddleOcrVlUseSealRecognition() { return paddleOcrVlUseSealRecognition; }
    public void setPaddleOcrVlUseSealRecognition(Boolean v) { paddleOcrVlUseSealRecognition = v; }
    public Boolean getPaddleOcrVlUseChartRecognition() { return paddleOcrVlUseChartRecognition; }
    public void setPaddleOcrVlUseChartRecognition(Boolean v) { paddleOcrVlUseChartRecognition = v; }
    public String getPaddleOcrVlCloudToken() { return paddleOcrVlCloudToken; }
    public void setPaddleOcrVlCloudToken(String v) { paddleOcrVlCloudToken = v == null ? "" : v; }
    public String getPaddleOcrVlCloudModel() { return paddleOcrVlCloudModel; }
    public void setPaddleOcrVlCloudModel(String v) { paddleOcrVlCloudModel = v == null ? "" : v; }
    public Boolean getPaddleOcrVlCloudUseSealRecognition() { return paddleOcrVlCloudUseSealRecognition; }
    public void setPaddleOcrVlCloudUseSealRecognition(Boolean v) { paddleOcrVlCloudUseSealRecognition = v; }
    public Boolean getPaddleOcrVlCloudUseChartRecognition() { return paddleOcrVlCloudUseChartRecognition; }
    public void setPaddleOcrVlCloudUseChartRecognition(Boolean v) { paddleOcrVlCloudUseChartRecognition = v; }
}
