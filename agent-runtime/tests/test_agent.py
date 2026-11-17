from app import agent


async def test_agent_loop_calls_tool_then_answers(session_id, monkeypatch):
    """喂给循环一个'剧本':第一轮要工具,第二轮给答案——验证 ReAct 机制本身"""
    calls = []

    async def fake_chat(messages, tools=None):
        calls.append([dict(m) for m in messages])
        if len(calls) == 1:
            return {"content": None,
                    "raw_assistant_message": {"role": "assistant", "content": None,
                        "tool_calls": [{"id": "call_1", "type": "function",
                                        "function": {"name": "get_current_time",
                                                     "arguments": "{}"}}]},
                    "tool_calls": [{"id": "call_1", "name": "get_current_time", "args": {}}],
                    "model": "deepseek-chat", "elapsed_s": 0.01, "usage": {}}
        return {"content": "现在是测试时间",
                "raw_assistant_message": {"role": "assistant", "content": "现在是测试时间"},
                "tool_calls": [], "model": "deepseek-chat", "elapsed_s": 0.01, "usage": {}}

    monkeypatch.setattr(agent.llm, "chat", fake_chat)               # 替身就位,不发网络请求

    result = await agent.run_agent(session_id, "现在几点了?")

    assert result["tool_rounds"] == 1
    assert result["content"] == "现在是测试时间"
    assert len(calls) == 2
    # 关键断言:第二轮的上下文里必须包含 role=tool 的观察结果(喂回机制)
    assert any(m["role"] == "tool" and "2026-" in m["content"] for m in calls[1])
    # 会话历史已正确落库
    from app.chat import get_history
    history = await get_history(session_id)
    assert [m["role"] for m in history] == ["user", "assistant"]