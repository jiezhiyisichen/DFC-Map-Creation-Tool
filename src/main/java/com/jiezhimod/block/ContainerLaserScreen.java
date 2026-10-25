package com.jiezhimod.block;

import com.jiezhimod.dfc.LaserLayout;
import com.jiezhimod.item.ItemLaserConnector;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.InventoryBasic;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;

/**
 * 激光状态显示器的面板容器。
 *
 * 【为什么要重写一份，而不是继续复用 {@link ContainerCustomScreen}】
 * v4.2 是直接套用自定义屏的面板：同一个容器、同一个 GUI、只是标题不同。
 * 结果一开面板就是**8 行**（自定义屏的数据槽数），而这块屏只有
 * {@link LaserLayout#SLOTS} 台激光 —— 空着 4 行、行里画的还是"罐/电量"那种
 * 自定义屏语义，跟"SL1 稳定器"完全不搭。
 * 更要紧的是槽位只收 DFC 遥控器，于是玩家必须用遥控器去接激光屏，
 * 而遥控器在激光屏上做的事又和它在自定义屏上做的事不一样。
 *
 * 所以这块屏现在有**自己的容器 + 自己的界面**：
 *   · 槽位只收 {@link ItemLaserConnector 激光链接器}（放遥控器进去会被拒）；
 *   · 列表固定 4 行，一行一台激光，行首就是类型短名（SL1 / PL1 / OL1 / INJ1）。
 * 版面常量全部放在这个类里，{@code GuiLaserScreen} 引用同一批常量 —— 改版面不会错位。
 *
 * 【槽位坐标为什么写在容器里】GUI 画格子、容器放 Slot，两处必须严丝合缝。
 *
 * 【链接器槽是临时的，关界面就还给你】和遥控器槽一样**不写进方块实体 NBT**：
 * 不会出现"屏被拆了，里面的链接器去哪了"，也不会出现"忘在屏里拿不回来"。
 */
public class ContainerLaserScreen extends Container {

    // ==================== 版面常量（GUI 与容器共用） ====================

    /**
     * 面板尺寸。高度卡在 240 的理由与自定义屏相同：
     * 1.12.2 只保证逻辑分辨率 ≥ 320×240，{@code GuiContainer} 不为超屏做任何处理。
     */
    public static final int PANEL_W = 312;
    public static final int PANEL_H = 240;
    public static final int MARGIN = 6;

    public static final int TITLE_Y = 4;

    // ---------- 左区：电源 / 链接器 / 添加 / 按钮 ----------
    public static final int POWER_X = 6;
    public static final int POWER_Y = 14;
    public static final int POWER_W = 96;
    public static final int STATE_Y = 33;

    /** 链接器槽在面板内的位置 */
    public static final int LINK_SLOT_X = 6;
    public static final int LINK_SLOT_Y = 44;
    public static final int LINK_LABEL_X = 28;
    public static final int LINK_LABEL_Y = 46;
    public static final int LINK_COUNT_Y = 57;

    public static final int ADD_BTN_X = 6;
    public static final int ADD_BTN_Y = 68;
    public static final int ADD_BTN_W = 96;
    public static final int ADD_HINT_Y = 88;

    /** 「已接入 n/4」那一行 */
    public static final int COUNT_Y = 100;

    public static final int CLEAR_X = 6;
    public static final int CLEAR_Y = 112;
    public static final int CLEAR_W = 56;
    public static final int CLOSE_X = 68;
    public static final int CLOSE_W = 56;

    // ---------- 右区：4 行激光列表 ----------
    public static final int LIST_X = 132;
    public static final int LIST_TOP = 12;
    public static final int ROW_H = 30;
    public static final int DEL_W = 32;
    public static final int DEL_H = 12;
    /** "删除"按钮在行内的纵向偏移 */
    public static final int DEL_DY = 8;

    // ---------- 底部 ----------
    public static final int HINT_Y = 132;

    /** 玩家背包 3×9 与快捷栏（9 格 × 18 = 162 宽，居中；与自定义屏同一套坐标） */
    public static final int INV_X = (PANEL_W - 9 * 18) / 2;
    public static final int INV_Y = 154;
    public static final int HOTBAR_Y = 210;

    private final TileLaserScreen tile;
    private final InventoryBasic connector = new ConnectorInventory();

    public ContainerLaserScreen(InventoryPlayer inv, TileLaserScreen tile) {
        this.tile = tile;

        // 0 号槽：激光链接器
        this.addSlotToContainer(new Slot(this.connector, 0, LINK_SLOT_X, LINK_SLOT_Y) {
            @Override
            public boolean isItemValid(ItemStack stack) {
                return stack != null && stack.getItem() instanceof ItemLaserConnector;
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

    /** 面板里的链接器（可能为空）。导入时由服务端读它 */
    public ItemStack getConnectorStack() {
        return this.connector.getStackInSlot(0);
    }

    public TileLaserScreen getTile() {
        return this.tile;
    }

    @Override
    public boolean canInteractWith(EntityPlayer player) {
        if (this.tile == null || this.tile.getWorld() == null) {
            return false;
        }
        return player.getDistanceSq(this.tile.getPos().getX() + 0.5D,
                this.tile.getPos().getY() + 0.5D,
                this.tile.getPos().getZ() + 0.5D) <= 64.0D;
    }

    /**
     * 关界面把链接器还给玩家。
     *
     * 只认服务端：客户端那份槽内容是同步过来的镜像，真往里塞会变成
     * "客户端凭空多出一个链接器"。
     */
    @Override
    public void onContainerClosed(EntityPlayer player) {
        super.onContainerClosed(player);
        if (player == null || player.world == null || player.world.isRemote) {
            return;
        }
        ItemStack s = this.connector.getStackInSlot(0);
        if (s.isEmpty()) {
            return;
        }
        this.connector.setInventorySlotContents(0, ItemStack.EMPTY);
        if (!player.inventory.addItemStackToInventory(s)) {
            player.dropItem(s, false);
        }
        player.inventory.markDirty();
    }

    /** Shift 点击：链接器槽 <-> 玩家背包（两边动作都写死，行为一眼可预期） */
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
            if (!this.mergeItemStack(cur, 1, this.inventorySlots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else {
            if (!(cur.getItem() instanceof ItemLaserConnector) || this.getSlot(0).getHasStack()) {
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

    /** 只装激光链接器、且只装一个的 1 格容器 */
    private static class ConnectorInventory extends InventoryBasic {
        ConnectorInventory() {
            super("jiezhimod:laser_connector_slot", false, 1);
        }

        @Override
        public int getInventoryStackLimit() {
            return 1;
        }

        @Override
        public boolean isItemValidForSlot(int index, ItemStack stack) {
            return stack.getItem() instanceof ItemLaserConnector;
        }

        /** 这是容器自己持有的临时格子，没有 NBT 要标脏 */
        @Override
        public void markDirty() {
            // 故意留空：链接器不落盘，见类注释
        }
    }
}
