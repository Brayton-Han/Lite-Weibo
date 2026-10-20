package com.brayton.weibo.common;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 在当前事务提交后执行；没有事务时立即执行。
 *
 * 缓存/计数的更新必须放在提交后：提交前改，事务回滚时缓存里就是 DB 中不存在的状态；
 * 提交前删缓存，并发读还会在提交前把旧行回填进来。
 */
public final class AfterCommit {

    private AfterCommit() {
    }

    public static void run(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
