# -*- coding: utf-8 -*-
"""噪声层抽取器（CORPUS_DESIGN.md Step 2 / §5）。

数据源：Leiden Weibo Corpus，CC BY-NC-SA 3.0，2012 年 1 月采集。
输入 `LWC-messages/parsed_messages.txt`（2.3GB，MySQL OUTFILE 格式，9 列 CSV）。

⚠️ 本脚本的产物受 CC BY-NC-SA 约束，输出目录 `corpus/` 已在 .gitignore 中，
   不要把派生文本提交到公开仓库。

用法：
    python extract_noise.py --scan 1000000     # 先小规模试跑看留存
    python extract_noise.py                    # 全量扫描，抽 900 条
"""
from __future__ import annotations

import argparse
import csv
import json
import random
import re
import sys
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "LWC-messages" / "parsed_messages.txt"
OUT_DIR = ROOT / "corpus"
OUT = OUT_DIR / "noise_candidates.jsonl"

MIN_LEN, MAX_LEN = 30, 200      # 下限 30：≥60 只覆盖真实微博最长的 19%，
                                # 且长文档天然利好词面检索，与本项目论点相悖。
                                # 抽样阶段再按靶区实际分布做分层匹配，见 sample_noise.py
TARGET = 900
POOL_MULT = 15                  # 池子要够大，分层抽样才有各长度段的余量

csv.field_size_limit(10 ** 7)

# ---------------------------------------------------------------- 清洗

URL = re.compile(r"https?://\S+|\bt\.cn/\w+")
MENTION = re.compile(r"@[\w一-鿿\-]{1,30}")
RETWEET = re.compile(r"//@.*$", re.S)
KEEP = re.compile(r"[一-鿿0-9a-zA-Z]+")


def clean(text: str) -> str:
    text = RETWEET.sub("", text)      # 砍掉转发链，只保留本人写的那段
    text = URL.sub("", text)
    text = MENTION.sub("", text)
    return re.sub(r"\s+", " ", text).strip()


def visible_len(text: str) -> int:
    return len("".join(KEEP.findall(text)))


# ---------------------------------------------------------------- 主题中立过滤
#
# CORPUS_DESIGN.md §5「硬约束一」：噪声层混入情绪表达类内容会与共鸣类 R01–R12
# 语义重叠，制造假阴性，且系统性打压向量检索。宁可过度排除——候选池有百万级，
# 只需要 900 条，召回率不重要，纯度才重要。

BLOCK = {
    "R01 职场": "上班 加班 辞职 离职 裸辞 老板 领导 同事 公司 工位 打工 请假 上司 职场 跳槽 简历 面试 绩效 出差 下班",
    "R02 孤独": "孤独 寂寞 孤单 没人 陪我 一个人 无人 冷清",
    "R03 亲密": "爱情 分手 失恋 想你 暧昧 异地 表白 单身 恋爱 男朋友 女朋友 前男友 前女友 老公 老婆 喜欢你 爱你 相爱 情人",
    "R04 家庭": "催婚 相亲 爸妈 父母 亲戚 婆婆 岳母",
    "R05 身材": "减肥 体重 身材 长胖 变胖 瘦了 丑",
    "R06 年龄": "年纪 老了 青春 年华 岁月",
    "R07 失眠": "失眠 熬夜 睡不着 凌晨 通宵 黑眼圈",
    "R08 学业": "论文 导师 毕业 挂科 延毕 答辩 开题 复习 考研",
    "R09 漂泊": "搬家 漂泊 故乡 家乡 异乡 出租屋 归属",
    "R10 宠物": "去世 离世 火化 安乐死 走好",
    "R11 吐槽": "开会 周报 汇报 需求 甲方 加班费",
    "R12 金钱": "没钱 月光 剁手 工资 存钱 穷死 账单 分期",
    "通用情绪": ("哭 泪 难过 伤心 崩溃 痛苦 绝望 压力 焦虑 抑郁 委屈 心疼 难受 无助 "
                 "叹气 想死 受不了 撑不住 坚持不下去 好累 心酸 失落 迷茫 烦躁 郁闷 "
                 "心情不好 不开心 emo 治愈 温暖 感动 眼泪"),
}
# 经验类 E01–E12 同样要排除：噪声层若含租房/育儿/数码等内容，经验类 query 会召回
# 真正相关却不在 ground truth 里的帖子——与共鸣类是同一个假阴性机制。
BLOCK["E01 租房"] = "租房 房东 中介 押金 退租 合租 房租 看房"
BLOCK["E02 求职"] = "求职 招聘 offer 应聘 转正 试用期 背调 投简历"
BLOCK["E03 考学"] = "考研 考公 择校 调剂 行测 申论 上岸 分数线"
BLOCK["E04 通勤"] = "通勤 地铁 公交 堵车 电动车 路况 限行"
BLOCK["E05 数码"] = "手机 电脑 笔记本 耳机 内存 续航 像素 充电 数码 平板"
BLOCK["E06 就医"] = "挂号 医院 医生 门诊 医保 体检 看病 报销 专家号"
BLOCK["E07 育儿"] = "宝宝 婴儿 新生儿 幼儿园 孩子 辅食 奶粉 疫苗 育儿 母乳 补钙 小孩 儿童"
BLOCK["E08 装修"] = "装修 甲醛 水电 全包 半包 瓷砖 硬装 软装"
BLOCK["E09 养宠"] = "绝育 驱虫 猫藓 寄养 养猫 养狗 铲屎 宠物 疫苗"
BLOCK["E10 理财"] = "理财 基金 定期 国债 存单 收益率 记账 储蓄"
BLOCK["E11 旅行"] = "签证 机票 攻略 青旅 民宿 行程 自由行 出境"
BLOCK["E12 技术"] = "分布式 redis 数据库 服务器 代码 接口 并发 缓存 部署 开发者"

