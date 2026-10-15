package com.jiezhimod.client;

import com.jiezhimod.client.util.GuiDraw;
import com.jiezhimod.item.JetpackEffect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.GL11;

/**
 * 喷气开关的**热量条**：画在屏幕上的一个小横条，表示当前热量 / 是否过热。
 *
 * 【为什么用 RenderGameOverlayEvent 而不是画在物品贴图上】
 * "画在物品上"（叠在手持模型里）需要改写 ItemRenderer 的矩阵状态，
 * 1.12.2 没有可覆写点，只能靠 RenderHandEvent 插一脚，坐标极难算准、
 * 且第三人称/第一人称表现不一致。这里改为画在**屏幕坐标**下。
 *
 * 【位置为什么是准星下方】
 * 早期版本放在屏幕顶部正中，会被同样居中绘制的倒计时压住。屏幕四周其实都被占了：
 *   顶部中央 = 倒计时    左下 = 血量 / 饥饿 / 经验条
 *   底部中央 = 快捷栏    右下 = 通知浮层      左上 = 聊天
 * 只有**准星正下方**这块是空的，而且它就在视线焦点上，飞行时不用刻意扫视。
 *
 * 颜色约定（与喷气尾焰一致，玩家不用学）：
 *   冷(低热量) 绿 -> 中 黄 -> 热 橙 -> 过热 红并闪烁
 */
@SideOnly(Side.CLIENT)
public final class JetHeatBarRenderer {

    private static final int BAR_W = 60;
    private static final int BAR_H = 5;
    /** 相对准星（屏幕正中心）向下的偏移，给准星本身留点空隙 */
    private static final int CROSSHAIR_GAP = 22;

    private JetHeatBarRenderer() {
    }

    /**
     * 画热量条。
     *
     * @param mc Minecraft 实例
     */
    public static void draw(Minecraft mc) {
        if (mc == null || mc.player == null || mc.gameSettings == null) {
            return;
        }
        // 隐藏 GUI（F1）时不画
        if (mc.gameSettings.hideGUI) {
            return;
        }
        EntityPlayer player = mc.player;
        if (!JetpackEffect.hasJetpack(player) || !JetpackEffect.hasSwitch(player)) {
            return;
        }
        float ratio = ClientJetState.heatRatio(player);
        if (ratio <= 0.0F) {
            // 完全没热量时不显示，避免常驻一根空条
            return;
        }
        boolean overheated = ratio >= 0.999F;

        ScaledResolution sr = new ScaledResolution(mc);
        int x = sr.getScaledWidth() / 2 - BAR_W / 2;
        int y = sr.getScaledHeight() / 2 + CROSSHAIR_GAP;

        float[] rgb = colorFor(ratio, overheated);

        GlStateManager.pushMatrix();
        GlStateManager.enableBlend();
        GlStateManager.disableTexture2D();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ZERO);

        // 底槽
        GuiDraw.drawRect(x - 1, y - 1, x + BAR_W + 1, y + BAR_H + 1, 0xB0000000);
        // 填充
        int fill = Math.round(BAR_W * Math.max(0.0F, Math.min(1.0F, ratio)));
        if (fill > 0) {
            int a = overheated ? blinkAlpha(mc) : 235;
            GuiDraw.drawRect(x, y, x + fill, y + BAR_H, (a << 24)
                    | ((int) (rgb[0] * 255) << 16)
                    | ((int) (rgb[1] * 255) << 8)
                    | (int) (rgb[2] * 255));
        }
        // 边框
        drawOutline(x, y, BAR_W, BAR_H, 0xFF000000);

        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
        GlStateManager.popMatrix();
    }

    /** 绿 -> 黄 -> 橙 的渐变；过热时强制红 */
    private static float[] colorFor(float ratio, boolean overheated) {
        if (overheated) {
            return new float[]{1.0F, 0.25F, 0.20F};
        }
        float r = ratio;
        float g;
        float b = 0.15F;
        if (ratio < 0.5F) {
            // 绿 -> 黄
            g = ratio * 2.0F;
        } else {
            // 黄 -> 橙红
            g = 1.0F - (ratio - 0.5F) * 1.2F;
            if (g < 0.35F) {
                g = 0.35F;
            }
        }
        return new float[]{r, g, b};
    }

    /**
     * 过热时闪烁（1s 周期），纯客户端视觉，不影响逻辑。
     *
     * 用 System.currentTimeMillis 而不是帧计数：1.12.2 没有 GlobalRenderingCache
     * 这类帧计数器（那是 1.15+ 才有的），而 EntityPlayer.ticksSinceWorldFlare
     * 在暂停/切屏时并不按预期推进，当闪烁依据不可靠。
     */
    private static int blinkAlpha(Minecraft mc) {
        long ms = System.currentTimeMillis() % 1000L;
        return ms < 500L ? 115 : 255;
    }

    /** 用 1px 细线画矩形边框（1.12.2 的 Gui.drawRect 只能画实心块） */
    private static void drawOutline(int x, int y, int w, int h, int color) {
        GuiDraw.drawRect(x, y, x + w, y + 1, color);
        GuiDraw.drawRect(x, y + h - 1, x + w, y + h, color);
        GuiDraw.drawRect(x, y, x + 1, y + h, color);
        GuiDraw.drawRect(x + w - 1, y, x + w, y + h, color);
    }
}
