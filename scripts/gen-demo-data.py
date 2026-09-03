#!/usr/bin/env python3
"""
商店截图用的演示数据。生成到 app/src/debug/assets/demo/，只打进 debug 包。

内容是编的，但要自洽——图上的回答会引用「去年 11 月低密度 3.31」「红曲断续吃」
「父亲做过支架」这些，这些事实必须真的在用药表和记忆里躺着，不然截图就在演。

时间基准写死在 BASE，不跟当天走：跟着今天走的话，每次重跑图上的日期分组
（今天 / 最近 7 天）都不一样，商店截图就不可复现了。

    python3 scripts/gen-demo-data.py

改完重跑，然后重新构建 debug 包。
"""
import json, os, random, uuid, datetime as dt

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app", "src", "debug", "assets", "demo")
TID = "8f3c1d20-4a55-4c7e-9b12-0d61a7e5c401"

BASE = dt.datetime(2026, 9, 3, 9, 30, tzinfo=dt.timezone.utc)

# id 也要可复现：随机 uuid 会让每次重跑都把 assets 全改一遍，git 里全是噪声。
_rng = random.Random(20260903)


def uid():
    return str(uuid.UUID(int=_rng.getrandbits(128), version=4))


def iso(days_ago=0, hours=0, minutes=0):
    t = BASE - dt.timedelta(days=days_ago, hours=hours, minutes=minutes)
    return t.isoformat().replace("+00:00", "Z")

def msg(role, text, days_ago, minutes=0, tools=None):
    m = {"id": uid(), "role": role, "text": text, "createdAt": iso(days_ago, minutes=minutes)}
    if tools:
        m["toolCalls"] = tools
    return m

def tool(name, inp, out):
    return {"id": uid(), "name": name, "input": json.dumps(inp, ensure_ascii=False), "output": out}

def session(sid, msgs, days_ago, title=None):
    s = {"id": sid, "messages": msgs, "createdAt": iso(days_ago, minutes=5), "updatedAt": iso(days_ago)}
    if title:
        s["threadId"] = "goal:" + sid[:8]
        s["threadTitle"] = title
    return s

root = OUT
sess_dir = os.path.join(root, "tenant", "sessions")
os.makedirs(sess_dir, exist_ok=True)

# ---- tenants.json ----
with open(os.path.join(root, "tenants.json"), "w") as f:
    json.dump({"version": 1, "tenants": [
        {"id": TID, "name": "我", "kind": "owner", "ageBand": "adult", "createdAt": iso(240)},
        {"id": "b21e5f77-1c39-4a80-8e64-2f7ac9d31b55", "name": "妈妈", "kind": "managed", "ageBand": "senior", "createdAt": iso(180)},
    ]}, f, ensure_ascii=False, indent=1)

# ---- 主聊天：化验单解读 ----
s1 = session("11111111-1111-4111-8111-111111111111", [
    msg("user", "这是今天体检的血脂四项，帮我看看", 0, 4),
    msg("assistant",
        "总胆固醇 5.9、低密度脂蛋白 3.82 都超了参考上限一点，甘油三酯 1.62 和高密度脂蛋白 1.21 在范围内。\n\n"
        "把它放到你自己的时间线上看更有意义：去年 11 月那次低密度是 3.31，这次 3.82，一年涨了 0.5。"
        "单看一次「略高」不算事，连着两次往上走才是要处理的信号。\n\n"
        "你三月开始吃的红曲我记着，在「需要时吃」那一组、写着断断续续。血脂这种指标断续吃基本等于没吃——"
        "要么规律吃三个月再复查，要么就别把它算进方案里。\n\n"
        "复查建议放在 12 月，空腹。到时候把这张单子和这次的一起给我，我们看趋势。",
        0, 3,
        tools=[tool("record_measurement", {"name": "低密度脂蛋白", "value": "3.82", "unit": "mmol/L"}, "已记录")]),
    msg("user", "需要吃他汀吗", 0, 2),
    msg("assistant",
        "这个我不能替你决定，得医生看。但可以先说清楚判断依据是什么，你去问的时候心里有数：\n\n"
        "指南看的不是单一个低密度数值，是「十年心血管风险」——年龄、血压、吸烟、糖尿病、家族史一起算。"
        "3.82 在没有其他危险因素的人身上，通常先给三到六个月的生活方式调整；"
        "但如果有早发冠心病家族史，起步线会低不少。\n\n"
        "你提过你爸五十多岁做过支架。这一条会明显改变结论，去门诊一定要主动说，别等医生问。",
        0, 1),
], 0)