BLOCK["鸡汤体"] = ("人生 生命 珍惜 幸福 感悟 无奈 忧伤 烦恼 心灵 人的一生 生活就像 "
                  "释怀 淡然 懂得 成长 回忆 遗憾 曾经 时光 命运 缘分 陪伴 "
                  "淡漠 纠缠 深情 余恨 恨 温柔 心里 内心 灵魂 真心 用心")
BLOCK_TERMS = [(tag, t) for tag, s in BLOCK.items() for t in s.split()]

# 繁体检测：LWC 含大量港台用户。屏蔽词表是简体的，繁体内容会整体绕过过滤
# （实测漏网样本「爲什麽我還會感覺心痛」「我愛你」全是繁体）。
# 与其为每个词维护繁简两套，不如直接丢弃繁体——2012 年港台微博的用语和
# 简体圈差异也大，混进来反而破坏语料一致性。候选池百万级，丢得起。
TRAD = set("麽爲個這說會來國對時們後東車見買賣愛過還進開關學實樣點兒頭萬與專業"
           "種覺無讓認識語話請謝誰讀課說談論記訴講證評詞試該詳議習題點線體總"
           "統應將導師開關問間聞題顯歲當種積稱穩積興舉處備復雜")


def is_traditional(text: str, threshold: int = 2) -> bool:
    return len(TRAD & set(text)) >= threshold


def blocked(text: str) -> str | None:
    if is_traditional(text):
        return "繁体:繁体"
    for tag, term in BLOCK_TERMS:
        if term in text:
            return f"{tag}:{term}"
    return None


# ---------------------------------------------------------------- 主流程

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--scan", type=int, help="只扫描前 N 行（试跑用）")
    ap.add_argument("--target", type=int, default=TARGET)
    ap.add_argument("--seed", type=int, default=20260918)
    args = ap.parse_args()

    if not SRC.exists():
        sys.exit(f"找不到 {SRC}")

    rng = random.Random(args.seed)
    pool_size = args.target * POOL_MULT
    reservoir: list[dict] = []
    seen_kept = 0
    stats = Counter()
    block_reasons = Counter()

    with SRC.open(encoding="utf-8", errors="replace", newline="") as fh:
        for row in csv.reader(fh):
            if args.scan and stats["total"] >= args.scan:
                break
            stats["total"] += 1
            if stats["total"] % 500_000 == 0:
                print(f"  ...已扫 {stats['total']:,} 行，留存 {seen_kept:,}", flush=True)

            if len(row) < 9:
                stats["字段异常"] += 1
                continue
            raw = row[5]
            if row[8] == r"\N" or raw.isdigit():
                stats["纯转发无正文"] += 1
                continue

            text = clean(raw)
            n = visible_len(text)
            if n < MIN_LEN:
                stats["清洗后过短"] += 1
                continue
            if n > MAX_LEN:
                stats["清洗后过长"] += 1
                continue

            why = blocked(text)
            if why:
                stats["主题中立过滤剔除"] += 1
                block_reasons[why.split(":")[0]] += 1
                continue

            stats["通过"] += 1
            # 蓄水池抽样：LWC 按 message_id 排序而 id 与时间相关，
            # 取前 N 条会得到一个很窄的时间窗口，必须随机抽
            seen_kept += 1
            item = {"message_id": row[0], "gender": row[3], "content": text, "len": n}
            if len(reservoir) < pool_size:
                reservoir.append(item)
            else:
                j = rng.randrange(seen_kept)
                if j < pool_size:
                    reservoir[j] = item

    # ---- 报告 ----
    print(f"\n=== 扫描结果（{stats['total']:,} 行）===")
    for k, v in stats.most_common():
        if k != "total":
            print(f"  {k:<16} {v:>9,}  {v / stats['total']:6.2%}")
    print(f"\n=== 主题中立过滤命中分布 ===")
    for tag, c in block_reasons.most_common():
        print(f"  {tag:<12} {c:>8,}")

    # 去重：LWC 含大量营销模板（实测「微三国」等推广文案重复出现）。
    # 近重复文档在检索评测里等同于同一篇被计了多次，会扭曲 top-k 的有效多样性。
    rng.shuffle(reservoir)
    bg = lambda t: {t[i:i + 2] for i in range(len(t) - 1)}
    final, kept_bg = [], []
    for it in reservoir:
        g = bg("".join(KEEP.findall(it["content"])))
        if any(len(g & k) / max(1, len(g | k)) > 0.5 for k in kept_bg):
            stats["近重复剔除"] += 1
            continue
        final.append(it)
        kept_bg.append(g)
        if len(final) >= pool_size:
            break
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    with OUT.open("w", encoding="utf-8") as fh:
        for i, it in enumerate(final):
            fh.write(json.dumps({
                "gen_id": f"N-{i:04d}",
                "layer": "noise",
                "cluster": None,
                "cluster_name": None,
                "family": None,
                "style": "",
                "content": it["content"],
                "source": "LWC",
                "source_id": it["message_id"],
            }, ensure_ascii=False) + "\n")

    print(f"\n蓄水池 {len(reservoir):,} → 去重后候选 {len(final):,} 条（目标 {args.target}）")
    print(f"写入 {OUT.relative_to(ROOT)}")
    if len(final) < args.target * 1.5:
        print(f"⚠️ 候选不足目标的 1.5 倍，LLM 复筛后可能凑不够，考虑放宽过滤")
    print("\n下一步：python llm_filter_noise.py  （关键词过滤挡不住语义近似，必须复筛）")


if __name__ == "__main__":
    main()
