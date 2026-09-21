package com.ragagent.storage.fileserve;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * {@link FileTransport} 的纯函数契约——期望值全部是 Go 实录
 * （/tmp/w5crec 探针跑 go1.26 的 {@code mime.FormatMediaType}；
 * parseRange 语义对照 go1.26 {@code net/http/fs.go}，错误文案逐字）。
 */
class FileTransportTest {

    // ── FormatMediaType（Go 实录 24 条）─────────────────────────────────────

    @Test
    void formatMediaTypeMatchesGo() {
        record V(String name, String inline, String attachment) {
        }
        List<V> vectors = List.of(
                new V("w5c-seed.txt", "inline; filename=w5c-seed.txt",
                        "attachment; filename=w5c-seed.txt"),
                new V("my file.txt", "inline; filename=\"my file.txt\"",
                        "attachment; filename=\"my file.txt\""),
                new V("w5c-数据.txt", "inline; filename*=utf-8''w5c-%E6%95%B0%E6%8D%AE.txt",
                        "attachment; filename*=utf-8''w5c-%E6%95%B0%E6%8D%AE.txt"),
                new V("a'b.txt", "inline; filename=a'b.txt", "attachment; filename=a'b.txt"),
                new V("a=b.txt", "inline; filename=\"a=b.txt\"", "attachment; filename=\"a=b.txt\""),
                new V("report.pdf", "inline; filename=report.pdf", "attachment; filename=report.pdf"),
                new V("img (1).png", "inline; filename=\"img (1).png\"",
                        "attachment; filename=\"img (1).png\""),
                new V("seed.unknownext", "inline; filename=seed.unknownext",
                        "attachment; filename=seed.unknownext"),
                new V("page.html", "inline; filename=page.html", "attachment; filename=page.html"),
                new V("a~b.txt", "inline; filename=a~b.txt", "attachment; filename=a~b.txt"),
                new V("a%b.txt", "inline; filename=a%b.txt", "attachment; filename=a%b.txt"),
                new V("a*b.txt", "inline; filename=a*b.txt", "attachment; filename=a*b.txt"));
        for (V v : vectors) {
            assertEquals(v.inline(), FileTransport.formatMediaType("inline", v.name()), v.name());
            assertEquals(v.attachment(), FileTransport.formatMediaType("attachment", v.name()), v.name());
        }
    }

    // ── parseRange（Go net/http 语义 + 错误文案）────────────────────────────

    @Test
    void parseRangeAbsent() {
        assertNull(FileTransport.parseRange(null, 100).error());
        assertNull(FileTransport.parseRange("", 100).ranges());
    }

    @Test
    void parseRangeInvalidUnits() {
        assertEquals("invalid range", FileTransport.parseRange("items=0-1", 100).error());
        assertEquals("invalid range", FileTransport.parseRange("bytes", 100).error());
    }

    @Test
    void parseRangeSimple() {
        FileTransport.ParseRange r = FileTransport.parseRange("bytes=2-5", 100);
        assertNull(r.error());
        assertArrayEquals(new long[] {2, 4}, new long[] {r.ranges().get(0).start(), r.ranges().get(0).length()});
        assertEquals("bytes 2-5/100", r.ranges().get(0).contentRange(100));
    }

    @Test
    void parseRangeOpenEndedAndSuffix() {
        FileTransport.ParseRange open = FileTransport.parseRange("bytes=2-", 100);
        assertEquals(98, open.ranges().get(0).length());
        // 后缀段：最后 3 字节
        FileTransport.ParseRange suffix = FileTransport.parseRange("bytes=-3", 100);
        assertEquals(97, suffix.ranges().get(0).start());
        assertEquals(3, suffix.ranges().get(0).length());
        // 后缀超过文件长 → clamp 到全量
        FileTransport.ParseRange bigSuffix = FileTransport.parseRange("bytes=-999", 100);
        assertEquals(0, bigSuffix.ranges().get(0).start());
        assertEquals(100, bigSuffix.ranges().get(0).length());
    }

    @Test
    void parseRangeEndClampedToSize() {
        FileTransport.ParseRange r = FileTransport.parseRange("bytes=98-200", 100);
        assertEquals(2, r.ranges().get(0).length());
    }

    @Test
    void parseRangeNoOverlap() {
        FileTransport.ParseRange r = FileTransport.parseRange("bytes=100-", 100);
        assertEquals("invalid range: failed to overlap", r.error());
        assertTrue(r.noOverlap());
        // 起点越界 + 后面还有合法段 → 越界段被跳过
        FileTransport.ParseRange mixed = FileTransport.parseRange("bytes=100-,0-1", 100);
        assertNull(mixed.error());
        assertEquals(1, mixed.ranges().size());
        assertEquals(0, mixed.ranges().get(0).start());
    }

    @Test
    void parseRangeMalformed() {
        assertEquals("invalid range", FileTransport.parseRange("bytes=a-b", 100).error());
        assertEquals("invalid range", FileTransport.parseRange("bytes=5-2", 100).error());
        assertEquals("invalid range", FileTransport.parseRange("bytes=-", 100).error());
        assertEquals("invalid range", FileTransport.parseRange("bytes=--1", 100).error());
    }

    @Test
    void parseRangeEmptyFileIgnoresRange() {
        // size==0 时 noOverlap 分支由调用方降级为 200 全量（serveContent 特例）
        FileTransport.ParseRange r = FileTransport.parseRange("bytes=0-", 0);
        assertEquals("invalid range: failed to overlap", r.error());
        assertTrue(r.noOverlap());
    }

    @Test
    void rangeSumOverSizeIgnored() {
        // sumRangesSize > size 的"忽略 Range"判定由 serveContent 做；这里只钉求和
        FileTransport.ParseRange r = FileTransport.parseRange("bytes=0-99,50-99", 100);
        assertEquals(150, r.ranges().stream().mapToLong(FileTransport.HttpRange::length).sum());
        assertFalse(r.noOverlap());
    }
}
