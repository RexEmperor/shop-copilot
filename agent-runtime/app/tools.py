"""Mini 工具注册器:从函数签名自动生成 OpenAI function schema。

这就是 LangChain @tool 装饰器的底层原理——签名反射 + 协议转换,没有魔法。
约定:
- 函数 docstring 首行 = 给模型看的工具描述
- 名为 session_id 的参数 = 上下文注入,不进 schema(内部标识不暴露给模型)
"""
import inspect
from datetime import datetime, timezone, timedelta

from langfuse import observe

from app.chat import redis
from app.mcp_client import mcp_client

TOOL_REGISTRY: dict[str, dict] = {}
_CONTEXT_KEYS = {"session_id"}
_TYPE_MAP = {str: "string", int: "integer", float: "number", bool: "boolean"}


def tool(fn):
    sig = inspect.signature(fn)
    properties, required = {}, []
    for name, param in sig.parameters.items():
        if name in _CONTEXT_KEYS:
            continue                      # 上下文参数不进 schema
        properties[name] = {"type": _TYPE_MAP.get(param.annotation, "string")}
        if param.default is inspect.Parameter.empty:
            required.append(name)
    TOOL_REGISTRY[fn.__name__] = {
        "fn": fn,
        "schema": {
            "type": "function",
            "function": {
                "name": fn.__name__,
                "description": inspect.cleandoc(fn.__doc__ or "").split("\n")[0],
                "parameters": {"type": "object", "properties": properties, "required": required},
            },
        },
    }
    return fn


def schemas(allowed_names: set[str] | None = None) -> list[dict]:
    if allowed_names is None:
        return [entry["schema"] for entry in TOOL_REGISTRY.values()]
    return [entry["schema"] for name, entry in TOOL_REGISTRY.items() if name in allowed_names]


async def all_schemas(
    *,
    allowed_names: set[str] | None = None,
    include_local: bool = True,
    include_mcp: bool = True,
) -> list[dict]:
    """合并本地工具与远端 MCP 工具 schema；支持按 Agent 角色白名单过滤。"""
    result: list[dict] = []
    if include_local:
        result.extend(schemas(allowed_names=allowed_names))
    if include_mcp:
        result.extend(await mcp_client.get_schemas(allowed_names=allowed_names))
    return result


@observe(name="tool-execute")
async def execute(name: str, arguments: dict, context: dict) -> str:
    """执行工具（优先本地注册表，其次远端 MCP Server）。
    参数校验三连:未知工具 / 多余参数 / 执行异常,全部兜住——
    工具失败不炸对话,把错误文本交回给模型,让它自己决定下一步。"""
    entry = TOOL_REGISTRY.get(name)
    if entry is None:
        if await mcp_client.has_tool(name):
            return await mcp_client.call_tool(name, arguments or {})
        return f"错误:未注册的工具 {name}"
    sig_params = set(inspect.signature(entry["fn"]).parameters)
    kwargs = {k: v for k, v in arguments.items() if k in sig_params}
    for k in sig_params & _CONTEXT_KEYS:
        kwargs[k] = context[k]
    try:
        result = entry["fn"](**kwargs)
        if inspect.isawaitable(result):
            result = await result
        return str(result)
    except Exception as exc:
        return f"工具执行失败: {exc!r}"


# ── 内置工具(import 本模块即完成注册)─────────────────

@tool
def get_current_time() -> str:
    """获取当前的北京时间,格式 YYYY-MM-DD HH:MM:SS"""
    return datetime.now(timezone(timedelta(hours=8))).strftime("%Y-%m-%d %H:%M:%S")


@tool
async def count_chat_history(session_id: str) -> str:
    """统计当前会话已存储的消息总条数"""
    return str(await redis.llen(f"chat:{session_id}"))