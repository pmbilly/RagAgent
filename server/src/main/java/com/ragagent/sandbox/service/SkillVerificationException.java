package com.ragagent.sandbox.service;

import java.util.List;

/**
 * 校验门的裁决（对照 Go {@code skillVerificationError}，internal/application/service/
 * tenant_skill_verify.go L44-61）。门是"这份镜像里什么必须解决"的唯一权威，把门自己
 * 的行原样交回去，安装器就不用二次推导"这个 skill 需要什么"。
 *
 * <p>{@link #getMessage()} 是 Go {@code Error()} 的逐字形态：有 findings 时
 * {@code "<language> verification failed: <p1>; <p2>"}，全无时（pass 没吐任何行就死了）
 * {@code "<language> verification failed (<summary>)"}。</p>
 */
public final class SkillVerificationException extends RuntimeException {

    /** 哪个 pass 失败了（操作者视角的名字：python / node / shell / runtime commands / runtime prerequisites）。 */
    public final String language;
    /** true = 装一个包就能满足每一行（再给安装器一轮是值得的）。 */
    public final boolean repairable;
    /** 校验器自己的行，一条一个 finding。 */
    public final List<String> problems;
    /** 命令结果本身的描述；pass 一行都没产出时它是唯一值得打印的东西。 */
    public final String summary;

    public SkillVerificationException(String language, boolean repairable,
            List<String> problems, String summary) {
        super(render(language, problems, summary));
        this.language = language;
        this.repairable = repairable;
        this.problems = problems == null ? List.of() : List.copyOf(problems);
        this.summary = summary == null ? "" : summary;
    }

    private static String render(String language, List<String> problems, String summary) {
        if (problems == null || problems.isEmpty()) {
            return language + " verification failed (" + summary + ")";
        }
        return language + " verification failed: " + String.join("; ", problems);
    }
}
