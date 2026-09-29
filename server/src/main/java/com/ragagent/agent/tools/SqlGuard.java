package com.ragagent.agent.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * SQL 校验与安全注入（对照 Go {@code internal/utils/inject.go}，database_query 用到的
 * 全配置路径逐字移植）。
 *
 * <p><b>已决策差异（报告备案）</b>：Go 用 pg_query_go 拿 PostgreSQL 官方解析树；
 * Java 侧无 pg_query 绑定，这里用<b>手写轻量解析器</b>——tokenizer + 关键字级
 * FROM/WHERE 切分——覆盖 database_query 语料支持的单条 SELECT 形态
 * （FROM/JOIN [ON]、WHERE、GROUP BY、HAVING、ORDER BY、LIMIT/OFFSET、函数调用、
 * {@code ::} 与 CAST 转型）。解析失败的分类与 Go 一致（parse_error），但底层
 * parse 错误文案不同——该文案不进工具 error（Go 侧 Details 只留在
 * SQLValidationError.Details，工具只透 Message），故工具输出保持逐字一致。
 * Deparse 归一化跳过（直接对原 SQL 注入），单表场景注入结果逐字一致，多表条件
 * 序 Go map 随机、Java 用出现序（已知差异）。</p>
 *
 * <p>校验错误的三元组（type/message/details）与 Phase 顺序（input → parse →
 * statement count → select-only → deep validate → table whitelist → injection
 * risk）逐字对照 Go；注入（tenant/soft-delete/hidden-KB/chunk-enabled/search
 * scope）与 {@code InjectAndConditions} 的字符串重写逐字对照。</p>
 */
public final class SqlGuard {

    private SqlGuard() {
    }

    /** 对照 SQLValidationError。 */
    public record SqlValidationError(String type, String message, String details) {
    }

    /** 对照 SQLValidationResult。 */
    public static final class SqlValidationResult {
        boolean valid = true;
        final List<SqlValidationError> errors = new ArrayList<>();

        public boolean isValid() {
            return valid;
        }

        public List<SqlValidationError> getErrors() {
            return errors;
        }
    }

    /** 校验失败时抛出，message = Go 的 Errors[0].Message（对照 ValidateAndSecureSQL 的 errMsg）。 */
    public static final class SqlGuardException extends Exception {
        public final SqlValidationResult result;

        SqlGuardException(SqlValidationResult result) {
            super(result.errors.get(0).message);
            this.result = result;
        }
    }

    /** 对照 utils.SearchScope。 */
    public record SearchScope(String knowledgeBaseId, List<String> knowledgeIds, List<String> tagIds) {
    }

    /** validate + 注入的合入口（对照 ValidateAndSecureSQL，rewriting 恒启用）。 */
    public static String validateAndSecure(String sql, long tenantID, List<SearchScope> scopes)
            throws SqlGuardException {
        SqlValidationResult validation = validate(sql, tenantID, scopes);
        if (!validation.valid) {
            throw new SqlGuardException(validation);
        }

        // 解析出的表→别名（出现序；Go map 序随机，单表场景无差异——已知差异④）。
        Map<String, String> tablesInQuery = SqlSelectDeepChecker.parseTablesInQuery(sql);

        String securedSQL = SqlInjectionAnalyzer.injectTenantConditions(sql, tablesInQuery, tenantID);
        securedSQL = SqlInjectionAnalyzer.injectSoftDeleteConditions(securedSQL, tablesInQuery);
        securedSQL = SqlInjectionAnalyzer.injectHiddenKbFilter(securedSQL, tablesInQuery);
        securedSQL = SqlInjectionAnalyzer.injectChunkEnabledFilter(securedSQL, tablesInQuery);
        securedSQL = SqlInjectionAnalyzer.injectStructuredSearchScopeConditions(securedSQL, tablesInQuery, scopes);
        return securedSQL;
    }

    // ==================== Phase 1-7 校验（对照 ValidateSQL） ====================

