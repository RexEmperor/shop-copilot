# 智购助手(shop-copilot)企业级电商 Agent 平台 — 开发计划

> 目标:3.5~4 个月内完成一个可写入简历、可公网演示、有评测数据的企业级双语言 Agent 项目,
> 对标岗位:淘天店铺方向 / 直播电商 AI 全栈 / 抖音支付 AI 应用开发。

## 0. 基本约束(计划依据)

| 项目 | 约定 |
|---|---|
| 时间投入 | 在职;工作日约 1h/天,周末 6~8h;合计 11~13h/周 |
| 截止线 | 16 周(≈3.7 个月)后开始投递;第 15~16 周为包装期 |
| Java 水平 | 熟练(做过完整项目),工程层不安排补课 |
| Python 水平 | 语言熟练,Web/工程化按需补(FastAPI 上手放在 Phase 1) |
| 模型 | DeepSeek(对话/工具调用/评测 judge);embedding 用本地 Ollama+BGE-M3 |
| 开发成本 | LLM API 总预算 <100 元;基础设施全本地 Docker |

## 1. 系统架构(冻结版 v1)

```
┌─────────────┐      SSE/HTTP      ┌──────────────────────────────┐
│  前端聊天 UI  │ ◄────────────────► │  Python: agent-runtime        │
│  (Vue3/Gradio)│                    │  FastAPI + LangGraph          │
└─────────────┘                     │  · 意图路由 Agent               │
                                    │  · 导购 / 订单售后 / 商品问答 RAG │
                                    │  · 记忆管理 · 安全护栏            │
                                    └──────┬───────────────┬─────────┘
                                     MCP 协议           │
                                    ┌──────▼───────┐   │
                                    │ Java: business-api │   │
                                    │ Spring Boot 3 + Spring AI  │
                                    │ · 商品/订单/售后 领域服务     │
                                    │ · MCP Server(业务工具暴露)  │
                                    └──────┬───────┴─────────┘
                        ┌──────────┬───────┴────┬─────────────┐
                        │ MySQL    │ Redis      │ RocketMQ    │ PG+pgvector
                        │ 业务数据  │ 缓存/限流/会话 │ 异步任务      │ (向量检索)
                        └──────────┴────────────┴─────────────┘
                        基础设施另含:Ollama(bge-m3)、Langfuse(追踪/成本)
```

**双语言分工原则**:业务逻辑与数据全在 Java(证明后端功力);Agent 编排全在 Python(生态优势);
两侧只通过 MCP(工具)和 HTTP(SSE 流式)通信——这本身就是大厂真实架构。

**关键技术决策记录(ADR)**

| 决策 | 选择 | 理由 / 面试话术 |
|---|---|---|
| Agent 编排 | LangGraph | 生产级多 Agent 事实标准,状态机可控 |
| 工具暴露 | Spring AI MCP Server | 衔接 MCP 协议实战;Java 生态差异化 |
| 向量库 | PG + pgvector | 单实例简单可靠;文章可写"平滑迁移 Milvus 路径" |
| Embedding | Ollama + BGE-M3 本地 | DeepSeek 无 embedding;零成本离线 |
| 消息队列 | RocketMQ | 国内电商主流,与 JD 匹配度最高 |
| 可观测 | Langfuse(自托管) | LLM 调用链+token 成本,企业级核心卖点 |
| 评测 | 自建评测集 + LLM-as-judge + GitHub Actions CI | 2026 面试高频考点 |

## 2. 阶段计划(16 周)

> 每个 Phase 都有:任务清单(工作日小块/周末整块)、产出物、验收标准(可验证)。
> 原则:**每个 Phase 结束系统都必须是"能跑"的**,不接受长期无法演示的开发方式。

### Phase 0 · 环境与地基(W1,~12h)✅ 2026-11-16 关闭
- [x] 建 monorepo `shop-copilot`(Java 模块 + Python 模块 + infra)
- [x] Docker Compose 一键起 MySQL / Redis / RocketMQ / PG(pgvector)(调整:Ollama 移至 Phase 4 本机安装;Langfuse 移至 Phase 1 部署)
- [x] 接通 DeepSeek API,封装统一 client(超时/重试/成本记录)
- [x] Spring Boot 3 骨架 + FastAPI 骨架,互相 HTTP 打通
- [x] 画架构图 v1(mermaid,README 里直接用)
- **验收**:`docker compose up -d` 全绿;Python 调 Java hello 接口返回成功;DeepSeek 首次调用成功并记录耗时 ✅(首调 0.74s / 29 tokens,GitHub 备份完成)

