package com.ragagent.agent.tools;



/**
 * 4.5c 测试的内存 fake（对照 Go 探针里复用 /tools 测试文件的
 * fakeSandboxFileSource / fakeShellExecutor 等的形状）。
 */
final class Tools45cFakes {

    private Tools45cFakes() {
    }

    /** 通用失败异常（fake 抛出，等价 Go 的 error 通道）。 */
    static RuntimeException boom(String msg) {
        return new RuntimeException(msg);
    }

    /** 任意对象 → Go json.Marshal 字节形态（对 Map<String,String> 等的便捷入口）。 */
    static String goJson(Object o) {
        return com.ragagent.common.web.GoJsonCodec.write(RecordingSupport.PLAIN.valueToTree(o));
    }

    /** 按 group+id 取 Go 实录常量（R_<GROUP>_<ID>，id 大写化）。 */
    static com.fasterxml.jackson.databind.JsonNode rec45c(String group, String id) {
        String name = "R_" + group.toUpperCase().replace('-', '_')
                + "_" + id.toUpperCase().replace('-', '_');
        try {
            String json = (String) GoRecording45C.class.getField(name).get(null);
            return GoRecording45C.rec(json);
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("unknown recording constant: " + name, e);
        }
    }

    /** 可变的 sandbox 文件源/汇（同时满足 Source/Sink/Editor 三种形状）。 */
}