    /**
     * 校验配置（对照 sqlValidator 的 option 开关组）。两个工厂：
     * {@link #securityDefaults()}（database_query 全配置）与
     * {@link #dataAnalysis(String)}（data_analysis：仅单语句 + 危险函数 +
     * 单表白名单，无 select-only/函数白名单/子查询/CTE/schema/系统列检查——
     * 与 Go {@code utils.ValidateSQL(WithAllowedTables(t), WithSingleStatement(),}
     * {@code WithNoDangerousFunctions())} 逐字段对应）。
     */
    public static final class GuardConfig {
        boolean inputValidation;
        boolean selectOnly;
        boolean singleStatement;
        boolean checkTableNames;
        final java.util.LinkedHashSet<String> allowedTables = new java.util.LinkedHashSet<>();
        boolean checkFunctionNames;
        final java.util.LinkedHashSet<String> allowedFunctions = new java.util.LinkedHashSet<>();
        boolean checkInjectionRisk;
        boolean checkSubqueries;
        boolean checkCTEs;
        boolean checkSystemColumns;
        boolean checkSchemaAccess;
        boolean checkDangerousFuncs;

        /** 对照 WithSecurityDefaults（allowed tables 三表 + 47 函数白名单 + 注入开关）。 */
        public static GuardConfig securityDefaults() {
            GuardConfig cfg = new GuardConfig();
            cfg.inputValidation = true;
            cfg.selectOnly = true;
            cfg.singleStatement = true;
            cfg.checkTableNames = true;
            for (String t : new String[] {"knowledge_bases", "knowledges", "chunks"}) {
                cfg.allowedTables.add(t);
            }
            cfg.checkFunctionNames = true;
            cfg.allowedFunctions.addAll(ALLOWED_FUNCTIONS.keySet());
            cfg.checkInjectionRisk = true;
            cfg.checkSubqueries = true;
            cfg.checkCTEs = true;
            cfg.checkSystemColumns = true;
            cfg.checkSchemaAccess = true;
            cfg.checkDangerousFuncs = true;
            return cfg;
        }

        /** 对照 data_analysis 的三选项 ValidateSQL（allowedTables 单表）。 */
        public static GuardConfig dataAnalysis(String tableName) {
            GuardConfig cfg = new GuardConfig();
            cfg.singleStatement = true;
            cfg.checkTableNames = true;
            cfg.allowedTables.add(tableName);
            cfg.checkDangerousFuncs = true;
            return cfg;
        }
    }

    /**
     * 对照 {@code ValidateSQL}（安全默认全配置入口；tenantID/scopes 为注入阶段
     * 参数，校验本体不使用）。返回的 result 带完整 errors 列表（与 Go 一样，
     * Phase 5/6/7 的错误可叠加）。
     */
    public static SqlValidationResult validate(String sql, long tenantID, List<SearchScope> scopes) {
        return validate(sql, GuardConfig.securityDefaults());
    }

