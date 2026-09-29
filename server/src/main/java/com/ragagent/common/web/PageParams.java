package com.ragagent.common.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 分页参数的 query 绑定基型（snake_case）。page 缺省 1、page_size 缺省 20；
 * 越界值由校验异常处理统一转 400「分页参数不合法」。
 */
public record PageParams(
        @Min(value = 1, message = "必须为正整数")
        @Max(value = 100000, message = "超出上限")
        Integer page,
        @Min(value = 1, message = "必须为正整数")
        @Max(value = 100, message = "必须为 1-100 的整数")
        Integer page_size) {

    public int pageOrDefault() {
        return page == null ? 1 : page;
    }

    public int pageSizeOrDefault() {
        return page_size == null ? 20 : page_size;
    }
}
