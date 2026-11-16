# 智购助手 shop-copilot

企业级电商智能导购 Agent 平台:多 Agent 协作 + RAG + MCP 双语言架构。

- `business-api/` Java (Spring Boot 3) 业务服务,通过 MCP 暴露业务工具
- `agent-runtime/` Python (FastAPI + LangGraph) Agent 编排运行时
- `infra/` Docker Compose 基础设施

## 架构图

```mermaid
flowchart TB
    subgraph client["用户"]
        UI["聊天 UI(Phase 6: Vue3)"]
    end
    subgraph py["agent-runtime · Python"]
        FA["FastAPI(SSE 流式)"]
        LG["LangGraph 多 Agent 编排"]
        LLM["DeepSeek 统一客户端"]
    end
    subgraph java["business-api · Java"]
        SB["Spring Boot 3 业务服务"]
        MCP["MCP Server(业务工具暴露)"]
    end
    subgraph infra["infra · Docker Compose"]
        MY[("MySQL")]
        RD[("Redis")]
        PG[("PG + pgvector")]
        MQ[["RocketMQ"]]
    end

    UI <-- "SSE 流式" --> FA
    FA --> LG
    LG --> LLM
    LG -- "MCP 协议" --> MCP
    MCP --> SB
    SB --> MY
    SB --> RD
    LG --> PG
    SB -.异步任务.-> MQ
```