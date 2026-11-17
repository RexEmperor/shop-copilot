from app.tools import tool, TOOL_REGISTRY, execute


def test_schema_generation_skips_context_keys():
    @tool
    async def dummy(session_id: str, city: str, days: int = 1) -> str:
        """查询天气"""
        return "ok"

    fn_schema = TOOL_REGISTRY["dummy"]["schema"]["function"]
    assert fn_schema["name"] == "dummy"
    assert fn_schema["description"] == "查询天气"
    # session_id 是上下文注入参数,必须被剔除出 schema
    assert set(fn_schema["parameters"]["properties"]) == {"city", "days"}
    assert fn_schema["parameters"]["required"] == ["city"]


async def test_execute_unknown_tool():
    out = await execute("no_such_tool", {}, {})
    assert "未注册" in out


async def test_execute_drops_extra_args_and_injects_context():
    captured = {}

    @tool
    async def probe(session_id: str, q: str) -> str:
        captured.update({"sid": session_id, "q": q})
        return f"done:{q}"

    out = await execute("probe", {"q": "hi", "hack": 1}, context={"session_id": "s-123"})
    assert out == "done:hi"
    assert captured == {"sid": "s-123", "q": "hi"}   # 注入生效,多余参数被丢弃


async def test_execute_tool_failure_returns_error_text():
    @tool
    async def boom() -> str:
        raise RuntimeError("炸了")

    out = await execute("boom", {}, {})
    assert "工具执行失败" in out                      # 工具崩了对话不能崩