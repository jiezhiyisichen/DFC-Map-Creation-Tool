package com.jiezhimod.dfc;

import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.items.IItemHandler;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
/**
 * DFC 数据读取桥 —— **全程反射**，不引用任何 HBM / 诅咒版 addon 的类。
 *
 * 【为什么必须反射】
 * 诅咒版的 DFC 数据接口 {@code IMixinTileEntityCore} 是 addon 用 Mixin 织进
 * {@code com.hbm.tileentity.machine.TileEntityCore} 的。如果编译期直接引用，
 * 玩家的整合包里一旦没有那个 addon（甚至只是没装 NTM），我们的 mod 会在加载时
 * 直接 NoClassDefFoundError 崩掉 —— 一个纯视觉模组不该有这种连带伤害。
 * 反射的代价只是每个方法名查一次表（结果缓存），换来的是"没装也不崩"。
 *
 * 【数据源有两种】
 *   1. 核心（Dark Fusion Core）—— 直接调 getDFC* getter；
 *   2. 稳定器（DFC Stabilizer）—— 它自己只有功率/透镜，核心数据要靠它转发：
 *      诅咒版给稳定器实现了 {@code IControllable.getQueryData()}（key 为
 *      core_temp / core_stabilization / core_energy / ...），并且它内部有
 *      {@code targetPosition} 指向绑定核心。
 *      这里走的是更直接的路径：读出稳定器自身字段，再**定位它绑定的核心**，
 *      直接读核心 —— 因为 getQueryData 返回的是 {@code DataValue} 包装对象，
 *      还得再反射一层 DataValue 的取值方法，不如直接读核心干净。
 */
public final class DfcBridge {

    /** 从稳定器出发找核心时的扫描半径（格）：DFC 多方块不算大，12 足够覆盖 */
    public static final int CORE_SCAN_RADIUS = 12;

    /** 已解析过的方法缓存：key = "类名#方法名" */
    private static final Map<String, Method> METHODS = new ConcurrentHashMap<String, Method>();
    /** 已知不存在的方法名，避免每次刷新都做一次失败的查找 */
    private static final Set<String> MISSING =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    /** 字段缓存：key = "类名#字段名" */
    private static final Map<String, Field> FIELDS = new ConcurrentHashMap<String, Field>();
    /** 已知不存在的字段名，避免每次刷新都重抛 NoSuchFieldException（见 findField） */
    private static final Set<String> MISSING_FIELDS =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private DfcBridge() {
    }

    // ==================== 对外入口 ====================

    /**
     * 读一次数据源。
     *
     * @param world 目标世界
     * @param pos   绑定的坐标（核心或稳定器）
     * @return 快照；没找到 / 不是 DFC 部件时返回"未连接"快照，绝不返回 null
     */
    public static DfcSnapshot read(World world, BlockPos pos) {
        DfcSnapshot out = new DfcSnapshot();
        if (world == null || pos == null) {
            return out;
        }
        TileEntity te = world.getTileEntity(pos);
        return read(te, out);
    }

    public static DfcSnapshot read(TileEntity te, DfcSnapshot out) {
        if (te == null) {
            return out;
        }
        if (isDfcCore(te)) {
            out.linked = true;
            out.sourceKind = DfcSnapshot.SOURCE_CORE;
            readCore(te, out);
        } else if (isStabilizer(te)) {
            out.linked = true;
            out.sourceKind = DfcSnapshot.SOURCE_STABILIZER;
            readStabilizer(te, out);
        }
        return out;
    }

    /** 这个方块实体是不是 DFC 核心（判断依据：有没有诅咒版织入的 getter） */
    public static boolean isDfcCore(TileEntity te) {
        return te != null && findMethod(te.getClass(), "getDFCTemperature") != null;
    }

    /** 这个方块实体是不是 DFC 稳定器（按类名判断，避免把别的 IControllable 认成它） */
    public static boolean isStabilizer(TileEntity te) {
        if (te == null) {
            return false;
        }
        String n = te.getClass().getName();
        return n.endsWith("TileEntityCoreStabilizer");
    }

    /** 是不是可以作为数据源的 DFC 部件 */
    public static boolean isDataSource(TileEntity te) {
        return isDfcCore(te) || isStabilizer(te);
    }

    /**
     * 把任意 DFC 部件解析成"能读到核心数据的坐标"。
     *
     * 绑定的是核心就直接返回它；绑定的是稳定器则去找它对应的核心
     * （优先用诅咒版给的 targetPosition，没有就按半径扫描）。
     * 这个操作比较重（可能要扫上千个方块），**只在绑定时调用一次**，
     * 结果应当缓存到方块实体里，不要每 tick 做。
     */
    public static BlockPos resolveCore(World world, BlockPos pos) {
        if (world == null || pos == null) {
            return null;
        }
        TileEntity te = world.getTileEntity(pos);
        if (isDfcCore(te)) {
            return pos;
        }
        if (isStabilizer(te)) {
            BlockPos t = readStabilizerTarget(te);
            if (t != null) {
                return t;
            }
            return scanForCore(world, pos, CORE_SCAN_RADIUS);
        }
        return null;
    }

    // ==================== 核心 ====================

