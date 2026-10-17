# Spring-RagForge

基于 Spring Boot + LangChain4j 的 **RAG 知识问答系统**，面向 Spring Framework / Spring Boot / Spring AI 官方文档（英文 AsciiDoc 语料）。提供流式对话、混合检索、重排、多轮指代消解、滚动摘要记忆、引用溯源、生成层韧性、限流与每日额度、增量灌库等能力，配套一个 Vue 3 前端。

## 特性一览

- **混合检索**：Elasticsearch 上 BM25（词法）+ KNN（向量）双路召回，自实现 RRF 融合。中文问题先改写成英文关键词串再喂 BM25，解决中文 query 打英文索引命中为零的问题。
- **重排**：可选 cross-encoder 精排（DashScope `qwen3.7-text-rerank`），逐条打分取前 N，可配相关性阈值过滤。
- **多轮指代消解**：带指代的追问结合会话历史改写成独立问题，再进检索。
- **滚动摘要记忆**：会话记忆是带摘要的 token 窗口，超预算逐出最老消息并增量摘要，摘要计入预算；持久化到 Redis。
- **引用溯源**：检索片段注入时带编号与出处，回答里的 `[ref:n]` 只回传真实被引用的来源。
- **生成层韧性**：首 token 前无损重试，首 token 后只做空闲超时；重试用尽自动降级（回吐检索原文或道歉）。重试走请求级缓存，不重跑检索/重排。
- **限流**：最外层速率闸（令牌桶）+ 并发闸（信号量），超限快速失败。
- **每日额度**：每用户每天固定免费次数，Redis 原子计数，次日自动重置。
- **邮箱验证码登录**：QQ 邮箱 SMTP 发 6 位验证码，「登录即注册」签发雪花 ID 用户。
- **增量灌库**：按文件 SHA-256 比对，只重灌变化的文档；切分器签名或 embedding 模型变更时自动全量重建。
- **AsciiDoc 专用切分器**：按标题层级切分，代码块 / tabs / 表格不被从内部切开，每段自带标题路径与官方文档锚点链接。
- **用量统计**：监听模型返回的 usage，Redis 累计，按价格表估算成本。
- **评测**：内置检索层与生成层离线评测（`eval/` 下有评测集与历史结果）。

## 技术栈

| 分类 | 选型 |
| --- | --- |
| 语言 / 框架 | Java 17、Spring Boot 3.5.8 |
| LLM 编排 | LangChain4j 1.18.1-beta28（AiServices、RAG、MCP、Skills、reactor 流式） |
| 模型服务 | 阿里云百炼 DashScope（OpenAI 兼容模式）：对话 `deepseek-v4.1-flash`、向量 `qwen3.7-text-embedding-flash`、重排 `qwen3.7-text-rerank` |
| 向量库 | Elasticsearch 8.15（`dense_vector` + BM25 混合检索） |
| 会话/缓存/计数 | Redis |
| 用户数据 | MySQL + Spring Data JPA |
| 对象存储 | MinIO（语料 `.adoc` 文档） |
| 韧性/限流 | Resilience4j（ratelimiter + bulkhead + reactor） |
| 前端 | Vue 3 + Vite + TypeScript + Element Plus + Tailwind CSS 4 + marked + highlight.js + DOMPurify |

## 系统架构

一次问答的检索管线分四段：**改写/消解 → 召回 → 聚合/重排 → 注入**，外层套韧性、再外层套限流。

```
用户提问
   │
   ▼
[限流] RateLimitedChatService        速率闸 + 并发闸，超限快速失败
   │
   ▼
[韧性] ResilientChatService          首token超时/重试/降级，请求级缓存包裹内层
   │
   ▼
AiServices (ChatService)             system prompt + 会话记忆 + RAG
   │
   ▼
RetrievalAugmentor
   ├─ QueryTransformer  多轮指代消解（CompressingQueryTransformer）→ 缓存
   ├─ ContentRetriever  混合检索 BM25 + KNN → RRF 融合 → 缓存
   ├─ ContentAggregator 重排（可选）/ TopN → 捕获来源供前端展示 → 缓存
   └─ ContentInjector   片段带 [ref:n] 编号与出处注入用户消息
   │
   ▼
StreamingChatModel  ──SSE──▶  前端
```

