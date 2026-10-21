# 靶区语料合成器

docs/CORPUS_DESIGN.md **Step 2** 的实现：生成 2100 条靶区语料（经验类 900 + 共鸣类 1200）。
噪声层 900 条不走这里，来自 Leiden Weibo Corpus（见 docs/CORPUS_DESIGN.md §5）。

## 文件

| 文件 | 作用 |
|---|---|
| `clusters.py` | 24 簇 × 4 族的定义，是 docs/CORPUS_DESIGN.md §3/§4 的可执行副本 |
| `generate.py` | Batch API 提交 / 轮询 / 落地 `corpus/manifest.jsonl` |
| `validate.py` | 长度、族内词面重叠、近重复的机械校验 |

## 用法

```bash
pip install -r requirements.txt
export ANTHROPIC_API_KEY=...          # 或先跑 ant auth login

python generate.py submit --dry-run   # 看 prompt 和成本，不花钱
python generate.py submit             # 提交（~$5，Opus 5，已含 batch 五折）
python generate.py fetch              # 轮询 + 落地 manifest
python validate.py                    # 校验，不过则退出码 1

python validate.py --show-pairs       # 人工抽检零重叠样本对
python generate.py submit --only R01  # 只重跑某几个簇

python generate.py submit --style-samples my_posts.txt   # 带语感锚点
```

## 语感锚点（`--style-samples`）

合成语料最大的弱点是「LLM 腔」——句式太齐整，缺少真人随手写的破碎感。
传一批**真实帖子**当语感参考可以显著改善这一点。

```
# my_posts.txt：用【空行】分隔每条，# 开头为注释
# 块内可以有换行 —— 微博本来就常带换行，这本身就是要模仿的风格特征

今天地铁又坏了。。。站台挤成沙丁鱼罐头 迟到第三次了[允悲]

改了一下午的方案
刚发出去
甲方说还是第一版好

散了
```

也兼容 JSONL：若首个非注释行以 `{` 开头，则按每行一条 JSON 处理（取 `content` 字段）。

20–40 条就够。三点说明：

1. **只模仿风格，不沿用话题。** prompt 里有显式约束，样本的话题、事件、人物都不会被沿用。
2. **自动剥离 @提及和 URL。** 这类样本多半来自个人账号导出，里面的 @好友 是第三方
   个人信息，不该进 prompt 也不该发给 API。这是防护性措施，**不替代你自己过一遍**；
   `--dry-run` 会打印 system 尾部供你核对。
3. **放在缓存断点之内。** 样本对所有请求都一样，放进去不增加边际成本，
   还能把前缀撑过最低可缓存长度（Opus 5 是 512 token，Sonnet 5 是 1024，
   原 system 约 700 token 在 Sonnet 上其实缓存不了）。

⚠️ 如果样本来自你自己的微博：内容著作权是你的，但**获取方式仍受 ToS 约束**——
走官方数据导出或手动复制，不要写爬虫抓自己的主页。转发的内容不是你的原创，先剔掉。

输出落在仓库根的 `corpus/`。只有合成的 `manifest.jsonl` 进仓库，其余都在 `.gitignore` 中——LWC 是
**CC BY-NC-SA**，派生文本进了公开仓库会被卷入同一许可（docs/CORPUS_DESIGN.md §5）。

## 两个设计决定

### 一个族一个请求

96 个族 = 96 个请求，每个请求一次生成该族全部 ~20 条。

逐条生成拿不到「族内词面错开」这个约束——模型看不到自己刚写过什么，
只会反复产出同一个句式的变体。一次性给它整族的额度，它才能主动错开用词。

### 四分之一直接、四分之三回避

prompt 里要求每族约 1/4 的帖子直接使用该话题最显然的词，其余 3/4 必须回避。

两边都需要：
- 直接的那部分是**词面检索能命中的样本**——没有它，Day 8 的「关键词直搜」query 无从标注，
  而且 `LIKE` 基线会低到不真实
- 回避的那部分是**向量检索的证明区**——没有它，混合检索的增益测不出来

## 校验口径

`validate.py` 用**字符 bigram 的 Jaccard**，不是 jieba 分词。

原因：本项目的词面基线是 `LIKE '%q%'` 和 tsvector，对中文而言它们匹配的是字符子串。
字符 n-gram 比分词更忠实于「词面检索到底能看到什么」。

| 阈值 | 含义 |
|---|---|
| Jaccard < 0.05 | 视为「词面零重叠」 |
| Jaccard > 0.50 | 近重复，必须重生成 |
| 每簇 ≥ 3 组零重叠对 | Day 3 验收标准 |

## 已知偏差

靶区长度限定 60–200 字，比真实微博偏长（真实中位数约 20–50 字）。

这是有意的：过短的帖子检索信号不足，单向量不稳定。
**LWC 噪声层按同一口径过滤**，所以长度在三层之间分布一致——
否则「长度」本身会变成与相关性相关的泄漏特征，让任何偏好长文档的方法虚高。

代价是整个语料比真实微博偏长，需要在 `EVAL.md` 里记一笔。
