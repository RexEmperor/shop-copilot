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

### Phase 0 · 环境与地基(W1,~12h)
- [x] 建 monorepo `shop-copilot`(Java 模块 + Python 模块 + infra)
- [x] Docker Compose 一键起 MySQL / Redis / RocketMQ / PG(pgvector)(调整:Ollama 移至 Phase 4 本机安装;Langfuse 移至 Phase 1 部署)
- [ ] 接通 DeepSeek API,封装统一 client(超时/重试/成本记录)
- [ ] Spring Boot 3 骨架 + FastAPI 骨架,互相 HTTP 打通
- [ ] 画架构图 v1(mermaid,README 里直接用)
- **验收**:`docker compose up -d` 全绿;Python 调 Java hello 接口返回成功;DeepSeek 首次调用成功并记录耗时

> **进度日志 2026-11-12**:基础设施竣工(5 容器全部 healthy,RocketMQ 集群注册成功,commit 1aa681e)。
> 排障记录(面试素材):①Homebrew git 配置损坏修复 ②死代理排查 ③本机 MySQL/Redis 与容器端口冲突(3306/6379)④RocketMQ 具名卷 root 属主导致 broker 崩溃,暂改为不挂数据卷(Phase 6 容器化时修正)。

### Phase 1 · LLM 应用基础工程化(W2–W3,~24h)
- [ ] 结构化输出:function calling 封装(工具注册、参数校验、循环调用)
- [ ] 流式响应:FastAPI SSE,打字机效果
- [ ] 多轮会话:消息历史持久化(MySQL)+ 热缓存(Redis),重启不丢
- [ ] Prompt 模板管理(版本化目录,不硬编码)
- [ ] 简单聊天页(Gradio 先顶上,Phase 6 换 Vue3)
- **产出**:`agent-runtime` 骨架 + pytest 单测
- **验收**:多轮对话上下文正确;杀进程重启会话不丢;每次调用在 Langfuse 可见

### Phase 2 · 业务服务层(Java)(W4–W5,~24h)
- [ ] 领域建模:商品 / 订单 / 售后工单 三模块,表结构设计
- [ ] Spring Boot 3 + MyBatis-Plus(或 JPA,按你习惯)实现 CRUD + 业务接口
- [ ] Redis 缓存(商品详情)+ 基于 Redis 的接口限流
- [ ] OpenAPI 文档;生成 500 条商品 + 订单 mock 数据
- **产出**:`business-api` 可独立运行
- **验收**:Swagger 全接口可调;缓存命中可观测;简单并发压测无误

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
