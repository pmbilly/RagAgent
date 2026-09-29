package com.ragagent.agent.tools;

import java.util.Map;
import java.util.Set;

/**
 * 生成 Python 的嵌套引号体检。
 *
 * <p>常见故障是同种 ASCII 引号出现在同类字符串字面量里（{@code "这不是一个"大干快上"..."}），
 * Python 会把它当字符串结束。在 write/edit 时就抓出来，省掉模型一轮
 * shell_exec + py_compile 的往返。</p>
 */
public final class PythonSyntax {

    /**
     * 生成 Python 的短规则提示。
     * skill_file 的描述里逐字引用。
     */
    public static final String PYTHON_QUOTE_GUIDANCE =
            "Python strings: never put ASCII `\"` inside `\"...\"` "
                    + "(or `'` inside `'...'`). Use the other quote for the literal, and 「」 "
                    + "for Chinese quotation.";

    private static final Set<String> PYTHON_KEYWORDS = Set.of(
            "False", "None", "True",
            "and", "as", "assert", "async", "await",
            "break", "class", "continue", "def", "del",
            "elif", "else", "except", "finally", "for",
            "from", "global", "if", "import", "in",
            "is", "lambda", "match", "nonlocal", "not",
            "or", "pass", "raise", "return", "try",
            "while", "with", "yield");

    private PythonSyntax() {
    }

    /**
     * 刚写完的文件若有嵌套引号故障则给出提示。
     * editTool 指明能修复它的工具：同一份提示服务 /workspace 写入与 skill 树写入，
     * 而两者的编辑器名字不同。
     */
    public static String pythonScriptSyntaxHint(String filePath, String src, String editTool) {
        String ext = extOf(filePath);
        if (!".py".equals(ext)) {
            return "";
        }
        long[] broken = firstBrokenPythonQuote(src);
        if (broken == null) {
            return "";
        }
        long line = broken[0];
        return "Python syntax looks broken around line " + line + ": an ASCII quote inside a "
                + "string of the same kind closed the literal early "
                + "(e.g. (\"这不是一个\"大干快上\"...\")). The file was written. "
                + "Fix it with " + editTool + ": wrap that text in the other quote, "
                + "or use 「」 / \\\" for the inner quotation. Do not execute the script until it parses.";
    }

    /** stderr 里出现 SyntaxError 时的提示。 */
    public static String pythonSyntaxErrorHint(String stderr) {
        if (stderr == null || !stderr.contains("SyntaxError")) {
            return "";
        }
        return "Hint: this is almost always an ASCII quote inside a same-kind Python string "
                + "(e.g. \"这不是一个\"大干快上\"...\"). "
                + "edit_sandbox_file: wrap the text in the other quote, or replace inner quotes with 「」 / \\\".";
    }

    /** 文件扩展名（含点号；无扩展名返回 ""）。 */
    private static String extOf(String filePath) {
        if (filePath == null) {
            return "";
        }
        for (int i = filePath.length() - 1; i >= 0; i--) {
            char c = filePath.charAt(i);
            if (c == '/') {
                return "";
            }
            if (c == '.') {
                return filePath.substring(i);
            }
        }
        return "";
    }

