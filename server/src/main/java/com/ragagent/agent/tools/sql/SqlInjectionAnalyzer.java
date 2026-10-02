package com.ragagent.agent.tools.sql;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL 注入防护：风险模式检测（恒真/恒假、注释尾、堆叠语句等）与租户/软删/
 * 隐藏知识库等条件的 WHERE 注入重写。
 *
 * <p>{@link SqlGuard} 的包内协作者：纯静态实现，由 SqlGuard 门面调用。</p>
 */
final class SqlInjectionAnalyzer {

    static final Pattern RE_SQL_WHITESPACE = Pattern.compile("\\s+");

    static final class RiskPattern {
        final Pattern pattern;
        final String description;

        RiskPattern(String regex, String description) {
            this.pattern = Pattern.compile(regex);
            this.description = description;
        }
    }

    static final RiskPattern[] SQL_ALWAYS_TRUE_PATTERNS = {
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

    static final RiskPattern[] SQL_ALWAYS_FALSE_PATTERNS = {
            new RiskPattern(
                    "(^|\\s|\\()(1\\s*=\\s*0|0\\s*=\\s*1|'1'\\s*=\\s*'0'|\"1\"\\s*=\\s*\"0\")(\\s|\\)|$|and|or)",
                    "Always-false condition '1=0' or similar"),
            new RiskPattern(
                    "(^|\\s|\\()(false)(\\s|\\)|$|and|or)",
                    "Always-false condition 'false'"),
    };

    static final Pattern RE_SQL_OR_ALWAYS_TRUE =
            Pattern.compile("or\\s+(1\\s*=\\s*1|'1'\\s*=\\s*'1'|true)");

    /** 匹配的规则各产生一条错误（不短路）。 */
    static List<SqlGuard.SqlValidationError> checkSqlInjectionRisks(String whereClause) {
        List<SqlGuard.SqlValidationError> errors = new ArrayList<>();
        if (whereClause == null || whereClause.isEmpty()) {
            return errors;
        }
        String normalizedWhere = whereClause.toLowerCase(Locale.ROOT).trim();
        normalizedWhere = RE_SQL_WHITESPACE.matcher(normalizedWhere).replaceAll(" ");

        for (RiskPattern pt : SQL_ALWAYS_TRUE_PATTERNS) {
            if (pt.pattern.matcher(normalizedWhere).find()) {
                errors.add(new SqlGuard.SqlValidationError(
                        "sql_injection_risk", "Potential SQL injection risk detected",
                        String.format("%s found in WHERE clause: %s", pt.description, whereClause)));
            }
        }
        for (RiskPattern pt : SQL_ALWAYS_FALSE_PATTERNS) {
            if (pt.pattern.matcher(normalizedWhere).find()) {
                errors.add(new SqlGuard.SqlValidationError(
                        "sql_injection_risk", "Suspicious SQL pattern detected",
                        String.format("%s found in WHERE clause: %s", pt.description, whereClause)));
            }
        }
        if (RE_SQL_OR_ALWAYS_TRUE.matcher(normalizedWhere).find()) {
            errors.add(new SqlGuard.SqlValidationError(
                    "sql_injection_risk", "High-risk SQL injection pattern detected",
                    String.format("OR with always-true condition found in WHERE clause: %s", whereClause)));
        }
        return errors;
    }

    /** naive 子串扫描取 WHERE 文本（字符串字面量里的 where 也会命中——照录的怪癖）。 */
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


    static final Pattern RE_SQL_WHERE_KEYWORD = Pattern.compile("(?i)\\bWHERE\\b");
    static final Pattern RE_SQL_TAIL_CLAUSE =
            Pattern.compile("(?i)\\b(GROUP BY|ORDER BY|LIMIT|OFFSET|HAVING|FETCH)\\b");

    /** 把过滤条件 AND 进 WHERE（WHERE 正则命中字符串字面量里的 where 的怪癖照录）。 */
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

    static String stripLeft(String s, String chars) {
        int i = 0;
        while (i < s.length() && chars.indexOf(s.charAt(i)) >= 0) {
            i++;
        }
        return s.substring(i);
    }

    static String stripRight(String s, String chars) {
        int i = s.length();
        while (i > 0 && chars.indexOf(s.charAt(i - 1)) >= 0) {
            i--;
        }
        return s.substring(0, i);
    }

    /** 注入 tenant 条件（tablesInQuery 出现序遍历，条件序确定）。 */
    static String injectTenantConditions(String sql, Map<String, String> tablesInQuery,
            long tenantID) {
        List<String> conditions = new ArrayList<>();
        for (Map.Entry<String, String> e : tablesInQuery.entrySet()) {
            if (SqlGuard.ALLOWED_TABLES.containsKey(e.getKey()) || TENANT_TABLES.containsKey(e.getKey())) {
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

    static final Map<String, Boolean> TENANT_TABLES = new LinkedHashMap<>();

    static {
        for (String t : new String[] {"knowledge_bases", "knowledges", "chunks"}) {
            TENANT_TABLES.put(t, Boolean.TRUE);
        }
    }

    static final Map<String, Boolean> SOFT_DELETE_TABLES = new LinkedHashMap<>();

    static {
        for (String t : new String[] {"knowledge_bases", "knowledges", "chunks"}) {
            SOFT_DELETE_TABLES.put(t, Boolean.TRUE);
        }
    }

    /** 注入软删条件（deleted_at IS NULL）。 */
    static String injectSoftDeleteConditions(String sql, Map<String, String> tablesInQuery) {
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

    /** 过滤临时知识库（is_temporary = false）。 */
    static String injectHiddenKbFilter(String sql, Map<String, String> tablesInQuery) {
        String alias = tablesInQuery.get("knowledge_bases");
        if (alias == null) {
            return sql;
        }
        return injectAndConditions(sql, String.format("%s.is_temporary = false", alias));
    }

    /** 过滤禁用 chunk（is_enabled = true）。 */
    static String injectChunkEnabledFilter(String sql, Map<String, String> tablesInQuery) {
        String alias = tablesInQuery.get("chunks");
        if (alias == null) {
            return sql;
        }
        return injectAndConditions(sql, String.format("%s.is_enabled = true", alias));
    }

    /** 注入检索 scope 条件（database_query 恒走 structured 路径）。 */
    static String injectStructuredSearchScopeConditions(String sql, Map<String, String> tablesInQuery,
            List<SqlGuard.SearchScope> scopes) {
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

    static String buildKnowledgeBaseScopeCondition(String alias, List<SqlGuard.SearchScope> scopes) {
        List<String> kbIDs = uniqueScopeKbIds(scopes);
        if (kbIDs.isEmpty()) {
            return "";
        }
        return String.format("%s.id IN (%s)", alias, String.join(", ", quoteStringSlice(kbIDs)));
    }

    /** scope 内条件 AND、scope 间 OR。 */
    static String buildScopeClause(String alias, String knowledgeIDColumn, SqlGuard.SearchScope scope) {
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

    static String buildKnowledgeScopeCondition(String alias, List<SqlGuard.SearchScope> scopes) {
        List<String> clauses = new ArrayList<>();
        for (SqlGuard.SearchScope scope : scopes) {
            String clause = buildScopeClause(alias, "id", scope);
            if (!clause.isEmpty()) {
                clauses.add(clause);
            }
        }
        return joinOrClauses(clauses);
    }

    static String buildChunkScopeCondition(String alias, List<SqlGuard.SearchScope> scopes) {
        List<String> clauses = new ArrayList<>();
        for (SqlGuard.SearchScope scope : scopes) {
            String clause = buildScopeClause(alias, "knowledge_id", scope);
            if (!clause.isEmpty()) {
                clauses.add(clause);
            }
        }
        return joinOrClauses(clauses);
    }

    static List<String> uniqueScopeKbIds(List<SqlGuard.SearchScope> scopes) {
        Map<String, Boolean> seen = new LinkedHashMap<>();
        List<String> out = new ArrayList<>();
        for (SqlGuard.SearchScope scope : scopes) {
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
