"""pytest 公共夹具"""
import uuid

import pytest


@pytest.fixture
def session_id() -> str:
    """每个测试独立会话,互不污染;MySQL 残留行在 dev 库中可接受"""
    return f"pytest-{uuid.uuid4().hex[:8]}"