    /**
     * 报告第一个"字符串字面量后面紧跟非关键字标识符"的行号——即
     * {@code "这不是一个"大干快上} 产生的解析错误。{@code "hello" if x} 不动。
     * 返回 {line}（长度 1）或 null（无故障）。
     */
    private static long[] firstBrokenPythonQuote(String src) {
        long line = 1;
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '\n') {
                line++;
                i++;
                continue;
            }
            if (c == '#') {
                int end = src.indexOf('\n', i);
                if (end < 0) {
                    return null;
                }
                i = end;
                continue;
            }
            StringStart start = pythonStringStart(src, i);
            if (start == null) {
                i++;
                continue;
            }
            ScanResult scan = scanPythonString(src, start.contentStart, start.quote, start.triple,
                    start.fstring, line);
            if (scan.unclosed) {
                return new long[] {line};
            }
            if (!start.fstring) {
                int j = scan.end;
                while (j < n && (src.charAt(j) == ' ' || src.charAt(j) == '\t' || src.charAt(j) == '\r')) {
                    j++;
                }
                String ident = peekPythonIdent(src, j);
                if (!ident.isEmpty() && !PYTHON_KEYWORDS.contains(ident)) {
                    return new long[] {scan.endLine};
                }
            }
            i = scan.end;
            line = scan.endLine;
        }
        return null;
    }

    private record StringStart(int contentStart, char quote, boolean triple, boolean fstring) {
    }

    private static final Map<Character, Boolean> PREFIX_BYTES = Map.of(
            'r', true, 'R', true, 'u', true, 'U', true,
            'b', true, 'B', true, 'f', true, 'F', true);

    private static StringStart pythonStringStart(String src, int i) {
        int n = src.length();
        int j = i;
        for (int k = 0; k < 2 && j < n && isPythonStringPrefixByte(src.charAt(j)); k++) {
            j++;
        }
        if (j >= n || (src.charAt(j) != '"' && src.charAt(j) != '\'')) {
            return null;
        }
        if (j > i) {
            if (!validPythonStringPrefix(src.substring(i, j))) {
                return null;
            }
            if (i > 0 && isPythonIdentContinueByte(src.charAt(i - 1))) {
                return null;
            }
        }
        char quote = src.charAt(j);
        boolean fstring = src.substring(i, j).indexOf('f') >= 0 || src.substring(i, j).indexOf('F') >= 0;
        if (j + 2 < n && src.charAt(j + 1) == quote && src.charAt(j + 2) == quote) {
            return new StringStart(j + 3, quote, true, fstring);
        }
        return new StringStart(j + 1, quote, false, fstring);
    }

    private static boolean isPythonStringPrefixByte(char b) {
        return PREFIX_BYTES.containsKey(b);
    }

    private static boolean isPythonIdentContinueByte(char b) {
        return b == '_' || (b >= '0' && b <= '9') || (b >= 'A' && b <= 'Z') || (b >= 'a' && b <= 'z');
    }

    private static boolean validPythonStringPrefix(String p) {
        return switch (p.toLowerCase(java.util.Locale.ROOT)) {
            case "r", "u", "b", "f", "fr", "rf", "br", "rb" -> true;
            default -> false;
        };
    }

    private record ScanResult(int end, long endLine, boolean unclosed) {
    }

    private static ScanResult scanPythonString(String src, int i, char quote, boolean triple,
            boolean fstring, long line) {
        int n = src.length();
        long endLine = line;
        int brace = 0;
        while (i < n) {
            char c = src.charAt(i);
            if (c == '\\' && i + 1 < n) {
                if (src.charAt(i + 1) == '\n') {
                    endLine++;
                }
                i += 2;
                continue;
            }
            if (fstring && brace == 0 && c == '{') {
                if (i + 1 < n && src.charAt(i + 1) == '{') {
                    i += 2;
                    continue;
                }
                brace++;
                i++;
                continue;
            }
            if (fstring && brace == 0 && c == '}') {
                if (i + 1 < n && src.charAt(i + 1) == '}') {
                    i += 2;
                    continue;
                }
            }
            if (fstring && brace > 0 && c == '}') {
                brace--;
                i++;
                continue;
            }
            if (fstring && brace > 0 && (c == '"' || c == '\'')) {
                int nested = i + 1;
                boolean tripleNest = i + 2 < n && src.charAt(i + 1) == c && src.charAt(i + 2) == c;
                if (tripleNest) {
                    nested = i + 3;
                }
                ScanResult inner = scanPythonString(src, nested, c, tripleNest, false, endLine);
                if (inner.unclosed) {
                    return new ScanResult(inner.end, inner.endLine, true);
                }
                i = inner.end;
                endLine = inner.endLine;
                continue;
            }
            if (c == '\n') {
                if (!triple && brace == 0) {
                    return new ScanResult(i, endLine, true);
                }
                endLine++;
                i++;
                continue;
            }
            if (brace == 0 && c == quote) {
                if (triple) {
                    if (i + 2 < n && src.charAt(i + 1) == quote && src.charAt(i + 2) == quote) {
                        return new ScanResult(i + 3, endLine, false);
                    }
                    i++;
                    continue;
                }
                return new ScanResult(i + 1, endLine, false);
            }
            i++;
        }
        return new ScanResult(i, endLine, true);
    }

    /** i 处的标识符（Unicode 字母/数字/下划线）；不是标识符开头返回 ""。 */
    private static String peekPythonIdent(String src, int i) {
        int n = src.length();
        if (i >= n) {
            return "";
        }
        int cp = src.codePointAt(i);
        if (!Character.isLetter(cp) && cp != '_') {
            return "";
        }
        int j = i + Character.charCount(cp);
        while (j < n) {
            cp = src.codePointAt(j);
            if (!Character.isLetter(cp) && !Character.isDigit(cp) && cp != '_') {
                break;
            }
            j += Character.charCount(cp);
        }
        return src.substring(i, j);
    }
}