    private static void readCore(TileEntity te, DfcSnapshot out) {
        out.hasCore = callBool(te, "isDFCHasCore", out.hasCore);
        out.temperature = callDouble(te, "getDFCTemperature", out.temperature);
        out.meltingPoint = callInt(te, "getDFCMeltingPoint", out.meltingPoint);
        out.failsafe = callDouble(te, "getDFCFailsafeLevel", out.failsafe);
        out.stabilization = callDouble(te, "getDFCStabilization", out.stabilization);

        out.containedEnergy = callDouble(te, "getDFCContainedEnergy", out.containedEnergy);
        out.maxEnergy = callDouble(te, "getDFCMaxEnergy", out.maxEnergy);
        out.gainedEnergy = callDouble(te, "getDFCGainedEnergy", out.gainedEnergy);
        out.expellingEnergy = callDouble(te, "getDFCExpellingEnergy", out.expellingEnergy);
        out.potentialGain = callDouble(te, "getDFCPotentialGain", out.potentialGain);
        out.incomingSpk = callDouble(te, "getDFCIncomingSpk", out.incomingSpk);
        out.expellingSpk = callDouble(te, "getDFCExpellingSpk", out.expellingSpk);

        out.ticks = callInt(te, "getDFCTicks", out.ticks);
        out.stabilizers = callInt(te, "getDFCStabilizers", out.stabilizers);
        out.lastStabilizers = callInt(te, "getDFCLastStabilizers", out.lastStabilizers);
        out.boosted = callBool(te, "isDFCWasBoosted", out.boosted);
        out.finalPhase = callBool(te, "isDFCFinalPhase", out.finalPhase);
        out.collapsing = callDouble(te, "getDFCCollapsing", out.collapsing);

        out.destroyed = callBool(te, "isDFCDestroyed", out.destroyed);
        out.explosionIn = callDouble(te, "getDFCExplosionIn", out.explosionIn);
        out.overloadTimer = callInt(te, "getDFCOverloadTimer", out.overloadTimer);
        out.detonation = callBool(te, "getDetonation", out.detonation);
        out.detonationTimer = callInt(te, "getDetonationTimer", out.detonationTimer);
        out.componentCount = callCollectionSize(te, "getDFCComponentPositions", out.componentCount);

        // 第二屏用的数据：与反应堆连接的各部件（发射器 / 接收器 / 燃料喷射器）与燃料
        readComponents(te, out);
        readFuel(te, out);
    }

    // ==================== 连接部件（发射器 / 接收器 / 燃料喷射器） ====================

    /**
     * 统计与核心连接的部件。
     *
     * 【部件清单从哪来】诅咒版里每个部件（发射器 / 接收器 / 燃料喷射器 / 稳定器）
     * 在 tick 里都会朝核心做一次射线/定位，命中后把**自己的坐标**塞进核心的
     * {@code componentPositions}（见 IDFCBase.leafia$getCore 与 MixinTileEntityCoreEmitter）。
     * 于是"核心的 componentPositions"就是整张连接图。
     *
     * 【为什么要取本 tick + 上一 tick 的并集】
     * 核心每 tick 开头会把 componentPositions 清空、再由各部件重新填。
     * 我们读的时候正好可能落在"已清空、还没填完"的窗口里，只读一份会时多时少、
     * 台数跳变。诅咒版自己也留了 prevComponentPositions（上一轮的完整快照），
     * 两者求并集就能稳定拿到完整集合。
     *
     * 【为什么用类名后缀分类】
     * 全程不引用 HBM 的类（没装也不该崩），所以只能按类名认：
     *   TileEntityCoreEmitter  -> 发射器（诅咒版另有 CoreCEmitterTE）
     *   TileEntityCoreReceiver -> 接收器（吸收器）
     *   TileEntityCoreInjector -> 燃料喷射器
     * 稳定器不计入第二屏（第一屏已经在盯它了）。
     */
    private static void readComponents(TileEntity core, DfcSnapshot out) {
        World world = core.getWorld();
        if (world == null) {
            return;
        }
        Set<BlockPos> positions = new java.util.HashSet<BlockPos>();
        collectPositions(core, "getDFCComponentPositions", positions);
        collectPositions(core, "getDFCPrevComponentPositions", positions);
        for (BlockPos p : positions) {
            if (p == null) {
                continue;
            }
            TileEntity part = world.getTileEntity(p);
            if (part == null || part == core) {
                continue;
            }
            classifyComponent(part, out);
        }
        // 【刻意不拿 getDFCAbsorbers() 兜底】那份列表是核心自己在 tick 里逐台登记的，
        // 结尾会被清空，读到的可能是半份 —— 拿它去补台数会出现"3 台但合计只有 2 台的电量"。
        // componentPositions 的并集已经覆盖接收器（它们同样会登记自己的坐标），
        // 台数与合计因此天然自洽。
    }

    /** 把一个部件的坐标列表塞进集合（去重） */
    private static void collectPositions(TileEntity core, String method, Set<BlockPos> out) {
        Object v = call(core, method);
        if (!(v instanceof List)) {
            return;
        }
        for (Object o : (List<?>) v) {
            if (o instanceof BlockPos) {
                out.add((BlockPos) o);
            }
        }
    }