    /**
     * 对照 {@code ValidateSQL}（可配置入口）。非 SELECT 语句（SHOW/EXPLAIN 等）
     * 在 select-only 关闭时不做任何深检查（对照 Go：stmt.GetSelectStmt() 为 nil
     * 时 Phase 5/6/7 整体跳过）。
     */
    public static SqlValidationResult validate(String sql, GuardConfig cfg) {
        SqlValidationResult validationResult = new SqlValidationResult();

        // Phase 1: Basic input validation（对照 validateInput）
        if (cfg.inputValidation) {
            String inputErr = validateInput(sql);
            if (inputErr != null) {
                validationResult.valid = false;
                validationResult.errors.add(new SqlValidationError(
                        "input_validation_error", "Input validation failed", inputErr));
                return validationResult;
            }
        }

        // Phase 2: Parse（手写解析器；分类对齐 pg_query：能/不能解析。
        // DESCRIBE/PRAGMA 等非 PG 语句的 parse 错误文案对照 pg_query 逐字。）
        List<SqlTokenizer.Token> tokens;
        try {
            tokens = SqlTokenizer.tokenize(sql);
        } catch (SqlTokenizer.ParseFailure e) {
            validationResult.valid = false;
            validationResult.errors.add(new SqlValidationError(
                    "parse_error", "Failed to parse SQL", "SQL parse error: " + e.getMessage()));
            return validationResult;
        }

        // Phase 3: statement count（对照 parseResult.Stmts 计数；空段不计）
        List<List<SqlTokenizer.Token>> statements = SqlTokenizer.splitStatements(tokens);
        if (statements.isEmpty()) {
            validationResult.valid = false;
            validationResult.errors.add(new SqlValidationError(
                    "empty_query", "Empty query", "No statements found in SQL"));
            return validationResult;
        }
        if (cfg.singleStatement && statements.size() > 1) {
            validationResult.valid = false;
            validationResult.errors.add(new SqlValidationError(
                    "multiple_statements", "Multiple statements are not allowed",
                    String.format("Found %d statements, only 1 is allowed", statements.size())));
            return validationResult;
        }

        List<SqlTokenizer.Token> stmt = statements.get(0);

        // Phase 2.5: 语句种类（对照 pg_query 的可解析集合）：无法识别的首 token
        // 是 parse error（如 DESCRIBE/PRAGMA——pg 语法里没有它们），可解析的非
        // SELECT 语句（SHOW/EXPLAIN/DELETE…）继续走后续阶段。
        SqlTokenizer.Token first0 = SqlTokenizer.firstMeaningful(stmt);
        if (first0 != null && !first0.isKeyword("select") && !first0.isKeyword("with")
                && first0.kind == SqlTokenizer.TokKind.IDENT && !SqlSelectDeepChecker.isKnownStatementKeyword(first0.text)) {
            validationResult.valid = false;
            validationResult.errors.add(new SqlValidationError(
                    "parse_error", "Failed to parse SQL",
                    String.format("SQL parse error: syntax error at or near \"%s\"", first0.text)));
            return validationResult;
        }

        // Phase 4: SELECT-only（WITH 开头时跳过 CTE 定义体看主语句——对照 Go 的
        // stmt.GetSelectStmt()：WITH...SELECT 是 SelectStmt，WITH...DELETE 不是）
        SqlTokenizer.Token first = SqlTokenizer.firstMeaningful(stmt);
        boolean startsWithWith = first != null && first.isKeyword("with");
        boolean isSelect = first != null && first.isKeyword("select");
        if (startsWithWith) {
            int idx = SqlTokenizer.skipWithClause(stmt);
            SqlTokenizer.Token main = idx < stmt.size() ? stmt.get(idx) : null;
            isSelect = main != null && main.isKeyword("select");
            if (isSelect && !cfg.checkCTEs) {
                // data_analysis：CTE 允许（checkCTEs=false）——CTE 本体不校验
                // （对照 Go：validateSelectStmt 只在 checkCTEs 时看 WithClause），
                // 深检查只对主语句做。
                stmt = new ArrayList<>(stmt.subList(idx, stmt.size()));
                startsWithWith = false;
            }
        }
        if (cfg.selectOnly && !isSelect) {
            // 可解析的非 SELECT 语句走到这里；无法识别的首 token 已被语句种类
            // 检测按 pg 语义转成 parse_error。
            validationResult.valid = false;
            validationResult.errors.add(new SqlValidationError(
                    "not_select_statement", "Only SELECT queries are allowed",
                    "Statement is not a SELECT query"));
            return validationResult;
        }

        // 非 SELECT 语句（SHOW/EXPLAIN 等，仅 data_analysis 配置可达）：
        // 对照 Go——selectStmt 为 nil 时不做 Phase 5/6/7，直接通过。
        if (!isSelect) {
            return validationResult;
        }

        // Phase 5: deep inspection（对照 validateSelectStmt，首个错误即 Details）
        SqlSelectDeepChecker.DeepCheck deep = SqlSelectDeepChecker.deepCheck(stmt, startsWithWith, cfg);
        if (deep.error != null) {
            validationResult.valid = false;
            validationResult.errors.add(new SqlValidationError(
                    "statement_validation_error", "Statement validation failed", deep.error));
        }

        // Phase 6: table whitelist（对照 result.TableNames：出现序去重、Relname
        // 原始大小写进文案）
        if (cfg.checkTableNames) {
            java.util.LinkedHashSet<String> seenTables = new java.util.LinkedHashSet<>();
            for (String table : deep.tableNames) {
                if (!seenTables.add(table)) {
                    continue;
                }
                if (!cfg.allowedTables.contains(table.toLowerCase(Locale.ROOT))) {
                    validationResult.valid = false;
                    List<String> allowed = new ArrayList<>(cfg.allowedTables);
                    java.util.Collections.sort(allowed);
                    validationResult.errors.add(new SqlValidationError(
                            "table_not_allowed",
                            String.format("Table '%s' is not in the allowed list", table),
                            // Go Details 为 map 键序（随机）；多表时不进工具输出，
                            // 单表（data_analysis）时序确定。
                            "Allowed tables: " + allowed));
                }
            }
        }

        // Phase 7: injection risk（对照 checkSQLInjectionRisks；仅真实 WHERE 存在时）
        if (cfg.checkInjectionRisk && deep.hasRealWhere) {
            String whereClause = SqlInjectionAnalyzer.extractWhereClauseText(sql);
            List<SqlValidationError> riskErrors = SqlInjectionAnalyzer.checkSqlInjectionRisks(whereClause);
            if (!riskErrors.isEmpty()) {
                validationResult.valid = false;
                validationResult.errors.addAll(riskErrors);
            }
        }

        return validationResult;
    }

