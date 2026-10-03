package com.ragagent.llm.chat;

/**
 * 所有 providerAdapter 方法的默认行为，也是未知 provider 的回退：
 * Bearer 鉴权、标准 endpoint、不发 thinking、不做请求整形。
 */
public class BaseProvider implements ProviderAdapter {

    @Override
    public String name() {
        return "";
    }
}