    /** 按类名把部件归类并累加它的读数 */
    private static void classifyComponent(TileEntity part, DfcSnapshot out) {
        String cn = part.getClass().getName();
        if (cn.endsWith("TileEntityCoreEmitter") || cn.endsWith("CoreCEmitterTE")) {
            out.emitterCount++;
            if (getBoolField(part, "isOn", false) || callBool(part, "leafia$isActive", false)) {
                out.emitterActiveCount++;
            }
            out.emitterWatts += getIntField(part, "watts", 0);
            out.emitterSpk += (double) getLongField(part, "prev", 0L);
        } else if (cn.endsWith("TileEntityCoreInjector")) {
            out.injectorCount++;
            out.injectorFuel += tankFillSum(part, "tanks");
        } else if (cn.endsWith("TileEntityCoreReceiver")) {
            out.receiverCount++;
            out.receiverJoules += (double) callLong(part, "leafia$syncJoules", 0L);
        }
    }

    // ==================== 燃料（核心自带的两个燃料罐） ====================

    /**
     * 核心的两个燃料罐（诅咒版 NBT 键 fuel1 / fuel2，字段 {@code tanks}）。
     * 燃料喷射器按类型把流体推进去、核心每 tick 消耗，所以"罐里还剩什么、剩多少"
     * 就是这台反应堆真实的燃料状况。
     */
    private static void readFuel(TileEntity core, DfcSnapshot out) {
        Object v = getFieldValue(core, "tanks");
        if (!(v instanceof Object[])) {
            return;
        }
        Object[] tanks = (Object[]) v;
        if (tanks.length > 0) {
            out.fuelTypeA = tankTypeName(tanks[0]);
            out.fuelRatioA = tankRatio(tanks[0]);
        }
        if (tanks.length > 1) {
            out.fuelTypeB = tankTypeName(tanks[1]);
            out.fuelRatioB = tankRatio(tanks[1]);
        }
    }

    /**
     * 罐内流体的名字。
     * 用 {@code FluidType.getName()}（= 内部 stringId，如 "DHC"）而不是
     * {@code getLocalizedName()}：后者是 @SideOnly(CLIENT) 且走 I18n，
     * 在服务端线程读会拿到未翻译的键、甚至直接抛异常。这里把下划线换成空格，
     * 终端里看着舒服些。
     */
    private static String tankTypeName(Object tank) {
        Object type = callOn(tank, "getTankType");
        if (type == null) {
            return "--";
        }
        Object name = callOn(type, "getName");
        if (!(name instanceof String)) {
            return "--";
        }
        String s = ((String) name).replace('_', ' ').trim();
        return s.isEmpty() ? "--" : s;
    }

    /** 罐的液位（0~1 比值）。拿不到就返回 0，界面会显示空罐而不是假读数 */
    private static double tankRatio(Object tank) {
        Object fill = callOn(tank, "getFill");
        Object max = callOn(tank, "getMaxFill");
        if (!(fill instanceof Number) || !(max instanceof Number)) {
            return 0.0D;
        }
        double m = ((Number) max).doubleValue();
        if (m <= 0.0D) {
            return 0.0D;
        }
        double r = ((Number) fill).doubleValue() / m;
        return r < 0.0D ? 0.0D : (r > 1.0D ? 1.0D : r);
    }

    /** 一个流体容器（如燃料喷射器的 tanks[]）的合计液量 */
    private static double tankFillSum(Object holder, String field) {
        Object v = getFieldValue(holder, field);
        if (!(v instanceof Object[])) {
            return 0.0D;
        }
        double sum = 0.0D;
        for (Object tank : (Object[]) v) {
            Object fill = callOn(tank, "getFill");
            if (fill instanceof Number) {
                sum += ((Number) fill).doubleValue();
            }
        }
        return sum;
    }

    // ==================== 稳定器 ====================

    private static void readStabilizer(TileEntity te, DfcSnapshot out) {
        out.stabPresent = true;
        out.stabActive = getBoolField(te, "isOn", false);
        out.stabLevel = getIntField(te, "watts", 0);
        out.stabPower = getLongField(te, "power", 0L);

        Object lens = getFieldValue(te, "lens");
        out.lensType = lens == null ? "NONE" : String.valueOf(lens);
        out.lensHealth = readLensHealth(te, lens);

        // 稳定器自己不带核心数据：只跟它登记过的 targetPosition 去读核心。
        // 【不要在这里做半径扫描】这个方法每个刷新周期都会跑，扫一次是上千次方块查询。
        // 真正的"找不到就扫一遍"由调用方在**绑定那一刻**做一次（见 TileDfcScreen.bindSource）。
        BlockPos corePos = readStabilizerTarget(te);
        if (corePos != null && te.getWorld() != null) {
            TileEntity core = te.getWorld().getTileEntity(corePos);
            if (isDfcCore(core)) {
                out.hasCore = true;
                readCore(core, out);
            }
        }
    }

    /** 诅咒版给稳定器留的 targetPosition（BlockPos） */
    private static BlockPos readStabilizerTarget(TileEntity te) {
        Object v = getFieldValue(te, "targetPosition");
        if (v instanceof BlockPos) {
            return (BlockPos) v;
        }
        return null;
    }