    /** 跳过 WITH cte AS (...)[, ...] 定义体，返回主语句起始下标。 */
    private static String validateInput(String sql) {
        if (sql == null) {
            sql = "";
        }
        if (sql.indexOf('\0') >= 0) {
            return "invalid character in SQL query";
        }
        if (sql.length() < 6) {
            return String.format("SQL query too short (min %d characters)", 6);
        }
        if (sql.length() > 4096) {
            return String.format("SQL query too long (max %d characters)", 4096);
        }
        return null;
    }


    static final Map<String, Boolean> ALLOWED_TABLES = new LinkedHashMap<>();

    static {
        for (String t : new String[] {"knowledge_bases", "knowledges", "chunks"}) {
            ALLOWED_TABLES.put(t, Boolean.TRUE);
        }
    }

    private static final Map<String, Boolean> ALLOWED_FUNCTIONS = new LinkedHashMap<>();

    /**
     * pg_query 对内建类型的归一化（INTEGER→pg_catalog.int4 等）：Go 的 TypeCast
     * 检查读 parse tree 归一化后的 TypeName.Names，因此 {@code ::INTEGER} 被拒而
     * 自定义类型放行。Java tokenizer 看到的是原文，须先查表归一。
     */
    private static final Map<String, String> PG_TYPE_NORMALIZATION = new LinkedHashMap<>();

    static {
        for (String f : new String[] {
                // Aggregate functions（对照 WithDefaultSafeFunctions，47 个，全小写）
                "count", "sum", "avg", "min", "max", "array_agg", "string_agg",
                "bool_and", "bool_or", "json_agg", "jsonb_agg", "json_object_agg",
                "jsonb_object_agg",
                // Safe scalar functions
                "coalesce", "nullif", "greatest", "least", "abs", "ceil", "floor",
                "round", "length", "lower", "upper", "trim", "ltrim", "rtrim",
                "substring", "concat", "concat_ws", "replace", "left", "right",
                "now", "current_date", "current_timestamp", "date_trunc", "extract",
                "to_char", "to_date", "to_timestamp", "date_part", "age"}) {
            ALLOWED_FUNCTIONS.put(f, Boolean.TRUE);
        }

        // 内建 SQL 类型名 → pg_catalog 归一名（对照 pg parse tree 的 Names 列表；
        // 多词类型键为空白连接小写形式）。
        String[][] types = {
                {"int", "pg_catalog.int4"}, {"integer", "pg_catalog.int4"},
                {"int4", "pg_catalog.int4"},
                {"bigint", "pg_catalog.int8"}, {"int8", "pg_catalog.int8"},
                {"smallint", "pg_catalog.int2"}, {"int2", "pg_catalog.int2"},
                {"serial", "pg_catalog.int4"}, {"bigserial", "pg_catalog.int8"},
                {"smallserial", "pg_catalog.int2"},
                {"text", "pg_catalog.text"},
                {"varchar", "pg_catalog.varchar"}, {"character varying", "pg_catalog.varchar"},
                {"char", "pg_catalog.bpchar"}, {"character", "pg_catalog.bpchar"},
                {"bpchar", "pg_catalog.bpchar"},
                {"bool", "pg_catalog.bool"}, {"boolean", "pg_catalog.bool"},
                {"numeric", "pg_catalog.numeric"}, {"decimal", "pg_catalog.numeric"},
                {"real", "pg_catalog.float4"}, {"float4", "pg_catalog.float4"},
                {"float", "pg_catalog.float8"}, {"float8", "pg_catalog.float8"},
                {"double precision", "pg_catalog.float8"},
                {"date", "pg_catalog.date"}, {"time", "pg_catalog.time"},
                {"timestamp", "pg_catalog.timestamp"},
                {"timestamp with time zone", "pg_catalog.timestamptz"},
                {"timestamp without time zone", "pg_catalog.timestamp"},
                {"timestamptz", "pg_catalog.timestamptz"},
                {"interval", "pg_catalog.interval"},
                {"uuid", "pg_catalog.uuid"},
                {"json", "pg_catalog.json"}, {"jsonb", "pg_catalog.jsonb"},
                {"bytea", "pg_catalog.bytea"}, {"money", "pg_catalog.money"},
                {"bit", "pg_catalog.bit"}, {"bit varying", "pg_catalog.varbit"},
                {"varbit", "pg_catalog.varbit"},
                {"oid", "pg_catalog.oid"}, {"name", "pg_catalog.name"},
                {"inet", "pg_catalog.inet"}, {"cidr", "pg_catalog.cidr"},
                {"macaddr", "pg_catalog.macaddr"}, {"macaddr8", "pg_catalog.macaddr8"},
                {"point", "pg_catalog.point"},
                {"regclass", "pg_catalog.regclass"}, {"regtype", "pg_catalog.regtype"},
                {"regproc", "pg_catalog.regproc"},
        };
        for (String[] t : types) {
            PG_TYPE_NORMALIZATION.put(t[0], t[1]);
        }
    }

