package com.ragagent.evaluation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.ragagent.evaluation.domain.QaPair;
import org.junit.jupiter.api.Test;

/**
 * DatasetService 单测：转换 JSON 与 Go dataset.go 的 DefaultDataset/Iterate 语义
 * 逐字段一致（样例数据固化：1 QA 对 / 4 passage）。
 */
class DatasetServiceTest {

    private final DatasetService service = new DatasetService();

    @Test
    void samplesMatchGoDataset() {
        List<QaPair> pairs = service.getDatasetByID("default");
        assertEquals(1, pairs.size());
        QaPair pair = pairs.get(0);
        assertEquals(1, pair.qid());
        assertEquals("计算机的操作系统有哪些", pair.question());
        // qrels 表序 [2,3,1,4] → passages 与 pids 一一对应
        assertEquals(List.of(2, 3, 1, 4), pair.pids());
        assertEquals(4, pair.passages().size());
        assertTrue(pair.passages().get(0).startsWith("UNIX 是在许多不同类型"),
                pair.passages().get(0));
        assertTrue(pair.passages().get(2).startsWith("Mac 操作系统。"), pair.passages().get(2));
        assertEquals(1, pair.aid());
        assertEquals("计算机的操作系统有 UNIX、Linux、FreeBSD、Mac OS 和 DOS。", pair.answer());
    }

    @Test
    void datasetIdIgnored() {
        assertEquals(1, service.getDatasetByID("").size());
        assertEquals(1, service.getDatasetByID("nosuch-dataset").size());
    }
}
