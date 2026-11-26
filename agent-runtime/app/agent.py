"""Agent 循环:思考 → 要工具 → 执行 → 喂结果 → 再思考……直到给出最终回答。

MAX_ROUNDS 防失控:模型可能反复要求调工具,烧钱又烧时间,必须设上限。
"""
import json
import time

from langfuse import observe, propagate_attributes

from app.chat import append_message, build_messages, get_history
from app.llm.client import DeepSeekClient
from app.tools import all_schemas, execute

llm = DeepSeekClient()
MAX_ROUNDS = 4


@observe(name="agent-chat")
async def run_agent(session_id: str, user_message: str) -> dict:
    # Langfuse 5.x:session 要用 propagate_attributes 上下文管理器设置,
    # 并传播到本 trace 的所有子 span。(3.x 的 update_current_trace 已移除)
    with propagate_attributes(session_id=session_id):
        await get_history(session_id)             # 顺序契约:S2 事故的教训,先回源预热
        await append_message(session_id, "user", user_message)
        messages = await build_messages(session_id)

        tool_schemas = await all_schemas()
        tool_rounds = 0
        result = None
        for _ in range(MAX_ROUNDS):
            result = await llm.chat(messages, tools=tool_schemas)
            if not result["tool_calls"]:
                break                             # 模型不要工具了 = 可以作答
            tool_rounds += 1
            messages.append({"role": "assistant",
                             "content": result["raw_assistant_message"].get("content") or "",
                             "tool_calls": result["raw_assistant_message"]["tool_calls"]})
            for call in result["tool_calls"]:
                output = await execute(call["name"], call["args"],
                                       context={"session_id": session_id})
                messages.append({"role": "tool", "tool_call_id": call["id"], "content": output})

        await append_message(session_id, "assistant", result["content"])
        result["tool_rounds"] = tool_rounds
        return result


async def run_agent_stream(session_id: str, user_message: str):
    """事件流版 Agent。协议:tool_status / tool_result / delta / final。
    持久化:final 之前落库——客户端拿到的"完成"就意味着历史已存好;
    已知限制:客户端中途断开则本轮 assistant 回复不入库(dev 期接受,backlog)。
    """
    await get_history(session_id)
    await append_message(session_id, "user", user_message)
    messages = await build_messages(session_id)

    tool_schemas = await all_schemas()
    tool_rounds = 0
    content_parts: list[str] = []
    usage = {}
    start = time.perf_counter()
    final_content = None

    for _ in range(MAX_ROUNDS):
        round_tool_calls = None
        async for ev in llm.chat_stream(messages, tools=tool_schemas):
            if ev["type"] == "delta":
                content_parts.append(ev["content"])
                yield {"type": "delta", "content": ev["content"]}
            elif ev["type"] == "tool_calls":
                round_tool_calls = ev["tool_calls"]
            elif ev["type"] == "done":
                usage = ev["usage"]

        if not round_tool_calls:                  # 没要工具 = 这轮就是最终回答
            final_content = "".join(content_parts)
            break

        tool_rounds += 1
        messages.append({"role": "assistant", "content": "".join(content_parts) or None,
                         "tool_calls": [{"id": c["id"], "type": "function",
                                         "function": {"name": c["name"],
                                                      "arguments": json.dumps(c["args"], ensure_ascii=False)}}
                                        for c in round_tool_calls]})
        content_parts = []                        # 中间轮的零星 content 不带入最终答案
        for c in round_tool_calls:
            yield {"type": "tool_status", "name": c["name"], "args": c["args"]}
            output = await execute(c["name"], c["args"], context={"session_id": session_id})
            yield {"type": "tool_result", "name": c["name"], "output": output}
            messages.append({"role": "tool", "tool_call_id": c["id"], "content": output})

    if final_content is None:                     # MAX_ROUNDS 用尽的兜底
        final_content = "".join(content_parts) or "抱歉,这次没能完成,请稍后重试。"

    await append_message(session_id, "assistant", final_content)   # 先落库,后发 final
    yield {"type": "final", "content": final_content, "tool_rounds": tool_rounds,
           "usage": usage, "elapsed_s": round(time.perf_counter() - start, 2)}