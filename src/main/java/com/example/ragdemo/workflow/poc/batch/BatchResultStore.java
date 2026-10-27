package com.example.ragdemo.workflow.poc.batch;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

/**
 * <b>测试专用</b>的批次结果持久化位置（step3 任务书 §三.3 的"测试专用的持久化位置"）。
 * <p>
 * <b>幂等保证</b>：表上对 {@code (task_id, batch_index)} 建主键/唯一约束，
 * 写入用 {@code INSERT ... ON CONFLICT DO NOTHING}。返回 {@code true} 表示本次真的插入，
 * {@code false} 表示该批次结果<b>已存在被跳过</b>。
 * 于是"批次结果已落库，但图 Checkpoint 尚未提交时崩溃 → 恢复后该批次被重跑"
 * 这一场景不会产生重复业务结果（实测见 {@code BatchRecoveryGraphTest} 幂等用例）。
 * <p>
 * <b>隔离性</b>：表名带 {@code poc_} 前缀，并且是<b>本步新建</b>的表，
 * 不修改任何已有业务表（{@code documents} / {@code document_chunks} 等一律不碰）。
 * <p>
 * <b>可在 Spring 之外使用</b>：只依赖 {@link DataSource}，因此真实 JVM 崩溃实验的
 * 子进程（{@code BatchCrashMain}）无需启动 Spring 也能用它。
 */
public class BatchResultStore {

    /** 测试专用表名（poc_ 前缀） */
    public static final String TABLE = "poc_batch_result";

    private final DataSource dataSource;

    public BatchResultStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** 建表（IF NOT EXISTS，可重复调用）。 */
    public void ensureSchema() throws Exception {
        String ddl = "CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                + " task_id     VARCHAR(160) NOT NULL,"
                + " batch_index INTEGER      NOT NULL,"
                + " item_count  INTEGER      NOT NULL,"
                + " created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),"
                + " CONSTRAINT " + TABLE + "_pk PRIMARY KEY (task_id, batch_index)"
                + ")";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(ddl)) {
            ps.execute();
        }
    }

    /**
     * 幂等写入一个批次的结果。
     *
     * @return {@code true}=本次新插入；{@code false}=已存在（重复执行被幂等跳过）
     */
    public boolean insertResult(String taskId, int batchIndex, int itemCount) throws Exception {
        String sql = "INSERT INTO " + TABLE + " (task_id, batch_index, item_count) VALUES (?, ?, ?) "
                + "ON CONFLICT (task_id, batch_index) DO NOTHING";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, taskId);
            ps.setInt(2, batchIndex);
            ps.setInt(3, itemCount);
            return ps.executeUpdate() == 1;
        }
    }

    /** 该 task 的结果行数（= 实际写入的批次数；重复执行不会让它变大）。 */
    public int countRows(String taskId) throws Exception {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM " + TABLE + " WHERE task_id = ?")) {
            ps.setString(1, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /** 该 task 已落库的批次下标（升序），用于检查重复/顺序/缺口。 */
    public List<Integer> batchIndexes(String taskId) throws Exception {
        List<Integer> result = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "SELECT batch_index FROM " + TABLE + " WHERE task_id = ? ORDER BY batch_index")) {
            ps.setString(1, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(rs.getInt(1));
                }
            }
        }
        return result;
    }

    /** 各批次的条目数明细（用于核对最后一批是否为不满 32 的残余批）。 */
    public List<String> batchDetails(String taskId) throws Exception {
        List<String> result = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement("SELECT batch_index, item_count FROM " + TABLE
                        + " WHERE task_id = ? ORDER BY batch_index")) {
            ps.setString(1, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(rs.getInt(1) + ":" + rs.getInt(2));
                }
            }
        }
        return result;
    }
}