> **进度日志 2026-11-12**:基础设施竣工(5 容器全部 healthy,RocketMQ 集群注册成功,commit 1aa681e)。
> 排障记录(面试素材):①Homebrew git 配置损坏修复 ②死代理排查 ③本机 MySQL/Redis 与容器端口冲突(3306/6379)④RocketMQ 具名卷 root 属主导致 broker 崩溃,暂改为不挂数据卷(Phase 6 容器化时修正)。
> **进度日志 2026-11-13**:双语言骨架互调打通(Boot 3.5.7 + FastAPI,commit ce1cb9e)。
> **进度日志 2026-11-16**:DeepSeek 统一 client 上线,llm-check 冒烟通过;README 架构图 v1;代码推送 GitHub,Phase 0 关闭。
> **观察记录**:deepseek-chat 首调即出现身份幻觉(自称"阿里云开发")——真实幻觉样本已入库,Phase 4 幻觉护栏与评测环节可引用此案例。

### Phase 1 · LLM 应用基础工程化(W2–W3,~24h)✅ 2026-11-18 关闭
- [x] 结构化输出:function calling 封装(工具注册、参数校验、循环调用)✅(S3 验收通过:mini 工具注册器 + agent 循环,三连全过)
- [x] 流式响应:FastAPI SSE,打字机效果 ✅(S4:事件流协议 delta/tool_status/tool_result/final,工具过程对用户可见)
- [x] 多轮会话:消息历史持久化(MySQL)+ 热缓存(Redis),重启不丢 ✅(S1 Redis 版 + S2 MySQL 双写/Cache-aside 均已验收)
- [x] Prompt 模板管理(版本化目录 prompts/<名字>/<版本>.md + lru_cache;shopping_assistant v1 已上线并实测生效)
- [x] 简单聊天页(Gradio 先顶上,Phase 6 换 Vue3)✅(S5b:app/ui.py,流式回答 + 工具轨迹可视化)
- **产出**:`agent-runtime` 骨架 + pytest 单测
- **验收**:多轮对话上下文正确;杀进程重启会话不丢;每次调用在 Langfuse 可见
- **进度日志 2026-11-16(S1)**:多轮会话 Redis 版上线,POST /api/chat + 截断 + TTL;用户自加 Pydantic 入参校验(422 实战案例);实测 prompt_tokens 随轮次 33→69→97,"记忆=重发历史=成本"有了第一手数据。
- **进度日志 2026-11-16(S2)**:MySQL 持久层上线(双写 + Cache-aside 回源回填)。排障记录(面试素材):①SQLAlchemy 无 relationship() 时 flush 顺序不保证 → 父子表 INSERT 乱序触发外键 1452,显式 flush 修复 ②AIOMYSQL 驱动需与 URL +aiomysql 配套安装 ③AUTO_INCREMENT 不回退,失败事务留下 id 空洞。assistant 行 model/tokens/elapsed_ms 元数据落库实测通过。
- **进度日志 2026-11-17(S3)**:function calling 循环上线(mini 工具注册器:签名反射生成 schema + execute 三重兜底;agent.py 循环 MAX_ROUNDS=4;session_id 代码注入不进 schema)。三连验收:A 动态时间 tool_rounds=1;B 上下文注入——工具返回 3、模型答 4(模型覆盖工具权威数据的风险,记入 Phase 5 护栏 backlog);C 闲聊 tool_rounds=0。观测:工具 schema 稳定前缀使 DeepSeek 前缀缓存首次命中(cache_hit=256);无工具请求也因 schema 随行多付 ~360 tokens/次(Phase 3 意图路由的经济学动机)。

- **进度日志 2026-11-17(S4)**:SSE 事件流上线(chat_stream 增量协议 + run_agent_stream 事件流 + StreamingResponse;工具轮只发 tool_status/result,最终回答逐字 delta)。验收 A/B/C 全过。两个新认知:①write-ahead 用户消息 + 运行时崩溃 = 重复行入库——设计保住了用户输入,但暴露上下文污染问题(status 列/幂等键进 Phase 5 backlog)②import 缺失的 NameError 发生在"先存后崩"的分界点之后,所以失败请求也有痕迹——审计视角的价值。

