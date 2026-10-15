# -*- coding: utf-8 -*-
"""靶区语料合成器（docs/CORPUS_DESIGN.md Step 2）。

走 Message Batches API（成本减半）。**一个族一个请求**——让模型一次看到该族的
全部 ~20 条，才能主动错开用词；逐条生成做不到这一点，只会产出高度雷同的句子。

用法：
    export ANTHROPIC_API_KEY=...        # 或先跑 `ant auth login`
    python generate.py submit           # 提交 96 个请求，批次 ID 存盘
    python generate.py fetch            # 轮询 + 落地 corpus/manifest.jsonl
    python generate.py run              # 上面两步连跑

    python generate.py submit --model claude-sonnet-5   # 换模型
    python generate.py submit --only R01,E12            # 只跑指定簇（调试用）
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time
from pathlib import Path

import anthropic
from anthropic.types.message_create_params import MessageCreateParamsNonStreaming
from anthropic.types.messages.batch_create_params import Request

from clusters import iter_families, STYLES

ROOT = Path(__file__).resolve().parents[2]
OUT_DIR = ROOT / "corpus"                      # 已在 .gitignore 中
STATE_FILE = OUT_DIR / ".batch_state.json"
MANIFEST = OUT_DIR / "manifest.jsonl"

DEFAULT_MODEL = "claude-opus-5"
MAX_TOKENS = 32000   # 实测：91 个成功请求 p50 7753、max 15963，另有 5 个撞上 16000 被截断。
                     # max_tokens 是上限不是计费量，留足余量不增加成本。

# ---------------------------------------------------------------- prompts

SYSTEM_STABLE = """\
你在为一个中文社交平台（类微博）生成语料，用途是**评测检索系统**，不是训练内容模型。
生成结果会被导入数据库，作为语义检索与词面检索的对照实验素材。

## 硬性要求

1. **长度 60–200 字（不含标点）。** 这一条最容易不达标，所以给一个可执行的标准：
   **每条至少要包含两个具体的东西**——一个动作、一个数字、一个场景、一句对话、
   一个后续结果，任选两样。只有一句情绪或一句吐槽，一定不够长。

   ✗ 太短：「中介张口就要一个月房租，太离谱了吧？？？」（只有一句情绪）
   ✓ 够长：「中介张口就要一个月房租的服务费。我说太贵了，他说这是行价。
     最后砍到半个月，签字那天我特地把聊天记录截了图存着，怕他反悔。」
     （有对话、有数字、有后续动作）

   写完每条自己数一下，不到 60 字就再补一个细节，**不要靠拉长句子或加总结凑字数**。
2. **口语化的社交平台文体**，不是书面语、不是新闻体、不是公众号文。允许并鼓励：
   语气词、省略主语、错别字、网络用语、emoji、中英夹杂、不完整的句子、连续标点。
3. **每条都是独立的一条帖子**，不是对话、不是列表、不是标题。不要加话题标签以外的任何格式标记。
4. 不要在帖子里写编号、不要写「第一条」「示例」之类的元信息。

## 最重要的约束：族内词面错开

你每次收到的是**同一个「同义表达族」**——这一族里的所有帖子都在讲同一件事。
但检索评测要求它们在**字面上尽量不重合**：

- 目标是「人一看就知道在说同一件事，但两条帖子几乎不共用实词」。
- 具体配比：**约四分之一**的帖子可以直接使用该话题最显然的那几个词（这些是词面检索能命中的样本）；
  **其余四分之三必须回避那些显然的词**，改用同义说法、上位词、具体场景描述、比喻、
  或者干脆只描述现象和感受而不点名。
- 反例（不要这样）：每条都出现同一个关键词，只是前后句子换了换。
- 正例：
    直接说：「中介张口就要一个月房租的服务费，这合理吗」
    回避说：「最后砍到半个月，签合同前一定要谈」
    回避说：「带看三次就想收我小半个月工资，离谱」
  三条都在说同一件事，实词几乎不重合。

## 多样性

同一族内要在这些维度上都拉开差距：句子长度、语气（抱怨/求助/分享/自嘲/陈述）、
人称、有无 emoji、是否中英夹杂、结尾是否带问句。不要写成同一个模板的变体。
"""

SYSTEM_RESONANCE_EXTRA = """\

## 这一族属于「共鸣类」，额外要求

