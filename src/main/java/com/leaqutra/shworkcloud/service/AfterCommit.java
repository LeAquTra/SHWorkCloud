package com.leaqutra.shworkcloud.service;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 把「删除 OSS 对象」这类不可回滚的远程操作推迟到事务提交之后执行。
 * <p>
 * 为什么需要它：删 OSS 是远程调用，数据库回滚<b>不会</b>撤销它。
 * 如果先删 OSS 再删数据库，一旦事务回滚，就会留下
 * 「数据库有索引、OSS 没有对象」的坏数据 —— 用户能看到文件，一打开却 404，
 * 而且任何重试都无法修复。
 * <p>
 * 因此统一约定：<b>先删数据库（事务内），提交后再删 OSS</b>。
 * 提交后删除失败时会留下孤儿对象，由 {@code OssReconcileJob} 兜底回收。
 */
public final class AfterCommit {

    private AfterCommit() {
    }

    /** 事务提交后执行；当前没有事务时立即执行 */
    public static void run(Runnable action) {
        if (action == null) {
            return;
        }
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
