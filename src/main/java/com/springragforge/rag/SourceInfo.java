package com.springragforge.rag;

/**
 * 单条参考来源，由后端从召回片段中提取，保证出处和链接准确。
 *
 * <p>text 是片段正文，正常回流前端时用不到（前端只渲染出处和链接），
 * 但降级路径（模型不可用）要靠它把检索原文拼给用户。
 */
public record SourceInfo(int index, String origin, String url, String text) {
}