- 允许并且应当表达真实的负面情绪：疲惫、孤独、焦虑、失落、麻木。
- 写的是**处境和感受**，不是解决方案。不要给建议、不要正能量收尾、不要「但是我会加油的」。
- ⚠️ 安全边界（硬性）：可以表达「撑不下去」「不想动」这类情绪状态，
  但**不得包含任何具体的自伤或轻生方法、计划、工具、时间**，也不得以鼓励、美化、
  或提供指引的方式书写。只写情绪，不写行为指引。
"""

USER_TEMPLATE = """\
请生成 {n} 条帖子，**每条 60–200 字（不含标点），至少含两个具体细节**。

- 所属话题簇：{cluster_name}
- 本族讲的事：{family}——{family_desc}
- 可用文体（请在这 {n} 条里混合使用，不要集中在一两种）：{styles}

提醒：约 {direct_n} 条可以直接使用该话题最显然的词，其余 {indirect_n} 条必须回避那些词，
用别的说法表达同一件事。
"""

STYLE_BLOCK_TMPL = """

## 语感参考

下面是这个平台上的真实帖子。仔细体会它们的**语言风格**：
句子的节奏与断句方式、标点习惯（包括不规范的连续标点）、语气词、
省略主语的方式、错别字与网络用语出现的自然程度、emoji 的位置和密度、
中英夹杂时的自然程度。

生成时要贴近这种语感——比起「工整」，更要像真人随手写的。

⚠️ **两条例外，样本在这两点上一律不作数：**

1. **不要模仿长度。** 这些样本普遍比你要写的短得多。
   每条的长度以前面「60–200 字」的硬性要求为准，与样本无关。
   写够长度的办法是**多给一个具体的细节、动作或场景**，
   不是把句子拉长、也不是加解释和总结——那会立刻失去这种语感。
2. **不要沿用话题、事件、人物或任何具体信息。**
   你要写的话题由后面的指令给出，与这些样本无关。