    /**
     * 透镜耐久百分比。
     * 透镜放在稳定器的 inventory 第 0 格；耐久 = (最大耐久 - 已损) / 最大耐久。
     * 拿不到就返回 -1（界面显示 "--"），不要假装是 0% —— 那是两回事。
     */
    private static float readLensHealth(TileEntity te, Object lens) {
        if (lens == null) {
            return -1.0F;
        }
        Object inv = getFieldValue(te, "inventory");
        if (!(inv instanceof IItemHandler)) {
            return -1.0F;
        }
        try {
            ItemStack stack = ((IItemHandler) inv).getStackInSlot(0);
            if (stack == null || stack.isEmpty()) {
                return -1.0F;
            }
            int max = stack.getMaxDamage();
            if (max <= 0) {
                return -1.0F;
            }
            int left = max - stack.getItemDamage();
            return Math.max(0.0F, Math.min(100.0F, 100.0F * left / (float) max));
        } catch (Throwable t) {
            return -1.0F;
        }
    }

    // ==================== 结构扫描 ====================

    /**
     * 以 origin 为中心扫一个立方体找 DFC 核心。
     *
     * 只用类名匹配、不加载 HBM 的类，且**只在绑定/重连时调用**。
     * 半径 12 会访问 25^3 = 15625 格，一次性开销约几毫秒，可以接受。
     */
    public static BlockPos scanForCore(World world, BlockPos origin, int radius) {
        if (world == null || origin == null) {
            return null;
        }
        BlockPos.MutableBlockPos cur = new BlockPos.MutableBlockPos();
        for (int dy = -radius; dy <= radius; dy++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    cur.setPos(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    if (isDfcCore(world.getTileEntity(cur))) {
                        return cur.toImmutable();
                    }
                }
            }
        }
        return null;
    }

    // ==================== 通用设备：储罐 / 电池 ====================
    //
    // 自定义显示屏可以"连着看"反应堆之外的设备。需求点名的是
    // **涡轮机 / 各种储罐 / 电池**这类"储存和发电的设备"，本轮先做储罐与电池：
    //   储罐 -> FluidTankNTM（流体名 + 液位）
    //   电池 -> getPower() / getMaxPower()（储能 + 电量）
    // 涡轮机（发电）后续按同一套"反射探测"接口加进来即可，不必改数据结构。
    //
    // 【为什么用探测而不是一份白名单类名】
    // HBM 里光"提到 FluidTankNTM 的方块实体"就有 141 个（桶、各类 machine tank、
    // 化工塔…），白名单永远列不全，而且换一个整合包版本就失效。
    // 这里改成两条通用判据：
    //   1. 有没有 getAllTanks()（HBM 的通用约定），没有就顺着类继承链扫
    //      "类型是 FluidTankNTM（或其数组）"的字段；
    //   2. 有没有 getPower() + getMaxPower() 这一对。
    // 谁能读出数谁就能被连上，不需要预先认识那个类。

    /** 已扫描过的"罐字段"缓存：key = 类 -> 该类（含父类）里所有 FluidTankNTM 字段 */
    private static final Map<Class<?>, List<Field>> TANK_FIELDS =
            new ConcurrentHashMap<Class<?>, List<Field>>();
    /** 类 -> 有没有 getPower/getMaxPower 这一对 */
    private static final Map<Class<?>, Boolean> POWER_API =
            new ConcurrentHashMap<Class<?>, Boolean>();

    /** 这台设备能不能被连接（至少要有一项读得出来的数据） */
    public static boolean isDevice(TileEntity te) {
        return te != null && (!findTanks(te).isEmpty() || hasPowerApi(te) || findTurbineCore(te) != null);
    }

    // ---------- 多方块外壳 / 代理方块 ----------

    /**
     * 类缓存：这个方块实体是不是 HBM 的**代理方块**。
     *
     * 【为什么要专门处理】HBM 的多方块机器（大型储罐、模块化涡轮、各类大机器）只有"核心"
     * 那一格挂真正的机器 TE，周围的外壳格挂的是 {@code TileEntityProxyBase} 子类
     * （{@code TileEntityProxyCombo}/{@code TileEntityProxyEnergy}/…）。
     * 玩家右键的往往是外壳，直接去读它当然读不出东西。
     *
     * 判据取 {@code TileEntityProxyBase} 的两个特征成员：字段 {@code cachedPosition}
     * （核心坐标）+ 方法 {@code getTE()}。认成员而不是认类名，版本换了名字也还成立。
     */
    private static final Map<Class<?>, Boolean> PROXY_CLASS =
            new ConcurrentHashMap<Class<?>, Boolean>();

    private static boolean isProxy(TileEntity te) {
        if (te == null) {
            return false;
        }
        Class<?> c = te.getClass();
        Boolean cached = PROXY_CLASS.get(c);
        if (cached == null) {
            cached = findField(c, "cachedPosition") != null && findMethod(c, "getTE") != null;
            PROXY_CLASS.put(c, cached);
        }
        return cached;
    }

    /**
     * 顺着代理链解到**真正的机器**。
     *
     * 不是代理、或者代理还没绑定核心（{@code getTE()} 返回 null / 返回自己）时，
     * 原样返回入参 —— 调用方拿到的永远是个能用的对象，不用自己判空。
     * 最多跟 3 跳并检测自环，防止 A→B→A 这种互相指向把服务端线程挂死。
     */
    public static TileEntity resolveProxy(TileEntity te) {
        TileEntity cur = te;
        for (int hop = 0; hop < 3 && isProxy(cur); hop++) {
            Object next = callOn(cur, "getTE");
            if (!(next instanceof TileEntity) || next == cur) {
                break;
            }
            cur = (TileEntity) next;
        }
        return cur;
    }

    /**
     * 玩家右键这一格时，**该记哪台设备**。
     *
     * 顺序：
     *   1. 这一格自己的 TE（含代理解链）；
     *   2. 读不出来就扫周围 {@code radius} 格，取**最近**的那台可读设备。
     *
     * 【为什么需要第 2 步】两种很常见的点不到的情况：
     *   - 大模型方块：HBM 的流体桶把模型放大到好几个方块，准心落在桶身上时
     *     射线其实打的是背后的方块（那一格没有 TE）；
     *   - 多方块外壳：外壳那一格可能连 TE 都没有（作者按 meta 返回 null）。
     * 只在"蹲下右键"这条**明确的玩家动作**里扫一次，且只认能读出数的设备，
     * 扫到谁都会在提示里报出名字和坐标，玩家看得出记的是不是自己想记的。
     *
     * @param radius 扫描半径（格）；建议 2
     */
    public static TileEntity findDeviceAt(World world, BlockPos pos, int radius) {
        if (world == null || pos == null) {
            return null;
        }
        TileEntity direct = resolveProxy(world.getTileEntity(pos));
        if (isDevice(direct)) {
            return direct;
        }
        TileEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    TileEntity t = resolveProxy(world.getTileEntity(pos.add(dx, dy, dz)));
                    if (!isDevice(t)) {
                        continue;
                    }
                    double dist = (double) (dx * dx + dy * dy + dz * dz);
                    if (dist < bestDist) {
                        bestDist = dist;
                        best = t;
                    }
                }
            }
        }
        return best;
    }

    /**
     * 探测一台设备，返回它**全部**可读数据槽（坐标、种类、读数都已填好）。
     *
     * 一台机器可能同时给出好几项（多罐机器的每个罐各一项、又带电池的还有储能），
     * 由调用方决定接几项、接到第几个槽 —— 屏幕总共只有 {@link DfcWatch#MAX} 个位置。
     */
    public static List<DfcWatch> probeDevice(TileEntity raw) {
        // 万一是多方块外壳：先解到真机器，否则记下的坐标是外壳的、读数也是空的
        TileEntity te = resolveProxy(raw);
        List<DfcWatch> out = new ArrayList<DfcWatch>();
        if (te == null || te.getWorld() == null) {
            return out;
        }
        int dim = te.getWorld().provider.getDimension();
        int px = te.getPos().getX();
        int py = te.getPos().getY();
        int pz = te.getPos().getZ();
        String dev = deviceName(te);

        List<Object> tanks = findTanks(te);
        for (int i = 0; i < tanks.size(); i++) {
            DfcWatch w = new DfcWatch(px, py, pz, dim, DfcWatch.KIND_FLUID, i);
            w.device = dev;
            w.alive = true;
            readFluid(tanks.get(i), w);
            out.add(w);
        }
        if (hasPowerApi(te)) {
            DfcWatch w = new DfcWatch(px, py, pz, dim, DfcWatch.KIND_ENERGY, 0);
            w.device = dev;
            w.alive = true;
            readEnergy(te, w);
            out.add(w);
        }
        // 模块化涡轮部件（飞轮 / 叶片 / 轴…）：自己没罐没电池，读数是它所属**核心**的
        Object core = findTurbineCore(te);
        if (core != null) {
            DfcWatch spin = new DfcWatch(px, py, pz, dim, DfcWatch.KIND_SPIN, 0);
            spin.device = dev;
            spin.alive = true;
            readTurbineSpin(core, spin);
            out.add(spin);

            DfcWatch power = new DfcWatch(px, py, pz, dim, DfcWatch.KIND_TURBINE_POWER, 0);
            power.device = dev;
            power.alive = true;
            readTurbinePower(core, power);
            out.add(power);
        }
        return out;
    }

    /**
     * 按数据槽里记的坐标重读一次读数。**服务端每 tick（0.05 秒）对每个槽调一次**。
     *
     * 设备被拆掉、或换成了别的方块时，槽**不删除**（那是玩家在 GUI 里的决定），
     * 只把 alive 置 false —— 界面上显示"离线"，玩家一眼就知道是设备没了，
     * 而不是显示屏坏了。
     */
    public static void refreshWatch(World world, DfcWatch w) {
        if (world == null || w == null) {
            return;
        }
        if (w.kind != DfcWatch.KIND_FLUID && w.kind != DfcWatch.KIND_ENERGY
                && w.kind != DfcWatch.KIND_SPIN && w.kind != DfcWatch.KIND_TURBINE_POWER) {
            w.clearReading();
            return;
        }
        if (world.provider.getDimension() != w.dim) {
            w.clearReading();
            return;
        }
        TileEntity te = resolveProxy(world.getTileEntity(new BlockPos(w.x, w.y, w.z)));
        if (te == null) {
            w.clearReading();
            return;
        }
        if (w.kind == DfcWatch.KIND_FLUID) {
            List<Object> tanks = findTanks(te);
            if (w.index < 0 || w.index >= tanks.size()) {
                w.clearReading();
                return;
            }
            w.alive = true;
            readFluid(tanks.get(w.index), w);
            return;
        }
        if (w.kind == DfcWatch.KIND_ENERGY) {
            if (!hasPowerApi(te)) {
                w.clearReading();
                return;
            }
            w.alive = true;
            readEnergy(te, w);
            return;
        }
        // 涡轮部件：读数在核心上，部件被拆 / 涡轮散架时 core 变 null -> 判离线
        Object core = findTurbineCore(te);
        if (core == null) {
            w.clearReading();
            return;
        }
        w.alive = true;
        if (w.kind == DfcWatch.KIND_SPIN) {
            readTurbineSpin(core, w);
        } else {
            readTurbinePower(core, w);
        }
    }

    /**
     * 设备显示名：**按类名归类成中文**，而不是走语言文件。
     *
     * 【为什么不用 Block.getLocalizedName()】它走 I18n，服务端拿不到翻译
     * （只会原样返回语言键），而这个字符串是服务端算出来发出去的。
     * 与其把"翻译键"发到客户端再翻（要为一堆 HBM 方块名兜底），
     * 不如按类名归成几类人话 —— 玩家要的也只是"这是个储罐还是个电池"。
     */
    public static String deviceName(TileEntity te) {
        if (te == null) {
            return "";
        }
        String cn = te.getClass().getSimpleName();
        if (cn.contains("Battery")) {
            return "电池";
        }
        if (cn.contains("Barrel")) {
            return "储罐";
        }
        // FEnSU（飞轮储能）类名不含 Battery 也不是 Tank，是独立的名字，单独判一下
        if (cn.contains("FENSU") || cn.contains("FEnSU")) {
            return "飞轮储能";
        }
        if (cn.contains("Tank")) {
            return "储液罐";
        }
        if (cn.contains("Flywheel")) {
            return "飞轮";
        }
        if (cn.contains("Blade")) {
            return "叶片";
        }
        if (cn.contains("Generator")) {
            return "发电机";
        }
        if (cn.contains("Separator")) {
            return "分离器";
        }
        if (cn.startsWith("TileEntity")) {
            cn = cn.substring("TileEntity".length());
        }
        return cn;
    }

    // ---------- 储罐 ----------

    /** 收集一台设备上所有的 FluidTankNTM（先走后 {@code getAllTanks()}，再退化成字段扫描） */
    private static List<Object> findTanks(TileEntity te) {
        List<Object> out = new ArrayList<Object>();
        if (te == null) {
            return out;
        }
        // 1) HBM 的通用约定：getAllTanks() / getSendingTanks()
        collectTankArray(callOn(te, "getAllTanks"), out);
        if (!out.isEmpty()) {
            return out;
        }
        collectTankArray(callOn(te, "getSendingTanks"), out);
        if (!out.isEmpty()) {
            return out;
        }
        // 2) 退化成字段扫描（有的机器只有 tank / tanks / tankNew 字段）
        for (Field f : tankFields(te.getClass())) {
            Object v;
            try {
                v = f.get(te);
            } catch (Throwable t) {
                continue;
            }
            if (v instanceof Object[]) {
                for (Object o : (Object[]) v) {
                    if (isTankObject(o)) {
                        out.add(o);
                    }
                }
            } else if (isTankObject(v)) {
                out.add(v);
            }
        }
        return out;
    }

    private static void collectTankArray(Object v, List<Object> out) {
        if (v instanceof Object[]) {
            for (Object o : (Object[]) v) {
                if (isTankObject(o)) {
                    out.add(o);
                }
            }
        }
    }

    /** 类名以 FluidTankNTM 结尾（HBM 的流体罐，全整合包只有这一个名字） */
    private static boolean isTankObject(Object o) {
        return o != null && o.getClass().getName().endsWith("FluidTankNTM");
    }

    private static boolean isTankType(Class<?> c) {
        if (c == null) {
            return false;
        }
        if (c.isArray()) {
            return isTankType(c.getComponentType());
        }
        return c.getName().endsWith("FluidTankNTM");
    }

    /** 该类（含父类）所有 FluidTankNTM 字段，结果按类缓存 */
    private static List<Field> tankFields(Class<?> cls) {
        List<Field> cached = TANK_FIELDS.get(cls);
        if (cached != null) {
            return cached;
        }
        List<Field> found = new ArrayList<Field>();
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            Field[] fs;
            try {
                fs = c.getDeclaredFields();
            } catch (Throwable t) {
                continue;
            }
            for (Field f : fs) {
                if (!isTankType(f.getType())) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                } catch (Throwable t) {
                    continue;
                }
                found.add(f);
            }
        }
        TANK_FIELDS.put(cls, found);
        return found;
    }

    /** 把罐里的流体名与液位写进数据槽 */
    private static void readFluid(Object tank, DfcWatch w) {
        double fill = numValue(callOn(tank, "getFill"));
        double max = numValue(callOn(tank, "getMaxFill"));
        w.name = tankTypeKey(tank);
        w.value = DfcFormat.amount(fill) + " / " + DfcFormat.amount(max);
        w.ratio = max > 0.0D ? clamp01(fill / max) : -1.0D;
    }

    /**
     * 罐内流体的**语言键**（服务端只能拿到键，翻译在客户端做）。
     *
     * HBM 的 {@code FluidType.getLocalizedName()} 走 {@code I18nUtil.resolveKey}，
     * 而后者在服务端**原样返回键**（见其字节码：非 client 侧直接 areturn）。
     * 所以这里拿到的就是 "fluid.diesel" 这样的键，客户端收到后
     * {@code I18n.format()} 一下就是"柴油"。取不到键就退回内部 stringId。
     */
    private static String tankTypeKey(Object tank) {
        Object type = callOn(tank, "getTankType");
        if (type == null) {
            return "--";
        }
        Object key = callOn(type, "getTranslationKey");
        if (key instanceof String && !((String) key).isEmpty()) {
            return (String) key;
        }
        Object nm = callOn(type, "getName");
        if (nm instanceof String) {
            String s = ((String) nm).replace('_', ' ').trim();
            return s.isEmpty() ? "--" : s;
        }
        return "--";
    }

    // ---------- 电池 ----------

    /** 有没有 getPower() + getMaxPower() 这一对（HBM 的能量存储通用接口） */
    private static boolean hasPowerApi(TileEntity te) {
        if (te == null) {
            return false;
        }
        Class<?> c = te.getClass();
        Boolean cached = POWER_API.get(c);
        if (cached != null) {
            return cached;
        }
        boolean ok = findMethod(c, "getPower") != null && findMethod(c, "getMaxPower") != null;
        POWER_API.put(c, ok);
        return ok;
    }

    private static void readEnergy(TileEntity te, DfcWatch w) {
        double cur = numValue(callOn(te, "getPower"));
        double max = numValue(callOn(te, "getMaxPower"));
        w.name = "储能";
        w.value = DfcFormat.energy(cur) + " / " + DfcFormat.energy(max);
        w.ratio = max > 0.0D ? clamp01(cur / max) : -1.0D;
    }

    // ---------- 模块化涡轮部件（飞轮等） ----------

    /**
     * 类缓存：这台方块实体"是不是模块化涡轮的部件"。
     *
     * 判据是**有没有一个类型名以 MTCoreTE 结尾的字段**（名字就叫 {@code core}）。
     * 不用类名去匹配 {@code ModularTurbine*}：一是那要引 HBM 的类层次，
     * 二是飞轮/叶片/轴/端口全都是不同类，但共享同一个基类的 core 字段，
     * 认字段比认类名更稳、也更少漏。
     */
    private static final Map<Class<?>, Boolean> TURBINE_PART =
            new ConcurrentHashMap<Class<?>, Boolean>();

    /**
     * 取这台部件所属涡轮核心（{@code MTCoreTE}）。不是涡轮部件 / 没组装好时返回 null。
     *
     * 【为什么要单独一条路径】模块化涡轮的飞轮（{@code MTFlywheelTE}）
     * 自己没有任何罐、也没有 {@code getPower()/getMaxPower()} —— 它就是一块配重。
     * 它唯一能读的东西是**核心的转速**（{@code core.rps}），而核心是它的一个 public 字段。
     * 玩家拿着一台飞轮右键遥控器时，期望看到的正是"这台飞轮转得多快"，
     * 所以这里顺这条字段跳到核心去读。
     *
     * 【组装前 core 为 null】作者把 core 在装配时才连上 —— 没组装好时读不到，
     * 界面显示"离线"，这是对的（散件的飞轮确实没有转速可言）。
     */
    static Object findTurbineCore(TileEntity te) {
        if (te == null) {
            return null;
        }
        Class<?> c = te.getClass();
        Boolean cached = TURBINE_PART.get(c);
        if (cached == null) {
            boolean part = false;
            // 顺继承链找类型名以 MTCoreTE 结尾的字段（core 声明在 ModularTurbineComponentTE 上）
            for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
                Field[] fs;
                try {
                    fs = k.getDeclaredFields();
                } catch (Throwable t) {
                    continue;
                }
                for (Field f : fs) {
                    if (f.getType().getName().endsWith("MTCoreTE")) {
                        part = true;
                        break;
                    }
                }
                if (part) {
                    break;
                }
            }
            TURBINE_PART.put(c, part);
            cached = part;
        }
        if (!cached) {
            return null;
        }
        Object core = getFieldValue(te, "core");
        return core;
    }

    /** 读核心转速（rps）；核心空转 0 也算有效读数（"停着"和"没设备"是两回事） */
    private static void readTurbineSpin(Object core, DfcWatch w) {
        double rps = numValue(getFieldValue(core, "rps"));
        w.name = "转速";
        w.value = DfcFormat.spin(rps);
        w.ratio = DfcFormat.spinRatio(rps);
    }

    /**
     * 读核心输出功率。
     *
     * 用 {@code displayPowerGenerated}（作者给 GUI 显示用的平滑值）优先，
     * 没有就退回 {@code powerOutput}。两个都是 public long 字段。
     */
    private static void readTurbinePower(Object core, DfcWatch w) {
        Object v = getFieldValue(core, "displayPowerGenerated");
        if (!(v instanceof Number)) {
            v = getFieldValue(core, "powerOutput");
        }
        double p = numValue(v);
        w.name = "输出功率";
        w.value = DfcFormat.burst(p);
        // 输出功率没有"满刻度"概念（涡轮能发的功率取决于蒸汽），不画进度条
        w.ratio = -1.0D;
    }

    private static double numValue(Object v) {
        return v instanceof Number ? ((Number) v).doubleValue() : 0.0D;
    }

    private static double clamp01(double v) {
        if (v < 0.0D) {
            return 0.0D;
        }
        return v > 1.0D ? 1.0D : v;
    }

    // ==================== 反射底层 ====================

    private static Method findMethod(Class<?> cls, String name) {
        String key = cls.getName() + "#" + name;
        Method cached = METHODS.get(key);
        if (cached != null) {
            return cached;
        }
        if (MISSING.contains(key)) {
            return null;
        }
        Method found = null;
        try {
            found = cls.getMethod(name);
        } catch (NoSuchMethodException ignored) {
            // 不是 public 或无此方法 -> 再翻一遍声明过的方法（Mixin 织入的方法可能不是 public）
            try {
                Method m = cls.getDeclaredMethod(name);
                m.setAccessible(true);
                found = m;
            } catch (Throwable ignored2) {
                found = null;
            }
        } catch (Throwable t) {
            found = null;
        }
        if (found == null) {
            MISSING.add(key);
        } else {
            METHODS.put(key, found);
        }
        return found;
    }

    private static Object call(TileEntity te, String name) {
        Method m = findMethod(te.getClass(), name);
        if (m == null) {
            return null;
        }
        try {
            return m.invoke(te);
        } catch (Throwable t) {
            return null;
        }
    }

    private static double callDouble(TileEntity te, String name, double def) {
        Object v = call(te, name);
        return v instanceof Number ? ((Number) v).doubleValue() : def;
    }

    private static int callInt(TileEntity te, String name, int def) {
        Object v = call(te, name);
        return v instanceof Number ? ((Number) v).intValue() : def;
    }

    private static long callLong(TileEntity te, String name, long def) {
        Object v = call(te, name);
        return v instanceof Number ? ((Number) v).longValue() : def;
    }

    /** 重载：稳定器的功率等是 public 字段而非方法，单独走字段读取 */
    private static int getIntField(TileEntity te, String name, int def) {
        Object v = getFieldValue(te, name);
        return v instanceof Number ? ((Number) v).intValue() : def;
    }

    private static long getLongField(TileEntity te, String name, long def) {
        Object v = getFieldValue(te, name);
        return v instanceof Number ? ((Number) v).longValue() : def;
    }

    private static boolean getBoolField(TileEntity te, String name, boolean def) {
        Object v = getFieldValue(te, name);
        return v instanceof Boolean ? (Boolean) v : def;
    }

    private static boolean callBool(TileEntity te, String name, boolean def) {
        Object v = call(te, name);
        return v instanceof Boolean ? (Boolean) v : def;
    }

    /** 集合类返回值只取 size（部件坐标列表之类的完整内容对界面没用） */
    private static int callCollectionSize(TileEntity te, String name, int def) {
        Object v = call(te, name);
        return v instanceof List ? ((List<?>) v).size() : def;
    }

    private static Object getFieldValue(Object target, String name) {
        if (target == null) {
            return null;
        }
        Field f = findField(target.getClass(), name);
        if (f == null) {
            return null;
        }
        try {
            return f.get(target);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 对**任意对象**调无参方法。
     *
     * 给 {@code FluidTankNTM} / {@code FluidType} 这类"只想知道一个读数"的类型用：
     * 它们同样不能引类（没装 HBM 时不能崩），但读取路径和 TileEntity 完全一样。
     */
    private static Object callOn(Object target, String name) {
        if (target == null) {
            return null;
        }
        Method m = findMethod(target.getClass(), name);
        if (m == null) {
            return null;
        }
        try {
            return m.invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 字段查找（带**负缓存**）。
     *
     * 负缓存是这次新增的：第二屏每次刷新都要对每个部件读若干字段
     * （isOn / watts / prev / tanks…），一旦某个类缺某个字段，
     * 不做负缓存就会每 0.2 秒重抛一次 NoSuchFieldException，
     * 在反射里抛异常是相当贵的。
     */
    private static Field findField(Class<?> cls, String name) {
        String key = cls.getName() + "#" + name;
        Field cached = FIELDS.get(key);
        if (cached != null) {
            return cached;
        }
        if (MISSING_FIELDS.contains(key)) {
            return null;
        }
        try {
            Field f = cls.getField(name);
            FIELDS.put(key, f);
            return f;
        } catch (Throwable ignored) {
            try {
                Field f = cls.getDeclaredField(name);
                f.setAccessible(true);
                FIELDS.put(key, f);
                return f;
            } catch (Throwable ignored2) {
                MISSING_FIELDS.add(key);
                return null;
            }
        }
    }
}
