package com.ragagent.datasource.dto;


/**
 * 凭据字段的"是否已配置"元数据。
 *
 * <p>与 model / mcp 两个模块的同名 DTO 一样，本模块各持一份——项目里已有的处置，
 * 刻意不抽公共类（三个模块的字段集本来就不一样，抽出去只会多一层间接）。</p>
 */
public record CredentialFieldMetadata(boolean configured) {
}