# ---- 用药说明 ----
s2 = session("22222222-2222-4222-8222-222222222222", [
    msg("user", "二甲双胍能和红曲一起吃吗", 2, 3),
    msg("assistant",
        "这两个没有公认的直接相互作用，不过有两件事值得注意：\n\n"
        "红曲里的莫纳可林 K 和洛伐他汀是同一个东西，所以它其实按他汀算。"
        "二甲双胍 + 他汀是很常见的组合，本身没问题。\n\n"
        "真正要盯的是肌肉症状。如果出现没来由的酸痛、乏力，尤其是大腿和上臂两侧对称的那种，停下来去查个肌酸激酶。",
        2, 2),
], 2)

# ---- 长期目标线程 ----
s3 = session("33333333-3333-4333-8333-333333333333", [
    msg("user", "这周跑了三次，膝盖没再疼", 5, 3),
    msg("assistant",
        "三次都到了三公里以上，比上周多了一次，而且膝盖没有反应——上个月你是跑完第二天下楼梯疼的。\n\n"
        "按之前说好的，连着两周不疼再加量。这是第一周，下周先维持三次三公里，别急着加。",
        5, 2),
], 5, title="膝盖不疼地跑起来")

for s in (s1, s2, s3):
    with open(os.path.join(sess_dir, s["id"] + ".json"), "w") as f:
        json.dump(s, f, ensure_ascii=False, indent=1)

tdir = os.path.join(root, "tenant")

# ---- medications.json ----
meds = [
    {"name": "二甲双胍缓释片", "status": "ongoing", "when": "每天晚餐后", "reason": "内分泌科开的，空腹血糖 6.8",
     "outcome": "吃了四个月，复查 6.1，肠胃一开始不舒服，两周后好了", "startedAt": iso(120)},
    {"name": "红曲胶囊", "status": "asNeeded", "when": "想起来就吃，断断续续",
     "reason": "自己买的，想降血脂", "outcome": "说不好，这次复查低密度反而涨了", "startedAt": iso(180)},
    {"name": "维生素 D3", "status": "ongoing", "when": "每天早上", "reason": "去年查 25-羟维生素 D 偏低",
     "outcome": "复查回到正常范围", "startedAt": iso(300)},
    {"name": "布洛芬", "status": "cannotTake", "when": "", "reason": "胃溃疡史，消化科明确说了不要用",
     "outcome": "", "note": "疼痛要用药先问医生"},
    {"name": "褪黑素", "status": "tried", "when": "睡不着的时候", "reason": "入睡困难",
     "outcome": "吃了两周没感觉，第二天反而发沉，停了"},
]
for m in meds:
    m.setdefault("id", uid())
    m.setdefault("origin", "manual")
    m.setdefault("createdAt", iso(150))
    m.setdefault("updatedAt", iso(1))
with open(os.path.join(tdir, "medications.json"), "w") as f:
    json.dump({"items": meds}, f, ensure_ascii=False, indent=1)

# ---- measurements.json ----
cards = [
    ("低密度脂蛋白", "3.82", "mmol/L", 0, "体检，空腹"),
    ("总胆固醇", "5.90", "mmol/L", 0, "体检，空腹"),
    ("空腹血糖", "6.1", "mmol/L", 0, "体检，空腹"),
    ("血压", "128/82", "mmHg", 1, "早上起床后量的"),
    ("体重", "71.4", "kg", 1, ""),
    ("低密度脂蛋白", "3.31", "mmol/L", 296, "去年体检"),
]
with open(os.path.join(tdir, "measurements.json"), "w") as f:
    json.dump({"cards": [
        {"id": uid(), "name": n, "value": v, "unit": u,
         "observedAt": iso(d), "note": note, "recordedAt": iso(d)}
        for n, v, u, d, note in cards]}, f, ensure_ascii=False, indent=1)

# ---- memory.json ----
mems = [
    ("父亲 50 多岁做过心脏支架，有早发冠心病家族史", "profile", "asked"),
    ("有胃溃疡史，消化科交代不要用布洛芬类", "profile", "asked"),
    ("希望直接说结论，不要先铺垫一段安慰的话", "preference", "extracted"),
    ("跑步时膝盖疼，2026 年 8 月起按「连续两周不疼再加量」推进", "interpretation", "extracted"),
    ("12 月复查血脂四项，空腹", "followUp", "asked"),
]
with open(os.path.join(tdir, "memory.json"), "w") as f:
    json.dump({"items": [
        {"id": uid(), "text": t, "kind": k, "origin": o,
         "createdAt": iso(30), "updatedAt": iso(2),
         **({"dueAt": iso(-98)} if k == "followUp" else {})}
        for t, k, o in mems]}, f, ensure_ascii=False, indent=1)

print("wrote", OUT)
