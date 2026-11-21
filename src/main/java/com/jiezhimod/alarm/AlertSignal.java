package com.jiezhimod.alarm;

/**
 * 一次告警判定的结果（纯数据）。
 *
 * <h3>为什么不直接返回一个整数等级</h3>
 * 警示灯的**颜色**只跟 {@link #level} 有关，但玩家在聊天栏 / 日志里需要知道
 * "到底哪一项触发了"，所以额外带一个语言键 {@link #key} 与一段明细 {@link #detail}。
 * 明细是**已经拼好的普通文本**（例如 {@code "SL1 · 剩余耐久 40%"}），
 * 因为它来自 {@code LaserInfo.warnText} —— 那串东西在服务端就算好了，
 * 再拆回语言键反而更脆。{@link #key} 则留给客户端用
 * {@code TextComponentTranslation} 现场翻译（服务端不碰 I18n）。
 */
public final class AlertSignal {

    /** 语言键的前缀，与 lang 文件里的 {@code alert.jiezhimod.*} 一一对应 */
    public static final String KEY_PREFIX = "alert.jiezhimod.";

    /** 0 = 正常；1 = 警告（黄）；2 = 危急（橙）；3 = 熔毁（红） */
    public final int level;
    /** 触发项的语言键（不含前缀，如 {@code "hot"}），正常时是 {@code "ok"} */
    public final String key;
    /** 明细（可为空串）：具体是哪个部件、剩多少 */
    public final String detail;

    public AlertSignal(int level, String key, String detail) {
        this.level = level;
        this.key = key == null ? "ok" : key;
        this.detail = detail == null ? "" : detail;
    }

    /** 完整语言键，给 {@code TextComponentTranslation} 用 */
    public String langKey() {
        return KEY_PREFIX + this.key;
    }

    public boolean isAlarm() {
        return this.level > AlertRules.OK;
    }

    @Override
    public String toString() {
        return "Alert(" + this.level + "," + this.key
                + (this.detail.isEmpty() ? "" : "," + this.detail) + ")";
    }
}
