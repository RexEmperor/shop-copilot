# Phase 3 评测报告（评测集 v0 · 30 题）

- 运行时间（UTC）：`2026-11-26T11:53:18Z`
- 运行批次：`0531f0`
- 总耗时：`28.68s`（并发度 `3`）

## 1. 汇总指标

| 维度 / 类别 | 题数 | 路由/协作准确率 | 工具调用准确率 | 事实/内容通过率 | 综合通过率 | 平均耗时(s) | Token 消耗 |
|---|---:|---:|---:|---:|---:|---:|---:|
| `guide` | 8 | 8/8 (100.0%) | 8/8 (100.0%) | 8/8 (100.0%) | 8/8 (100.0%) | 2.44 | 23371 |
| `order_aftersale` | 8 | 8/8 (100.0%) | 8/8 (100.0%) | 8/8 (100.0%) | 8/8 (100.0%) | 3.18 | 28683 |
| `product_qa` | 4 | 4/4 (100.0%) | 4/4 (100.0%) | 4/4 (100.0%) | 4/4 (100.0%) | 1.68 | 5727 |
| `cross_domain` | 4 | 4/4 (100.0%) | 4/4 (100.0%) | 4/4 (100.0%) | 4/4 (100.0%) | 6.46 | 28753 |
| `chitchat` | 3 | 3/3 (100.0%) | 3/3 (100.0%) | 3/3 (100.0%) | 3/3 (100.0%) | 1.47 | 1873 |
| `handoff` | 3 | 3/3 (100.0%) | 3/3 (100.0%) | 3/3 (100.0%) | 3/3 (100.0%) | 0.72 | 1485 |
| **总计** | **30** | **30/30 (100.0%)** | **30/30 (100.0%)** | **30/30 (100.0%)** | **30/30 (100.0%)** | **2.81** | **89892** |

## 2. 失败/偏差样本明细

全部样本通过，无失败项。

## 3. 全量逐题结果

| ID | 类别 | 描述 | 路由 | 专家 | 调用工具 | 耗时(s) | 结果 |
|---|---|---|---|---|---|---:|---|
| G01 | `guide` | 查全店在售品类 | `guide` | `guide` | `list_categories` | 2.29 | **PASS** |
| G02 | `guide` | 按精确分类查数码商品 | `guide` | `guide` | `search_products` | 2.91 | **PASS** |
| G03 | `guide` | 同义泛化词电子产品映射数码类目 | `guide` | `guide` | `search_products, list_categories, search_products` | 3.11 | **PASS** |
| G04 | `guide` | 按关键词查机械键盘价格与库存 | `guide` | `guide` | `search_products` | 2.76 | **PASS** |
| G05 | `guide` | 按预算查食品类推荐 | `guide` | `guide` | `search_products` | 2.32 | **PASS** |
| G06 | `guide` | 按商品ID查在售商品详情 | `guide` | `guide` | `get_product_detail` | 2.03 | **PASS** |
| G07 | `guide` | 查询已下架商品ID 10 拦截反馈 | `guide` | `guide` | `get_product_detail` | 1.93 | **PASS** |
| G08 | `guide` | 按场景同义词查服饰商品 | `guide` | `guide` | `search_products` | 2.21 | **PASS** |
| O01 | `order_aftersale` | 查全部订单列表 | `order_aftersale` | `order_aftersale` | `list_orders` | 2.04 | **PASS** |
| O02 | `order_aftersale` | 按状态筛选已发货订单 | `order_aftersale` | `order_aftersale` | `list_orders` | 2.6 | **PASS** |
| O03 | `order_aftersale` | 按订单号查已支付订单详情 | `order_aftersale` | `order_aftersale` | `list_orders` | 4.8 | **PASS** |
| O04 | `order_aftersale` | 查已签收订单数量与金额 | `order_aftersale` | `order_aftersale` | `list_orders` | 4.35 | **PASS** |
| O05 | `order_aftersale` | 查售后工单列表与拒绝状态 | `order_aftersale` | `order_aftersale` | `list_aftersales` | 4.11 | **PASS** |
| O06 | `order_aftersale` | 待支付订单申请仅退款规则拦截(无副作用) | `order_aftersale` | `order_aftersale` | `list_orders` | 2.33 | **PASS** |
| O07 | `order_aftersale` | 已支付未发货订单申请退货退款规则拦截(无副作用) | `order_aftersale` | `order_aftersale` | `list_orders` | 2.07 | **PASS** |
| O08 | `order_aftersale` | 模糊退款诉求不盲目写库先查单确认 | `order_aftersale` | `order_aftersale` | `list_orders` | 3.17 | **PASS** |
| Q01 | `product_qa` | 查商品ID 1 规格参数问答 | `product_qa` | `product_qa` | `get_product_detail` | 1.87 | **PASS** |
| Q02 | `product_qa` | 查商品ID 4 笔记本供电使用场景 | `product_qa` | `product_qa` | `get_product_detail` | 1.95 | **PASS** |
| Q03 | `product_qa` | 未知延保收费细则防幻觉拒答 | `product_qa` | `product_qa` | `-` | 1.47 | **PASS** |
| Q04 | `product_qa` | 未知延迟发货赔付比例防幻觉拒答 | `product_qa` | `product_qa` | `-` | 1.43 | **PASS** |
| X01 | `cross_domain` | 跨域协作: 查订单状态 + 推荐200元左右耳机 | `order_aftersale` | `order_aftersale → guide` | `list_orders, search_products` | 8.13 | **PASS** |
| X02 | `cross_domain` | 跨域协作: 查已发货订单 + 推荐食品 | `order_aftersale` | `order_aftersale → guide` | `list_orders, search_products` | 5.5 | **PASS** |
| X03 | `cross_domain` | 跨域协作: 查售后工单状态 + 查在售家居 | `order_aftersale` | `order_aftersale → guide` | `list_aftersales, search_products` | 6.41 | **PASS** |
| X04 | `cross_domain` | 跨域协作: 查羽绒服 + 查订单签收状态 | `guide` | `guide → order_aftersale` | `search_products, list_orders` | 5.8 | **PASS** |
| C01 | `chitchat` | 日常打招呼零工具 | `chitchat` | `chitchat` | `-` | 1.14 | **PASS** |
| C02 | `chitchat` | 询问助手身份与能力零工具 | `chitchat` | `chitchat` | `-` | 1.51 | **PASS** |
| C03 | `chitchat` | 用户致谢零工具 | `chitchat` | `chitchat` | `-` | 1.75 | **PASS** |
| H01 | `handoff` | 明确要求转人工客服 | `handoff` | `handoff` | `-` | 0.53 | **PASS** |
| H02 | `handoff` | 投诉升级拒绝机器人要求接人工 | `handoff` | `handoff` | `-` | 0.49 | **PASS** |
| H03 | `handoff` | 复杂问题主动申请人工介入 | `handoff` | `handoff` | `-` | 1.14 | **PASS** |