{samples}
"""

_URL = re.compile(r"https?://\S+|\bt\.cn/\w+")
_MENTION = re.compile(r"@[\w\u4e00-\u9fff\-]{1,30}")


def load_style_samples(path: Path, limit: int = 40) -> tuple[str, int]:
    """读取风格锚点样本。

    格式：**空行分隔**，一条微博一块，块内可以有换行。`#` 开头的行是注释。
    真实微博经常自带换行，而换行本身就是要模仿的风格特征之一，所以保留块内换行。

    也兼容 JSONL：若首个非注释行以 `{` 开头，则按每行一条 JSON 处理（取 content）。

    会**自动剥掉 @提及和 URL**——这类样本多半来自用户自己的账号导出，
    里面的 @好友 是第三方个人信息，不该进 prompt 也不该发给 API。
    这是防护性措施，不替代你自己过一遍（正文里的人名剥不掉）。
    """
    lines = [l for l in path.read_text(encoding="utf-8").splitlines()
             if not l.lstrip().startswith("#")]

    if next((l for l in lines if l.strip()), "").lstrip().startswith("{"):
        blocks = []
        for l in lines:
            if l.strip():
                try:
                    blocks.append(json.loads(l).get("content", ""))
                except json.JSONDecodeError:
                    pass
    else:
        blocks = re.split(r"\n\s*\n", "\n".join(lines))

    out, scrubbed = [], 0
    for b in blocks:
        b = b.strip()
        if not b:
            continue
        cleaned = _MENTION.sub("", _URL.sub("", b)).strip()
        if cleaned != b:
            scrubbed += 1
        if cleaned:
            out.append(cleaned)

    if len(out) > limit:
        print(f"⚠️ 风格样本 {len(out)} 条，只取前 {limit} 条（过多会撑大每个请求的前缀）")
        out = out[:limit]
    block = "\n\n".join(f"样本 {i}：\n{t}" for i, t in enumerate(out, 1))
    return STYLE_BLOCK_TMPL.format(samples=block), scrubbed


SCHEMA = {
    "type": "object",
    "properties": {
        "posts": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "content": {"type": "string"},
                    "style": {"type": "string"},
                },
                "required": ["content", "style"],
                "additionalProperties": False,
            },
        }
    },
    "required": ["posts"],
    "additionalProperties": False,
}


def build_system(layer: str, style_block: str = "") -> list[dict]:
    """稳定前缀放最前并打缓存断点；所有请求共用同一份 system。

    风格样本放在缓存断点**之内**——它对所有请求都一样，而且能把前缀撑过
    最低可缓存长度（Opus 5 是 512 token，Sonnet 5 是 1024，原 system 约 700 偏悬）。
    """
    text = SYSTEM_STABLE
    if layer == "resonance":
        text += SYSTEM_RESONANCE_EXTRA
    text += style_block
    return [{"type": "text", "text": text, "cache_control": {"type": "ephemeral"}}]


def build_requests(model: str, only: set[str] | None, style_block: str = "") -> list[Request]:
    reqs = []
    for f in iter_families():
        fam_id = f'{f["cluster"]}-{f["family_idx"]}'
        if only and f["cluster"] not in only and fam_id not in only:
            continue
        direct_n = max(1, round(f["n"] / 4))
        user = USER_TEMPLATE.format(
            n=f["n"],
            cluster_name=f["cluster_name"],
            family=f["family"],
            family_desc=f["family_desc"],
            styles="、".join(STYLES[f["layer"]]),
            direct_n=direct_n,
            indirect_n=f["n"] - direct_n,
        )
        params = {
            "model": model,
            "max_tokens": MAX_TOKENS,
            "system": build_system(f["layer"], style_block),
            "messages": [{"role": "user", "content": user}],
            "output_config": {"format": {"type": "json_schema", "schema": SCHEMA}},
        }
        # Opus 5 / Sonnet 5 默认自适应思考；Haiku 4.5 不支持 adaptive，跳过。
        if not model.startswith("claude-haiku"):
            params["thinking"] = {"type": "adaptive"}

        reqs.append(Request(
            custom_id=f'{f["cluster"]}-{f["family_idx"]}',
            params=MessageCreateParamsNonStreaming(**params),
        ))
    return reqs


# ---------------------------------------------------------------- commands

# Batch API 为标准价的 50%（$/1M tokens）
PRICING = {
    "claude-opus-5":   (5.00, 25.00),
    "claude-sonnet-5": (2.00, 10.00),
    "claude-haiku-4-5": (1.00, 5.00),
}


def estimate_cost(reqs, model: str, total_posts: int, no_batch: bool = False) -> str:
    """成本估算。三个口径都容易低估，这里逐项说明：

    - 中文分词：Claude 的分词器对中文约 0.8 token/字，不是 0.5。
    - 可见输出：每条帖子正文 ~120 字 + JSON 包装 ~15 token。
    - **思考 token**：adaptive thinking 的思考 token 按输出计费。本任务有硬约束
      （族内词面错开 + 文体分布），模型会认真想，每请求按 2000 token 估。
      这一项最容易漏，也是总价的大头。
    """
    rate = PRICING.get(model)
    if not rate:
        return "（未知模型，无法估算）"
    mult = 1.0 if no_batch else 0.5                      # Batch API 五折
    in_rate, out_rate = rate[0] * mult, rate[1] * mult

    chars = sum(len(r["params"]["system"][0]["text"]) +
                len(r["params"]["messages"][0]["content"]) for r in reqs)
    in_tok = chars * 0.8
    visible = total_posts * 135
    thinking = len(reqs) * 2000 if any("thinking" in r["params"] for r in reqs) else 0

    cost = in_tok / 1e6 * in_rate + (visible + thinking) / 1e6 * out_rate
    lo, hi = cost * 0.8, cost * 1.5      # 思考量的不确定性

    return (f"约 ${cost:.2f}（区间 ${lo:.2f}–${hi:.2f}）\n"
            f"    输入 ~{in_tok/1000:.0f}K token = ${in_tok/1e6*in_rate:.2f}\n"
            f"    可见输出 ~{visible/1000:.0f}K = ${visible/1e6*out_rate:.2f}\n"
            f"    思考 token ~{thinking/1000:.0f}K = ${thinking/1e6*out_rate:.2f}  ← 不确定性主要在这\n"
            f"    （{'并发直调，原价' if no_batch else '均已含 Batch API 五折'}）")


def cmd_submit(args) -> str:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    only = set(args.only.split(",")) if args.only else None

    style_block = ""
    if args.style_samples:
        path = Path(args.style_samples)
        if not path.exists():
            sys.exit(f"找不到风格样本文件 {path}")
        style_block, scrubbed = load_style_samples(path)
        n = style_block.count("样本 ")
        print(f"风格锚点：{n} 条样本（{len(style_block)} 字符），已剥离 {scrubbed} 条中的 @提及/URL")

    reqs = build_requests(args.model, only, style_block)
    if not reqs:
        sys.exit("没有匹配的族，检查 --only 参数")

    total = sum(f["n"] for f in iter_families()
                if not only or f["cluster"] in only
                or f'{f["cluster"]}-{f["family_idx"]}' in only)
    print(f"提交 {len(reqs)} 个请求（{total} 条帖子），模型 {args.model}")
    print(f"成本估算：{estimate_cost(reqs, args.model, total, getattr(args, 'no_batch', False))}")

    if getattr(args, "dry_run", False):
        print("\n--- dry-run：不提交。首个请求的 user prompt ---")
        print(reqs[0]["params"]["messages"][0]["content"])
        sys_text = reqs[0]["params"]["system"][0]["text"]
        print(f"--- system 长度 {len(sys_text)} 字符（所有请求共用，已打缓存断点）---")
        if style_block:
            print("--- system 尾部的语感参考（核对有没有漏掉的个人信息）---")
            print(style_block[-600:])
        return ""

    if getattr(args, "no_batch", False):
        cmd_concurrent(args, reqs, total, merge=bool(only))
        return ""

    client = anthropic.Anthropic()
    batch = client.messages.batches.create(requests=reqs)

    state = {"batch_id": batch.id, "model": args.model, "n_requests": len(reqs),
             "expected_posts": total, "partial": bool(only)}
    STATE_FILE.write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"批次 ID: {batch.id}（已存入 {STATE_FILE.relative_to(ROOT)}）")
    print(f"状态: {batch.processing_status}")
    return batch.id


def write_manifest(results: dict[str, list[dict]], failures: list, expected: int,
                   merge: bool = False) -> None:
    """把 {custom_id: [post,...]} 落成 manifest.jsonl。batch 与并发两条路径共用。

    merge=True 时保留 manifest 中**本次未重新生成的**族，只替换本次产出的部分。
    局部重跑（--only / --only-family）必须开启，否则会把其余 90 多个族的数据抹掉。
    """
    meta = {f'{f["cluster"]}-{f["family_idx"]}': f for f in iter_families()}
    rows = []
    for cid, posts in results.items():
        f = meta.get(cid)
        if f is None:
            failures.append((cid, "未知 custom_id"))
            continue
        for i, p in enumerate(posts):
            rows.append({
                "gen_id": f'{f["cluster"]}-{f["family_idx"]}-{i:03d}',
                "layer": f["layer"],
                "cluster": f["cluster"],
                "cluster_name": f["cluster_name"],
                "family": f["family"],
                "style": p.get("style", ""),
                "content": p["content"].strip(),
            })
    n_new = len(rows)
    if merge and MANIFEST.exists():
        regenerated = set(results)          # 本次产出的族，用 custom_id 标识
        kept = [r for l in MANIFEST.read_text(encoding="utf-8").splitlines() if l.strip()
                for r in [json.loads(l)]
                if f'{r["cluster"]}-{r["gen_id"].split("-")[1]}' not in regenerated]
        print(f"  合并：保留原有 {len(kept)} 条 + 本次 {len(rows)} 条")
        rows = kept + rows

    rows.sort(key=lambda r: r["gen_id"])
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    with MANIFEST.open("w", encoding="utf-8") as fh:
        for r in rows:
            fh.write(json.dumps(r, ensure_ascii=False) + "\n")

    print(f"\n写入 {MANIFEST.relative_to(ROOT)}：{len(rows)} 条")
    if merge:
        print(f"（本次生成 {n_new} 条 / 预期 {expected}，其余为合并保留）")
    else:
        print(f"预期 {expected} 条，差值 {len(rows) - expected:+d}")
    if failures:
        print(f"\n⚠️ {len(failures)} 个请求未产出：")
        for cid, why in failures[:20]:
            print(f"   {cid}: {why}")
        print("   可用 --only 重跑这些簇，结果会覆盖 manifest，注意先备份")
    print("\n下一步：python validate.py")


def parse_posts(msg) -> list[dict]:
    text = next((b.text for b in msg.content if b.type == "text"), None)
    if not text:
        raise ValueError("响应里没有 text block")
    return json.loads(text)["posts"]


def cmd_concurrent(args, reqs, expected: int, merge: bool = False) -> None:
    """不走 Batch API：并发直调。约两倍价钱，换可预期的完成时间。"""
    from concurrent.futures import ThreadPoolExecutor, as_completed
    client = anthropic.Anthropic()
    results, failures = {}, []

    def work(r):
        return r["custom_id"], parse_posts(client.messages.create(**r["params"]))

    print(f"并发直调，{args.workers} 个 worker…")
    with ThreadPoolExecutor(max_workers=args.workers) as ex:
        futs = {ex.submit(work, r): r["custom_id"] for r in reqs}
        for n, fut in enumerate(as_completed(futs), 1):
            try:
                cid, posts = fut.result()
                results[cid] = posts
            except Exception as e:
                failures.append((futs[fut], f"{type(e).__name__}: {e}"))
            print(f"  {n}/{len(reqs)} 完成（成功 {len(results)} 失败 {len(failures)}）", flush=True)

    write_manifest(results, failures, expected, merge=merge)


def cmd_fetch(args) -> None:
    if not STATE_FILE.exists():
        sys.exit(f"找不到 {STATE_FILE}，先跑 submit")
    state = json.loads(STATE_FILE.read_text(encoding="utf-8"))
    batch_id = state["batch_id"]
    client = anthropic.Anthropic()

    while True:
        batch = client.messages.batches.retrieve(batch_id)
        if batch.processing_status == "ended":
            break
        c = batch.request_counts
        print(f"  {batch.processing_status} | 处理中 {c.processing} "
              f"成功 {c.succeeded} 失败 {c.errored}", flush=True)
        time.sleep(args.interval)

    c = batch.request_counts
    print(f"批次结束：成功 {c.succeeded} / 失败 {c.errored} / "
          f"取消 {c.canceled} / 过期 {c.expired}")

    results, failures = {}, []
    for res in client.messages.batches.results(batch_id):
        if res.result.type != "succeeded":
            detail = getattr(getattr(res.result, "error", None), "type", res.result.type)
            failures.append((res.custom_id, detail))
            continue
        try:
            results[res.custom_id] = parse_posts(res.result.message)
        except (ValueError, json.JSONDecodeError, KeyError) as e:
            failures.append((res.custom_id, f"解析失败: {e}"))

    write_manifest(results, failures, state["expected_posts"], merge=state.get("partial", False))


def main():
    ap = argparse.ArgumentParser(description="靶区语料合成器")
    sub = ap.add_subparsers(dest="cmd", required=True)

    s = sub.add_parser("submit", help="提交批次")
    s.add_argument("--model", default=DEFAULT_MODEL)
    s.add_argument("--only", help="只跑这些簇或族，逗号分隔。"
                                  "簇如 R01,E12；族如 R08-0,R02-1（族粒度更省钱）")
    s.add_argument("--dry-run", action="store_true", help="只打印 prompt 和成本，不提交")
    s.add_argument("--style-samples", metavar="FILE",
                   help="真实帖子作为语感锚点（空行分隔，或 JSONL）。"
                        "只用于模仿语言风格，不会沿用其话题")
    s.add_argument("--no-batch", action="store_true",
                   help="不走 Batch API，并发直调。约两倍价钱，换可预期的完成时间")
    s.add_argument("--workers", type=int, default=8, help="--no-batch 时的并发数")

    f = sub.add_parser("fetch", help="轮询并落地 manifest")
    f.add_argument("--interval", type=int, default=60, help="轮询间隔秒数")

    r = sub.add_parser("run", help="submit + fetch")
    r.add_argument("--model", default=DEFAULT_MODEL)
    r.add_argument("--only")
    r.add_argument("--style-samples", metavar="FILE")
    r.add_argument("--no-batch", action="store_true")
    r.add_argument("--workers", type=int, default=8)
    r.add_argument("--interval", type=int, default=60)

    args = ap.parse_args()
    if args.cmd == "submit":
        cmd_submit(args)
    elif args.cmd == "fetch":
        cmd_fetch(args)
    else:
        cmd_submit(args)
        cmd_fetch(args)


if __name__ == "__main__":
    main()
