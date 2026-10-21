"""一次跑完所有 Python 测试。CI 友好：任何失败返回非零退出码。

    python server/tests/run_all.py
"""
from __future__ import annotations

import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
TESTS = ["test_smoke.py", "test_cron.py", "test_live_pipeline.py"]

# 录制 fixture 是「生成器」不是「测试」，单独留一个开关
if "--record" in sys.argv:
    TESTS.append("record_sse_fixture.py")


def main() -> int:
    env = dict(os.environ, PYTHONIOENCODING="utf-8", WATCHHUB_QUIET="1")
    failed = []
    for t in TESTS:
        print(f"\n{'=' * 60}\n>>> {t}\n{'=' * 60}")
        r = subprocess.run([sys.executable, os.path.join(HERE, t)], env=env)
        if r.returncode != 0:
            failed.append(t)
    print(f"\n{'=' * 60}")
    if failed:
        print(f"FAILED ({len(failed)}): {', '.join(failed)}")
        return 1
    print(f"ALL {len(TESTS)} SUITES PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(main())
