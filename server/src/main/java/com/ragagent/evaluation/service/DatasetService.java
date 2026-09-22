package com.ragagent.evaluation.service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.evaluation.domain.QaPair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 对照 Go {@code DatasetService}（internal/application/service/dataset.go 全文）。
 *
 * <p>与 Go 一致：{@code GetDatasetByID} <b>忽略入参 datasetID</b>，总返回默认数据集
 * （{@code DefaultDataset()} + {@code PrintStats} 日志 + {@code Iterate()}）。</p>
 *
 * <p><b>数据加载实现差异（Javadoc 即契约）</b>：Go 运行时从
 * {@code ./dataset/samples/{queries,corpus,answers,qrels,qas}.parquet}（parquet-go）读
 * 5 张表；Java 以<b>同数据</b>的一次性转换 JSON（classpath
 * {@code dataset/samples.json}）内置读取——样例数据极小（1 QA 对 / 4 passage / 1 答案）
 * 且随仓库固化，QA 对内容逐字一致。数据集更新时需用 Go 转换脚本重新生成
 * （见 docs/known-issues）。文件缺失 Go panic、Java 抛 IllegalStateException
 * （均为部署期错误，非运行期契约）。</p>
 */
@Service
public class DatasetService {

    private static final Logger log = LoggerFactory.getLogger(DatasetService.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 对照 Go 的 dataset 结构（5 张表；键序 = 转换 JSON 键序，即数值升序）。 */
    private record Dataset(Map<Long, String> queries, Map<Long, String> corpus,
                           Map<Long, String> answers, Map<Long, List<Long>> qrels,
                           Map<Long, Long> qas) {
    }

    private volatile Dataset cached;

    /** 对照 GetDatasetByID：忽略 datasetID，恒取默认数据集。 */
    public List<QaPair> getDatasetByID(String datasetId) {
        Dataset dataset = dataset();
        printStats(dataset);
        List<QaPair> pairs = iterate(dataset);
        log.info("Retrieved {} QA pairs from dataset", pairs.size());
        return pairs;
    }

    /** 对照 dataset.Iterate：qid → QAPair（无答案时 aid=0/answer=""；passages 与 pids 同序）。 */
    static List<QaPair> iterate(Dataset dataset) {
        List<QaPair> pairs = new ArrayList<>();
        for (Map.Entry<Long, String> entry : dataset.queries().entrySet()) {
            long qid = entry.getKey();
            String question = entry.getValue();
            Long aid = dataset.qas().get(qid);
            String answer = "";
            if (aid != null) {
                answer = dataset.answers().getOrDefault(aid, "");
            }
            List<Long> pids = dataset.qrels().getOrDefault(qid, List.of());
            List<Integer> pidList = new ArrayList<>(pids.size());
            List<String> passages = new ArrayList<>(pids.size());
            for (Long pid : pids) {
                pidList.add(pid.intValue());
                passages.add(dataset.corpus().getOrDefault(pid, ""));
            }
            pairs.add(new QaPair((int) qid, question, pidList, passages,
                    aid == null ? 0 : aid.intValue(), answer));
        }
        return pairs;
    }

    /** 对照 dataset.PrintStats：统计日志（0/0 时 Go/Java 同为 NaN——除法语义照抄）。 */
    private static void printStats(Dataset dataset) {
        log.info("QA System Statistics:");
        log.info("- Total queries: {}", dataset.queries().size());
        log.info("- Total corpus passages: {}", dataset.corpus().size());
        log.info("- Total answers: {}", dataset.answers().size());
        long totalRelations = 0;
        for (List<Long> pids : dataset.qrels().values()) {
            totalRelations += pids.size();
        }
        double avgPassages = (double) totalRelations / dataset.qrels().size();
        log.info("- Average passages per query: {}", String.format("%.2f", avgPassages));
        int coveredQueries = dataset.qas().size();
        double coverage = (double) coveredQueries / dataset.queries().size() * 100;
        log.info("- Answer coverage: {}% ({}/{})",
                String.format("%.2f", coverage), coveredQueries, dataset.queries().size());
    }

    private Dataset dataset() {
        Dataset local = cached;
        if (local != null) {
            return local;
        }
        synchronized (this) {
            if (cached == null) {
                cached = load();
            }
            return cached;
        }
    }

    private static Dataset load() {
        try (InputStream in = DatasetService.class.getResourceAsStream("/dataset/samples.json")) {
            if (in == null) {
                // 对照 Go DefaultDataset 的 panic（部署期错误）
                throw new IllegalStateException("dataset samples missing: /dataset/samples.json");
            }
            JsonNode root = MAPPER.readTree(in);
            return new Dataset(
                    readTextMap(root.get("queries")),
                    readTextMap(root.get("corpus")),
                    readTextMap(root.get("answers")),
                    readRelsMap(root.get("qrels")),
                    readQasMap(root.get("qas")));
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("load dataset samples: " + e.getMessage(), e);
        }
    }

    private static Map<Long, String> readTextMap(JsonNode node) {
        Map<Long, String> out = new TreeMap<>();
        if (node != null) {
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                out.put(Long.parseLong(e.getKey()), e.getValue().asText());
            }
        }
        return out;
    }

    private static Map<Long, List<Long>> readRelsMap(JsonNode node) {
        Map<Long, List<Long>> out = new TreeMap<>();
        if (node != null) {
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                List<Long> pids = new ArrayList<>();
                for (JsonNode pid : e.getValue()) {
                    pids.add(pid.asLong());
                }
                out.put(Long.parseLong(e.getKey()), pids);
            }
        }
        return out;
    }

    private static Map<Long, Long> readQasMap(JsonNode node) {
        Map<Long, Long> out = new TreeMap<>();
        if (node != null) {
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                out.put(Long.parseLong(e.getKey()), e.getValue().asLong());
            }
        }
        return out;
    }
}
