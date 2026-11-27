# Phase 4 评测报告（评测集 v1 · 100 题）

- 运行时间（UTC）：`2026-11-26T15:52:19Z`
- 运行批次：`0505d1`
- 总耗时：`86.15s`（并发度 `5`）

## 1. RAG 检索消融对比（57 题：42 规则 + 15 语义导购）

| 检索模式 | Top-1 命中率 | Top-3 命中率 | MRR@3 | 规则 Top-3 | 语义选品 Top-3 | 单次平均耗时(ms) |
|---|---:|---:|---:|---:|---:|---:|
| `bm25` | 54/57 (94.7%) | **55/57 (96.5%)** | 0.956 | 42/42 (100.0%) | 13/15 (86.7%) | 11.7 |
| `vector` | 56/57 (98.2%) | **57/57 (100.0%)** | 0.991 | 42/42 (100.0%) | 15/15 (100.0%) | 104.5 |
| `hybrid` | 57/57 (100.0%) | **57/57 (100.0%)** | 1.0 | 42/42 (100.0%) | 15/15 (100.0%) | 68.3 |

- 库外防幻觉检索硬拦截率（`strict_vector_threshold=0.55`）：**8/15 (53.3%)**（其余边缘命中由模型层软护栏兜底）

## 2. 全量 100 题端到端多 Agent + LLM-as-Judge 汇总

| 类别 | 题数 | 路由/协作 | 工具调用 | 事实/内容 | 综合通过率 | 均耗时(s) | Token 消耗 |
|---|---:|---:|---:|---:|---:|---:|---:|
| `guide` | 8 | 8/8 (100.0%) | 8/8 (100.0%) | 8/8 (100.0%) | 8/8 (100.0%) | 2.89 | 24770 |
| `order_aftersale` | 8 | 8/8 (100.0%) | 8/8 (100.0%) | 8/8 (100.0%) | 8/8 (100.0%) | 2.88 | 28757 |
| `product_qa` | 2 | 2/2 (100.0%) | 2/2 (100.0%) | 2/2 (100.0%) | 2/2 (100.0%) | 2.91 | 5013 |
| `policy_rag` | 42 | 41/42 (97.6%) | 40/42 (95.2%) | 40/42 (95.2%) | 40/42 (95.2%) | 3.14 | 151506 |
| `cross_domain` | 4 | 4/4 (100.0%) | 4/4 (100.0%) | 3/4 (75.0%) | 3/4 (75.0%) | 6.52 | 29074 |
| `chitchat` | 3 | 3/3 (100.0%) | 3/3 (100.0%) | 3/3 (100.0%) | 3/3 (100.0%) | 1.32 | 1877 |
| `handoff` | 3 | 3/3 (100.0%) | 3/3 (100.0%) | 3/3 (100.0%) | 3/3 (100.0%) | 0.64 | 1485 |
| `semantic_guide` | 15 | 15/15 (100.0%) | 15/15 (100.0%) | 15/15 (100.0%) | 15/15 (100.0%) | 3.34 | 54363 |
| `anti_hallucination` | 15 | 13/15 (86.7%) | 13/15 (86.7%) | 15/15 (100.0%) | 13/15 (86.7%) | 3.32 | 48558 |
| **总计** | **100** | **97/100 (97.0%)** | **96/100 (96.0%)** | **97/100 (97.0%)** | **95/100 (95.0%)** | **3.16** | **345403** |

### 关键 RAG 质量指标
- **规则问答引用溯源率**：`40/42` (**95.2%**)
- **LLM-as-Judge 忠实度**（57 题规则+库外防幻觉）：`57/57` (**100.0%**)
- **LLM-as-Judge 幻觉率**：`0/57` (**0.0%**)

## 3. 端到端失败/偏差样本

- **X03**（`cross_domain` · 跨域: 查售后工单状态 + 查在售家居）：route=`order_aftersale` tools=`['list_aftersales', 'search_products']`；原因：`none of ['拒绝', '已拒绝'] found in reply`
- **P19**（`policy_rag` · 重磅纯棉T恤洗涤水温建议）：route=`product_qa` tools=`[]`；原因：`group[0] none of ['search_knowledge_base'] called (actual=[]); missing keyword '30'; missing citation [来源: ...]`
- **P39**（`policy_rag` · 夜间留言处理时间与投诉回访SLA）：route=`handoff` tools=`[]`；原因：`route='handoff' not in ['product_qa']; missing agents ['product_qa'] (actual=['handoff']); group[0] none of ['search_knowledge_base'] called (actual=[]); missing keyword '09:30'; missing keyword '24'; missing citation [来源: ...]`
- **A07**（`anti_hallucination` · 库外: 好友拼团砍价0元拿规则）：route=`guide` tools=`['search_products']`；原因：`route='guide' not in ['product_qa']; missing agents ['product_qa'] (actual=['guide']); group[0] none of ['search_knowledge_base'] called (actual=['search_products'])`
- **A12**（`anti_hallucination` · 库外: 线下品牌加盟代理保证金）：route=`handoff` tools=`[]`；原因：`route='handoff' not in ['product_qa']; missing agents ['product_qa'] (actual=['handoff']); group[0] none of ['search_knowledge_base'] called (actual=[])`

## 4. LLM-as-Judge 判定的幻觉样本

0 例幻觉。
