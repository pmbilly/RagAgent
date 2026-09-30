package com.ragagent.retrieval.engine.doris;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Doris 引擎的兼容模式解析与表管理簇：compat AUTO 探测（结果只解析一次，含错误缓存）、
 * 建表（分桶/副本/布尔重写）、ANN 索引就绪轮询、既有表枚举。
 */
final class DorisAdminOps {

    private static final Logger log = LoggerFactory.getLogger(DorisAdminOps.class);

    private final DorisRetrieveRepository service;

    DorisAdminOps(DorisRetrieveRepository service) {
        this.service = service;
    }

    // ── 兼容模式解析（照 compat.go 全文） ──────────────────────────────────

    /** 解析结果（mode 与 error 二选一；照 Go 的 sync.Once 缓存含错误）。 */
    record CompatResolution(DorisCompatMode mode, RuntimeException error) {
    }


    record DetectResult(DorisCompatMode mode, String exampleTable, boolean found) {
    }


    record Probe(boolean innerProductApproximate, boolean cosineDistanceApproximate) {
    }


    DorisCompatMode resolveCompatModeOrThrow() {
        CompatResolution resolution = resolveCompatMode();
        if (resolution.error() != null) {
            throw resolution.error();
        }
        return resolution.mode();
    }


    CompatResolution resolveCompatMode() {
        CompatResolution current = service.compatResolution;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (service.compatResolution == null) {
                service.compatResolution = doResolveCompatMode();
            }
            return service.compatResolution;
        }
    }


    CompatResolution doResolveCompatMode() {
        DorisCompatMode requested = service.compatModeRequested;
        if (requested == null) {
            return new CompatResolution(DorisCompatMode.INNER_PRODUCT_DUPLICATE, null);
        }
        DetectResult existing;
        try {
            existing = detectExistingCompatMode();
        } catch (RuntimeException e) {
            log.error("[Doris] {}", e.getMessage());
            return new CompatResolution(null, e);
        }
        if (existing.found()) {
            if (requested != DorisCompatMode.AUTO && requested != existing.mode()) {
                RuntimeException err = new IllegalStateException(mismatchMessage(requested,
                        existing.mode(), existing.exampleTable()));
                log.error("[Doris] {}", err.getMessage());
                return new CompatResolution(null, err);
            }
            log.warn("[Doris] Using compat mode {} from existing embedding tables ({}). {} is "
                            + "not interchangeable after {}_* tables are created; recreate those "
                            + "tables before switching modes.",
                    existing.mode().wire(), existing.exampleTable(), DorisCompatMode.ENV_KEY,
                    service.tableBaseName);
            return new CompatResolution(existing.mode(), null);
        }
        if (requested == DorisCompatMode.AUTO) {
            Probe probe = probeCompatMode();
            log.info("[Doris] Compat probe result: inner_product_approximate={}, "
                            + "cosine_distance_approximate={}",
                    probe.innerProductApproximate(), probe.cosineDistanceApproximate());
            DorisCompatMode resolved;
            if (probe.innerProductApproximate()) {
                resolved = DorisCompatMode.INNER_PRODUCT_DUPLICATE;
            } else if (probe.cosineDistanceApproximate()) {
                resolved = DorisCompatMode.LEGACY;
            } else {
                RuntimeException err = new IllegalStateException(
                        "Doris compatibility auto-detection could not find a supported vector "
                                + "function. Set " + DorisCompatMode.ENV_KEY + "="
                                + DorisCompatMode.INNER_PRODUCT_DUPLICATE.wire() + " or "
                                + DorisCompatMode.ENV_KEY + "="
                                + DorisCompatMode.LEGACY.wire() + " explicitly after verifying "
                                + "your Doris build. " + DorisCompatMode.ENV_KEY
                                + " is not interchangeable after " + service.tableBaseName
                                + "_* tables are created");
                log.error("[Doris] {}", err.getMessage());
                return new CompatResolution(null, err);
            }
            log.warn("[Doris] Auto-selected compat mode {} for new embedding tables. {} is not "
                            + "interchangeable after {}_* tables are created; recreate those tables "
                            + "before switching modes.",
                    resolved.wire(), DorisCompatMode.ENV_KEY, service.tableBaseName);
            return new CompatResolution(resolved, null);
        }
        log.warn("[Doris] Using configured compat mode {} for new embedding tables. {} is not "
                        + "interchangeable after {}_* tables are created; recreate those tables "
                        + "before switching modes.",
                requested.wire(), DorisCompatMode.ENV_KEY, service.tableBaseName);
        return new CompatResolution(requested, null);
    }


    String mismatchMessage(DorisCompatMode requested, DorisCompatMode existing,
                                   String exampleTable) {
        return "Doris compat mode \"" + requested.wire() + "\" does not match existing embedding "
                + "tables (detected \"" + existing.wire() + "\" from " + exampleTable + "). "
                + DorisCompatMode.ENV_KEY + " is not interchangeable after " + service.tableBaseName
                + "_* tables are created. Recreate the existing " + service.tableBaseName
                + "_* tables before switching modes, or set " + DorisCompatMode.ENV_KEY
                + "=" + existing.wire();
    }

    /** 对照 {@code detectExistingCompatMode}：列既有表（字典序）→ SHOW CREATE TABLE → 模式判读。 */
    DetectResult detectExistingCompatMode() {
        List<String> tables;
        try {
            tables = listEmbeddingTables();
        } catch (RuntimeException e) {
            throw new IllegalStateException("list Doris embedding tables: " + e.getMessage(), e);
        }
        if (tables.isEmpty()) {
            return new DetectResult(null, null, false);
        }
        List<String> sorted = new ArrayList<>(tables);
        Collections.sort(sorted);
        DorisCompatMode detected = null;
        for (String table : sorted) {
            String ddl;
            try {
                ddl = showCreateTable(table);
            } catch (RuntimeException e) {
                throw new IllegalStateException(
                        "show create table " + table + ": " + e.getMessage(), e);
            }
            DorisCompatMode mode;
            try {
                mode = DorisCompatMode.fromDdl(ddl);
            } catch (RuntimeException e) {
                throw new IllegalStateException(
                        "detect compat mode from " + table + ": " + e.getMessage(), e);
            }
            if (detected == null) {
                detected = mode;
                continue;
            }
            if (detected != mode) {
                throw new IllegalStateException("existing Doris embedding tables use mixed compat "
                        + "modes (" + detected.wire() + " and " + mode.wire() + "). "
                        + DorisCompatMode.ENV_KEY + " is not interchangeable after " + service.tableBaseName
                        + "_* tables are created; recreate the existing " + service.tableBaseName
                        + "_* tables with a single mode");
            }
        }
        return new DetectResult(detected, sorted.get(0), true);
    }


    String showCreateTable(String table) {
        List<String> rows;
        try {
            rows = service.sql.query("SHOW CREATE TABLE `" + table + "`", List.of(),
                    row -> row.string(1));
        } catch (SQLException e) {
            throw new IllegalStateException(DorisRetrieveRepository.message(e), e);
        }
        if (rows.isEmpty()) {
            throw new IllegalStateException("service.sql: no rows in result set");
        }
        return rows.get(0);
    }


    Probe probeCompatMode() {
        return new Probe(
                vectorFunctionSupported("inner_product_approximate([1.0],[1.0])"),
                vectorFunctionSupported("cosine_distance_approximate([1.0],[1.0])"));
    }


    boolean vectorFunctionSupported(String expr) {
        try {
            service.sql.scalar("SELECT " + expr, List.of());
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    // ── 表管理（照 schema.go） ──────────────────────────────────────────────

    /**
     * 对照 {@code ensureTable}：不存在则 CREATE TABLE IF NOT EXISTS，并起后台线程轮询
     * ANN 索引就绪（写入路径不阻塞——索引未就绪期间检索退化为 brute-force）。
     */
    void ensureTable(int dimension) {
        if (service.initializedTables.containsKey(dimension)) {
            return;
        }
        DorisCompatMode compatMode = resolveCompatModeOrThrow();
        String tableName = service.getTableName(dimension);
        boolean exists;
        try {
            exists = tableExists(tableName);
        } catch (RuntimeException e) {
            log.error("[Doris] Failed to check table existence: {}", e.getMessage());
            throw new IllegalStateException("check table existence: " + e.getMessage(), e);
        }
        if (!exists) {
            log.info("[Doris] Creating table {} with dimension {} in compat mode {}",
                    tableName, dimension, compatMode.wire());
            try {
                createTable(tableName, dimension, compatMode);
            } catch (RuntimeException e) {
                log.error("[Doris] Failed to create table: {}", e.getMessage());
                throw new IllegalStateException("create table: " + e.getMessage(), e);
            }
            String tableForPoll = tableName;
            Thread.startVirtualThread(() -> {
                try {
                    waitAnnReady(tableForPoll);
                    log.info("[Doris] ANN index for {} ready", tableForPoll);
                } catch (RuntimeException e) {
                    log.warn("[Doris] ANN index for {} not ready within {}: {} "
                                    + "(queries may fall back to brute force temporarily)",
                            tableForPoll, DorisRetrieveRepository.ANN_READY_TIMEOUT_MS, e.getMessage());
                }
            });
        }
        service.initializedTables.put(dimension, true);
    }

    /**
     * 对照 {@code tableExists}：走 information_schema（Doris 4.1 的 SHOW TABLES LIKE
     * 大小写敏感，information_schema 兼容性更好）。
     */
    boolean tableExists(String tableName) {
        Object count;
        try {
            count = service.sql.scalar("SELECT COUNT(1) FROM information_schema.tables "
                    + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?", List.of(service.database, tableName));
        } catch (SQLException e) {
            throw new IllegalStateException(DorisRetrieveRepository.message(e), e);
        }
        return count instanceof Number n && n.longValue() > 0;
    }


    void createTable(String tableName, int dimension, DorisCompatMode compatMode) {
        int buckets = service.bucketsNum > 0 ? service.bucketsNum : DorisRetrieveRepository.DEFAULT_BUCKETS_NUM;
        int replication = service.replicationNum > 0 ? service.replicationNum : DorisRetrieveRepository.DEFAULT_REPLICATION_NUM;
        String ddl = DorisSql.buildCreateTableDdl(tableName, dimension, buckets, replication,
                compatMode);
        try {
            service.sql.execute(ddl, List.of());
        } catch (SQLException e) {
            if (compatMode == DorisCompatMode.LEGACY) {
                throw new IllegalStateException("legacy Doris table creation failed: "
                        + DorisRetrieveRepository.message(e) + ". If your Doris build rejects ANN indexes on UNIQUE KEY "
                        + "tables, set " + DorisCompatMode.ENV_KEY + "="
                        + DorisCompatMode.INNER_PRODUCT_DUPLICATE.wire() + " before creating "
                        + "embedding tables. " + DorisCompatMode.ENV_KEY
                        + " is not interchangeable after " + service.tableBaseName
                        + "_* tables are created", e);
            }
            throw new IllegalStateException(DorisRetrieveRepository.message(e), e);
        }
    }

    /** 对照 {@code waitANNReady}：到点未就绪只报错，不阻塞。 */
    void waitAnnReady(String tableName) {
        long deadline = System.currentTimeMillis() + DorisRetrieveRepository.ANN_READY_TIMEOUT_MS;
        while (true) {
            if (annIndexReady(tableName)) {
                return;
            }
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException(
                        "ann index not ready within " + DorisRetrieveRepository.ANN_READY_TIMEOUT_MS + "ms");
            }
            try {
                Thread.sleep(DorisRetrieveRepository.ANN_READY_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting ann index", e);
            }
        }
    }

    /**
     * 对照 {@code annIndexReady}：SHOW INDEX 按列名匹配 key_name / state（不同小版本
     * 列序有差异）；找不到 idx_emb 行或旧版本无 state 列都视为已就绪。
     */
    boolean annIndexReady(String tableName) {
        List<String[]> rows;
        try {
            rows = service.sql.query("SHOW INDEX FROM `" + tableName + "`", List.of(), row -> {
                int keyNameIdx = -1;
                int stateIdx = -1;
                for (int i = 0; i < row.columnCount(); i++) {
                    String c = row.columnName(i) == null ? ""
                            : row.columnName(i).toLowerCase(Locale.ROOT);
                    if ("key_name".equals(c)) {
                        keyNameIdx = i;
                    } else if ("state".equals(c) || "index_state".equals(c)) {
                        stateIdx = i;
                    }
                }
                String keyName = keyNameIdx >= 0 ? row.string(keyNameIdx) : "";
                String state = stateIdx >= 0 ? row.string(stateIdx) : null;
                return new String[] {keyName, state};
            });
        } catch (SQLException e) {
            throw new IllegalStateException(DorisRetrieveRepository.message(e), e);
        }
        for (String[] row : rows) {
            if (!"idx_emb".equals(row[0])) {
                continue;
            }
            if (row[1] == null) {
                // 旧版本不暴露 state 列，乐观认为已就绪。
                return true;
            }
            if (!"FINISHED".equalsIgnoreCase(row[1]) && !"NORMAL".equalsIgnoreCase(row[1])) {
                return false;
            }
        }
        // 找到 idx_emb 且状态 FINISHED/NORMAL，或极旧版本不暴露该索引名 → 都视为就绪。
        return true;
    }

    /** 对照 {@code listEmbeddingTables}：{@code <base>\_%}（LIKE 里 \_ 转义下划线）。 */
    List<String> listEmbeddingTables() {
        try {
            return service.sql.query("SELECT TABLE_NAME FROM information_schema.tables "
                            + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME LIKE ?",
                    List.of(service.database, service.tableBaseName + "\\_%"), row -> row.string(0));
        } catch (SQLException e) {
            throw new IllegalStateException(DorisRetrieveRepository.message(e), e);
        }
    }
}
