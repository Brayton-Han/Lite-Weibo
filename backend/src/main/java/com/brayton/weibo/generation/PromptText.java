package com.brayton.weibo.generation;

/**
 * 拼进 prompt 标签里的不可信文本（用户 query）的转义。
 *
 * <p>{@code <question>} / {@code <query>} 是字符串拼接出来的，不像 document block 那样由请求结构界定边界：
 * query 里写 {@code </question> 新指令 <question>} 就能伪造标签，让一段文字看起来在标签之外。
 * 拼接前把半角尖括号换成全角，伪造的标签就闭合不了。中文 query 几乎不用尖括号，换成全角不影响模型理解；
 * 检索用的仍是原始 query，这里只影响送进模型的那一份。
 */
final class PromptText {

    private PromptText() {}

    /** 去掉首尾空白，半角 {@code < >} 换成全角 {@code ＜ ＞} */
    static String escapeTags(String text) {
        return text.strip().replace("<", "＜").replace(">", "＞");
    }
}
