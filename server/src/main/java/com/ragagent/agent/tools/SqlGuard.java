package com.ragagent.agent.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
        Map<String, String> tablesInQuery = parseTablesInQuery(sql);

        String securedSQL = injectTenantConditions(sql, tablesInQuery, tenantID);
        securedSQL = injectSoftDeleteConditions(securedSQL, tablesInQuery);
        securedSQL = injectHiddenKbFilter(securedSQL, tablesInQuery);
        securedSQL = injectChunkEnabledFilter(securedSQL, tablesInQuery);
        securedSQL = injectStructuredSearchScopeConditions(securedSQL, tablesInQuery, scopes);
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
        List<Token> tokens;
        try {
            tokens = tokenize(sql);
        } catch (ParseFailure e) {
            validationResult.valid = false;
            validationResult.errors.add(new SqlValidationError(
                    "parse_error", "Failed to parse SQL", "SQL parse error: " + e.getMessage()));
            return validationResult;
        }

        // Phase 3: statement count（对照 parseResult.Stmts 计数；空段不计）
        List<List<Token>> statements = splitStatements(tokens);
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

        List<Token> stmt = statements.get(0);

        // Phase 2.5: 语句种类（对照 pg_query 的可解析集合）：无法识别的首 token
        // 是 parse error（如 DESCRIBE/PRAGMA——pg 语法里没有它们），可解析的非
        // SELECT 语句（SHOW/EXPLAIN/DELETE…）继续走后续阶段。
        Token first0 = firstMeaningful(stmt);
        if (first0 != null && !first0.isKeyword("select") && !first0.isKeyword("with")
                && first0.kind == TokKind.IDENT && !isKnownStatementKeyword(first0.text)) {
            validationResult.valid = false;
            validationResult.errors.add(new SqlValidationError(
                    "parse_error", "Failed to parse SQL",
                    String.format("SQL parse error: syntax error at or near \"%s\"", first0.text)));
            return validationResult;
        }

        // Phase 4: SELECT-only（WITH 开头时跳过 CTE 定义体看主语句——对照 Go 的
        // stmt.GetSelectStmt()：WITH...SELECT 是 SelectStmt，WITH...DELETE 不是）
        Token first = firstMeaningful(stmt);
        boolean startsWithWith = first != null && first.isKeyword("with");
        boolean isSelect = first != null && first.isKeyword("select");
        if (startsWithWith) {
            int idx = skipWithClause(stmt);
            Token main = idx < stmt.size() ? stmt.get(idx) : null;
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
        DeepCheck deep = deepCheck(stmt, startsWithWith, cfg);
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
            String whereClause = extractWhereClauseText(sql);
            List<SqlValidationError> riskErrors = checkSqlInjectionRisks(whereClause);
            if (!riskErrors.isEmpty()) {
                validationResult.valid = false;
                validationResult.errors.addAll(riskErrors);
            }
        }

        return validationResult;
    }

    /** 跳过 WITH cte AS (...)[, ...] 定义体，返回主语句起始下标。 */
    private static int skipWithClause(List<Token> stmt) {
        int idx = 1;
        int depth = 0;
        boolean seenParen = false;
        while (idx < stmt.size()) {
            Token t = stmt.get(idx);
            if (t.kind == TokKind.PUNCT && t.text.equals("(")) {
                depth++;
                seenParen = true;
            } else if (t.kind == TokKind.PUNCT && t.text.equals(")")) {
                depth--;
            }
            idx++;
            if (seenParen && depth == 0) {
                if (idx < stmt.size() && stmt.get(idx).kind == TokKind.PUNCT
                        && stmt.get(idx).text.equals(",")) {
                    idx++;
                    seenParen = false;
                    continue;
                }
                break;
            }
        }
        return idx;
    }

    /** 对照 validateInput。返回 null = 通过。 */
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

    // ==================== 手写 tokenizer ====================

    private enum TokKind {
        IDENT, QIDENT, STRING, NUMBER, OP, PUNCT, PARAM
    }

    /** SQL token。keyword 判定只对 IDENT（裸标识符，大小写不敏感）。 */
    private static final class Token {
        final TokKind kind;
        final String text;

        Token(TokKind kind, String text) {
            this.kind = kind;
            this.text = text;
        }

        boolean isKeyword(String kw) {
            return kind == TokKind.IDENT && text.equalsIgnoreCase(kw);
        }

        /** 标识符语义值：裸标识原样、双引号标识剥引号。 */
        String identValue() {
            return text;
        }
    }

    /** 手写解析失败（对照 pg_query.Parse 的 error 分类）。 */
    private static final class ParseFailure extends Exception {
        ParseFailure(String message) {
            super(message);
        }
    }

    private static boolean isIdentStart(char c) {
        return Character.isLetter(c) || c == '_' || c >= 0x80;
    }

    private static boolean isIdentPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c >= 0x80;
    }

    private static List<Token> tokenize(String sql) throws ParseFailure {
        List<Token> out = new ArrayList<>();
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            // 注释（pg 支持嵌套块注释；手写按非嵌套处理——语料不用注释）
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                while (i < n && sql.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                if (end < 0) {
                    throw new ParseFailure("unterminated comment");
                }
                i = end + 2;
                continue;
            }
            // 字符串（'...'，'' 转义）
            if (c == '\'') {
                StringBuilder sb = new StringBuilder();
                sb.append('\'');
                i++;
                boolean closed = false;
                while (i < n) {
                    char ch = sql.charAt(i);
                    sb.append(ch);
                    if (ch == '\'') {
                        if (i + 1 < n && sql.charAt(i + 1) == '\'') {
                            sb.append('\'');
                            i += 2;
                            continue;
                        }
                        i++;
                        closed = true;
                        break;
                    }
                    i++;
                }
                if (!closed) {
                    throw new ParseFailure("unterminated quoted string at or near \"" + sb + "\"");
                }
                out.add(new Token(TokKind.STRING, sb.toString()));
                continue;
            }
            // 双引号标识符
            if (c == '"') {
                StringBuilder sb = new StringBuilder();
                i++;
                boolean closed = false;
                while (i < n) {
                    char ch = sql.charAt(i);
                    if (ch == '"') {
                        if (i + 1 < n && sql.charAt(i + 1) == '"') {
                            sb.append('"');
                            i += 2;
                            continue;
                        }
                        i++;
                        closed = true;
                        break;
                    }
                    sb.append(ch);
                    i++;
                }
                if (!closed) {
                    throw new ParseFailure("unterminated quoted identifier");
                }
                out.add(new Token(TokKind.QIDENT, sb.toString()));
                continue;
            }
            // 参数占位（$1）
            if (c == '$' && i + 1 < n && Character.isDigit(sql.charAt(i + 1))) {
                int j = i + 1;
                while (j < n && Character.isDigit(sql.charAt(j))) {
                    j++;
                }
                out.add(new Token(TokKind.PARAM, sql.substring(i, j)));
                i = j;
                continue;
            }
            if (isIdentStart(c)) {
                int j = i + 1;
                while (j < n && isIdentPart(sql.charAt(j))) {
                    j++;
                }
                out.add(new Token(TokKind.IDENT, sql.substring(i, j)));
                i = j;
                continue;
            }
            if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(sql.charAt(i + 1)))) {
                int j = i + 1;
                while (j < n && (Character.isDigit(sql.charAt(j)) || sql.charAt(j) == '.'
                        || sql.charAt(j) == 'e' || sql.charAt(j) == 'E'
                        || ((sql.charAt(j) == '+' || sql.charAt(j) == '-') && j > i
                                && (sql.charAt(j - 1) == 'e' || sql.charAt(j - 1) == 'E')))) {
                    j++;
                }
                out.add(new Token(TokKind.NUMBER, sql.substring(i, j)));
                i = j;
                continue;
            }
            if (c == '(' || c == ')' || c == ',' || c == ';' || c == '[' || c == ']') {
                out.add(new Token(TokKind.PUNCT, String.valueOf(c)));
                i++;
                continue;
            }
            // 运算符（最长匹配 :: != <= >= || ~* ->> ->> #>> 等按通用两字符吞）
            if (c == ':' && i + 1 < n && sql.charAt(i + 1) == ':') {
                out.add(new Token(TokKind.OP, "::"));
                i += 2;
                continue;
            }
            if ((c == '!' || c == '<' || c == '>' || c == '|' || c == '=') && i + 1 < n) {
                char d = sql.charAt(i + 1);
                if (d == '=' || d == '>' || d == '<' || d == '|' || (c == '~' && false)) {
                    out.add(new Token(TokKind.OP, "" + c + d));
                    i += 2;
                    continue;
                }
            }
            if (c == '~' || c == '!' || c == '=' || c == '<' || c > ' ' || c < ' ') {
                out.add(new Token(TokKind.OP, String.valueOf(c)));
                i++;
                continue;
            }
            throw new ParseFailure("unexpected character " + c);
        }
        return out;
    }

    /** 对照 parseResult.Stmts 计数：按顶层分号切段，空段（无 token）不计。 */
    private static List<List<Token>> splitStatements(List<Token> tokens) {
        List<List<Token>> statements = new ArrayList<>();
        List<Token> current = new ArrayList<>();
        for (Token t : tokens) {
            if (t.kind == TokKind.PUNCT && t.text.equals(";")) {
                if (!current.isEmpty()) {
                    statements.add(current);
                }
                current = new ArrayList<>();
            } else {
                current.add(t);
            }
        }
        if (!current.isEmpty()) {
            statements.add(current);
        }
        return statements;
    }

    private static Token firstMeaningful(List<Token> tokens) {
        return tokens.isEmpty() ? null : tokens.get(0);
    }

    // ==================== Phase 5 深检查（对照 validateSelectStmt） ====================

    /** 深检查输出：首个错误文案 + 表→别名（出现序）+ 表名（出现序，Relname 原始大小写）+ 是否有真实 WHERE。 */
    private static final class DeepCheck {
        String error;
        final Map<String, String> tablesInQuery = new LinkedHashMap<>();
        final List<String> tableNames = new ArrayList<>();
        boolean hasRealWhere;
    }

    /** 子句边界关键字（深度 0 处截断 WHERE/GROUP 等的后继子句）。 */
    private static boolean isClauseKeyword(Token t) {
        return t.kind == TokKind.IDENT
                && (t.isKeyword("where") || t.isKeyword("group") || t.isKeyword("order")
                        || t.isKeyword("limit") || t.isKeyword("offset") || t.isKeyword("having")
                        || t.isKeyword("fetch") || t.isKeyword("for") || t.isKeyword("union")
                        || t.isKeyword("intersect") || t.isKeyword("except"));
    }

    /** JOIN 族关键字（from 项之间的连接词）。 */
    private static boolean isJoinKeyword(Token t) {
        return t.kind == TokKind.IDENT
                && (t.isKeyword("join") || t.isKeyword("inner") || t.isKeyword("left")
                        || t.isKeyword("right") || t.isKeyword("full") || t.isKeyword("cross"));
    }

    /**
     * PG 可解析的非 SELECT 语句首关键字（对照 pg_query 的语句集合；不在这个
     * 集合里的首 token——如 DESCRIBE/PRAGMA——pg 侧是 syntax error）。
     */
    private static boolean isKnownStatementKeyword(String ident) {
        String kw = ident.toLowerCase(Locale.ROOT);
        return switch (kw) {
            case "delete", "update", "insert", "drop", "create", "alter", "truncate",
                    "vacuum", "analyze", "grant", "revoke", "call", "do", "copy",
                    "begin", "commit", "start", "rollback", "savepoint", "end",
                    "set", "reset", "show", "explain", "values", "table", "listen",
                    "notify", "unlisten", "checkpoint", "cluster", "reindex",
                    "comment", "security", "import", "merge" -> true;
            default -> false;
        };
    }

    private static final class ExprValidator {
        String error;
        final GuardConfig cfg;

        ExprValidator(GuardConfig cfg) {
            this.cfg = cfg;
        }

        /** 对照 validateNode 的表达式递归检查：子查询 / 函数（schema/危险/白名单）/ 系统列 / pg_ 转型。 */
        void validate(List<Token> tokens) {
            for (int i = 0; i < tokens.size(); i++) {
                Token t = tokens.get(i);
                if (error != null) {
                    return;
                }
                if (t.kind == TokKind.PUNCT && t.text.equals("(")) {
                    Token next = i + 1 < tokens.size() ? tokens.get(i + 1) : null;
                    if (next != null && next.isKeyword("select")) {
                        // 对照 SubLink 检查（checkSubqueries=true）：拒绝；
                        // checkSubqueries=false（data_analysis）时对照 Go 只校验
                        // Testexpr、子查询本体跳过——直接跳过多匹配括号。
                        if (cfg.checkSubqueries) {
                            error = "subqueries are not allowed";
                            return;
                        }
                        int close = matchingParen(tokens, i);
                        if (close < 0) {
                            error = "unbalanced parentheses";
                            return;
                        }
                        i = close;
                        continue;
                    }
                    continue;
                }
                if (t.kind == TokKind.IDENT && t.isKeyword("cast")) {
                    Token next = i + 1 < tokens.size() ? tokens.get(i + 1) : null;
                    if (next != null && next.text.equals("(")) {
                        int close = matchingParen(tokens, i + 1);
                        if (close < 0) {
                            error = "unbalanced parentheses";
                            return;
                        }
                        checkCastType(tokens, i + 2, close);
                        if (error != null) {
                            return;
                        }
                        i = close;
                        continue;
                    }
                }
                if (t.kind == TokKind.OP && t.text.equals("::")) {
                    // 转型：收集后续类型名（ident 链 + 多词类型）
                    int j = i + 1;
                    if (j < tokens.size() && tokens.get(j).kind == TokKind.IDENT) {
                        List<String> parts = new ArrayList<>();
                        while (j < tokens.size() && (tokens.get(j).kind == TokKind.IDENT
                                || (tokens.get(j).kind == TokKind.PUNCT && tokens.get(j).text.equals(".")))) {
                            if (tokens.get(j).kind == TokKind.IDENT) {
                                parts.add(tokens.get(j).text);
                            }
                            j++;
                            if (j < tokens.size() && tokens.get(j).kind == TokKind.PUNCT
                                    && tokens.get(j).text.equals("(")) {
                                int close = matchingParen(tokens, j);
                                if (close < 0) {
                                    error = "unbalanced parentheses";
                                    return;
                                }
                                j = close + 1;
                                break;
                            }
                        }
                        String typeName = normalizeCastTypeName(parts);
                        if (typeName.toLowerCase(Locale.ROOT).startsWith("pg_")) {
                            error = String.format("casting to system type '%s' is not allowed", typeName);
                            return;
                        }
                        i = j - 1;
                        continue;
                    }
                }
                if (t.kind == TokKind.IDENT || t.kind == TokKind.QIDENT) {
                    if (isExprKeyword(t)) {
                        continue;
                    }
                    Token next = i + 1 < tokens.size() ? tokens.get(i + 1) : null;
                    boolean isFuncCall = next != null && next.text.equals("(");
                    if (isFuncCall) {
                        // schema 限定函数调用：a.b(...)（对照 validateFuncCall；
                        // 仅 checkSchemaAccess 时拒绝非 pg_catalog 限定）
                        String funcName = t.text;
                        if (cfg.checkSchemaAccess && i - 1 >= 0
                                && tokens.get(i - 1).kind == TokKind.PUNCT
                                && tokens.get(i - 1).text.equals(".") && i - 2 >= 0
                                && (tokens.get(i - 2).kind == TokKind.IDENT
                                        || tokens.get(i - 2).kind == TokKind.QIDENT)) {
                            String schema = tokens.get(i - 2).text.toLowerCase(Locale.ROOT);
                            if (!schema.equals("pg_catalog")) {
                                error = String.format("schema-qualified function calls are not allowed: %s", schema);
                                return;
                            }
                        }
                        funcName = t.text.toLowerCase(Locale.ROOT);
                        if (cfg.checkDangerousFuncs) {
                            for (String prefix : DANGEROUS_PREFIXES) {
                                if (funcName.startsWith(prefix)) {
                                    error = String.format("function '%s' is not allowed (dangerous prefix)", funcName);
                                    return;
                                }
                            }
                            if (DANGEROUS_FUNCTIONS.containsKey(funcName)) {
                                error = String.format("function '%s' is not allowed", funcName);
                                return;
                            }
                        }
                        if (cfg.checkFunctionNames && !cfg.allowedFunctions.contains(funcName)) {
                            error = String.format("function not allowed: %s", funcName);
                            return;
                        }
                        continue;
                    }
                    if (!cfg.checkSystemColumns) {
                        continue;
                    }
                    // 列引用系统列检查（对照 validateColumnRef）
                    String colName = t.text.toLowerCase(Locale.ROOT);
                    for (String sysCol : SYSTEM_COLUMNS) {
                        if (colName.equals(sysCol)) {
                            error = String.format("access to system column '%s' is not allowed", colName);
                            return;
                        }
                    }
                    if (colName.startsWith("pg_")) {
                        error = String.format("access to '%s' is not allowed", colName);
                        return;
                    }
                }
            }
        }

        /** CAST(x AS type) 的类型名检查（对照 TypeCast 的 pg_ 前缀检查）。 */
        private void checkCastType(List<Token> tokens, int from, int to) {
            // 类型名是 AS 之后的部分（对照 TypeName 节点本身；AS 之前是参数表达式）。
            int typeStart = from;
            for (int i = from; i < to; i++) {
                if (tokens.get(i).isKeyword("as")) {
                    typeStart = i + 1;
                    break;
                }
            }
            List<String> parts = new ArrayList<>();
            for (int i = typeStart; i < to; i++) {
                Token t = tokens.get(i);
                if (t.kind == TokKind.IDENT) {
                    parts.add(t.text);
                }
            }
            String typeName = normalizeCastTypeName(parts);
            if (typeName.toLowerCase(Locale.ROOT).startsWith("pg_")) {
                error = String.format("casting to system type '%s' is not allowed", typeName);
            }
        }
    }

    /** 表达式里的非列引用关键字（对照 Go 侧不产生 ColumnRef/FuncCall 的节点）。 */
    private static boolean isExprKeyword(Token t) {
        return t.isKeyword("select") || t.isKeyword("from") || t.isKeyword("where")
                || t.isKeyword("and") || t.isKeyword("or") || t.isKeyword("not")
                || t.isKeyword("in") || t.isKeyword("like") || t.isKeyword("ilike")
                || t.isKeyword("between") || t.isKeyword("case") || t.isKeyword("when")
                || t.isKeyword("then") || t.isKeyword("else") || t.isKeyword("end")
                || t.isKeyword("is") || t.isKeyword("null") || t.isKeyword("true")
                || t.isKeyword("false") || t.isKeyword("as") || t.isKeyword("on")
                || t.isKeyword("join") || t.isKeyword("inner") || t.isKeyword("left")
                || t.isKeyword("right") || t.isKeyword("full") || t.isKeyword("cross")
                || t.isKeyword("outer") || t.isKeyword("group") || t.isKeyword("by")
                || t.isKeyword("order") || t.isKeyword("having") || t.isKeyword("limit")
                || t.isKeyword("offset") || t.isKeyword("asc") || t.isKeyword("desc")
                || t.isKeyword("nulls") || t.isKeyword("first") || t.isKeyword("last")
                || t.isKeyword("distinct") || t.isKeyword("all") || t.isKeyword("union")
                || t.isKeyword("intersect") || t.isKeyword("except") || t.isKeyword("exists")
                || t.isKeyword("any") || t.isKeyword("some") || t.isKeyword("array")
                || t.isKeyword("row") || t.isKeyword("collate") || t.isKeyword("cast")
                || t.isKeyword("symmetric") || t.isKeyword("isnull") || t.isKeyword("notnull")
                || t.isKeyword("escape") || t.isKeyword("interval") || t.isKeyword("timestamp")
                || t.isKeyword("date") || t.isKeyword("time") || t.isKeyword("using")
                || t.isKeyword("lateral");
    }

    private static int matchingParen(List<Token> tokens, int openIdx) {
        int depth = 0;
        for (int i = openIdx; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.kind == TokKind.PUNCT && t.text.equals("(")) {
                depth++;
            } else if (t.kind == TokKind.PUNCT && t.text.equals(")")) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * 对照 validateSelectStmt：compound/CTE/INTO/locking → FROM 项（schema/子查询/表函数）
     * → target list / WHERE / GROUP / HAVING / ORDER 的表达式检查 → 至少一张表。
     */
    private static DeepCheck deepCheck(List<Token> stmt, boolean startsWithWith, GuardConfig cfg) {
        DeepCheck out = new DeepCheck();
        ExprValidator validator = new ExprValidator(cfg);

        // WITH clause（对照 stmt.WithClause != nil && checkCTEs）
        if (startsWithWith && cfg.checkCTEs) {
            out.error = "WITH clause (CTEs) is not allowed";
            return out;
        }

        int n = stmt.size();
        int i = 1; // skip SELECT
        // SELECT 修饰：DISTINCT / ALL（对照 SelectStmt 的修饰位，无附加检查）
        if (i < n && (stmt.get(i).isKeyword("distinct") || stmt.get(i).isKeyword("all"))) {
            i++;
        }

        // compound（对照 stmt.Op != SETOP_NONE）：深度 0 的 UNION/INTERSECT/EXCEPT
        for (int k = i; k < n; k++) {
            Token t = stmt.get(k);
            if (t.kind == TokKind.PUNCT && t.text.equals("(")) {
                k = matchingParen(stmt, k);
                if (k < 0) {
                    out.error = "unbalanced parentheses";
                    return out;
                }
                continue;
            }
            if (t.isKeyword("union") || t.isKeyword("intersect") || t.isKeyword("except")) {
                out.error = "compound queries (UNION/INTERSECT/EXCEPT) are not allowed";
                return out;
            }
        }

        // target list：SELECT ... [INTO ...] FROM —— INTO 在 from 前（对照 IntoClause）
        int fromIdx = -1;
        int depth = 0;
        for (int k = i; k < n; k++) {
            Token t = stmt.get(k);
            if (t.kind == TokKind.PUNCT && t.text.equals("(")) {
                depth++;
            } else if (t.kind == TokKind.PUNCT && t.text.equals(")")) {
                depth--;
            } else if (depth == 0 && t.isKeyword("from")) {
                fromIdx = k;
                break;
            } else if (depth == 0 && t.isKeyword("into")) {
                out.error = "SELECT INTO is not allowed";
                return out;
            }
        }

        if (fromIdx < 0) {
            // 无 FROM：仍是合法 SELECT（如 SELECT 1），但表集为空——由调用点报
            // "no valid table found in query"；target list 仍需检查（Go 同样校验）。
            validator.validate(stmt.subList(i, n));
            out.error = validator.error != null ? validator.error : "no valid table found in query";
            return out;
        }

        // target list 表达式检查（对照 for target := range stmt.TargetList）
        List<Token> targetList = new ArrayList<>(stmt.subList(i, fromIdx));
        stripAliases(targetList);
        validator.validate(targetList);
        if (validator.error != null) {
            out.error = validator.error;
            return out;
        }

        // FROM 项解析（对照 validateFromItem：RangeVar/JoinExpr/RangeSubselect/RangeFunction）
        i = fromIdx + 1;
        while (i < n) {
            Token t = stmt.get(i);
            if (isClauseKeyword(t)) {
                break;
            }
            if (t.kind == TokKind.PUNCT && t.text.equals(",")) {
                i++;
                continue;
            }
            if (isJoinKeyword(t)) {
                i++;
                continue;
            }
            if (t.isKeyword("on")) {
                // JOIN quals：扫到下一个 from 项边界（对照 JoinExpr.Quals 的 validateNode）
                int j = i + 1;
                depth = 0;
                while (j < n) {
                    Token u = stmt.get(j);
                    if (u.kind == TokKind.PUNCT && u.text.equals("(")) {
                        depth++;
                    } else if (u.kind == TokKind.PUNCT && u.text.equals(")")) {
                        depth--;
                    } else if (depth == 0 && (isJoinKeyword(u) || u.text.equals(",")
                            || isClauseKeyword(u))) {
                        break;
                    }
                    j++;
                }
                validator.validate(stmt.subList(i + 1, j));
                if (validator.error != null) {
                    out.error = validator.error;
                    return out;
                }
                i = j;
                continue;
            }
            if (t.isKeyword("using")) {
                // USING (...)：跳到右括号
                int close = i + 1 < n && stmt.get(i + 1).text.equals("(")
                        ? matchingParen(stmt, i + 1) : -1;
                i = close < 0 ? i + 1 : close + 1;
                continue;
            }
            if (t.kind == TokKind.PUNCT && t.text.equals("(")) {
                // RangeSubselect / 表函数（对照 validateFromItem 的两条路径：
                // 子查询在 checkSubqueries 时拒绝，否则递归校验（validateSubquery）；
                // 表函数恒拒绝。白名单的表提取不进子查询——对照 Go
                // extractTableNamesFromNode 对 RangeSubselect 返回空的怪癖。）
                Token next = i + 1 < n ? stmt.get(i + 1) : null;
                if (next != null && next.isKeyword("select")) {
                    if (cfg.checkSubqueries) {
                        out.error = "subqueries in FROM clause are not allowed";
                        return out;
                    }
                    int close = matchingParen(stmt, i);
                    if (close < 0) {
                        out.error = "unbalanced parentheses";
                        return out;
                    }
                    DeepCheck inner = deepCheck(stmt.subList(i + 1, close), false, cfg);
                    if (inner.error != null) {
                        out.error = inner.error;
                        return out;
                    }
                    i = close + 1;
                    // 可选别名
                    if (i < n && stmt.get(i).isKeyword("as")) {
                        i++;
                    }
                    if (i < n && (stmt.get(i).kind == TokKind.IDENT
                            || stmt.get(i).kind == TokKind.QIDENT)
                            && !isJoinKeyword(stmt.get(i)) && !isClauseKeyword(stmt.get(i))
                            && !stmt.get(i).isKeyword("on") && !stmt.get(i).isKeyword("using")) {
                        i++;
                    }
                    continue;
                }
                out.error = "functions in FROM clause are not allowed";
                return out;
            }
            if (t.kind == TokKind.IDENT || t.kind == TokKind.QIDENT) {
                // 表名（可 schema.table[.table]）+ 可选 AS + 可选别名
                List<String> parts = new ArrayList<>();
                parts.add(t.text);
                int j = i + 1;
                while (j + 1 < n && stmt.get(j).kind == TokKind.PUNCT && stmt.get(j).text.equals(".")
                        && (stmt.get(j + 1).kind == TokKind.IDENT
                                || stmt.get(j + 1).kind == TokKind.QIDENT)) {
                    parts.add(stmt.get(j + 1).text);
                    j += 2;
                }
                // 表函数（IDENT 后紧跟 '('，如 read_csv_auto(...)）：对照 Go
                // validateFromItem 对 RangeFunction 直接报错，且
                // extractTableNamesFromNode 不收录函数名——Java tokenizer 先撞
                // IDENT 分支，须在此识别，既不记白名单也不记别名映射。
                if (j < n && stmt.get(j).kind == TokKind.PUNCT && stmt.get(j).text.equals("(")) {
                    out.error = "functions in FROM clause are not allowed";
                    return out;
                }
                if (parts.size() > 1 && cfg.checkSchemaAccess) {
                    // schema 限定（对照 rv.Schemaname != public 检查；取倒数第二段；
                    // checkSchemaAccess=false 时照录 Go 放行，白名单只看 Relname）
                    String schema = parts.get(parts.size() - 2);
                    if (!schema.equalsIgnoreCase("public")) {
                        out.error = String.format("access to schema '%s' is not allowed", schema);
                        return out;
                    }
                }
                String table = parts.get(parts.size() - 1);
                String alias = table;
                if (j < n && stmt.get(j).isKeyword("as")) {
                    j++;
                }
                if (j < n && (stmt.get(j).kind == TokKind.IDENT || stmt.get(j).kind == TokKind.QIDENT)
                        && !isJoinKeyword(stmt.get(j)) && !isClauseKeyword(stmt.get(j))
                        && !stmt.get(j).isKeyword("on") && !stmt.get(j).isKeyword("using")) {
                    alias = stmt.get(j).text;
                    j++;
                }
                // 表名记录用 Relname（最后一段）原始大小写（对照 extractTableNames；
                // 注入用的别名 map 才小写化）
                out.tableNames.add(table);
                out.tablesInQuery.put(table.toLowerCase(Locale.ROOT), alias.toLowerCase(Locale.ROOT));
                i = j;
                continue;
            }
            // FROM 里出现无法识别的东西（数字/字符串/运算符）——pg 侧本就 parse error，
            // tokenizer 已过，这里按无法解析处理。
            out.error = "syntax error";
            return out;
        }

        // WHERE 子句（对照 stmt.WhereClause 的 validateNode）
        if (i < n && stmt.get(i).isKeyword("where")) {
            out.hasRealWhere = true;
            int j = i + 1;
            depth = 0;
            while (j < n) {
                Token u = stmt.get(j);
                if (u.kind == TokKind.PUNCT && u.text.equals("(")) {
                    depth++;
                } else if (u.kind == TokKind.PUNCT && u.text.equals(")")) {
                    depth--;
                } else if (depth == 0 && isClauseKeyword(u)) {
                    break;
                }
                j++;
            }
            validator.validate(stmt.subList(i + 1, j));
            if (validator.error != null) {
                out.error = validator.error;
                return out;
            }
            i = j;
        }

        // GROUP BY / HAVING / ORDER BY（对照对应子句的 validateNode；LIMIT/OFFSET 为常量）
        while (i < n) {
            Token t = stmt.get(i);
            if (t.isKeyword("group")) {
                int j = clauseEnd(stmt, i + 1);
                validator.validate(stmt.subList(i + 1, j));
                if (validator.error != null) {
                    out.error = validator.error;
                    return out;
                }
                i = j;
            } else if (t.isKeyword("having")) {
                int j = clauseEnd(stmt, i + 1);
                validator.validate(stmt.subList(i + 1, j));
                if (validator.error != null) {
                    out.error = validator.error;
                    return out;
                }
                i = j;
            } else if (t.isKeyword("order")) {
                int j = clauseEnd(stmt, i + 1);
                List<Token> seg = new ArrayList<>(stmt.subList(i + 1, j));
                stripAliases(seg); // ASC/DESC/FIRST/LAST 已在关键字表；NULLS 也已在表
                validator.validate(seg);
                if (validator.error != null) {
                    out.error = validator.error;
                    return out;
                }
                i = j;
            } else if (t.isKeyword("limit") || t.isKeyword("offset") || t.isKeyword("fetch")) {
                i = clauseEnd(stmt, i + 1);
            } else if (t.isKeyword("for")) {
                // locking（对照 len(stmt.LockingClause) > 0）
                out.error = "locking clauses (FOR UPDATE, etc.) are not allowed";
                return out;
            } else if (t.isKeyword("union") || t.isKeyword("intersect") || t.isKeyword("except")) {
                out.error = "compound queries (UNION/INTERSECT/EXCEPT) are not allowed";
                return out;
            } else {
                i++;
            }
        }

        // 至少一张表（对照 len(tablesInQuery) == 0）
        if (out.tablesInQuery.isEmpty()) {
            out.error = "no valid table found in query";
            return out;
        }
        return out;
    }

    /** 子句终点：深度 0 的下一个子句关键字或 EOF。 */
    private static int clauseEnd(List<Token> tokens, int from) {
        int depth = 0;
        for (int j = from; j < tokens.size(); j++) {
            Token u = tokens.get(j);
            if (u.kind == TokKind.PUNCT && u.text.equals("(")) {
                depth++;
            } else if (u.kind == TokKind.PUNCT && u.text.equals(")")) {
                depth--;
            } else if (depth == 0 && isClauseKeyword(u)) {
                return j;
            }
        }
        return tokens.size();
    }

    /** SELECT 列表/ORDER BY 的别名剥离：去掉 AS x 与尾随裸别名（对照 ResTarget.Name——别名不是 ColumnRef）。 */
    private static void stripAliases(List<Token> tokens) {
        for (int k = 0; k < tokens.size(); k++) {
            Token t = tokens.get(k);
            if (t.kind == TokKind.IDENT && t.isKeyword("as") && k + 1 < tokens.size()
                    && (tokens.get(k + 1).kind == TokKind.IDENT
                            || tokens.get(k + 1).kind == TokKind.QIDENT)) {
                tokens.remove(k + 1);
                tokens.remove(k);
                k--;
            }
        }
    }

    /** 只提取表→别名（注入用；出现序 LinkedHashMap）。 */
    private static Map<String, String> parseTablesInQuery(String sql) {
        try {
            List<Token> tokens = tokenize(sql);
            List<List<Token>> statements = splitStatements(tokens);
            if (statements.isEmpty()) {
                return Map.of();
            }
            DeepCheck deep = deepCheckSkipOnError(statements.get(0));
            return deep.tablesInQuery;
        } catch (ParseFailure e) {
            return Map.of();
        }
    }

    /**
     * 注入前的表提取：校验已通过，这里不再关心错误——但 deepCheck 遇错误会提前
     * 返回导致表不全；校验通过时两者等价。包装一层吞掉错误语义。
     */
    private static DeepCheck deepCheckSkipOnError(List<Token> stmt) {
        return deepCheck(stmt, stmt.get(0).isKeyword("with"), GuardConfig.securityDefaults());
    }

    // ==================== Phase 7 注入风险（对照 checkSQLInjectionRisks，逐字） ====================

    private static final Pattern RE_SQL_WHITESPACE = Pattern.compile("\\s+");

    private static final class RiskPattern {
        final Pattern pattern;
        final String description;

        RiskPattern(String regex, String description) {
            this.pattern = Pattern.compile(regex);
            this.description = description;
        }
    }

    private static final RiskPattern[] SQL_ALWAYS_TRUE_PATTERNS = {
            new RiskPattern(
                    "(^|\\s|\\()(1\\s*=\\s*1|'1'\\s*=\\s*'1'|\"1\"\\s*=\\s*\"1\")(\\s|\\)|$|and|or)",
                    "Always-true condition '1=1' or similar"),
            new RiskPattern(
                    "(^|\\s|\\()(0\\s*=\\s*0|'0'\\s*=\\s*'0'|\"0\"\\s*=\\s*\"0\")(\\s|\\)|$|and|or)",
                    "Always-true condition '0=0' or similar"),
            new RiskPattern(
                    "(^|\\s|\\()(true)(\\s|\\)|$|and|or)",
                    "Always-true condition 'true'"),
            new RiskPattern(
                    "(^|\\s|\\()('\\s*'\\s*=\\s*'\\s*'|\"\\s*\"\\s*=\\s*\"\\s*\")(\\s|\\)|$|and|or)",
                    "Always-true condition with empty strings"),
    };

    private static final RiskPattern[] SQL_ALWAYS_FALSE_PATTERNS = {
            new RiskPattern(
                    "(^|\\s|\\()(1\\s*=\\s*0|0\\s*=\\s*1|'1'\\s*=\\s*'0'|\"1\"\\s*=\\s*\"0\")(\\s|\\)|$|and|or)",
                    "Always-false condition '1=0' or similar"),
            new RiskPattern(
                    "(^|\\s|\\()(false)(\\s|\\)|$|and|or)",
                    "Always-false condition 'false'"),
    };

    private static final Pattern RE_SQL_OR_ALWAYS_TRUE =
            Pattern.compile("or\\s+(1\\s*=\\s*1|'1'\\s*=\\s*'1'|true)");

    /** 对照 checkSQLInjectionRisks：匹配的规则各产生一条错误（不短路）。 */
    static List<SqlValidationError> checkSqlInjectionRisks(String whereClause) {
        List<SqlValidationError> errors = new ArrayList<>();
        if (whereClause == null || whereClause.isEmpty()) {
            return errors;
        }
        String normalizedWhere = whereClause.toLowerCase(Locale.ROOT).trim();
        normalizedWhere = RE_SQL_WHITESPACE.matcher(normalizedWhere).replaceAll(" ");

        for (RiskPattern pt : SQL_ALWAYS_TRUE_PATTERNS) {
            if (pt.pattern.matcher(normalizedWhere).find()) {
                errors.add(new SqlValidationError(
                        "sql_injection_risk", "Potential SQL injection risk detected",
                        String.format("%s found in WHERE clause: %s", pt.description, whereClause)));
            }
        }
        for (RiskPattern pt : SQL_ALWAYS_FALSE_PATTERNS) {
            if (pt.pattern.matcher(normalizedWhere).find()) {
                errors.add(new SqlValidationError(
                        "sql_injection_risk", "Suspicious SQL pattern detected",
                        String.format("%s found in WHERE clause: %s", pt.description, whereClause)));
            }
        }
        if (RE_SQL_OR_ALWAYS_TRUE.matcher(normalizedWhere).find()) {
            errors.add(new SqlValidationError(
                    "sql_injection_risk", "High-risk SQL injection pattern detected",
                    String.format("OR with always-true condition found in WHERE clause: %s", whereClause)));
        }
        return errors;
    }

    /** 对照 extractWhereClauseText（naive 子串扫描，含字符串字面量误配的原始怪癖，照录）。 */
    static String extractWhereClauseText(String sql) {
        String lowerSQL = sql.toLowerCase(Locale.ROOT);
        int wherePos = lowerSQL.indexOf("where");
        if (wherePos == -1) {
            return "";
        }
        int whereClauseEnd = sql.length();
        for (String keyword : new String[] {"group by", "order by", "limit", "having",
                "union", "intersect", "except"}) {
            int pos = lowerSQL.indexOf(keyword, wherePos);
            if (pos != -1 && pos < whereClauseEnd) {
                whereClauseEnd = pos;
            }
        }
        return sql.substring(wherePos + 5, whereClauseEnd).trim();
    }

    // ==================== 白名单/黑名单常量（对照 WithSecurityDefaults） ====================

    private static final Map<String, Boolean> ALLOWED_TABLES = new LinkedHashMap<>();

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

    private static final String[] DANGEROUS_PREFIXES = {
            "pg_", "lo_", "dblink", "file_", "copy_", "binary_",
    };

    private static final Map<String, Boolean> DANGEROUS_FUNCTIONS = new LinkedHashMap<>();

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

    private static final String[] SYSTEM_COLUMNS = {"xmin", "xmax", "cmin", "cmax", "ctid", "tableoid"};

    // ==================== 注入重写（对照 ValidateAndSecureSQL 的注入序列） ====================

    private static final Pattern RE_SQL_WHERE_KEYWORD = Pattern.compile("(?i)\\bWHERE\\b");
    private static final Pattern RE_SQL_TAIL_CLAUSE =
            Pattern.compile("(?i)\\b(GROUP BY|ORDER BY|LIMIT|OFFSET|HAVING|FETCH)\\b");

    /** 对照 InjectAndConditions（逐字：WHERE 正则命中字符串字面量里的 where 的怪癖照录）。 */
    public static String injectAndConditions(String sql, String filter) {
        filter = filter == null ? "" : filter.trim();
        if (filter.isEmpty()) {
            return sql;
        }

        Matcher whereMatcher = RE_SQL_WHERE_KEYWORD.matcher(sql);
        if (whereMatcher.find()) {
            int whereExprStart = whereMatcher.end();
            Matcher tailMatcher = RE_SQL_TAIL_CLAUSE.matcher(sql.substring(whereExprStart));
            if (!tailMatcher.find()) {
                String originalWhereExpr = sql.substring(whereExprStart).trim();
                return String.format("%sWHERE %s AND (%s)",
                        sql.substring(0, whereMatcher.start()), filter, originalWhereExpr);
            }
            int whereExprEnd = whereExprStart + tailMatcher.start();
            String originalWhereExpr = sql.substring(whereExprStart, whereExprEnd).trim();
            String tailClause = stripLeft(sql.substring(whereExprEnd), " \t\r\n");
            return String.format("%sWHERE %s AND (%s) %s",
                    sql.substring(0, whereMatcher.start()), filter, originalWhereExpr, tailClause);
        }

        Matcher tailMatcher = RE_SQL_TAIL_CLAUSE.matcher(sql);
        if (tailMatcher.find()) {
            String prefix = stripRight(sql.substring(0, tailMatcher.start()), " \t\r\n");
            String suffix = stripLeft(sql.substring(tailMatcher.start()), " \t\r\n");
            return String.format("%s WHERE %s %s", prefix, filter, suffix);
        }

        return String.format("%s WHERE %s", sql, filter);
    }

    private static String stripLeft(String s, String chars) {
        int i = 0;
        while (i < s.length() && chars.indexOf(s.charAt(i)) >= 0) {
            i++;
        }
        return s.substring(i);
    }

    private static String stripRight(String s, String chars) {
        int i = s.length();
        while (i > 0 && chars.indexOf(s.charAt(i - 1)) >= 0) {
            i--;
        }
        return s.substring(0, i);
    }

    /** 对照 injectTenantConditions（tablesInQuery 出现序遍历；Go map 随机——已知差异④）。 */
    private static String injectTenantConditions(String sql, Map<String, String> tablesInQuery,
            long tenantID) {
        List<String> conditions = new ArrayList<>();
        for (Map.Entry<String, String> e : tablesInQuery.entrySet()) {
            if (ALLOWED_TABLES.containsKey(e.getKey()) || TENANT_TABLES.containsKey(e.getKey())) {
                if ("tenants".equals(e.getKey())) {
                    conditions.add(String.format("%s.id = %d", e.getValue(), tenantID));
                } else {
                    conditions.add(String.format("%s.tenant_id = %d", e.getValue(), tenantID));
                }
            }
        }
        if (conditions.isEmpty()) {
            return sql;
        }
        return injectAndConditions(sql, String.join(" AND ", conditions));
    }

    private static final Map<String, Boolean> TENANT_TABLES = new LinkedHashMap<>();

    static {
        for (String t : new String[] {"knowledge_bases", "knowledges", "chunks"}) {
            TENANT_TABLES.put(t, Boolean.TRUE);
        }
    }

    private static final Map<String, Boolean> SOFT_DELETE_TABLES = new LinkedHashMap<>();

    static {
        for (String t : new String[] {"knowledge_bases", "knowledges", "chunks"}) {
            SOFT_DELETE_TABLES.put(t, Boolean.TRUE);
        }
    }

    /** 对照 injectSoftDeleteConditions。 */
    private static String injectSoftDeleteConditions(String sql, Map<String, String> tablesInQuery) {
        List<String> conditions = new ArrayList<>();
        for (Map.Entry<String, String> e : tablesInQuery.entrySet()) {
            if (SOFT_DELETE_TABLES.containsKey(e.getKey())) {
                conditions.add(String.format("%s.deleted_at IS NULL", e.getValue()));
            }
        }
        if (conditions.isEmpty()) {
            return sql;
        }
        return injectAndConditions(sql, String.join(" AND ", conditions));
    }

    /** 对照 injectHiddenKBFilter。 */
    private static String injectHiddenKbFilter(String sql, Map<String, String> tablesInQuery) {
        String alias = tablesInQuery.get("knowledge_bases");
        if (alias == null) {
            return sql;
        }
        return injectAndConditions(sql, String.format("%s.is_temporary = false", alias));
    }

    /** 对照 injectChunkEnabledFilter。 */
    private static String injectChunkEnabledFilter(String sql, Map<String, String> tablesInQuery) {
        String alias = tablesInQuery.get("chunks");
        if (alias == null) {
            return sql;
        }
        return injectAndConditions(sql, String.format("%s.is_enabled = true", alias));
    }

    /** 对照 injectStructuredSearchScopeConditions（database_query 恒走 structured 路径）。 */
    static String injectStructuredSearchScopeConditions(String sql, Map<String, String> tablesInQuery,
            List<SearchScope> scopes) {
        List<String> conditions = new ArrayList<>();
        String kbAlias = tablesInQuery.get("knowledge_bases");
        if (kbAlias != null) {
            String cond = buildKnowledgeBaseScopeCondition(kbAlias, scopes);
            if (!cond.isEmpty()) {
                conditions.add(cond);
            }
        }
        String kAlias = tablesInQuery.get("knowledges");
        if (kAlias != null) {
            String cond = buildKnowledgeScopeCondition(kAlias, scopes);
            if (!cond.isEmpty()) {
                conditions.add(cond);
            }
        }
        String cAlias = tablesInQuery.get("chunks");
        if (cAlias != null) {
            String cond = buildChunkScopeCondition(cAlias, scopes);
            if (!cond.isEmpty()) {
                conditions.add(cond);
            }
        }
        if (conditions.isEmpty()) {
            return sql;
        }
        return injectAndConditions(sql, String.join(" AND ", conditions));
    }

    static String buildKnowledgeBaseScopeCondition(String alias, List<SearchScope> scopes) {
        List<String> kbIDs = uniqueScopeKbIds(scopes);
        if (kbIDs.isEmpty()) {
            return "";
        }
        return String.format("%s.id IN (%s)", alias, String.join(", ", quoteStringSlice(kbIDs)));
    }

    /** 对照 buildScopeClause（scope 内 AND、scope 间 OR）。 */
    static String buildScopeClause(String alias, String knowledgeIDColumn, SearchScope scope) {
        if (scope.knowledgeBaseId() == null || scope.knowledgeBaseId().isEmpty()) {
            return "";
        }
        List<String> conditions = new ArrayList<>();
        conditions.add(String.format("%s.knowledge_base_id = %s", alias, quoteString(scope.knowledgeBaseId())));
        if (scope.knowledgeIds() != null && !scope.knowledgeIds().isEmpty()) {
            conditions.add(String.format("%s.%s IN (%s)",
                    alias, knowledgeIDColumn, String.join(", ", quoteStringSlice(scope.knowledgeIds()))));
        }
        if (scope.tagIds() != null && !scope.tagIds().isEmpty()) {
            conditions.add(String.format(
                    "EXISTS (SELECT 1 FROM knowledge_tag_relations ktr WHERE ktr.knowledge_id = %s.%s"
                            + " AND ktr.tag_id IN (%s))",
                    alias, knowledgeIDColumn, String.join(", ", quoteStringSlice(scope.tagIds()))));
        }
        if (conditions.size() == 1) {
            return conditions.get(0);
        }
        return "(" + String.join(" AND ", conditions) + ")";
    }

    static String buildKnowledgeScopeCondition(String alias, List<SearchScope> scopes) {
        List<String> clauses = new ArrayList<>();
        for (SearchScope scope : scopes) {
            String clause = buildScopeClause(alias, "id", scope);
            if (!clause.isEmpty()) {
                clauses.add(clause);
            }
        }
        return joinOrClauses(clauses);
    }

    static String buildChunkScopeCondition(String alias, List<SearchScope> scopes) {
        List<String> clauses = new ArrayList<>();
        for (SearchScope scope : scopes) {
            String clause = buildScopeClause(alias, "knowledge_id", scope);
            if (!clause.isEmpty()) {
                clauses.add(clause);
            }
        }
        return joinOrClauses(clauses);
    }

    static List<String> uniqueScopeKbIds(List<SearchScope> scopes) {
        Map<String, Boolean> seen = new LinkedHashMap<>();
        List<String> out = new ArrayList<>();
        for (SearchScope scope : scopes) {
            String id = scope.knowledgeBaseId();
            if (id == null || id.isEmpty() || seen.containsKey(id)) {
                continue;
            }
            seen.put(id, Boolean.TRUE);
            out.add(id);
        }
        return out;
    }

    static String joinOrClauses(List<String> clauses) {
        if (clauses.isEmpty()) {
            return "";
        }
        if (clauses.size() == 1) {
            return clauses.get(0);
        }
        return "(" + String.join(" OR ", clauses) + ")";
    }

    static String quoteString(String s) {
        return quoteStringSlice(List.of(s)).get(0);
    }

    static List<String> quoteStringSlice(List<String> ss) {
        List<String> quoted = new ArrayList<>(ss.size());
        for (String s : ss) {
            String escaped = s.replace("'", "''");
            quoted.add(String.format("'%s'", escaped));
        }
        return quoted;
    }
}