- **进度日志 2026-11-17(S5a)**:Langfuse Cloud 接入(@observe 三处装饰 + propagate_attributes 传播 session)。观测:trace 层级 agent-chat→deepseek-chat→tool-execute→deepseek-chat 正确;失败请求也被记录(level=ERROR);选型故事:MinIO 2026-09 撤源导致自托管断裂,评估后切云版。
- **进度日志 2026-11-17(S5b)**:Gradio 聊天页上线(app/ui.py,Gradio 6.x Blocks):回答逐字流式渲染;工具事件可视化——tool_status→"🔧 调用中…"、tool_result→"✅ 结果",汇入"工具轨迹"框;每会话独立 ui-<uuid8> ID。两处自记取舍:①dev 期进程内直调 run_agent_stream 不走 HTTP,生产改消费 /api/agent-chat/stream ②流式路径 Langfuse 上报暂缺——均已入 backlog。
- **进度日志 2026-11-18(S5c)**:pytest 回归固化(tests/ 共 8 例,教练实测复跑 0.78s 全绿):chat 3 例(往返、**缓存未命中回源 MySQL——S2 顺序 bug 的专属回归测试**、截断)、agent 1 例(mock LLM 验证"要工具→喂结果→作答"循环)、tools 4 例(schema 跳过上下文键、未知工具兜底、多余参数丢弃+session_id 注入、异常转错误文本)。pytest-asyncio auto 模式配置于 pyproject。价值:cache-aside 与工具注册器的行为契约自此有回归保护,后续重构不再靠肉眼。
- **Phase 1 关闭小结(2026-11-18)**:地基四件套(结构化输出/流式/记忆/Prompt 管理)+ 可观测(Langfuse Cloud,SDK v5.2)+ 可视化(Gradio)+ 回归(8 例)全部落地,实际用时约两周半(计划 W2–W3 ≈ 24h 场次全部走完)。Phase 2(Java 业务层)待启。

### Phase 2 · 业务服务层(Java)(W4–W5,~24h)
- [x] 领域建模:商品 / 订单 / 售后工单 三模块,表结构设计(S1 已完成并验证)
- [x] Spring Boot 3 + MyBatis-Plus(或 JPA,按你习惯)实现 CRUD + 业务接口(S2 已完成,含状态机与并发控制)
- [x] Redis 缓存(商品详情)+ 基于 Redis 的接口限流(S3 已完成,待主人 review)
- [ ] OpenAPI 文档;生成 500 条商品 + 订单 mock 数据(springdoc 已接入;500 条 mock 待 S4)
- **产出**:`business-api` 可独立运行
- **验收**:Swagger 全接口可调;缓存命中可观测;简单并发压测无误

> **进度日志 2026-11-26(S1/S2 验收合并)**:完成 `feature/phase2-s1-modeling` review 与合并。①数据库校验脚本 `infra/schema/verify_shop.sh` 24 项全 PASS;②修复 `AftersaleServiceImpl.page()` 未传 `status` 时因拆箱触发 NPE 的缺陷并补回归测试(`fd8498a`);③`business-api` 全量 `mvn test` 183 例全绿;④已合并回 `master` 并清理 `.worktrees/phase2-s1-modeling`。
> **进度日志 2026-11-26(S3 实现与 Review 优化)**:完成商品详情 Cache-aside(`ProductCache`,含空值/下架短 TTL 哨兵防穿透、下架商品详情拦截返回 404、TTL 随机抖动防雪崩、事务提交后删键兜底、订单扣减/恢复库存联动失效)、Redis **ZSET + Lua 原子滑动窗口限流**(`@RateLimit` + `RateLimitInterceptor` + `RedisRateLimiter`,默认不信任 `X-Forwarded-For`,仅对白名单受信任代理从右向左解析真实 IP,读写/交易分级阈值,超限 429),并补齐订单查询接口(`GET /api/orders` 分页过滤、`GET /api/orders/{id}` 详情);`mvn test` **213 例全绿**。等待确认提交后推进 S4。

#### Phase 2 执行拆分(2026-11-24 确认)

