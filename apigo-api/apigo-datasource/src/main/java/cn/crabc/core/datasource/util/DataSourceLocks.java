package cn.crabc.core.datasource.util;

import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 数据源级串行锁（分段锁）。
 * <p>
 * 背景：连接池的「读旧池 → 建新池 → 预热 → 替换 → 关闭旧池」不是原子操作。
 * 管理端编辑数据源与 5 分钟定时刷新并发时，后写入的池会覆盖先写入的池，
 * 被覆盖的那只 Hikari 池永远不会 close，连同 minIdle 常驻连接一起泄漏。
 * <p>
 * 这里按 {@code datasourceId} 取分段锁，保证同一数据源的建池/删池串行，
 * 不同数据源仍可并行。
 *
 * @author yuqf
 */
public final class DataSourceLocks {

    /**
     * 锁分段数，远大于常见数据源数量，冲突概率可忽略。
     */
    private static final int STRIPES = 64;

    private static final ReentrantLock[] LOCKS = new ReentrantLock[STRIPES];

    static {
        for (int i = 0; i < STRIPES; i++) {
            LOCKS[i] = new ReentrantLock();
        }
    }

    private DataSourceLocks() {
    }

    /**
     * 获取指定数据源的锁；id 为空时退化为固定槽位，避免 NPE。
     *
     * @param dataSourceId 数据源ID（不含 :type:schema 后缀）
     * @return 对应的可重入锁
     */
    public static ReentrantLock of(String dataSourceId) {
        int hash = dataSourceId == null ? 0 : Objects.hashCode(dataSourceId);
        return LOCKS[Math.floorMod(hash, STRIPES)];
    }
}
