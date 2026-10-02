package com.ragagent.agent.tools.sql;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * SELECT 语句深检查（Phase 5）：子句/表达式校验、表别名解析、危险函数与
 * 系统列检查，输出 DeepCheck 结论；含注入前的表→别名提取（parseTablesInQuery）。
 *
 * <p>{@link SqlGuard} 的包内协作者：纯静态实现，由 SqlGuard 门面调用。</p>
 */
final class SqlSelectDeepChecker {

    /** 深检查输出：首个错误文案 + 表→别名（出现序）+ 表名（出现序，原始大小写）+ 是否有真实 WHERE。 */
    static final class DeepCheck {
        String error;
        final Map<String, String> tablesInQuery = new LinkedHashMap<>();
        final List<String> tableNames = new ArrayList<>();
        boolean hasRealWhere;
    }

    /** 子句边界关键字（深度 0 处截断 WHERE/GROUP 等的后继子句）。 */
    static boolean isClauseKeyword(SqlTokenizer.Token t) {
        return t.kind == SqlTokenizer.TokKind.IDENT
                && (t.isKeyword("where") || t.isKeyword("group") || t.isKeyword("order")
                        || t.isKeyword("limit") || t.isKeyword("offset") || t.isKeyword("having")
                        || t.isKeyword("fetch") || t.isKeyword("for") || t.isKeyword("union")
                        || t.isKeyword("intersect") || t.isKeyword("except"));
    }

    /** JOIN 族关键字（from 项之间的连接词）。 */
    static boolean isJoinKeyword(SqlTokenizer.Token t) {
        return t.kind == SqlTokenizer.TokKind.IDENT
                && (t.isKeyword("join") || t.isKeyword("inner") || t.isKeyword("left")
                        || t.isKeyword("right") || t.isKeyword("full") || t.isKeyword("cross"));
    }

