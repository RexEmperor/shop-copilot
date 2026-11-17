"""Agent 循环:思考 → 要工具 → 执行 → 喂结果 → 再思考……直到给出最终回答。

MAX_ROUNDS 防失控:模型可能反复要求调工具,烧钱又烧时间,必须设上限。
"""
from app.chat import append_message, build_messages, get_history
from app.llm.client import DeepSeekClient
from app.tools import execute, schemas

llm = DeepSeekClient()
MAX_ROUNDS = 4


async def run_agent(session_id: str, user_message: str) -> dict:
    await get_history(session_id)                 # 顺序契约:S2 事故的教训,先回源预热
    await append_message(session_id, "user", user_message)
    messages = await build_messages(session_id)

    tool_rounds = 0
    result = None
    for _ in range(MAX_ROUNDS):
        result = await llm.chat(messages, tools=schemas())
        if not result["tool_calls"]:
            break                                 # 模型不要工具了 = 可以作答
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