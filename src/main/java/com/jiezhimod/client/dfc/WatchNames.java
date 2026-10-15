package com.jiezhimod.client.dfc;

import com.jiezhimod.dfc.DfcWatch;

import java.util.List;

/**
 * 数据槽里"数据名"的客户端翻译。
 *
 * 【为什么要这一步】数据槽是**服务端**算出来的，而 HBM 的
 * {@code FluidType.getLocalizedName()} 走 {@code I18nUtil.resolveKey}，
 * 后者在服务端**原样返回语言键**（"fluid.diesel"）—— 服务端没有 I18n。
 * 所以服务端只能把键发出来，翻译只能在客户端做一次。
 *
 * 【只允许客户端调用】用到了 {@code net.minecraft.client.resources.I18n}，
 * 在专用服务端上那个类根本不存在。调用点（{@code TileDfcScreen.applyServerState}）
 * 用 {@code world.isRemote} 把这条路封死了。
 */
public final class WatchNames {

    private WatchNames() {
    }

    /** 把所有数据槽的名字翻成当前语言 */
    public static void localize(List<DfcWatch> watches) {
        if (watches == null) {
            return;
        }
        for (DfcWatch w : watches) {
            w.name = resolve(w.name);
        }
    }

    /**
     * 翻一个名字。
     *
     * - 空 -> "--"；
     * - 不含 '.'  -> 已经是成品（"储能" 这种我们自己写死的中文），原样返回；
     * - 含 '.'    -> 当作语言键翻；翻不出来（键缺失）就退化成最后一段，
     *   免得屏幕上出现 "fluid.diesel" 这种内部串。
     */
    public static String resolve(String key) {
        if (key == null || key.isEmpty()) {
            return "--";
        }
        if (key.indexOf('.') < 0) {
            return key;
        }
        String translated;
        try {
            translated = net.minecraft.client.resources.I18n.format(key);
        } catch (Throwable t) {
            translated = key;
        }
        if (translated != null && !translated.equals(key)) {
            return translated;
        }
        int dot = key.lastIndexOf('.');
        String tail = key.substring(dot + 1).replace('_', ' ').trim();
        return tail.isEmpty() ? key : tail;
    }
}