图例：缓存层（`Caching*`）保证生成层重试时不会重跑检索、重排、指代消解。

## 目录结构

```
Spring-RagForge/
├── src/main/java/com/springragforge/
│   ├── config/            ChatConfig（RAG/限流装配）、WebMvcConfig、AuthInterceptor、BatchEmbeddingModel
│   ├── controller/        ChatController(SSE)、AuthController、AdminController、FileController、UsageController
│   ├── service/           ChatService 接口、ResilientChatService、RateLimitedChatService、UserService、MailService、CodeStore、QuotaService、SnowflakeIdGen
│   ├── rag/
│   │   ├── ingest/        增量灌库 IncrementalIndexer、ES 建索引、MinIO/本地语料源、灌库清单
│   │   ├── retriever/     混合检索 HybridContentRetriever、RRF、中文改写、指代消解
│   │   ├── rerank/        DashScope 打分模型、TopN 聚合、来源捕获
│   │   ├── memory/        SummarizingChatMemory 滚动摘要、token 估算
│   │   ├── injector/      NumberedContentInjector 编号注入
│   │   ├── splitter/      AsciiDoc 切分器 + 块扫描器
│   │   ├── resilience/    请求级缓存与查询/聚合缓存包装
│   │   ├── ratelimit/     限流参数
│   │   └── source/        SourceLinkResolver（chunk_id / 官方链接换算）
│   ├── storage/           MinIO 配置与存储服务
│   ├── repository/        Redis 会话记忆存储、UserRepository
│   ├── usage/             token 用量注册表与价格表
│   └── constant/          SystemPrompts、MemoryPrompts
├── src/main/resources/    application.yml、前端构建产物 static/
├── src/test/java/.../     eval/（检索层、生成层评测）与各单测
├── frontend/              Vue 3 前端源码（构建产物输出到 resources/static）
├── eval/                  评测集 JSON 与历史结果 CSV/JSON
├── scripts/upload_corpus.py  批量上传语料到 MinIO
└── data/                  灌库清单 manifest（运行期生成）
```

## 快速开始

### 1. 前置依赖

- JDK 17、Maven
- 运行中的 **Elasticsearch 8.15**、**Redis**、**MySQL**、**MinIO**
- 阿里云百炼 **DashScope API Key**
- 一个开启 SMTP 服务的 **QQ 邮箱**（授权码，非登录密码）

### 2. 准备数据库与语料

- MySQL 建空库 `rag`（Hibernate `ddl-auto: update` 会自动建 `user` 表）。
- MinIO 建 bucket `rag-docs`，把 `.adoc` 语料放进去（可用脚本批量传）：

  ```bash
  python scripts/upload_corpus.py D:/corpus --dry-run   # 先看差异
  python scripts/upload_corpus.py D:/corpus             # 再上传
  ```

### 3. 配置环境变量

| 变量 | 说明 |
| --- | --- |
| `DASHSCOPE_API_KEY` | DashScope API Key，对话/向量/重排共用 |
| `MYSQL_USERNAME` / `MYSQL_PASSWORD` | MySQL 账号（默认 root/123456） |
| `SPRING_MAIL_USERNAME` / `SPRING_MAIL_PASSWORD` | QQ 邮箱与 SMTP 授权码 |
| `MINIO_ACCESS_KEY` / `MINIO_SECRET_KEY` | MinIO 账号（默认 admin/12345678） |

> Elasticsearch 地址在 `ChatConfig#elasticsearchClient` 中写死为 `http://192.168.16.128:9200`，MinIO endpoint 在 `application.yml` 的 `minio.endpoint`，请按部署环境修改。

### 4. 启动后端

```bash
mvn spring-boot:run
```

服务端口 **8099**。首次启动不会灌库，需手动触发。

### 5. 灌库

```bash
curl -X POST http://localhost:8099/admin/reindex
```

同步执行，返回各状态计数（重建/新增/更新/删除/跳过/失败/耗时）。写入文件后需再次调用才会进知识库。

### 6. 启动前端（开发）

```bash
cd frontend
npm install
npm run dev        # Vite dev server，接口代理到 localhost:8099
```

