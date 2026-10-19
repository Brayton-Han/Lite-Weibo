package com.brayton.weibo.rerank;

/**
 * 重排失败（重试耗尽、不可重试的 4xx、响应不合预期）。
 * 与 {@code EmbeddingException} 一样是基础设施错误，不走 WeiboException 的错误码体系；
 * 线上由 {@code RerankStrategy} 接住并降级为第一阶段排序。
 */
public class RerankException extends RuntimeException {

    public RerankException(String message) {
        super(message);
    }

    public RerankException(String message, Throwable cause) {
        super(message, cause);
    }
}
