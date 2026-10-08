import DOMPurify from 'dompurify'
import hljs from 'highlight.js/lib/core'
import bash from 'highlight.js/lib/languages/bash'
import css from 'highlight.js/lib/languages/css'
import go from 'highlight.js/lib/languages/go'
import java from 'highlight.js/lib/languages/java'
import javascript from 'highlight.js/lib/languages/javascript'
import json from 'highlight.js/lib/languages/json'
import markdown from 'highlight.js/lib/languages/markdown'
import python from 'highlight.js/lib/languages/python'
import sql from 'highlight.js/lib/languages/sql'
import typescript from 'highlight.js/lib/languages/typescript'
import xml from 'highlight.js/lib/languages/xml'
import yaml from 'highlight.js/lib/languages/yaml'
import { marked, type Tokens, type TokenizerAndRendererExtension } from 'marked'
import type { SourceRef } from '../types'

// 按需注册常用语言（xml 同时覆盖 html / vue），控制打包体积
const languages = {
  javascript,
  typescript,
  java,
  python,
  json,
  bash,
  xml,
  sql,
  css,
  yaml,
  markdown,
  go,
}
for (const [name, lang] of Object.entries(languages)) {
  hljs.registerLanguage(name, lang)
}

// 与旧页面一致：gfm 表格、breaks 让单个换行也断行
marked.setOptions({ gfm: true, breaks: true })

// 回答里的链接一律新标签打开
DOMPurify.addHook('afterSanitizeAttributes', (node) => {
  if (node instanceof Element && node.tagName === 'A' && node.getAttribute('href')) {
    node.setAttribute('target', '_blank')
    node.setAttribute('rel', 'noopener noreferrer')
  }
})

/**
 * 当前这次渲染用到的来源表。
 *
 * <p>角标要在渲染时就知道「有没有链接」和「出处是什么」，而 marked 的 renderer 签名里塞不进额外参数，
 * 所以用一个模块级变量传进去。渲染是同步的、单线程的，调用前赋值即可。
 */
let activeSources = new Map<number, SourceRef>()

interface CiteToken extends Tokens.Generic {
  type: 'cite'
  ref: number
}

/**
 * [ref:N] 的内联扩展。
 *
 * 不用「先正则替换成 &lt;sup&gt; 再交给 marked」：那样会连代码块里的 [ref:1] 一起替换掉，
 * 而且行首的 &lt;sup&gt; 可能被 CommonMark 判成 HTML 块，把段落切断。扩展走 tokenizer，
 * 天然不会在代码里触发，也不会被提升成块级。
 */
const citeExtension: TokenizerAndRendererExtension = {
  name: 'cite',
  level: 'inline',
  start(src: string) {
    const at = src.indexOf('[ref:')
    return at < 0 ? undefined : at
  },
  tokenizer(src: string) {
    const matched = /^\[ref:(\d+)\]/.exec(src)
    if (!matched) return undefined
    return { type: 'cite', raw: matched[0], ref: Number(matched[1]) } satisfies CiteToken
  },
  renderer(token: Tokens.Generic) {
    const { ref } = token as CiteToken
    const source = activeSources.get(ref)
    const title = source ? `出处：${source.origin}` : `来源 ${ref}：后端未提供`
    // 没有链接的来源照样出角标，只是置灰不可点
    const plain = source?.url ? '' : ' cite-plain'
    return `<sup class="cite${plain}" data-ref="${ref}" title="${escapeAttribute(title)}">${ref}</sup>`
  },
}

/**
 * 模型有时把标题写成 `###代理机制`：# 后漏了空格。CommonMark 规定此处必须有空白，
 * marked 会把整行当普通段落，# 就原样显示给用户。块级扩展把这种漏空格的写法补渲染成标题。
 *
 * 安全性：块级 tokenizer 永远不会进入 fenced/indented 代码块内部，所以代码里的
 * `#define`、`#!/bin/bash` 不会被误改成标题；规范内写法（带空格）也直接放行给内置 heading。
 */
interface LooseHeadingToken extends Tokens.Generic {
  type: 'looseHeading'
  depth: number
  text: string
}

const looseHeadingExtension: TokenizerAndRendererExtension = {
  name: 'looseHeading',
  level: 'block',
  start(src: string) {
    const at = src.search(/^#{1,6}\S/m)
    return at < 0 ? undefined : at
  },
  tokenizer(this: any, src: string) {
    // 7 个及以上 # 本就不是标题（规范），不接管
    if (/^#{7,}/.test(src)) return undefined
    const matched = /^(#{1,6})([^\n]*)/.exec(src)
    if (!matched) return undefined
    // 带空格的规范写法交给内置 heading，只接管漏空格的
    if (/^#{1,6}\s/.test(matched[0])) return undefined
    const token: LooseHeadingToken = {
      type: 'looseHeading',
      raw: matched[0],
      depth: matched[1].length,
      text: matched[2],
      tokens: [],
    }
    this.lexer.inline(token.text, token.tokens)
    return token
  },
  renderer(this: any, token: Tokens.Generic) {
    const { depth, tokens } = token as LooseHeadingToken
    return `<h${depth}>${this.parser.parseInline(tokens)}</h${depth}>`
  },
}

/**
 * 代码块接管：输出「语言标签 + 复制按钮 + pre」结构（复制点击由 MessageBubble 委托处理）。
 * 高亮交给 highlight.js，未注册的语言退化为纯文本，标签仍显示原文语言名。
 */
const codeRenderer = {
  renderer: {
    code(token: Tokens.Code): string {
      const lang = (token.lang ?? '').trim().split(/\s+/)[0] ?? ''
      const label = lang || 'text'
      const body =
        lang && hljs.getLanguage(lang)
          ? hljs.highlight(token.text, { language: lang }).value
          : escapeAttribute(token.text)
      return (
        `<div class="code-block">` +
        `<div class="code-header"><span class="code-lang">${escapeAttribute(label)}</span>` +
        `<button class="code-copy" type="button">复制</button></div>` +
        `<pre><code class="hljs">${body}</code></pre></div>`
      )
    },
  },
}

marked.use({ extensions: [citeExtension, looseHeadingExtension] })
marked.use(codeRenderer)

/** 渲染 AI 回答为可安全注入的 HTML。sources 用来决定角标可点否、悬停显示什么。 */
export function renderMarkdown(source: string, sources: SourceRef[]): string {
  activeSources = new Map(sources.map((item) => [item.index, item]))
  return DOMPurify.sanitize(marked.parse(source, { async: false }))
}

/** 属性值里可能带引号或 &，先转义再拼进 HTML，避免拼出畸形标签。 */
function escapeAttribute(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}