    /**
     * 对照 pg parse tree 的 TypeName.Names：内建类型归一为 pg_catalog.*（小写）；
     * 自定义类型原样返回点连接形式。多词类型（double precision 等）按空白连接
     * 查表，兼容 tokenizer 吃掉空格后的点连接序列。
     */
    static String normalizeCastTypeName(List<String> parts) {
        if (parts == null || parts.isEmpty()) {
            return "";
        }
        String mapped = PG_TYPE_NORMALIZATION.get(String.join(" ", parts)
                .toLowerCase(Locale.ROOT));
        return mapped != null ? mapped : String.join(".", parts);
    }

    static final String[] DANGEROUS_PREFIXES = {
            "pg_", "lo_", "dblink", "file_", "copy_", "binary_",
    };

    static final Map<String, Boolean> DANGEROUS_FUNCTIONS = new LinkedHashMap<>();

    static {
        for (String f : new String[] {
                // Configuration and settings
                "current_setting", "set_config",
                // XML/XPath functions (XXE risks)
                "query_to_xml", "xpath", "xmlparse", "xmlroot", "xmlelement", "xmlforest",
                "xmlconcat", "xmlagg", "xmlpi", "xmlcomment", "xmlexists", "xml_is_well_formed",
                "xpath_exists", "table_to_xml", "cursor_to_xml", "database_to_xml", "schema_to_xml",
                // Transaction and system info
                "txid_current", "txid_current_snapshot", "txid_snapshot_xmin", "txid_snapshot_xmax",
                // Encoding functions (used in attack payloads)
                "encode", "decode",
                // Extension management
                "create_extension",
                // Copy operations
                "copy", "copy_to", "copy_from", "pg_copy_to", "pg_dump", "pg_dumpall",
                "pg_restore", "pg_basebackup",
                // Process and system functions
                "pg_terminate_backend", "pg_cancel_backend", "pg_rotate_logfile",
                // Advisory locks (can be abused for DoS)
                "pg_advisory_lock", "pg_advisory_unlock", "pg_advisory_lock_shared",
                "pg_advisory_unlock_shared", "pg_try_advisory_lock", "pg_try_advisory_lock_shared",
                // Backup and replication
                "pg_start_backup", "pg_stop_backup", "pg_switch_wal", "pg_create_restore_point",
                // Foreign data wrappers
                "postgres_fdw_handler", "file_fdw_handler",
                // Procedural languages (code execution)
                "plpgsql_call_handler", "plpython_call_handler", "plperl_call_handler",
                // System catalog modification
                "pg_catalog", "information_schema",
                // DuckDB file-access functions（data_analysis 共用清单）
                "read_text", "read_blob", "read_csv", "read_csv_auto", "read_parquet",
                "read_json", "read_json_auto", "read_ndjson", "read_ndjson_auto",
                "read_json_objects", "read_xlsx", "sniff_csv", "glob", "st_read",
                "st_read_meta"}) {
            DANGEROUS_FUNCTIONS.put(f, Boolean.TRUE);
        }
    }

    static final String[] SYSTEM_COLUMNS = {"xmin", "xmax", "cmin", "cmax", "ctid", "tableoid"};

}
