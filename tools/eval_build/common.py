# -*- coding: utf-8 -*-
"""评测集构造（Day 8）的共用部分：路径、JSONL 读写、并发调用 Claude。"""
from __future__ import annotations

import csv
import json
import sys
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from typing import Callable

import anthropic

ROOT = Path(__file__).resolve().parents[2]
CORPUS = ROOT / "corpus"
MANIFEST = CORPUS / "manifest.jsonl"
EVAL = ROOT / "eval"
WORK = EVAL / "work"            # 含帖子正文（其中有 LWC 派生文本），已在 .gitignore 中
GUIDE = EVAL / "LABELING_GUIDE.md"

DEFAULT_MODEL = "claude-sonnet-5"   # RAG_ROADMAP Day 8 指定

# 五种 query 类型与目标条数（RAG_ROADMAP Day 8）
QUOTAS = {"keyword": 15, "synonym": 20, "mixed": 10, "resonance": 25, "no_answer": 10}
# query 类型 → 它所服务的语料层；Day 9 按层分组报数要用
TYPE_LAYER = {"keyword": "experience", "synonym": "experience", "mixed": "experience",
              "resonance": "resonance", "no_answer": None}

# 标准价 $/1M token（并发直调，不走 Batch）
PRICING = {"claude-sonnet-5": (2.00, 10.00), "claude-opus-5": (5.00, 25.00),
           "claude-sonnet-5-5": (2.00, 10.00), "claude-opus-5-5": (4.00, 20.00)}


def read_jsonl(path: Path) -> list[dict]:
    if not path.exists():
        sys.exit(f"找不到 {path.relative_to(ROOT)}")
    return [json.loads(l) for l in path.read_text(encoding="utf-8").splitlines() if l.strip()]


def write_jsonl(path: Path, rows: list[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as fh:
        for r in rows:
            fh.write(json.dumps(r, ensure_ascii=False) + "\n")


def write_tsv(path: Path, header: list[str], rows: list[list]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as fh:
        w = csv.writer(fh, delimiter="\t", quoting=csv.QUOTE_MINIMAL)
        w.writerow(header)
        w.writerows(rows)


def read_tsv(path: Path) -> list[dict]:
    if not path.exists():
        sys.exit(f"找不到 {path.relative_to(ROOT)}")
    with path.open(encoding="utf-8", newline="") as fh:
        return list(csv.DictReader(fh, delimiter="\t"))


def bigram_jaccard(a: str, b: str) -> float:
    """字符 bigram Jaccard，与 corpus_gen/validate.py 同口径——更贴近 LIKE / trgm 实际看到的词面。"""
    def grams(s: str) -> set[str]:
        s = "".join(ch for ch in s.lower() if ch.isalnum())
        return {s[i:i + 2] for i in range(len(s) - 1)}
    ga, gb = grams(a), grams(b)
    return len(ga & gb) / len(ga | gb) if ga and gb else 0.0


def estimate_cost(model: str, in_chars: int, out_tokens: int) -> str:
    rate = PRICING.get(model)
    if not rate:
        return "（未知模型，无法估算）"
    in_tok = in_chars * 0.8                         # 中文约 0.8 token/字
    cost = in_tok / 1e6 * rate[0] + out_tokens / 1e6 * rate[1]
    return f"约 ${cost:.2f}（区间 ${cost * 0.8:.2f}–${cost * 1.6:.2f}，思考 token 是主要不确定性）"


def call_json(client: anthropic.Anthropic, model: str, system: str, user: str,
              schema: dict, max_tokens: int = 16000) -> dict:
    """一次结构化输出调用。system 打缓存断点：同一批请求共用同一份 system。"""
    params = {
        "model": model,
        "max_tokens": max_tokens,
        "system": [{"type": "text", "text": system, "cache_control": {"type": "ephemeral"}}],
        "messages": [{"role": "user", "content": user}],
        "output_config": {"format": {"type": "json_schema", "schema": schema}},
        "thinking": {"type": "adaptive"},
    }
    msg = client.messages.create(**params)
    if msg.stop_reason in ("refusal", "max_tokens"):
        raise ValueError(f"stop_reason={msg.stop_reason}")
    text = next((b.text for b in msg.content if b.type == "text"), None)
    if not text:
        raise ValueError("响应里没有 text block")
    return json.loads(text)


def run_concurrent(jobs: dict[str, Callable[[], object]], workers: int) -> tuple[dict, list]:
    """并发执行 {key: fn}，返回 (成功结果, 失败列表)。失败不中断其余任务，最后统一报告。"""
    results, failures = {}, []
    with ThreadPoolExecutor(max_workers=workers) as ex:
        futs = {ex.submit(fn): key for key, fn in jobs.items()}
        for n, fut in enumerate(as_completed(futs), 1):
            key = futs[fut]
            try:
                results[key] = fut.result()
            except Exception as e:      # noqa: BLE001 —— 汇总报告，不吞：调用方据此决定是否重跑
                failures.append((key, f"{type(e).__name__}: {e}"))
            print(f"  {n}/{len(jobs)} 完成（成功 {len(results)} 失败 {len(failures)}）", flush=True)
    return results, failures