生产构建直接产出到后端静态目录，jar 里自带页面：

```bash
cd frontend
npm run build      # 输出到 ../src/main/resources/static
```

## API 接口

除 `/auth` 与静态资源外，业务接口均需带 `X-User-Id` 请求头（未登录返回 401）。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/chat?message=&memoryId=` | 流式对话，SSE（`text/event-stream`） |
| `POST` | `/auth/send-code` | 发送邮箱验证码，body `{ "email": "..." }` |
| `POST` | `/auth/login` | 验证码登录/注册，body `{ "email", "code" }`，返回 `userId` |
| `POST` | `/admin/reindex` | 触发增量灌库（同步）；409 表示已有任务在跑或语料根不存在 |
| `GET` | `/admin/usage` | 查看 token 用量与估算成本报表 |
| `DELETE` | `/admin/usage` | 清零用量计数 |
| `POST` | `/files/upload` | 上传文档到 MinIO，multipart `file` + 可选 `directory` |
| `DELETE` | `/files?objectName=` | 删除 MinIO 中的文档对象（下次灌库时摘除向量） |

### SSE 事件约定

`/chat` 返回的流中包含以下几类事件：

- **普通 data 事件**：模型输出的 token 增量（含限流提示、降级文本）。
- **`event: quota`**：每日额度已用完。
- **`event: degraded`**：本次走了降级，data 为降级原因。
- **`event: sources`**：正文结束后推送，data 为本次实际被引用的来源（出处 + 链接）。
- **`data: [DONE]`**：流结束标记。

## 关键配置（`application.yml`）

| 配置前缀 | 关键项 | 说明 |
| --- | --- | --- |
| `rag.ingest` | `storage`、`index-name`、`dimension` | 语料来源（minio/local）、ES 索引名、向量维度 |
| `rag.retrieval` | `bm25-top-k`、`knn-top-k`、`rrf-k`、`candidate-pool`、`max-results`、`rewrite-enabled` | 双路候选池、RRF k、重排输入池、最终条数、是否中文改写 |
| `rag.rerank` | `enabled`、`model-name`、`min-score` | 重排开关、模型、相关性阈值 |
| `rag.memory` | `budget-tokens`、`keep-recent-messages`、`evict-batch`、`ttl` | 记忆预算、保留条数、逐出批量、Redis TTL |
| `rag.resilience` | `first-token-timeout`、`idle-timeout`、`overall-deadline`、`max-retries` | 生成层超时与重试 |
| `rag.rate-limit` | `limit-for-period`、`limit-refresh-period`、`max-concurrent-calls` | 速率闸与并发闸 |
| `rag.usage` | `pricing` | 按模型名配置每百万 token 价格 |

> `rag.retrieval.rewrite-enabled`、`rag.rerank.enabled`、`rag.rate-limit.enabled` 等开关主要用于对照组评测，可按需调整。

## 评测

评测默认跳过（避免误打 embedding 接口），需显式开启系统属性：

```bash
# 检索层：召回 + 重排，不出 LLM，输出 Recall/MRR 到 CSV
mvn test -Dtest=RetrievalEvalTest -Deval.run=true -Deval.config=baseline

# 生成层：走完整 RAG 链路，存每题回答与来源供人工判分
mvn test -Dtest=GenerationEvalTest -Deval.run=true -Deval.config=baseline
```

- 评测集在 `eval/`（`questions-*.json` / `question-*.json`），历史结果见 `results-*.csv`、`generation-*.json`。
- 可用 `-Deval.questions=... -Deval.out=... -Deval.topK=...` 覆盖默认路径与 top-K。
- 生成层评测前需先灌好库，并确保 Redis 与模型接口可达。

## 备注

- 鉴权是轻量的：`X-User-Id` 是登录后前端持有的公开值，可被伪造，只挡「未登录直接调接口」。要做强鉴权需改为签发 token/JWT。
- 语料是 Spring 官方文档的英文 AsciiDoc；换成中文语料时，中文改写（`rewrite-enabled`）与 BM25 字段权重需要重新调参。
- 灌库产物 `data/` 已在 `.gitignore` 中忽略，各环境自行生成。
