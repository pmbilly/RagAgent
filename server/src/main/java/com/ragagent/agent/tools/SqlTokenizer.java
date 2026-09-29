package com.ragagent.agent.tools;

import java.util.ArrayList;
import java.util.List;

/**
 * SQL 手写分词器：Token/TokKind/ParseFailure、语句切分、WITH 定义体跳过。
 *
 * <p>{@link SqlGuard} 的包内协作者：纯静态实现，由 SqlGuard 门面调用。</p>
 */
final class SqlTokenizer {

    static int skipWithClause(List<Token> stmt) {
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

    enum TokKind {
        IDENT, QIDENT, STRING, NUMBER, OP, PUNCT, PARAM
    }

    /** SQL token。keyword 判定只对 IDENT（裸标识符，大小写不敏感）。 */
    static final class Token {
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
    static final class ParseFailure extends Exception {
        ParseFailure(String message) {
            super(message);
        }
    }

    static boolean isIdentStart(char c) {
        return Character.isLetter(c) || c == '_' || c >= 0x80;
    }

    static boolean isIdentPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c >= 0x80;
    }

    static List<Token> tokenize(String sql) throws ParseFailure {
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
    static List<List<Token>> splitStatements(List<Token> tokens) {
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

    static Token firstMeaningful(List<Token> tokens) {
        return tokens.isEmpty() ? null : tokens.get(0);
    }
}