> 固定前提:三表冻结、Java 侧无物理外键、金额存分、固定 `user_id=10001`、MyBatis-Plus。
> 代码由 AI 编写,每个阶段完成后由项目主人 review Java 代码再进入下一阶段。

| 步骤 | 主要工作 | 产物 / 文件 | 验收标准 | Review 卡点 |
|---|---|---|---|---|
| **S1 领域建模与 DDL** | 确认字段字典、枚举、状态机;编写三表 DDL 和最小种子数据 | `infra/schema/shop.sql`、`infra/schema/shop_seed.sql`;三表 `product/orders/aftersale_ticket` | utf8mb4;列 COMMENT 齐全;`updated_at ON UPDATE`;唯一/业务索引符合 §7;种子 10 商品(1 下架)、4 订单(覆盖四状态)、1 售后 | **S1 完成后暂停**,主人 review 表结构与状态机 |
| **S2a 工程底座** | 引入 MyBatis-Plus、Validation、springdoc;配置 MySQL;统一响应、异常、分页对象 | `business-api/pom.xml`、`application.yaml`、`common/` 包、Swagger 配置 | 应用可连 shop 库启动;`/v3/api-docs` 可访问;非法入参返回 400;未知异常返回统一错误结构 | 随 S2b 一起 review |
| **S2b 商品模块** | 商品 CRUD、上下架、分页/关键词/分类查询;实体/Mapper/Service/Controller/DTO | `product/` 包与对应测试 | Swagger 可调;下架商品不出现在在售列表;分页和过滤正确;核心服务单测通过 | 随 S2d 一起 review |
| **S2c 订单模块与状态机** | 创建订单、支付、发货、确认收货、取消;后端金额计算与非法流转拦截 | `order/` 包、`OrderStatus` 状态机及测试 | `CREATED→PAID→SHIPPED→DELIVERED`,非终态可取消;重复/越级流转返回业务错误;订单金额不信任前端 | **订单状态机单独讲评**,确认后再做售后 |
| **S2d 售后模块与 Phase 2 业务验收** | 发起售后、查询售后、审核售后;仅允许 PAID/SHIPPED/DELIVERED 发起 | `aftersale/` 包与对应测试;完整 Swagger 接口清单 | 非法状态发起售后被拒;退款金额从订单派生;商品/订单/售后最小闭环可演示;`mvn test` 全绿 | **S2 完成后暂停**,主人 review Java 业务代码 |
| **S3 Redis 商品缓存与限流** | 商品详情 Cache-aside、更新/下架失效;Redis 原子限流拦截器或注解;命中/拒绝日志 | `config/`、`product/` 缓存代码、`common/ratelimit/`、测试与配置 | 首次 MISS、二次 HIT;修改后旧值不可见;超限返回 429;并发下计数正确 | **S3 完成后暂停**,主人 review 缓存一致性与限流边界 |
| **S4 数据扩充、压测与收官** | 将商品/订单 mock 扩到 500 条;执行并发压测;记录延迟、错误率、缓存命中;更新文档 | `shop_mock_500.sql`、压测脚本或命令记录、`docs/PLAN.md`、`docs/面试题库.md` | `ab`/JMeter 压测无 5xx;缓存命中可观测;限流按预期触发;Phase 2 验收项逐条打勾 | **Phase 2 收官 review**,确认后再进入 Phase 3 |

**S1 建模补充约定(待主人确认)**:
- 为维持三表冻结,一期订单按“一单一件商品”建模,订单保存 `product_id`、商品名快照、成交单价与数量,总价由后端计算。
- 售后退款金额从订单总价派生,Agent 或前端不能直接指定权威金额。
- 最小种子数据用于 S1/S2 验收;500 条 mock 在 S4 扩充,避免早期 SQL 噪声过大。

### Phase 3 · Agent 核心(W6–W8,~36h)★核心,不可砍
- [ ] LangGraph 状态机:意图路由 → 导购 Agent / 订单售后 Agent / 商品问答 Agent
- [ ] Java 侧用 Spring AI 实现 **MCP Server**,暴露:查商品、查订单、查物流、发起退款等工具
- [ ] Python 侧接入 MCP Client,工具调用全链路打通
- [ ] ReAct 循环 + 工具调用失败重试 + 兜底(转人工话术)
- **产出**:端到端可演示的多 Agent 系统
- **验收**:30 条典型 query 全部路由正确、工具调用正确(评测集 v0)

