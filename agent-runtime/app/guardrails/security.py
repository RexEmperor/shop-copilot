"""输入侧安全护栏（敏感词过滤 + Prompt Injection 基础防御）与输出脱敏。"""
from dataclasses import dataclass
import re


@dataclass(frozen=True)
class SafetyResult:
    allowed: bool
    category: str | None = None
    reason: str = ""
    safe_reply: str = ""


# 1. 高危敏感词 / 违禁及诈骗诱导规则
_SENSITIVE_PATTERNS: list[tuple[str, re.Pattern]] = [
    ("fraud_inducement", re.compile(r"(加微信|私下转账|发.*短信验证码|刷单返现|洗钱|套现)", re.IGNORECASE)),
    ("illegal_content", re.compile(r"(枪支弹药|冰毒|海洛因|赌博网站|办假证|色情服务)", re.IGNORECASE)),
    ("abuse", re.compile(r"(草泥马|去死吧|全家死光|滚蛋垃圾客服)", re.IGNORECASE)),
]

# 2. Prompt Injection / 越狱与资金越权篡改攻击规则
_INJECTION_PATTERNS: list[tuple[str, re.Pattern]] = [
    (
        "override_instructions",
        re.compile(
            r"(忽略(以上|上面|之前|前面)(所有|全部)?(指令|规则|提示|设定)|"
            r"无视(所有|全部)?(规则|限制|安全)|"
            r"ignore\s+(all\s+)?(previous|above|prior)\s+instructions|"
            r"forget\s+(all\s+)?(your\s+)?(rules|instructions)|"
            r"进入开发者模式|jailbreak|你现在是\s*DAN)",
            re.IGNORECASE,
        ),
    ),
    (
        "prompt_exfiltration",
        re.compile(
            r"((输出|打印|告诉我|泄露|展示|重复)(你的)?(完整)?(系统提示词|system\s*prompt|原始指令|内部提示词)|"
            r"reveal\s+(your\s+)?(system\s+)?prompt|"
            r"(打印|输出|告诉我).*(API[_\s]?KEY|DEEPSEEK[_\s]?API|数据库密码|环境变量))",
            re.IGNORECASE,
        ),
    ),
    (
        "fund_tampering",
        re.compile(
            r"((跳过|绕过)(所有)?(校验|审核|确认|风控).*(退款|售后|改价)|"
            r"(把|将)(退款金额|订单价格|单价)(改成|修改为|设为)\s*\d+|"
            r"bypass\s+(confirmation|validation|auth))",
            re.IGNORECASE,
        ),
    ),
]

_SECRET_MASK_RE = re.compile(r"\bsk-[A-Za-z0-9_-]{16,}\b")


def check_input_safety(user_message: str) -> SafetyResult:
    """前置检测用户输入：拦截敏感词与 Prompt Injection，短路不消耗大模型额度。"""
    text = (user_message or "").strip()
    if not text:
        return SafetyResult(allowed=True)

    for tag, pattern in _SENSITIVE_PATTERNS:
        if pattern.search(text):
            if tag == "fraud_inducement":
                reply = (
                    "安全提醒：本店所有交易、退款与理赔均仅通过官方订单系统原路办理，"
                    "绝不支持私下转账或索要短信验证码。如需正常购物或售后咨询，请告诉我您的具体需求。"
                )
            elif tag == "abuse":
                reply = (
                    "非常理解您可能遇到了不顺心的问题，请文明沟通。"
                    "您可以直接告诉我订单号或遇到的问题，或回复“转人工”由人工客服协助解决。"
                )
            else:
                reply = (
                    "抱歉，您的请求包含受限内容，智购助手仅提供合规电商选品、订单查询与售后服务。"
                )
            return SafetyResult(
                allowed=False,
                category="sensitive_word",
                reason=tag,
                safe_reply=reply,
            )

    for tag, pattern in _INJECTION_PATTERNS:
        if pattern.search(text):
            if tag == "fund_tampering":
                reply = (
                    "抱歉，订单价格与退款金额均由业务系统按交易记录自动核定，"
                    "且售后提交须经过标准校验与本人二次确认，无法绕过规则或手动篡改金额。"
                )
            else:
                reply = (
                    "抱歉，我无法执行修改系统设定或展示内部配置/提示词的请求。"
                    "我是“智购助手”，可以帮您挑选商品、查询订单售后或解答平台规则，请问有什么可以帮您？"
                )
            return SafetyResult(
                allowed=False,
                category="prompt_injection",
                reason=tag,
                safe_reply=reply,
            )

    return SafetyResult(allowed=True)


def sanitize_output(text: str) -> str:
    """输出侧兜底脱敏：遮蔽意外出现的类 API Key 敏感凭证。"""
    if not text:
        return text
    return _SECRET_MASK_RE.sub("sk-***", text)
