package com.jiezhimod.block;

import com.jiezhimod.item.ItemDfcRemote;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.InventoryBasic;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;

/**
 * 自定义显示屏 GUI 的容器。
 *
 * 【为什么现在有格子了】原来那 8 个数据槽是"显示屏自己的配置"（存在方块实体里，
 * 跟玩家背包无关），所以这个容器一个格子都不放。现在交互变了：
 * 玩家不再靠"蹲下右键本屏"接入设备，而是**把 DFC 遥控器放进槽位、再点「添加」**。
 * 既然要往里放东西，就必须有**真正的物品槽** —— 也就必须走 {@code GuiContainer}：
 * 拖放 / Shift 点击是原版机制，拿 GuiScreen 自己画个假格子是拖不进去的。
 *
 * 【槽位坐标为什么写在这里】GUI 画格子、容器放 Slot，两处必须严丝合缝。
 * 常量只留这一份，{@code GuiCustomScreen} 引用同一批常量 —— 改版面不会错位。
 *
 * 【遥控器槽是临时的，关界面就还给你】
 * 它**不写进方块实体 NBT**：
 *   · 不持久化 → 方块被拆 / 被炸时不存在"里面那个遥控器去哪了"的问题；
 *   · 关界面必定归还（装不下就掉在脚下）→ 不存在"忘在屏里拿不回来"。
 * 唯一代价是"开着界面时服务器崩了"会丢一个遥控器（可合成品）。
 * 用这点换掉一整类"物品凭空消失"的 bug，划算。
 */
public class ContainerCustomScreen extends Container {

    // ==================== 版面常量（GUI 与容器共用） ====================

    public static final int PANEL_W = 268;
    public static final int PANEL_H = 306;

    /** 遥控器槽在面板内的位置 */
    public static final int REMOTE_SLOT_X = 53;
    public static final int REMOTE_SLOT_Y = 40;

    /** 玩家背包 3×9 与快捷栏在面板内的位置（9 格 × 18 = 162 宽，居中） */
    public static final int INV_X = 53;
    public static final int INV_Y = 222;
    public static final int HOTBAR_Y = 280;

    private final TileCustomScreen tile;
    private final InventoryBasic remote = new RemoteInventory();

    public ContainerCustomScreen(InventoryPlayer inv, TileCustomScreen tile) {
        this.tile = tile;

        // 0 号槽：遥控器
        this.addSlotToContainer(new Slot(this.remote, 0, REMOTE_SLOT_X, REMOTE_SLOT_Y) {
            @Override
            public boolean isItemValid(ItemStack stack) {
                return stack != null && stack.getItem() instanceof ItemDfcRemote;
            }

            @Override
            public int getSlotStackLimit() {
                return 1;
            }
        });

        // 1..27 玩家背包主区
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlotToContainer(new Slot(inv, col + row * 9 + 9,
                        INV_X + col * 18, INV_Y + row * 18));
            }
        }
        // 28..36 快捷栏
        for (int col = 0; col < 9; col++) {
            this.addSlotToContainer(new Slot(inv, col, INV_X + col * 18, HOTBAR_Y));
        }
    }

    /** 面板里的遥控器（可能为空）。导入时由服务端读它 */
    public ItemStack getRemoteStack() {
        return this.remote.getStackInSlot(0);
    }

    public TileCustomScreen getTile() {
        return this.tile;
    }

    @Override
    public boolean canInteractWith(EntityPlayer player) {
        if (this.tile == null || this.tile.getWorld() == null) {
            return false;
        }
        // 与方块实体保持 8 格以内的交互距离（原版箱子同款判定）
        return player.getDistanceSq(this.tile.getPos().getX() + 0.5D,
                this.tile.getPos().getY() + 0.5D,
                this.tile.getPos().getZ() + 0.5D) <= 64.0D;
    }

    /**
     * 关界面把遥控器还给玩家。
     *
     * 只认服务端：客户端也会走到这里，但客户端那份槽内容是同步过来的镜像，
     * 真往里塞东西会变成"客户端凭空多出一个遥控器"。
     */
    @Override
    public void onContainerClosed(EntityPlayer player) {
        super.onContainerClosed(player);
        if (player == null || player.world == null || player.world.isRemote) {
            return;
        }
        ItemStack s = this.remote.getStackInSlot(0);
        if (s.isEmpty()) {
            return;
        }
        this.remote.setInventorySlotContents(0, ItemStack.EMPTY);
        if (!player.inventory.addItemStackToInventory(s)) {
            player.dropItem(s, false);
        }
        player.inventory.markDirty();
    }

    /**
     * Shift 点击：遥控器槽 <-> 玩家背包。
     *
     * 不用 {@code mergeItemStack} 的通用合并（它对 1 格上限的槽行为不直观），
     * 这里两边的动作都写死，行为一眼可预期。
     */
    @Override
    public ItemStack transferStackInSlot(EntityPlayer player, int index) {
        Slot slot = index >= 0 && index < this.inventorySlots.size()
                ? this.inventorySlots.get(index) : null;
        if (slot == null || !slot.getHasStack()) {
            return ItemStack.EMPTY;
        }
        ItemStack cur = slot.getStack();
        ItemStack copy = cur.copy();

        if (index == 0) {
            // 槽 -> 背包
            if (!this.mergeItemStack(cur, 1, this.inventorySlots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else {
            // 背包 -> 槽：只收遥控器，且槽必须空
            if (!(cur.getItem() instanceof ItemDfcRemote) || this.getSlot(0).getHasStack()) {
                return ItemStack.EMPTY;
            }
            this.getSlot(0).putStack(cur.splitStack(1));
            this.getSlot(0).onSlotChanged();
        }
        if (cur.isEmpty()) {
            slot.putStack(ItemStack.EMPTY);
        } else {
            slot.onSlotChanged();
        }
        this.detectAndSendChanges();
        return copy;
    }

    /**
     * 只装 DFC 遥控器、且只装一个的 1 格容器。
     *
     * 直接继承 {@link InventoryBasic}：IInventory 有 16 个方法，
     * 为了一个格子手写一遍只会多出 16 处可能写错的地方。
     */
    private static class RemoteInventory extends InventoryBasic {
        RemoteInventory() {
            super("jiezhimod:dfc_remote_slot", false, 1);
        }

        @Override
        public int getInventoryStackLimit() {
            return 1;
        }

        @Override
        public boolean isItemValidForSlot(int index, ItemStack stack) {
            return stack.getItem() instanceof ItemDfcRemote;
        }

        /** 这是容器自己持有的临时格子，没有 NBT 要标脏 */
        @Override
        public void markDirty() {
            // 故意留空：遥控器不落盘，见类注释
        }
    }
}
