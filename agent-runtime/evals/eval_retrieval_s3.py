"""Phase 4 S3 检索消融对比评测脚本（Vector-only vs BM25-only vs Hybrid RRF）。

用法：
    cd agent-runtime && uv run python -m evals.eval_retrieval_s3
"""
import asyncio
import time

from app.rag.retriever import HybridRetriever

# 20 题带标准答案 doc_key 的检索评测集（14 规则问答 + 6 语义选品）
RETRIEVAL_BENCH: list[dict] = [
    {"id": "R01", "query": "七天无理由退货从哪天开始算？拆封了的零食能退吗？", "source_type": "policy", "expected": ["policy:01_return_7days", "policy:09_food_quality_safety"]},
    {"id": "R02", "query": "已付款还没发货的订单能申请退货退款吗？", "source_type": "policy", "expected": ["policy:02_refund_rules"]},
    {"id": "R03", "query": "商家延迟发货超过48小时按订单金额的百分之多少赔付？", "source_type": "policy", "expected": ["policy:03_shipping_delay_compensation"]},
    {"id": "R04", "query": "买满多少钱送退货运费险？最高赔多少运费？", "source_type": "policy", "expected": ["policy:04_return_shipping_freight"]},
    {"id": "R05", "query": "买完几天内降价可以申请价保退差价？秒杀参加价保吗？", "source_type": "policy", "expected": ["policy:05_price_protection"]},
    {"id": "R06", "query": "签收后多久内可以补开电子发票或增值税专票？", "source_type": "policy", "expected": ["policy:06_invoice_policy"]},
    {"id": "R07", "query": "数码产品质保期几个月？进水了免费修吗？", "source_type": "policy", "expected": ["policy:07_warranty_digital", "policy:08_extended_warranty"]},
    {"id": "R08", "query": "店铺延保服务一年多少钱？包含意外进水保修吗？", "source_type": "policy", "expected": ["policy:08_extended_warranty"]},
    {"id": "R09", "query": "吃到过期变质食品怎么赔付？不足1000元怎么算？", "source_type": "policy", "expected": ["policy:09_food_quality_safety"]},
    {"id": "R10", "query": "纯棉T恤和羽绒服尺码不合怎么换货？羽绒服能机洗吗？", "source_type": "policy", "expected": ["policy:10_apparel_sizing_exchange"]},
    {"id": "R11", "query": "香薰蜡烛玻璃瓶寄碎了需要寄回吗？多久补发？", "source_type": "policy", "expected": ["policy:11_home_fragile_damage"]},
    {"id": "R12", "query": "未支付订单多少分钟会自动关闭取消？支持货到付款吗？", "source_type": "policy", "expected": ["policy:14_payment_methods_timeout"]},
    {"id": "R13", "query": "多少积分能抵扣一元钱？积分年底清零吗？", "source_type": "policy", "expected": ["policy:16_member_points_vip"]},
    {"id": "R14", "query": "20000mAh 充电宝额定能量多少Wh？能带上飞机吗？", "source_type": "policy", "expected": ["policy:18_battery_safety_shipping"]},
    {"id": "P01", "query": "送给长辈的健康监测礼物推荐什么", "source_type": "product", "expected": ["product:2"]},
    {"id": "P02", "query": "出差坐飞机需要给笔记本应急供电的设备", "source_type": "product", "expected": ["product:4"]},
    {"id": "P03", "query": "打字手感好支持蓝牙和2.4G无线连接的办公外设", "source_type": "product", "expected": ["product:3"]},
    {"id": "P04", "query": "通勤降噪听歌用的无线设备", "source_type": "product", "expected": ["product:1"]},
    {"id": "P05", "query": "适合秋天冬天上班穿的保暖衣服", "source_type": "product", "expected": ["product:6"]},
    {"id": "P06", "query": "放在卧室放松助眠有香味的居家好物", "source_type": "product", "expected": ["product:9"]},
]

OOD_QUERIES = [
    "跨境保税仓直邮商品怎么计算个人行邮税和关税？",
    "旧手机以旧换新怎么在线估价折抵？",
    "你们线下实体门店支持自提吗？北京朝阳区有门店吗？",
]


async def evaluate_mode(retriever: HybridRetriever, mode: str) -> dict:
    top1 = 0
    top3 = 0
    mrr3 = 0.0
    t0 = time.perf_counter()
    misses = []
    for item in RETRIEVAL_BENCH:
        hits = await retriever.retrieve(
            item["query"], top_k=3, source_type=item["source_type"], mode=mode
        )
        got = [h.doc_key for h in hits]
        expected = set(item["expected"])
        if got and got[0] in expected:
            top1 += 1
        hit_rank = next((idx + 1 for idx, dk in enumerate(got) if dk in expected), None)
        if hit_rank is not None:
            top3 += 1
            mrr3 += 1.0 / hit_rank
        else:
            misses.append((item["id"], item["query"], got, item["expected"]))

    elapsed = time.perf_counter() - t0
    n = len(RETRIEVAL_BENCH)
    return {
        "mode": mode,
        "top1": f"{top1}/{n} ({top1 / n * 100:.1f}%)",
        "top3": f"{top3}/{n} ({top3 / n * 100:.1f}%)",
        "mrr3": round(mrr3 / n, 3),
        "avg_ms": round(elapsed / n * 1000, 1),
        "misses": misses,
    }


async def main() -> None:
    retriever = HybridRetriever()
    for mode in ("vector", "bm25", "hybrid"):
        res = await evaluate_mode(retriever, mode)
        print(
            f"[{res['mode']:6s}] Top1={res['top1']} | Top3={res['top3']} | "
            f"MRR@3={res['mrr3']} | Avg={res['avg_ms']}ms"
        )
        for m in res["misses"]:
            print(f"   MISS {m[0]}: got={m[2]} expected={m[3]}")

    print("\n--- Out-of-domain threshold check (hybrid, source_type=policy) ---")
    for q in OOD_QUERIES:
        hits = await retriever.retrieve(q, top_k=3, source_type="policy", mode="hybrid")
        top_info = [(h.doc_key, round(h.vector_score or 0, 3), round(h.bm25_score or 0, 3)) for h in hits]
        print(f"OOD: {q}\n  -> {top_info}")


if __name__ == "__main__":
    asyncio.run(main())
