package com.ragagent.sandbox.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 脚本安全校验器（对照 Go {@code internal/sandbox/validator.go} 全文的确定性纯逻辑移植）。
 *
 * <p>三种校验入口：脚本内容（危险命令字面匹配 + 危险正则 + 网络访问 + 反弹 shell）、
 * 参数（shell 操作符 / 命令替换 / 注入正则）、stdin（内嵌 shell 命令）。
 * Go 用 RE2 + {@code (?i)} 前缀；Java 用 {@link Pattern#CASE_INSENSITIVE} 等价表达。
 * 错误消息逐字保留——它们会经 runScriptValidation 进入 ExecuteResult.Error / Stderr
 * 与 ErrSecurityViolation 的信封文案。</p>
 *
 * <p>已知差异（备案）：Go 的 {@code truncate} 按字节切片，可能在多字节字符中间截断；
 * Java 按码元截断，产出只用于上下文提示，不影响 Valid 判定。</p>
 */
public final class ScriptValidator {

    /** 对照 ValidationError：一条校验失败。 */
    public static final class ValidationError extends RuntimeException {
        public final String type;
        public final String pattern;
        public final String context;
        public final String message;

        ValidationError(String type, String pattern, String context, String message) {
            super(String.format(
                    "security validation failed [%s]: %s (pattern: %s, context: %s)",
                    type, message, pattern, context));
            this.type = type;
            this.pattern = pattern;
            this.context = context;
            this.message = message;
        }
    }

    /** 对照 ValidationResult。 */
    public static final class ValidationResult {
        public boolean valid = true;
        public final List<ValidationError> errors = new ArrayList<>();
    }

    private final List<String> dangerousCommands;
    private final List<Pattern> dangerousPatterns;
    private final List<Pattern> argInjectionPatterns;

    public ScriptValidator() {
        this.dangerousCommands = getDefaultDangerousCommands();
        this.dangerousPatterns = compilePatterns(getDefaultDangerousPatterns());
        this.argInjectionPatterns = compilePatterns(getDefaultArgInjectionPatterns());
    }

    /** 对照 ValidateScript。 */
    public ValidationResult validateScript(String content) {
        ValidationResult result = new ValidationResult();

        for (String cmd : dangerousCommands) {
            if (content.contains(cmd)) {
                result.valid = false;
                result.errors.add(new ValidationError(
                        "dangerous_command", cmd, extractContext(content, cmd),
                        String.format("Script contains dangerous command: %s", cmd)));
            }
        }

        String lowerContent = content.toLowerCase();
        for (Pattern pattern : dangerousPatterns) {
            var matcher = pattern.matcher(lowerContent);
            if (matcher.find()) {
                String matches = matcher.group();
                result.valid = false;
                result.errors.add(new ValidationError(
                        "dangerous_pattern", pattern.pattern(), extractContext(content, matches),
                        String.format("Script contains dangerous pattern: %s", matches)));
            }
        }

        if (hasNetworkAccess(content)) {
            result.valid = false;
            result.errors.add(new ValidationError(
                    "network_access", "network commands", "script content",
                    "Script attempts to access network resources"));
        }

        if (hasReverseShellPattern(content)) {
            result.valid = false;
            result.errors.add(new ValidationError(
                    "reverse_shell", "reverse shell pattern", "script content",
                    "Script contains potential reverse shell pattern"));
        }

        return result;
    }

    /** 对照 ValidateArgs。 */
    public ValidationResult validateArgs(List<String> args) {
        ValidationResult result = new ValidationResult();

        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (hasShellOperators(arg)) {
                result.valid = false;
                result.errors.add(new ValidationError(
                        "shell_injection", "shell operators",
                        String.format("arg[%d]: %s", i, truncate(arg, 50)),
                        "Argument contains shell command operators"));
            }
            if (hasCommandSubstitution(arg)) {
                result.valid = false;
                result.errors.add(new ValidationError(
                        "command_substitution", "command substitution",
                        String.format("arg[%d]: %s", i, truncate(arg, 50)),
                        "Argument contains command substitution syntax"));
            }
            for (Pattern pattern : argInjectionPatterns) {
                if (pattern.matcher(arg).find()) {
                    result.valid = false;
                    result.errors.add(new ValidationError(
                            "arg_injection", pattern.pattern(),
                            String.format("arg[%d]: %s", i, truncate(arg, 50)),
                            "Argument matches injection pattern"));
                }
            }
        }

        return result;
    }

    /** 对照 ValidateStdin。 */
    public ValidationResult validateStdin(String stdin) {
        ValidationResult result = new ValidationResult();

        if (hasEmbeddedShellCommands(stdin)) {
            result.valid = false;
            result.errors.add(new ValidationError(
                    "stdin_injection", "embedded shell commands", truncate(stdin, 100),
                    "Stdin contains embedded shell command patterns"));
        }

        return result;
    }

    /** 对照 ValidateAll。 */
    public ValidationResult validateAll(String scriptContent, List<String> args, String stdin) {
        ValidationResult result = new ValidationResult();

        ValidationResult scriptResult = validateScript(scriptContent);
        if (!scriptResult.valid) {
            result.valid = false;
            result.errors.addAll(scriptResult.errors);
        }

        ValidationResult argsResult = validateArgs(args);
        if (!argsResult.valid) {
            result.valid = false;
            result.errors.addAll(argsResult.errors);
        }

        if (stdin != null && !stdin.isEmpty()) {
            ValidationResult stdinResult = validateStdin(stdin);
            if (!stdinResult.valid) {
                result.valid = false;
                result.errors.addAll(stdinResult.errors);
            }
        }

        return result;
    }

    // ---- 私有判定（对照 validator.go L193-318） ----------------------------

    private static boolean hasShellOperators(String s) {
        String[] operators = {
                "&&", "||", ";", "|", "\n", "\r", "$(", "`",
                ">", "<", ">>", "2>", "&>",
        };
        for (String op : operators) {
            if (s.contains(op)) {
                return true;
            }
        }
        return false;
    }

    private static final Pattern[] COMMAND_SUBSTITUTION_PATTERNS = {
            Pattern.compile("\\$\\([^)]+\\)"),
            Pattern.compile("`[^`]+`"),
            Pattern.compile("\\$\\{[^}]*\\$\\("),
    };

    private static boolean hasCommandSubstitution(String s) {
        for (Pattern p : COMMAND_SUBSTITUTION_PATTERNS) {
            if (p.matcher(s).find()) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasNetworkAccess(String content) {
        String[] patterns = {
                "\\bcurl\\b", "\\bwget\\b", "\\bnc\\b", "\\bnetcat\\b", "\\btelnet\\b",
                "\\bssh\\b", "\\bscp\\b", "\\brsync\\b", "\\bftp\\b", "\\bsftp\\b",
                "socket\\.connect", "urllib\\.request", "requests\\.get", "requests\\.post",
                "http\\.client", "httplib", "fetch\\s*\\(", "axios", "XMLHttpRequest",
        };
        for (String pattern : patterns) {
            if (caseInsensitive(pattern).matcher(content).find()) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasReverseShellPattern(String content) {
        String[] patterns = {
                "/dev/tcp/", "/dev/udp/", "bash\\s+-i", "sh\\s+-i",
                "/bin/bash\\s+-i", "/bin/sh\\s+-i", "python.*pty\\.spawn",
                "perl.*-e.*socket", "ruby.*-rsocket", "socat.*exec",
                "mkfifo", "mknod.*p", "0<&196", "196>&0",
                "/inet/tcp/", "bash.*>&.*0>&1", "nc.*-e", "ncat.*-e", "netcat.*-e",
        };
        for (String pattern : patterns) {
            if (caseInsensitive(pattern).matcher(content).find()) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasEmbeddedShellCommands(String content) {
        String[] patterns = {
                "\\$\\(.*\\)", "`.*`", "\\n\\s*[;&|]", "\\\\n.*[;&|]",
        };
        for (String pattern : patterns) {
            if (Pattern.compile(pattern).matcher(content).find()) {
                return true;
            }
        }
        return false;
    }

    private static List<String> getDefaultDangerousCommands() {
        List<String> commands = new ArrayList<>();
        // System modification - various forms of dangerous rm
        commands.add("rm -rf /");
        commands.add("rm -fr /");
        commands.add("rm -rf /"); // with different spacing
        commands.add("rm -rf/*");
        commands.add("rm -rf *");
        // Filesystem destruction
        commands.add("mkfs");
        commands.add("dd if=/dev/zero");
        commands.add("dd if=/dev/random");
        // Fork bombs (various forms)
        commands.add(":(){ :|:& };:");
        commands.add(":(){:|:&};:");
        commands.add("bomb(){ bomb|bomb& };bomb");
        // Process and system control
        commands.add("shutdown");
        commands.add("reboot");
        commands.add("halt");
        commands.add("poweroff");
        commands.add("init 0");
        commands.add("init 6");
        commands.add("killall");
        commands.add("pkill");
        // Permission escalation
        commands.add("chmod 777 /");
        commands.add("chown root");
        commands.add("setuid");
        commands.add("setgid");
        commands.add("passwd");
        // Credential access
        commands.add("/etc/passwd");
        commands.add("/etc/shadow");
        commands.add("/etc/sudoers");
        commands.add(".ssh/");
        commands.add("id_rsa");
        commands.add("id_ed25519");
        // Environment manipulation
        commands.add("export PATH=");
        commands.add("export LD_PRELOAD");
        commands.add("export LD_LIBRARY_PATH");
        // Cron manipulation
        commands.add("crontab");
        commands.add("/etc/cron");
        // Service manipulation
        commands.add("systemctl");
        commands.add("service");
        // Module/kernel manipulation
        commands.add("insmod");
        commands.add("modprobe");
        commands.add("rmmod");
        // Container escape attempts
        commands.add("docker");
        commands.add("kubectl");
        commands.add("nsenter");
        commands.add("unshare");
        commands.add("capsh");
        return commands;
    }

    private static List<String> getDefaultDangerousPatterns() {
        List<String> patterns = new ArrayList<>();
        // Base64 encoded payloads (often used to hide malicious code)
        patterns.add("base64\\s+(-d|--decode)");
        patterns.add("echo\\s+.*\\|\\s*base64\\s+-d");
        // Hex encoded payloads
        patterns.add("xxd\\s+-r");
        patterns.add("echo\\s+-e\\s+.*\\\\x");
        // Code download and execution
        patterns.add("curl.*\\|\\s*(bash|sh)");
        patterns.add("wget.*\\|\\s*(bash|sh)");
        patterns.add("python.*http\\.server");
        // Eval and exec patterns (code injection)
        patterns.add("eval\\s*\\(");
        patterns.add("exec\\s*\\(");
        patterns.add("os\\.system\\s*\\(");
        patterns.add("subprocess\\.call\\s*\\(.*shell\\s*=\\s*True");
        patterns.add("subprocess\\.Popen\\s*\\(.*shell\\s*=\\s*True");
        patterns.add("os\\.popen\\s*\\(");
        patterns.add("commands\\.getoutput\\s*\\(");
        patterns.add("commands\\.getstatusoutput\\s*\\(");
        // History/log manipulation
        patterns.add("history\\s+-c");
        patterns.add("unset\\s+HISTFILE");
        patterns.add("export\\s+HISTSIZE=0");
        // Python dangerous functions
        patterns.add("__import__\\s*\\(");
        patterns.add("importlib\\.import_module");
        patterns.add("compile\\s*\\(.*exec");
        // Pickle deserialization (can execute arbitrary code)
        patterns.add("pickle\\.loads?\\s*\\(");
        patterns.add("cPickle\\.loads?\\s*\\(");
        // YAML unsafe loading
        patterns.add("yaml\\.load\\s*\\([^,]+\\)"); // Without Loader argument
        patterns.add("yaml\\.unsafe_load");
        // Fork bomb patterns (function recursion with backgrounding)
        patterns.add(":\\s*\\(\\s*\\)\\s*\\{\\s*:"); // :() { : pattern
        patterns.add("\\(\\)\\s*\\{\\s*\\w+\\s*\\|\\s*\\w+\\s*&"); // () { x | x & pattern
        // Dangerous rm patterns
        patterns.add("rm\\s+-[rf]+\\s+/"); // rm -rf / or rm -fr /
        patterns.add("rm\\s+--no-preserve-root");
        return patterns;
    }

    private static List<String> getDefaultArgInjectionPatterns() {
        List<String> patterns = new ArrayList<>();
        // Path traversal
        patterns.add("\\.\\.\\/");
        patterns.add("\\.\\.\\\\");
        // Environment variable injection
        patterns.add("\\$\\{[A-Z_]+\\}");
        patterns.add("\\$[A-Z_]+");
        // Special shell characters
        patterns.add("\\$\\(");
        patterns.add("`");
        patterns.add("\\n");
        patterns.add("\\r");
        return patterns;
    }

    private static Pattern caseInsensitive(String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
    }

    private static List<Pattern> compilePatterns(List<String> patterns) {
        List<Pattern> compiled = new ArrayList<>(patterns.size());
        for (String p : patterns) {
            try {
                compiled.add(caseInsensitive(p));
            } catch (RuntimeException ignored) {
                // Go: regexp.Compile error → pattern skipped
            }
        }
        return compiled;
    }

    private static String extractContext(String content, String match) {
        int idx = content.toLowerCase().indexOf(match.toLowerCase());
        if (idx == -1) {
            return "";
        }
        int start = Math.max(0, idx - 20);
        int end = Math.min(content.length(), idx + match.length() + 20);
        String context = content.substring(start, end);
        if (start > 0) {
            context = "..." + context;
        }
        if (end < content.length()) {
            context = context + "...";
        }
        return context;
    }

    private static String truncate(String s, int maxLen) {
        if (s == null || s.length() <= maxLen) {
            return s == null ? "" : s;
        }
        return s.substring(0, maxLen) + "...";
    }
}
