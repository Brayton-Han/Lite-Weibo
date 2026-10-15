# 评测集构造（Day 8）

docs/RAG_ROADMAP.md **Day 8** 的实现：产出 80 条 query + 分级相关性标注，切成 train / test。

## 流程

```bash
cd tools/eval_build
PY=../corpus_gen/.venv/bin/python        # 与语料合成共用一个 venv（只依赖 anthropic）

# 1–2. 分层抽 150 条种子帖，导出撰写任务；query 由 Claude Code 会话直接写，不调 API
$PY gen_queries.py prepare               # → eval/work/query_task.md
#      在 Claude Code 里：「按 eval/work/query_task.md 写 query」→ eval/work/query_responses.jsonl
$PY gen_queries.py import                # 校验覆盖与格式 → eval/work/query_candidates.tsv
# 3. 人工：在 query_candidates.tsv 的 keep 列填 1，按配额选 80 条
$PY gen_queries.py finalize

# 4. pooling（需要 PostgreSQL 与 VOYAGE_API_KEY；backend 容器占着 8080 时加端口参数）
cd ../../backend && EVAL_POOL=true ./mvnw spring-boot:run -Dspring-boot.run.profiles=tools \
    -Dspring-boot.run.arguments=--server.port=0
cd ../tools/eval_build

# 5. 标注：Claude 标全部候选池（~$5，可断点续跑），人工标导出的表
$PY label.py claude --dry-run
$PY label.py claude
$PY label.py sheet                       # → eval/work/human_sheet.tsv，在 grade 列填 0/1/2

# 验收：一致率 ≥ 80%、共鸣类 κ ≥ 0.6 → eval/AGREEMENT.md
$PY agreement.py
# 合并（人工优先）+ 按类型分层切分 → eval/train/、eval/test/、eval/qrels.tsv
$PY split.py
```

## 文件

| 文件 | 作用 |
|---|---|
| `gen_queries.py` | 种子帖抽样与撰写任务导出、候选 query 校验、人工筛选后的配额校验与编号（不调 API） |
| `label.py` | Claude 标注候选池；导出人工标注表 |
| `agreement.py` | 人机一致率、Cohen's κ（含线性加权）、混淆矩阵 |
| `split.py` | 合并标注，校验验收标准，train / test 切分 |
| `backend/.../tools/EvalPoolRunner.java` | 候选池构造（在后端跑，与被测检索同一套代码） |
| `eval/LABELING_GUIDE.md` | 标注指南，**人工与 Claude 共用同一份** |

## 几个设计决定

### 候选池的六个来源

`like`（原样的线上搜索）、`trgm`（`word_similarity` 排序）、`vector`（语义搜索同一条 SQL）、`random`
各取 top-20，再加种子帖与它的**同义表达族**。前四个是检索系统；后两个来自语料元数据，
专门补上所有检索系统都漏掉的相关帖——否则池子的上限就是被测系统召回的并集，Recall 会系统性虚高。

pooling 在后端跑而不是在 Python 里重写 SQL：Day 9 测的就是这几条检索路径，候选池必须和被测系统同口径。

### 双盲

Claude 与人工都看不到来源、排名、种子帖、族，候选顺序按 qid 定种子打乱；人工表不含 Claude 的分数。
所以 30% 抽检也是盲标，而不是「看着 Claude 的答案点确认」——后者测不出一致率。

### 人工工作量

默认整池亲手标 20 条 query + 其余 60 条各抽 30%，按每池 ~60 条候选估算约 **2,300 对**。
`label.py sheet --full / --spot` 可调；共鸣类至少整池 6 条，保证 κ 有足够样本。

### 只含 ID 的产物才进 git

候选池与标注表含帖子正文（噪声层是 LWC 派生文本，CC BY-NC-SA），放在已忽略的 `eval/work/`。
最终产物只有 query 文本与 `post_id`。`post_id` 由导入时的数据库分配，所以 `qrels.tsv` 同时记了 `gen_id`，重建数据库后可据此重新映射。