    /**
     * PG 可解析的非 SELECT 语句首关键字；不在这个集合里的首 token
     * ——如 DESCRIBE/PRAGMA——在 pg 语法里是 syntax error。
     */
    static boolean isKnownStatementKeyword(String ident) {
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

    static final class ExprValidator {
        String error;
        final SqlGuard.GuardConfig cfg;

        ExprValidator(SqlGuard.GuardConfig cfg) {
            this.cfg = cfg;
        }

        /** 表达式递归检查：子查询 / 函数（schema/危险/白名单）/ 系统列 / pg_ 转型。 */
        void validate(List<SqlTokenizer.Token> tokens) {
            for (int i = 0; i < tokens.size(); i++) {
                SqlTokenizer.Token t = tokens.get(i);
                if (error != null) {
                    return;
                }
                if (t.kind == SqlTokenizer.TokKind.PUNCT && t.text.equals("(")) {
                    SqlTokenizer.Token next = i + 1 < tokens.size() ? tokens.get(i + 1) : null;
                    if (next != null && next.isKeyword("select")) {
                        // 子查询检查（checkSubqueries=true）：拒绝；
                        // checkSubqueries=false（data_analysis）时只校验 IN 左侧
                        // 表达式、子查询本体跳过——直接跳到多匹配括号之后。
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
                if (t.kind == SqlTokenizer.TokKind.IDENT && t.isKeyword("cast")) {
                    SqlTokenizer.Token next = i + 1 < tokens.size() ? tokens.get(i + 1) : null;
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
                if (t.kind == SqlTokenizer.TokKind.OP && t.text.equals("::")) {
                    // 转型：收集后续类型名（ident 链 + 多词类型）
                    int j = i + 1;
                    if (j < tokens.size() && tokens.get(j).kind == SqlTokenizer.TokKind.IDENT) {
                        List<String> parts = new ArrayList<>();
                        while (j < tokens.size() && (tokens.get(j).kind == SqlTokenizer.TokKind.IDENT
                                || (tokens.get(j).kind == SqlTokenizer.TokKind.PUNCT && tokens.get(j).text.equals(".")))) {
                            if (tokens.get(j).kind == SqlTokenizer.TokKind.IDENT) {
                                parts.add(tokens.get(j).text);
                            }
                            j++;
                            if (j < tokens.size() && tokens.get(j).kind == SqlTokenizer.TokKind.PUNCT
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
                        String typeName = SqlGuard.normalizeCastTypeName(parts);
                        if (typeName.toLowerCase(Locale.ROOT).startsWith("pg_")) {
                            error = String.format("casting to system type '%s' is not allowed", typeName);
                            return;
                        }
                        i = j - 1;
                        continue;
                    }
                }
                if (t.kind == SqlTokenizer.TokKind.IDENT || t.kind == SqlTokenizer.TokKind.QIDENT) {
                    if (isExprKeyword(t)) {
                        continue;
                    }
                    SqlTokenizer.Token next = i + 1 < tokens.size() ? tokens.get(i + 1) : null;
                    boolean isFuncCall = next != null && next.text.equals("(");
                    if (isFuncCall) {
                        // schema 限定函数调用：a.b(...)
                        // （仅 checkSchemaAccess 时拒绝非 pg_catalog 限定）
                        String funcName = t.text;
                        if (cfg.checkSchemaAccess && i - 1 >= 0
                                && tokens.get(i - 1).kind == SqlTokenizer.TokKind.PUNCT
                                && tokens.get(i - 1).text.equals(".") && i - 2 >= 0
                                && (tokens.get(i - 2).kind == SqlTokenizer.TokKind.IDENT
                                        || tokens.get(i - 2).kind == SqlTokenizer.TokKind.QIDENT)) {
                            String schema = tokens.get(i - 2).text.toLowerCase(Locale.ROOT);
                            if (!schema.equals("pg_catalog")) {
                                error = String.format("schema-qualified function calls are not allowed: %s", schema);
                                return;
                            }
                        }
                        funcName = t.text.toLowerCase(Locale.ROOT);
                        if (cfg.checkDangerousFuncs) {
                            for (String prefix : SqlGuard.DANGEROUS_PREFIXES) {
                                if (funcName.startsWith(prefix)) {
                                    error = String.format("function '%s' is not allowed (dangerous prefix)", funcName);
                                    return;
                                }
                            }
                            if (SqlGuard.DANGEROUS_FUNCTIONS.containsKey(funcName)) {
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
                    // 列引用系统列检查
                    String colName = t.text.toLowerCase(Locale.ROOT);
                    for (String sysCol : SqlGuard.SYSTEM_COLUMNS) {
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

        /** CAST(x AS type) 的类型名检查（拒绝 pg_ 前缀系统类型）。 */
        private void checkCastType(List<SqlTokenizer.Token> tokens, int from, int to) {
            // 类型名是 AS 之后的部分（AS 之前是参数表达式）。
            int typeStart = from;
            for (int i = from; i < to; i++) {
                if (tokens.get(i).isKeyword("as")) {
                    typeStart = i + 1;
                    break;
                }
            }
            List<String> parts = new ArrayList<>();
            for (int i = typeStart; i < to; i++) {
                SqlTokenizer.Token t = tokens.get(i);
                if (t.kind == SqlTokenizer.TokKind.IDENT) {
                    parts.add(t.text);
                }
            }
            String typeName = SqlGuard.normalizeCastTypeName(parts);
            if (typeName.toLowerCase(Locale.ROOT).startsWith("pg_")) {
                error = String.format("casting to system type '%s' is not allowed", typeName);
            }
        }
    }

    /** 表达式里的非列引用关键字（不会是列名或函数名）。 */
    static boolean isExprKeyword(SqlTokenizer.Token t) {
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

    static int matchingParen(List<SqlTokenizer.Token> tokens, int openIdx) {
        int depth = 0;
        for (int i = openIdx; i < tokens.size(); i++) {
            SqlTokenizer.Token t = tokens.get(i);
            if (t.kind == SqlTokenizer.TokKind.PUNCT && t.text.equals("(")) {
                depth++;
            } else if (t.kind == SqlTokenizer.TokKind.PUNCT && t.text.equals(")")) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * 检查序：compound/CTE/INTO/locking → FROM 项（schema/子查询/表函数）
     * → target list / WHERE / GROUP / HAVING / ORDER 的表达式检查 → 至少一张表。
     */
    static DeepCheck deepCheck(List<SqlTokenizer.Token> stmt, boolean startsWithWith, SqlGuard.GuardConfig cfg) {
        DeepCheck out = new DeepCheck();
        ExprValidator validator = new ExprValidator(cfg);

        // WITH clause（checkCTEs 开时拒绝）
        if (startsWithWith && cfg.checkCTEs) {
            out.error = "WITH clause (CTEs) is not allowed";
            return out;
        }

        int n = stmt.size();
        int i = 1; // skip SELECT
        // SELECT 修饰：DISTINCT / ALL（无附加检查）
        if (i < n && (stmt.get(i).isKeyword("distinct") || stmt.get(i).isKeyword("all"))) {
            i++;
        }

        // compound：深度 0 的 UNION/INTERSECT/EXCEPT
        for (int k = i; k < n; k++) {
            SqlTokenizer.Token t = stmt.get(k);
            if (t.kind == SqlTokenizer.TokKind.PUNCT && t.text.equals("(")) {
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

        // target list：SELECT ... [INTO ...] FROM —— INTO 在 from 前
        int fromIdx = -1;
        int depth = 0;
        for (int k = i; k < n; k++) {
            SqlTokenizer.Token t = stmt.get(k);
            if (t.kind == SqlTokenizer.TokKind.PUNCT && t.text.equals("(")) {
                depth++;
            } else if (t.kind == SqlTokenizer.TokKind.PUNCT && t.text.equals(")")) {
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
            // "no valid table found in query"；target list 仍需检查。
            validator.validate(stmt.subList(i, n));
            out.error = validator.error != null ? validator.error : "no valid table found in query";
            return out;
        }

        // target list 表达式检查
        List<SqlTokenizer.Token> targetList = new ArrayList<>(stmt.subList(i, fromIdx));
        stripAliases(targetList);
        validator.validate(targetList);
        if (validator.error != null) {
            out.error = validator.error;
            return out;
        }

        // FROM 项解析（表 / JOIN / 子查询 / 表函数）
        i = fromIdx + 1;
        while (i < n) {
            SqlTokenizer.Token t = stmt.get(i);
            if (isClauseKeyword(t)) {
                break;
            }
            if (t.kind == SqlTokenizer.TokKind.PUNCT && t.text.equals(",")) {
                i++;
                continue;
            }
            if (isJoinKeyword(t)) {
                i++;
                continue;
            }
            if (t.isKeyword("on")) {
                // JOIN quals：扫到下一个 from 项边界
                int j = i + 1;
                depth = 0;
                while (j < n) {
                    SqlTokenizer.Token u = stmt.get(j);
                    if (u.kind == SqlTokenizer.TokKind.PUNCT && u.text.equals("(")) {
                        depth++;
                    } else if (u.kind == SqlTokenizer.TokKind.PUNCT && u.text.equals(")")) {
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
            if (t.kind == SqlTokenizer.TokKind.PUNCT && t.text.equals("(")) {
                // 括号开头：子查询或表函数。子查询在 checkSubqueries 时拒绝，
                // 否则递归校验；表函数恒拒绝。白名单的表提取不进子查询
                // （子查询里的表不收录——照录的怪癖）。
                SqlTokenizer.Token next = i + 1 < n ? stmt.get(i + 1) : null;
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
                    if (i < n && (stmt.get(i).kind == SqlTokenizer.TokKind.IDENT
                            || stmt.get(i).kind == SqlTokenizer.TokKind.QIDENT)
                            && !isJoinKeyword(stmt.get(i)) && !isClauseKeyword(stmt.get(i))
                            && !stmt.get(i).isKeyword("on") && !stmt.get(i).isKeyword("using")) {
                        i++;
                    }
                    continue;
                }
                out.error = "functions in FROM clause are not allowed";
                return out;
            }
            if (t.kind == SqlTokenizer.TokKind.IDENT || t.kind == SqlTokenizer.TokKind.QIDENT) {
                // 表名（可 schema.table[.table]）+ 可选 AS + 可选别名
                List<String> parts = new ArrayList<>();
                parts.add(t.text);
                int j = i + 1;
                while (j + 1 < n && stmt.get(j).kind == SqlTokenizer.TokKind.PUNCT && stmt.get(j).text.equals(".")
                        && (stmt.get(j + 1).kind == SqlTokenizer.TokKind.IDENT
                                || stmt.get(j + 1).kind == SqlTokenizer.TokKind.QIDENT)) {
                    parts.add(stmt.get(j + 1).text);
                    j += 2;
                }
                // 表函数（IDENT 后紧跟 '('，如 read_csv_auto(...)）：直接报错，
                // 不收录白名单也不记别名映射——tokenizer 先撞 IDENT 分支，
                // 须在此识别。
                if (j < n && stmt.get(j).kind == SqlTokenizer.TokKind.PUNCT && stmt.get(j).text.equals("(")) {
                    out.error = "functions in FROM clause are not allowed";
                    return out;
                }
                if (parts.size() > 1 && cfg.checkSchemaAccess) {
                    // schema 限定（取倒数第二段；checkSchemaAccess=false 时放行，
                    // 白名单只看表名）
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
                if (j < n && (stmt.get(j).kind == SqlTokenizer.TokKind.IDENT || stmt.get(j).kind == SqlTokenizer.TokKind.QIDENT)
                        && !isJoinKeyword(stmt.get(j)) && !isClauseKeyword(stmt.get(j))
                        && !stmt.get(j).isKeyword("on") && !stmt.get(j).isKeyword("using")) {
                    alias = stmt.get(j).text;
                    j++;
                }
                // 表名记录用最后一段的原始大小写；注入用的别名 map 才小写化
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

        // WHERE 子句
        if (i < n && stmt.get(i).isKeyword("where")) {
            out.hasRealWhere = true;
            int j = i + 1;
            depth = 0;
            while (j < n) {
                SqlTokenizer.Token u = stmt.get(j);
                if (u.kind == SqlTokenizer.TokKind.PUNCT && u.text.equals("(")) {
                    depth++;
                } else if (u.kind == SqlTokenizer.TokKind.PUNCT && u.text.equals(")")) {
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

        // GROUP BY / HAVING / ORDER BY（LIMIT/OFFSET 为常量）
        while (i < n) {
            SqlTokenizer.Token t = stmt.get(i);
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
                List<SqlTokenizer.Token> seg = new ArrayList<>(stmt.subList(i + 1, j));
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
                // locking
                out.error = "locking clauses (FOR UPDATE, etc.) are not allowed";
                return out;
            } else if (t.isKeyword("union") || t.isKeyword("intersect") || t.isKeyword("except")) {
                out.error = "compound queries (UNION/INTERSECT/EXCEPT) are not allowed";
                return out;
            } else {
                i++;
            }
        }

        // 至少一张表
        if (out.tablesInQuery.isEmpty()) {
            out.error = "no valid table found in query";
            return out;
        }
        return out;
    }

    /** 子句终点：深度 0 的下一个子句关键字或 EOF。 */
    static int clauseEnd(List<SqlTokenizer.Token> tokens, int from) {
        int depth = 0;
        for (int j = from; j < tokens.size(); j++) {
            SqlTokenizer.Token u = tokens.get(j);
            if (u.kind == SqlTokenizer.TokKind.PUNCT && u.text.equals("(")) {
                depth++;
            } else if (u.kind == SqlTokenizer.TokKind.PUNCT && u.text.equals(")")) {
                depth--;
            } else if (depth == 0 && isClauseKeyword(u)) {
                return j;
            }
        }
        return tokens.size();
    }

    /** SELECT 列表/ORDER BY 的别名剥离：去掉 AS x 与尾随裸别名（别名不是列引用）。 */
    static void stripAliases(List<SqlTokenizer.Token> tokens) {
        for (int k = 0; k < tokens.size(); k++) {
            SqlTokenizer.Token t = tokens.get(k);
            if (t.kind == SqlTokenizer.TokKind.IDENT && t.isKeyword("as") && k + 1 < tokens.size()
                    && (tokens.get(k + 1).kind == SqlTokenizer.TokKind.IDENT
                            || tokens.get(k + 1).kind == SqlTokenizer.TokKind.QIDENT)) {
                tokens.remove(k + 1);
                tokens.remove(k);
                k--;
            }
        }
    }

    /** 只提取表→别名（注入用；出现序 LinkedHashMap）。 */
    static Map<String, String> parseTablesInQuery(String sql) {
        try {
            List<SqlTokenizer.Token> tokens = SqlTokenizer.tokenize(sql);
            List<List<SqlTokenizer.Token>> statements = SqlTokenizer.splitStatements(tokens);
            if (statements.isEmpty()) {
                return Map.of();
            }
            DeepCheck deep = deepCheckSkipOnError(statements.get(0));
            return deep.tablesInQuery;
        } catch (SqlTokenizer.ParseFailure e) {
            return Map.of();
        }
    }

    /**
     * 注入前的表提取：校验已通过，这里不再关心错误——但 deepCheck 遇错误会提前
     * 返回导致表不全；校验通过时两者等价。包装一层吞掉错误语义。
     */
    static DeepCheck deepCheckSkipOnError(List<SqlTokenizer.Token> stmt) {
        return deepCheck(stmt, stmt.get(0).isKeyword("with"), SqlGuard.GuardConfig.securityDefaults());
    }
}
