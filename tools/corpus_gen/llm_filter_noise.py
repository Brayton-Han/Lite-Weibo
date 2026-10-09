# -*- coding: utf-8 -*-
"""噪声层 LLM 复筛（CORPUS_DESIGN.md §5「硬约束一」的第二道关）。

为什么需要这一道：`extract_noise.py` 的关键词过滤挡不住语义近似。实测漏网样本——

    「工作总会有的，在找的只是一份更适合自己的工作，不要着急…」   → 撞 R01/E02
    「爱是一种奢侈品。如同巴黎橱窗里的狐皮大衣…」                 → 撞 R03
    「写给2012的自己：再烦，也别忘记微笑；再累，也要保护自己…」    → 撞通用情绪

再往屏蔽表里加词就要屏蔽「工作」「爱」「累」这类高频字，会把大量真正中立的内容
一起杀掉。而鸡汤与情感文本恰恰最擅长换着说法表达同一件事——这正是词面方法的固有
盲区，也正是本项目要用向量检索去证明的那件事。

用法：
    python llm_filter_noise.py --dry-run     # 看 prompt 与成本
    python llm_filter_noise.py               # 并发复筛（快）
    python llm_filter_noise.py --batch       # 走 Batch API（半价，但要等）
"""
from __future__ import annotations

import argparse
import json
import sys
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

import anthropic

ROOT = Path(__file__).resolve().parents[2]
IN = ROOT / "corpus" / "noise_candidates.jsonl"
OUT = ROOT / "corpus" / "noise.jsonl"
REJECTED = ROOT / "corpus" / "noise_rejected.jsonl"

MODEL = "claude-haiku-4-5"          # 分类任务，不需要更贵的模型
CHUNK = 40                          # 每请求判定多少条
TARGET = 900

SYSTEM = """\
你在为一个中文社交平台的**检索系统评测**筛选「噪声层」语料。

噪声层的唯一作用是充当**干扰项**：把语料库撑到足够大，让 Recall@10 不虚高。
它必须是**主题中立**的——不承载任何可被检索命中的信息需求或情绪共鸣。

## 必须剔除（neutral=false）

只要沾上下面任何一类，就剔除。宁可错杀，候选池很大。

**情绪与共鸣类**：疲惫、倦怠、孤独、焦虑、失眠、失落、思念、委屈、迷茫、
容貌或身材焦虑、年龄焦虑、漂泊感、失去与怀念。

**情感关系类**：爱情、暗恋、分手、思念某人、亲密关系的感悟——
**包括以格言/警句/散文形式谈论爱与关系的「鸡汤」**。

**人生感悟与励志鸡汤**：谈人生、成长、珍惜、释怀、坚持、心态的说理或劝慰文字。
即使语气平静、没有第一人称，只要内核是情绪抚慰或人生说教，就剔除。

**生活经验与求助类**：租房、求职、考学、通勤、数码选购、就医、育儿、装修、
养宠、理财、旅行攻略、技术问题——这些承载可被回答的信息需求。

## 应当保留（neutral=true）

事实性资讯、新闻报道、商品广告与促销、游戏与娱乐推广、活动通知、
明星八卦、星座娱乐、天气路况、吃喝玩乐的流水账、无主题的日常碎碎念、
纯粹的段子与玩笑、转发抽奖、节日祝福。

判断标准不是「这条写得好不好」，而是「用户会不会带着某种需求来搜它」。
会 → 剔除；不会 → 保留。"""

USER_TMPL = """\
判定下面每一条。对每条给出 id、neutral（true 保留 / false 剔除）、
以及 tag（剔除时给一个两到四字的理由，如「情感鸡汤」「育儿」「职场」；保留时给空串）。

{items}"""

SCHEMA = {
    "type": "object",
    "properties": {
        "verdicts": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "id": {"type": "string"},
                    "neutral": {"type": "boolean"},
                    "tag": {"type": "string"},
                },
                "required": ["id", "neutral", "tag"],
                "additionalProperties": False,
            },
        }
    },
    "required": ["verdicts"],
    "additionalProperties": False,
}


def chunks(rows, n):
    for i in range(0, len(rows), n):
        yield rows[i:i + n]


def render(chunk):
    return "\n".join(f'{r["gen_id"]}: {r["content"]}' for r in chunk)


def build_params(chunk):
    return {
        "model": MODEL,
        "max_tokens": 4000,
        "system": SYSTEM,
        "messages": [{"role": "user", "content": USER_TMPL.format(items=render(chunk))}],
        "output_config": {"format": {"type": "json_schema", "schema": SCHEMA}},
    }


