package com.brayton.weibo.embedding;

/**
 * 向量化失败（重试耗尽、不可重试的 4xx、响应不合预期）。
 * 这是基础设施错误而非业务错误，所以不走 WeiboException 的错误码体系。
 */
public class EmbeddingException extends RuntimeException {

    public EmbeddingException(String message) {
        super(message);
    }

    public EmbeddingException(String message, Throwable cause) {
        super(message, cause);
    }
}