### Phase 4 · RAG(W9–W10,~24h)★核心,不可砍
- [ ] 语料:平台规则文档(自写 20~30 篇)+ 商品目录
- [ ] 切分 → BGE-M3 向量化 → pgvector 入库;增量更新脚本
- [ ] 混合检索(向量 + 关键词)+ rerank(可选 SiliconFlow bge-reranker)
- [ ] 引用溯源(回答带来源)+ 幻觉护栏(检索不到就明说/转人工)
- [ ] 评测集扩到 100 条,开始记录指标
- **验收**:检索 top3 命中率 ≥80%;有引用、无编造;Langfuse 可见每次检索链路

### Phase 5 · 企业级工程化(W11–W12,~24h)
- [ ] RocketMQ:订单状态变更 → 异步通知 Agent 主动播报;批量向量索引任务
- [ ] 稳定性:LLM 超时/失败降级(规则 fallback)、工具幂等、重试风暴防护
- [ ] 安全护栏:敏感词、资金类操作二次确认、prompt injection 基础防御
- [ ] 评测 CI:GitHub Actions 跑评测集并出报告(准确率/幻觉率/成本)
- [ ] 可选:Prometheus + Grafana 业务监控
- **验收**:人为制造 LLM 超时/接口故障,系统优雅降级不崩;CI 评测报告自动产出
- **简历话术来源**:本阶段的降级设计、成本统计、评测数据是面试差异化核心

### Phase 6 · 前端 + 部署 + 打磨(W13–W14,~24h)
- [ ] Vue3 简洁聊天页(SSE 流式、会话列表、引用展示)——我可直接帮你写,你负责联调
- [ ] 全服务 Docker 化,docker compose 一键部署
- [ ] 可选:云服务器部署公网可访问(或仅本地 + 演示视频)
- [ ] README 重写:架构图、GIF 演示、评测数据表、QPS/成本数据
- **验收**:新机器 30 分钟内按 README 跑通全系统

### Phase 7 · 求职包装(W15–W16,~24h)
- [ ] 技术文章 2~3 篇,选题:①幻觉率从 15%→3% 的优化过程 ②Agent 降级与成本控制 ③MCP 双语言实战
- [ ] 简历项目描述打磨(每条对应 JD 关键词)
- [ ] 面试题自测清单(我出题+陪练):RAG 调优、Agent 稳定性、成本、MCP、双语言架构权衡
- [ ] 模拟面试 2 轮

## 3. 每周节奏模板

| 时段 | 内容 |
|---|---|
| 工作日 1h ×5 | 按 Phase 任务清单做小块:读文档/写单模块/写测试(每次提交 git,留 checkpoint) |
| 周末整块 3~4h ×2 | 联调、填坑、写周报(30 分钟),推进验收项 |
| 每周日 | 和我同步进度,我根据实际进度滚动调整下周任务 |

## 4. 风险与应对

| 风险 | 应对 |
|---|---|
| 在职时间少导致烂尾(最大风险) | 每 Phase 有最小可交付;任何周中断可从 git checkpoint 续;进度落后先砍 §5 的"可砍项" |
| 范围蔓延 | 冻结范围:多租户、A/B、微服务拆分等一律进 backlog 不做 |
| 技术栈演进 | 模型 client 抽象隔离;评测集保证任何替换可回归 |
| API 成本失控 | Langfuse 记录每次调用成本;评测 judge 用小模型 |
| DeepSeek 无 embedding(已确认) | 本地 Ollama+BGE-M3,已纳入架构 |

## 5. 裁剪优先级(进度落后时)

1. **绝不砍**:Phase 3 多 Agent + MCP、Phase 4 RAG、Phase 5 评测与降级(面试核心)
2. **可简化**:rerank、Prometheus/Grafana、云服务器部署(换本地+录屏)
3. **可延后到入职后**:Vue 前端(Gradio 顶替)、RocketMQ(降级为 Redis Stream)

## 6. 项目落位

- 代码仓库:`~/IdeaProjects/shop-copilot`(monorepo)
- 本计划文件:随进度滚动更新,每 Phase 结束打勾并记录偏差