def parse(msg) -> list[dict]:
    text = next((b.text for b in msg.content if b.type == "text"), None)
    return json.loads(text)["verdicts"] if text else []


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--batch", action="store_true", help="走 Batch API（半价，需等待）")
    ap.add_argument("--target", type=int, default=TARGET)
    ap.add_argument("--workers", type=int, default=8)
    args = ap.parse_args()

    if not IN.exists():
        sys.exit(f"找不到 {IN}，先跑 extract_noise.py")
    rows = [json.loads(l) for l in IN.read_text(encoding="utf-8").splitlines() if l.strip()]
    batches = list(chunks(rows, CHUNK))
    print(f"候选 {len(rows):,} 条 → {len(batches)} 个请求（每请求 {CHUNK} 条），模型 {MODEL}")

    # Haiku 4.5 定价 $1/$5 per 1M；--batch 再五折
    in_tok = sum(len(SYSTEM) + len(render(c)) for c in batches) * 0.8
    out_tok = len(rows) * 30
    mult = 0.5 if args.batch else 1.0
    print(f"成本估算：约 ${(in_tok/1e6*1 + out_tok/1e6*5) * mult:.2f}"
          f"（输入 ~{in_tok/1000:.0f}K / 输出 ~{out_tok/1000:.0f}K token）")

    if args.dry_run:
        print(f"\n--- system（{len(SYSTEM)} 字符）---\n{SYSTEM}")
        print(f"\n--- 首个请求的 user（前 400 字符）---\n{USER_TMPL.format(items=render(batches[0]))[:400]}…")
        return

    client = anthropic.Anthropic()
    verdicts: dict[str, dict] = {}

    if args.batch:
        from anthropic.types.message_create_params import MessageCreateParamsNonStreaming
        from anthropic.types.messages.batch_create_params import Request
        import time
        reqs = [Request(custom_id=f"chunk-{i:04d}",
                        params=MessageCreateParamsNonStreaming(**build_params(c)))
                for i, c in enumerate(batches)]
        b = client.messages.batches.create(requests=reqs)
        print(f"批次 {b.id}，轮询中…")
        while client.messages.batches.retrieve(b.id).processing_status != "ended":
            time.sleep(30)
        for res in client.messages.batches.results(b.id):
            if res.result.type == "succeeded":
                for v in parse(res.result.message):
                    verdicts[v["id"]] = v
    else:
        def work(c):
            return parse(client.messages.create(**build_params(c)))
        with ThreadPoolExecutor(max_workers=args.workers) as ex:
            futs = {ex.submit(work, c): i for i, c in enumerate(batches)}
            for n, fut in enumerate(as_completed(futs), 1):
                try:
                    for v in fut.result():
                        verdicts[v["id"]] = v
                except Exception as e:
                    print(f"  ✗ chunk {futs[fut]} 失败: {type(e).__name__}: {e}")
                if n % 10 == 0:
                    print(f"  …{n}/{len(batches)} 个请求完成", flush=True)

    kept = [r for r in rows if verdicts.get(r["gen_id"], {}).get("neutral")]
    rejected = [(r, verdicts.get(r["gen_id"], {}).get("tag", "未判定")) for r in rows
                if not verdicts.get(r["gen_id"], {}).get("neutral")]

    from collections import Counter
    print(f"\n=== 复筛结果 ===")
    print(f"  保留 {len(kept):,} / {len(rows):,}  ({len(kept)/len(rows):.1%})")
    print(f"  剔除 {len(rejected):,}")
    for tag, c in Counter(t for _, t in rejected).most_common(12):
        print(f"    {tag or '(空)':<10} {c:>5}")

    final = kept[: args.target]
    for i, r in enumerate(final):
        r["gen_id"] = f"N-{i:04d}"
    with OUT.open("w", encoding="utf-8") as fh:
        for r in final:
            fh.write(json.dumps(r, ensure_ascii=False) + "\n")
    with REJECTED.open("w", encoding="utf-8") as fh:
        for r, tag in rejected:
            fh.write(json.dumps({**r, "reject_tag": tag}, ensure_ascii=False) + "\n")

    print(f"\n写入 {OUT.relative_to(ROOT)}：{len(final)} 条")
    print(f"剔除样本留档 {REJECTED.relative_to(ROOT)}（抽检 LLM 判得对不对）")
    if len(final) < args.target:
        print(f"⚠️ 只有 {len(final)} 条，不足 {args.target}。扩大候选池后重跑")
    print("\n下一步：人工抽检 50 条（CORPUS_DESIGN.md §5），然后 Step 4 导入")


if __name__ == "__main__":
    main()
