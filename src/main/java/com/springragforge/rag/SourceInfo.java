package com.springragforge.rag;

/**
 * 单条参考来源的元数据，由后端从召回片段中提取，保证出处和链接准确。
 * 编号、出处、链接
 */
public record SourceInfo(int index, String origin, String url) {
}
