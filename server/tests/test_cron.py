import sys, os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from datetime import datetime, timedelta
from hub.sources import CronSource, TZ

print("[cron] 单元测试")
now = datetime.now(TZ)
hm = now.strftime("%H:%M")
other = (now + timedelta(hours=1)).strftime("%H:%M")
ok = 0; bad = 0
def t(name, cond):
    global ok, bad
    if cond: ok += 1; print("  PASS", name)
    else: bad += 1; print("  FAIL", name)

s = CronSource("r1", {"kind": "cron", "times": [hm], "title": "喝水"})
t("命中当前分钟", len(s.fetch()) == 1)
t("key 含日期时刻", s.fetch()[0].key.endswith(now.strftime("%Y%m%d%H%M")))
t("不命中其他时刻", len(CronSource("r2", {"times": [other]}).fetch()) == 0)
d = now.weekday()
t("days 含今天→命中", len(CronSource("r3", {"times": [hm], "days": [d]}).fetch()) == 1)
t("days 不含今天→不命中", len(CronSource("r4", {"times": [hm], "days": [(d + 1) % 7]}).fetch()) == 0)
t("多时刻不重复计", len(CronSource("r5", {"times": [hm, hm]}).fetch()) == 2)
print(f"  {ok} passed, {bad} failed")
sys.exit(1 if bad else 0)
