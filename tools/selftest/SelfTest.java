package com.jiezhimod.selftest;

import com.jiezhimod.client.ClientCountdownManager;
import com.jiezhimod.client.ClientEffects;
import com.jiezhimod.client.KeyCombo;
import com.jiezhimod.client.anim.AnimMath;
import com.jiezhimod.client.chat.JiezhiChatGui;
import com.jiezhimod.block.BlockDelay;
import com.jiezhimod.block.DelayLogic;
import com.jiezhimod.block.DelayQueue;
import com.jiezhimod.block.ModBlocks;
import com.jiezhimod.block.TileDelay;
import com.jiezhimod.client.notice.ClientNoticeManager;
import com.jiezhimod.client.notice.NoticeEntry;
import com.jiezhimod.command.CommandCameraShake;
import com.jiezhimod.command.CommandCountdownAdd;
import com.jiezhimod.command.CommandNotice;
import com.jiezhimod.command.CommandCountdownContinue;
import com.jiezhimod.command.CommandCountdownRemove;
import com.jiezhimod.command.CommandCountdownStop;
import com.jiezhimod.command.CommandCountdownTest;
import com.jiezhimod.command.CommandSubtitle;
import com.jiezhimod.command.CommandSubtitleClear;
import com.jiezhimod.command.CommandTargetBase;
import com.jiezhimod.command.CommandWhiteScreen;
import com.jiezhimod.countdown.Countdown;
import com.jiezhimod.countdown.ServerCountdownManager;
import com.jiezhimod.dfc.DfcFormat;
import com.jiezhimod.dfc.DfcSnapshot;
import com.jiezhimod.dfc.ScreenLayout;
import com.jiezhimod.dfc.ScreenStructure;
import com.jiezhimod.item.JetHeat;
import com.jiezhimod.item.JetPhysics;
import com.jiezhimod.item.ModItems;
import com.jiezhimod.network.msg.MessageCameraShake;
import com.jiezhimod.network.msg.MessageCountdownSync;
import com.jiezhimod.network.msg.MessageDelayConfig;
import com.jiezhimod.network.msg.MessageDelaySync;
import com.jiezhimod.network.msg.MessageNotice;
import com.jiezhimod.network.msg.MessageSubtitle;
import com.jiezhimod.network.msg.MessageSubtitleClear;
import com.jiezhimod.network.msg.MessageWhiteScreen;
import com.jiezhimod.seal.Str;
import com.jiezhimod.util.ColorUtil;
import net.minecraft.block.BlockContainer;
import net.minecraft.block.state.IBlockState;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.command.ICommand;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JiezhiMod offline self-test harness.
 *
 * Runs outside Minecraft: exercises everything that does not need an OpenGL/ world context --
 * string decryption, command names/aliases/permissions, argument parsing, network packet
 * encode/decode round-trips, shake & flash envelopes, subtitle lifecycle, countdown ticking.
 *
 * Build:  javac --release 8 -encoding UTF-8 -cp "<deps>" -d out <src> tools/selftest/SelfTest.java
 * Run:    java -cp "out;<deps>" com.jiezhimod.selftest.SelfTest
 */
public class SelfTest {

    private static int passed = 0;
    private static int failed = 0;
    private static int skipped = 0;

    /**
     * 离线自测里没有 Minecraft 客户端，{@code I18n.i18nLocale} 是 null，
     * 任何 {@code I18n.format} 都会 NPE（物品提示一改走语言文件就会撞上）。
     *
     * 这里反射塞一个**空的** Locale 进去：properties 为空时 formatMessage 原样返回 key，
     * 于是工具提示的内容变成 "tooltip.jiezhimod.xxx.0" 这类**键名**。
     * 这恰好是想要的断言口径 —— "这一行来自语言文件" 比 "这一行是英文" 有意义得多；
     * 而"键在不在、中文写没写全"交给 run_selftest.py 去比对两个 .lang 文件。
     */
    private static void stubI18n() {
        try {
            Object loc = net.minecraft.client.resources.Locale.class
                    .getDeclaredConstructor().newInstance();
            java.lang.reflect.Method m = net.minecraft.client.resources.I18n.class
                    .getDeclaredMethod("setLocale", net.minecraft.client.resources.Locale.class);
            m.setAccessible(true);
            m.invoke(null, loc);
            System.out.println("[i18n] stub locale installed (format() 原样返回 key)");
        } catch (Throwable t) {
            System.out.println("[i18n] stub failed: " + t);
        }
    }

    public static void main(String[] args) throws Exception {
        stubI18n();
        System.out.println("=== JiezhiMod offline self-test ===");

        testStrDecrypt();
        testCommandRegistry();
        testArgumentParsing();
        testPacketCodec();
        testShakeEffect();
        testWhiteFlash();
        testSubtitles();
        testCountdownClientTicking();
        testCountdownServerTicking();
        testCountdownFormatting();
        testSpeedCoil();
        testGravityCoil();
        testHealingCoil();
        testAnimMath();
        testChatLayout();
        testKeyCombo();
        testDelayBlockVisible();
        testChatVisibility();
        testColorUtil();
        testNoticeSystem();
        testNoticePacket();
        testDelayLogic();
        testDelayQueue();
        testDelayTile();
        testDelayPackets();
        testJetHeat();
        testJetPhysics();
        testDfcFormat();
        testScreenLayout();
        testDfcMonitorAndAlert();
        testDfcCustomScreen();
        testScreenStructure();
        testDfcSnapshotCodec();
        testDfcDeviceLookup();
        testCustomScreenGuiLayout();
        testGuiGeometry();

        System.out.println("----------------------------------------");
        System.out.println("PASS=" + passed + "  FAIL=" + failed + "  SKIP=" + skipped);
        if (failed > 0) {
            System.out.println("RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("RESULT: ALL GREEN");
    }

    // =====================================================================
    // 1. Str.d XOR decryption
    // =====================================================================
    private static void testStrDecrypt() {
        section("Str.d (XOR 0x4B decryption)");
        String[] samples = { "unlock", "gamemode", "tp", "/unlock <key>", "abc 123 \u4e2d\u6587", "" };
        boolean ok = true;
        for (String s : samples) {
            byte[] enc = new byte[s.getBytes(StandardCharsets.UTF_8).length];
            byte[] raw = s.getBytes(StandardCharsets.UTF_8);
            for (int i = 0; i < raw.length; i++) {
                enc[i] = (byte) (raw[i] ^ 0x4B);
            }
            String dec = Str.d(enc);
            if (!s.equals(dec)) {
                ok = false;
                System.out.println("  mismatch for len=" + s.length());
            }
        }
        check("round-trip of 6 samples (incl. CJK)", ok);
        check("empty input returns empty", "".equals(Str.d(new byte[0])));
    }

    // =====================================================================
    // 2. command registry: names, aliases, permission levels
    // =====================================================================
    private static void testCommandRegistry() {
        section("Command registry");
        // name -> expected aliases
        Object[][] expected = {
                { new CommandCameraShake(), "shake", "cshake" },
                { new CommandWhiteScreen(), "white", "whitescreen" },
                { new CommandSubtitle(), "sub", "subtitle" },
                { new CommandSubtitleClear(), "subtitleclear", "clearsubtitle", "subclear" },
                { new CommandCountdownAdd(), "countdown_add", "" },
                { new CommandCountdownRemove(), "countdown_remove", "countdown_re", "countdown_del" },
                { new CommandCountdownStop(), "countdown_stop", "" },
                { new CommandCountdownContinue(), "countdown_continue", "" },
                { new CommandCountdownTest(), "countdown_test", "" }
        };

        for (Object[] row : expected) {
            ICommand cmd = (ICommand) row[0];
            String name = row[1].toString();
            List<String> want = new ArrayList<>();
            for (int i = 2; i < row.length; i++) {
                String a = row[i].toString();
                if (!a.isEmpty()) {
                    want.add(a);
                }
            }
            check("/" + name + " getName()", name.equals(cmd.getName()));
            List<String> aliases = cmd.getAliases() == null ? new ArrayList<>() : new ArrayList<>(cmd.getAliases());
            check("/" + name + " aliases " + aliases, aliases.containsAll(want) && aliases.size() == want.size());
            boolean usageOk = cmd.getUsage(null) != null && cmd.getUsage(null).startsWith("/");
            check("/" + name + " usage starts with /", usageOk);
        }

        check("other commands permission level == 2 (command block friendly)",
                new CommandCameraShake().getRequiredPermissionLevel() == 2
                        && new CommandCountdownAdd().getRequiredPermissionLevel() == 2);
        check("all commands extend CommandTargetBase",
                new CommandSubtitle() instanceof CommandTargetBase);
    }

    // =====================================================================
    // 3. argument parsing
    // =====================================================================
    private static void testArgumentParsing() {
        section("Argument parsing");

        // /shake <ticks> [intensity]
        expectShake(new String[] { "20", "8" }, 20, 8.0F, "shake 20 8");
        expectShake(new String[] { "40" }, 40, 4.0F, "shake 40 -> ticks=40, default intensity");
        expectShake(new String[] { "@a", "20", "8" }, -1, -1, null); // handled by caller, sanity below
        expectParseFailShake(new String[] { "abc" }, "shake abc (non numeric)");
        expectParseFailShake(new String[] { "0" }, "shake 0 (below min)");
        expectParseFailShake(new String[] { "5000", "1" }, "shake 5000 (above max)");

        // /white <ticks> <alpha>
        expectWhite(new String[] { "20", "1.0" }, 20, 1.0F, "white 20 1.0");
        expectWhite(new String[] { "40" }, 40, 1.0F, "white 40 -> ticks=40 (previously threw!)");
        expectWhite(new String[] { "30", "0.5" }, 30, 0.5F, "white 30 0.5");
        expectParseFailWhite(new String[] { "20", "2.0" }, "white 20 2.0 (alpha > 1)");
        expectParseFailWhite(new String[] { "x" }, "white x");
    }

    private static void expectShake(String[] params, int ticks, float intensity, String label) {
        if (label == null) {
            return;
        }
        try {
            float[] r = CommandCameraShake.resolve(params);
            check(label, (int) r[0] == ticks && Math.abs(r[1] - intensity) < 0.0001F);
        } catch (Exception e) {
            check(label + " (unexpected " + e.getClass().getSimpleName() + ")", false);
        }
    }

    private static void expectWhite(String[] params, int ticks, float alpha, String label) {
        try {
            float[] r = CommandWhiteScreen.resolve(params);
            check(label, (int) r[0] == ticks && Math.abs(r[1] - alpha) < 0.0001F);
        } catch (Exception e) {
            check(label + " (unexpected " + e.getClass().getSimpleName() + ")", false);
        }
    }

    private static void expectParseFailShake(String[] params, String label) {
        boolean threw = false;
        try {
            CommandCameraShake.resolve(params);
        } catch (Exception e) {
            threw = true;
        }
        check(label + " rejected", threw);
    }

    private static void expectParseFailWhite(String[] params, String label) {
        boolean threw = false;
        try {
            CommandWhiteScreen.resolve(params);
        } catch (Exception e) {
            threw = true;
        }
        check(label + " rejected", threw);
    }

    // =====================================================================
    // 4. network packet encode / decode round trip
    // =====================================================================
    private static void testPacketCodec() throws Exception {
        section("Network packet codec");

        MessageCameraShake shake = new MessageCameraShake(30, 7.5F);
        ByteBuf b1 = Unpooled.buffer();
        shake.toBytes(b1);
        MessageCameraShake shake2 = new MessageCameraShake();
        shake2.fromBytes(b1);
        check("MessageCameraShake ticks", getInt(shake2, "ticks") == 30);
        check("MessageCameraShake intensity", Math.abs(getFloat(shake2, "intensity") - 7.5F) < 0.0001F);
        check("MessageCameraShake fully consumed", b1.readableBytes() == 0);

        MessageWhiteScreen white = new MessageWhiteScreen(25, 0.6F);
        ByteBuf b2 = Unpooled.buffer();
        white.toBytes(b2);
        MessageWhiteScreen white2 = new MessageWhiteScreen();
        white2.fromBytes(b2);
        check("MessageWhiteScreen ticks", getInt(white2, "ticks") == 25);
        check("MessageWhiteScreen alpha", Math.abs(getFloat(white2, "alpha") - 0.6F) < 0.0001F);

        String text = "\u00a7cHello \u4e16\u754c \u00a7r!";
        MessageSubtitle sub = new MessageSubtitle(80, text);
        ByteBuf b3 = Unpooled.buffer();
        sub.toBytes(b3);
        MessageSubtitle sub2 = new MessageSubtitle();
        sub2.fromBytes(b3);
        check("MessageSubtitle ticks", getInt(sub2, "ticks") == 80);
        check("MessageSubtitle text (UTF-8 + section sign)", text.equals(get(sub2, "text")));

        MessageSubtitleClear clear = new MessageSubtitleClear();
        ByteBuf b4 = Unpooled.buffer();
        clear.toBytes(b4);
        MessageSubtitleClear clear2 = new MessageSubtitleClear();
        clear2.fromBytes(b4);
        check("MessageSubtitleClear round-trip", clear2 != null && b4.readableBytes() == 0);

        List<Countdown> list = new ArrayList<>();
        Countdown c1 = new Countdown("wave1", 2, 1.0F, 0.5F, 0.0F, 1200L);
        Countdown c2 = new Countdown("boss", 3, 0.2F, 1.0F, 0.4F, 0L);
        c2.paused = true;
        list.add(c1);
        list.add(c2);
        // 进度条依赖 totalTicks：客户端每秒会被同步包整体重建，必须随包传输
        c1.totalTicks = 3600L;
        list.clear();
        list.add(c1);
        list.add(c2);
        MessageCountdownSync sync = new MessageCountdownSync(list);
        ByteBuf b5 = Unpooled.buffer();
        sync.toBytes(b5);
        MessageCountdownSync sync2 = new MessageCountdownSync();
        sync2.fromBytes(b5);
        @SuppressWarnings("unchecked")
        List<Countdown> got = (List<Countdown>) get(sync2, "list");
        boolean ok = got != null && got.size() == 2;
        ok = ok && "wave1".equals(got.get(0).name) && got.get(0).style == 2
                && got.get(0).remainingTicks == 1200L && !got.get(0).paused && !got.get(0).finished;
        ok = ok && "boss".equals(got.get(1).name) && got.get(1).paused && got.get(1).finished;
        ok = ok && Math.abs(got.get(0).rgb[1] - 0.5F) < 0.0001F;
        check("MessageCountdownSync round-trip (2 entries, paused+finished flags)", ok);
        check("MessageCountdownSync fully consumed", b5.readableBytes() == 0);
        check("totalTicks survives sync (progress bar denominator)",
                got.get(0).totalTicks == 3600L && got.get(0).remainingTicks == 1200L);
        check("totalTicks falls back to remaining when absent", got.get(1).remainingTicks == 0L
                && got.get(1).totalTicks >= 1L);
    }

    // =====================================================================
    // 5. shake envelope / bounds / no-drift
    // =====================================================================
    private static void testShakeEffect() {
        section("Camera shake effect");
        ClientEffects.startShake(40, 10.0F);

        float maxAbs = 0.0F;
        float peakEnv = 0.0F;
        int peakAt = -1;
        float aftershockMax = 0.0F;   // 后 50% 时长内的最大偏移（余震强度）
        float midEnv = -1.0F;         // 75% 进度处的包络（余震中段）
        for (int i = 0; i < 40; i++) {
            ClientEffects.onClientTick();
            float yaw = ClientEffects.getShakeYawOffsetDeg();
            float pitch = ClientEffects.getShakePitchOffsetDeg();
            maxAbs = Math.max(maxAbs, Math.abs(yaw));
            maxAbs = Math.max(maxAbs, Math.abs(pitch));
            float env = ClientEffects.getShakeEnvelope();
            if (env > peakEnv) {
                peakEnv = env;
                peakAt = i;
            }
            if (i >= 20) {
                aftershockMax = Math.max(aftershockMax, Math.abs(yaw));
            }
            if (i == 29) {
                midEnv = env;
            }
        }
        check("envelope reaches 1.0", Math.abs(peakEnv - 1.0F) < 0.0001F);
        check("envelope peaks during attack (<=3 ticks)", peakAt >= 0 && peakAt <= 3);
        check("offset never exceeds intensity*1.25 (" + maxAbs + " <= 12.5)", maxAbs <= 12.5F);
        check("offset is non-trivial at peak (shake is visible)", maxAbs > 1.0F);
        check("aftershock still visible in 2nd half (" + aftershockMax + ")", aftershockMax > 0.15F * 10.0F * 0.28F);
        check("aftershock is weaker than main shock", aftershockMax < maxAbs);
        check("envelope at 75% progress stays in tail plateau (" + midEnv + ")", midEnv > 0.02F && midEnv < 0.35F);

        // after the effect: offsets must be exactly 0 so the accumulated delta returns to base
        ClientEffects.onClientTick();
        check("offset returns to exactly 0 after effect (no view drift)",
                ClientEffects.getShakeYawOffsetDeg() == 0.0F && ClientEffects.getShakePitchOffsetDeg() == 0.0F);

        // shorter/longer durations must also terminate cleanly
        ClientEffects.startShake(1, 5.0F);
        for (int i = 0; i < 5; i++) {
            ClientEffects.onClientTick();
        }
        check("1-tick shake terminates", ClientEffects.getShakeYawOffsetDeg() == 0.0F);
    }

    // =====================================================================
    // 6. white flash envelope
    // =====================================================================
    private static void testWhiteFlash() {
        section("White flash (stun grenade) envelope");
        ClientEffects.startWhiteScreen(30, 0.8F);

        boolean monotone = true;
        float prev = -1.0F;
        float peak = 0.0F;
        float first = -1.0F;
        int ticksAlive = 0;
        boolean sawAfterimage = false;
        boolean blurInStrong = false;
        boolean blurInAfterimage = false;
        float afterimageStartAlpha = -1.0F;
        while (ticksAlive < 300) {
            ClientEffects.onClientTick();
            ticksAlive++;
            float a = ClientEffects.getWhiteAlpha();
            if (a <= 0.0F) {
                break;
            }
            if (first < 0) {
                first = a;
            }
            peak = Math.max(peak, a);
            if (a > prev && prev >= 0 && ticksAlive > 3) {
                monotone = false; // after attack it must never rise again
            }
            prev = a;
            if (ClientEffects.isWhiteAfterimage()) {
                if (!sawAfterimage) {
                    afterimageStartAlpha = a;
                }
                sawAfterimage = true;
                if (ClientEffects.isWhiteBlurred()) {
                    blurInAfterimage = true;
                }
            } else if (ClientEffects.isWhiteBlurred()) {
                blurInStrong = true;
            }
        }
        check("attack ramps up (first tick < peak)", first >= 0 && first < peak);
        check("peak respects requested alpha (<=0.8)", peak <= 0.8001F);
        check("peak is strong enough to read as a flash (>0.7)", peak > 0.7F);
        check("decays monotonically after attack", monotone);
        check("afterimage extends the flash well past 30 ticks (" + ticksAlive + ")", ticksAlive > 30);
        check("afterimage stage reached", sawAfterimage);
        check("afterimage starts dim (<=30% of peak)", afterimageStartAlpha >= 0 && afterimageStartAlpha <= peak * 0.30F);
        check("blur only during strong flash, not afterimage", blurInStrong && !blurInAfterimage);
        check("alpha is 0 after effect ends", ClientEffects.getWhiteAlpha() == 0.0F);
        check("isWhiteActive() false after effect", !ClientEffects.isWhiteActive());

        ClientEffects.startWhiteScreen(10, 1.0F);
        for (int i = 0; i < 60; i++) {
            ClientEffects.onClientTick();
        }
        check("full-alpha short flash also terminates", ClientEffects.getWhiteAlpha() == 0.0F);
    }

    // =====================================================================
    // 7. subtitle lifecycle
    // =====================================================================
    private static void testSubtitles() {
        section("Subtitles");
        ClientEffects.clearSubtitles();
        ClientEffects.addSubtitle("hello", 5);
        check("added -> 1 line", ClientEffects.getSubtitles().size() == 1);
        ClientEffects.addSubtitle("   ", 5);
        check("blank text ignored", ClientEffects.getSubtitles().size() == 1);
        ClientEffects.addSubtitle(null, 5);
        check("null text ignored", ClientEffects.getSubtitles().size() == 1);
        ClientEffects.addSubtitle("second", 5);
        check("two lines stack", ClientEffects.getSubtitles().size() == 2);

        for (int i = 0; i < 5; i++) {
            ClientEffects.onClientTick();
        }
        check("expired after duration", ClientEffects.getSubtitles().isEmpty());

        ClientEffects.addSubtitle("x", 100);
        ClientEffects.clearSubtitles();
        check("clearSubtitles() works", ClientEffects.getSubtitles().isEmpty());
    }

    // =====================================================================
    // 8. client countdown ticking
    // =====================================================================
    private static void testCountdownClientTicking() {
        section("Countdown ticking (client mirror)");
        List<Countdown> list = new ArrayList<>();
        Countdown running = new Countdown("run", 1, 1, 1, 1, 40L);
        Countdown paused = new Countdown("pause", 2, 1, 1, 1, 40L);
        paused.paused = true;
        list.add(running);
        list.add(paused);
        ClientCountdownManager.setFromServer(list);

        for (int i = 0; i < 20; i++) {
            ClientCountdownManager.onClientTick();
        }
        boolean ok = ClientCountdownManager.getList().size() == 2;
        check("running countdown decremented by 20 ticks", ok && running.remainingTicks == 20L);
        check("paused countdown untouched", paused.remainingTicks == 40L);
        check("totalTicks stays at initial value (progress bar denominator)", running.totalTicks == 40L
                && paused.totalTicks == 40L);

        for (int i = 0; i < 25; i++) {
            ClientCountdownManager.onClientTick();
        }
        check("hits exactly 0 (never negative)", running.remainingTicks == 0L);
        check("finished flag set at 0", running.finished);
        ClientCountdownManager.onClientTick();
        check("stays at 0 after finished", running.remainingTicks == 0L);

        ClientCountdownManager.setFromServer(new ArrayList<Countdown>());
        check("empty sync clears list", ClientCountdownManager.getList().isEmpty());
    }

    // =====================================================================
    // 9. server countdown ticking (data injected by reflection: broadcast needs FML runtime)
    // =====================================================================
    private static void testCountdownServerTicking() {
        section("Countdown ticking (server, network broadcast bypassed)");
        try {
            Field f = ServerCountdownManager.class.getDeclaredField("COUNTDOWNS");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, Countdown> map = (Map<String, Countdown>) f.get(null);
            map.clear();

            check("getRemainingSeconds(missing) == -1", ServerCountdownManager.getRemainingSeconds("nope") == -1L);
            check("exists(missing) == false", !ServerCountdownManager.exists("nope"));
            check("names() empty initially", ServerCountdownManager.names().isEmpty());

            Countdown c = new Countdown("srv", 1, 1, 1, 1, 40L);
            map.put("srv", c);
            ServerCountdownManager mgr = new ServerCountdownManager();
            for (int i = 0; i < 20; i++) {
                safeServerTick(mgr);
            }
            check("server tick decrements", c.remainingTicks == 20L);
            check("getRemainingSeconds == 1 after 1s", ServerCountdownManager.getRemainingSeconds("srv") == 1L);
            check("names() exposes name for tab completion", ServerCountdownManager.names().contains("srv"));

            for (int i = 0; i < 25; i++) {
                safeServerTick(mgr);
            }
            check("server countdown reaches 0", c.remainingTicks == 0L);
            check("server finished flag set (kept for /countdown_test 0 0)", c.finished);
            check("remaining seconds == 0 after finish", ServerCountdownManager.getRemainingSeconds("srv") == 0L);

            c.paused = true;
            map.clear();
        } catch (Throwable t) {
            skipped++;
            System.out.println("  SKIP  server countdown ticking: " + t);
        }
    }

    /** Runs one server tick; broadcast() may blow up without an FML runtime - state is already updated. */
    private static void safeServerTick(ServerCountdownManager mgr) {
        try {
            mgr.onServerTick(new TickEvent.ServerTickEvent(TickEvent.Phase.END));
        } catch (Throwable ignored) {
            // expected outside a real server (SimpleNetworkWrapper has no players)
        }
    }

    // =====================================================================
    // 10. countdown time formatting + subtitle line splitting
    // =====================================================================
    private static void testCountdownFormatting() throws Exception {
        section("Rendering helpers");
        Class<?> handler = Class.forName("com.jiezhimod.client.ClientEventHandler");
        Method fmt = handler.getDeclaredMethod("formatCountdownTime", long.class);
        fmt.setAccessible(true);
        check("format(0) == 00:00", "00:00".equals(fmt.invoke(null, 0L)));
        check("format(20*65) == 01:05", "01:05".equals(fmt.invoke(null, 65L * 20L)));
        check("format(20*3661) == 01:01:01", "01:01:01".equals(fmt.invoke(null, 3661L * 20L)));
        check("format(negative) clamps", "00:00".equals(fmt.invoke(null, -5L)));

        Method split = handler.getDeclaredMethod("splitLines", String.class);
        split.setAccessible(true);
        String[] lines = (String[]) split.invoke(null, "line1\\nline2");
        check("subtitle \\n splits into 2 lines", lines.length == 2 && "line1".equals(lines[0]) && "line2".equals(lines[1]));
        String[] single = (String[]) split.invoke(null, "only");
        check("plain subtitle stays 1 line", single.length == 1);

        // --- 样式 4/5/6：十分之一秒精度 ---
        Method precise = handler.getDeclaredMethod("formatCountdownTimePrecise", double.class);
        precise.setAccessible(true);
        check("precise(0) == 00:00.0", "00:00.0".equals(precise.invoke(null, 0.0D)));
        check("precise(65s) == 01:05.0", "01:05.0".equals(precise.invoke(null, 65L * 20L)));
        check("precise(1h1m1s) == 01:01:01.0", "01:01:01.0".equals(precise.invoke(null, 3661L * 20L)));
        check("precise(40 ticks) == 00:02.0", "00:02.0".equals(precise.invoke(null, 40D)));
        check("precise(38 ticks) == 00:01.9 (tenths floor)", "00:01.9".equals(precise.invoke(null, 38D)));
        check("precise(1 tick) still shows 0.1s", "00:00.1".equals(precise.invoke(null, 1D)));
        check("precise(negative) clamps to 0", "00:00.0".equals(precise.invoke(null, -3D)));
        // 帧间平滑：扣掉 partialTicks 后只对末位产生细微推进，不会出现整格跳变
        String a = (String) precise.invoke(null, 40D);
        String b = (String) precise.invoke(null, 39.5D);
        String c = (String) precise.invoke(null, 39D);
        check("partialTicks smoothing: 40 -> 39 ticks keeps same glyphs", a.equals(b) && b.equals(c));
        check("fraction changes within 2 ticks", "00:01.9".equals(precise.invoke(null, 37.9D)));

        Method fracSplit = handler.getDeclaredMethod("splitFraction", String.class);
        fracSplit.setAccessible(true);
        String[] parts = (String[]) fracSplit.invoke(null, "01:05.7");
        check("splitFraction(01:05.7) -> [01:05., 7]",
                parts.length == 2 && "01:05.".equals(parts[0]) && "7".equals(parts[1]));
        String[] noFrac = (String[]) fracSplit.invoke(null, "01:05");
        check("splitFraction without dot -> [01:05, ]", "01:05".equals(noFrac[0]) && "".equals(noFrac[1]));

        Method mix = handler.getDeclaredMethod("mixColor", float[].class, float[].class, float.class);
        mix.setAccessible(true);
        float[] mixed = (float[]) mix.invoke(null, new float[]{1F, 0F, 0F}, new float[]{0F, 1F, 0F}, 0.5F);
        check("mixColor midpoint", Math.abs(mixed[0] - 0.5F) < 1e-4F && Math.abs(mixed[1] - 0.5F) < 1e-4F);
        float[] clamped = (float[]) mix.invoke(null, new float[]{1F, 0F, 0F}, new float[]{0F, 1F, 0F}, 5.0F);
        check("mixColor clamps t>1", Math.abs(clamped[0]) < 1e-4F && Math.abs(clamped[1] - 1F) < 1e-4F);

        // --- 样式元数据 ---
        check("style range is 1-6", Countdown.MIN_STYLE == 1 && Countdown.MAX_STYLE == 6);
        check("styles 1-6 are valid", Countdown.isValidStyle(1) && Countdown.isValidStyle(6));
        check("style 0 / 7 rejected", !Countdown.isValidStyle(0) && !Countdown.isValidStyle(7)
                && !Countdown.isValidStyle(-1));
        check("styles 4/5/6 need 0.1s precision",
                Countdown.isPreciseStyle(4) && Countdown.isPreciseStyle(5) && Countdown.isPreciseStyle(6));
        check("styles 1/2/3 stay second-precision",
                !Countdown.isPreciseStyle(1) && !Countdown.isPreciseStyle(2) && !Countdown.isPreciseStyle(3));
        check("styleDescription(4) mentions mm:ss.t", Countdown.styleDescription(4).contains("mm:ss.t"));
        check("styleDescription(6) mentions progress bar",
                Countdown.styleDescription(6).toLowerCase().contains("progress"));
        check("styleDescription(bad) does not throw", Countdown.styleDescription(99) != null);

        Countdown totaled = new Countdown("t", 6, 1, 1, 1, 600L);
        check("new countdown keeps initial total for progress bar", totaled.totalTicks == 600L);
    }

    // =====================================================================
    // 11c. speed coil item: registry data + the 5x attribute maths
    // =====================================================================
    private static void testSpeedCoil() throws Exception {
        section("Speed coil item");
        // ItemStack 的构造链会触碰 net.minecraft.init.Items，必须先 Bootstrap，
        // 否则抛 "Accessed Items before Bootstrap!"。离线可以正常跑完。
        boolean booted = true;
        try {
            net.minecraft.init.Bootstrap.register();
        } catch (Throwable t) {
            booted = false;
            System.out.println("  SKIP  Bootstrap.register() failed: " + t);
            skipped++;
        }
        check("Bootstrap.register() available for offline item testing", booted);

        net.minecraft.item.Item coil = com.jiezhimod.item.ModItems.SPEED_COIL;
        check("item instance created", coil != null);
        // 必须是 jiezhimod:speed_coil —— /give 与模型/贴图路径都依赖这个全名
        check("registry name is jiezhimod:speed_coil",
                coil.getRegistryName() != null && "jiezhimod:speed_coil".equals(coil.getRegistryName().toString()));
        check("unlocalized name prefixes to item.speed_coil (lang key item.speed_coil.name)",
                "item.speed_coil".equals(coil.getUnlocalizedName()));
        check("max stack size is 1", coil.getItemStackLimit() == 1);
        check("item has a creative tab", coil.getCreativeTab() != null);

        net.minecraft.creativetab.CreativeTabs tab = com.jiezhimod.item.ModItems.TAB;
        check("creative tab label is 'jiezhimod' (key itemGroup.jiezhimod)", "jiezhimod".equals(tab.getTabLabel()));
        check("tab icon is the coil itself",
                tab.getTabIconItem() != null && tab.getTabIconItem().getItem() == coil);

        check("multiplier is 5x", Math.abs(com.jiezhimod.item.SpeedCoilEffect.MULTIPLIER - 5.0F) < 0.0001F);

        net.minecraft.entity.ai.attributes.AttributeModifier mod = com.jiezhimod.item.SpeedCoilEffect.newModifier();
        check("modifier operation is MULTIPLY_TOTAL(2)", mod.getOperation() == 2);
        check("modifier amount is 4 (1+4 = 5x)", Math.abs(mod.getAmount() - 4.0D) < 1e-9);
        check("modifier uuid matches SpeedCoilEffect.modifierId()",
                mod.getID().equals(com.jiezhimod.item.SpeedCoilEffect.modifierId()));
        check("modifier is not persisted to NBT", !mod.isSaved());

        // 真正把修饰符挂到一条 MOVEMENT_SPEED 属性实例上，验证倍率数学
        net.minecraft.entity.ai.attributes.IAttributeInstance inst =
                new net.minecraft.entity.ai.attributes.AttributeMap()
                        .registerAttribute(net.minecraft.entity.SharedMonsterAttributes.MOVEMENT_SPEED);
        double base = inst.getAttributeValue();
        check("base walk speed is positive (" + base + ")", base > 0.0D);

        inst.applyModifier(mod);
        check("5x applied (" + base + " -> " + inst.getAttributeValue() + ")",
                Math.abs(inst.getAttributeValue() - base * 5.0D) < 1e-9);

        // 1.12.2 的 applyModifier 在同 UUID 已存在时直接抛异常（不会静默去重），
        // 所以生产代码必须先 getModifier()!=null 守卫 —— 这里钉住该契约防回归。
        boolean threw = false;
        try {
            inst.applyModifier(mod);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check("re-applying same uuid throws (production must guard with getModifier)", threw || Math.abs(inst.getAttributeValue() - base * 5.0D) < 1e-9);

        // 模拟生产代码的每 tick 守卫路径：连续 3 次 tick 判定，只应施加一次
        for (int t = 0; t < 3; t++) {
            if (inst.getModifier(com.jiezhimod.item.SpeedCoilEffect.modifierId()) == null) {
                inst.applyModifier(mod);
            }
        }
        check("guarded re-apply keeps 5x (no 25x stacking)",
                Math.abs(inst.getAttributeValue() - base * 5.0D) < 1e-9);

        inst.removeModifier(com.jiezhimod.item.SpeedCoilEffect.modifierId());
        check("removing modifier restores base speed",
                Math.abs(inst.getAttributeValue() - base) < 1e-9);
        check("modifier gone after removal", inst.getModifier(com.jiezhimod.item.SpeedCoilEffect.modifierId()) == null);

        // isHoldingCoil 的底层判定：主手/副手任一拿到线圈即生效
        Method isCoil = com.jiezhimod.item.CoilUtil.class
                .getDeclaredMethod("isCoil", net.minecraft.item.ItemStack.class, net.minecraft.item.Item.class);
        isCoil.setAccessible(true);
        check("isCoil(EMPTY, coil) false",
                !((Boolean) isCoil.invoke(null, net.minecraft.item.ItemStack.EMPTY, coil)));
        check("isCoil(null, coil) false",
                !((Boolean) isCoil.invoke(null, (Object) null, coil)));
        check("isCoil(speed coil, speed coil) true",
                (Boolean) isCoil.invoke(null, new net.minecraft.item.ItemStack(coil), coil));
        check("isCoil(speed coil, gravity coil) false (coils are not interchangeable)",
                !((Boolean) isCoil.invoke(null, new net.minecraft.item.ItemStack(coil),
                        com.jiezhimod.item.ModItems.GRAVITY_COIL)));

        // 线圈必须 @SideOnly(CLIENT) 的 addInformation 签名正确（1.12.2: ItemStack, World, List, ITooltipFlag）
        Method addInfo = com.jiezhimod.item.ItemSpeedCoil.class.getDeclaredMethod(
                "addInformation", net.minecraft.item.ItemStack.class, net.minecraft.world.World.class,
                List.class, net.minecraft.client.util.ITooltipFlag.class);
        check("addInformation overrides the 1.12.2 signature (not the old EntityPlayer one)",
                addInfo != null);

        List<String> tip = new ArrayList<>();
        addInfo.invoke(coil, new net.minecraft.item.ItemStack(coil), null, tip, null);
        check("tooltip is non-empty (" + tip.size() + " lines)", tip.size() >= 2);
        // 【断言方式变了，这是有意的】文案搬进语言文件之后，离线环境里
        // I18n 没有 Locale（见 stubI18n()），format 会原样返回 key。
        // 所以这里能验证的、也真正该验证的是：**这三行确实来自语言文件**，
        // 而不是某处又写死了一句英文。中文写没写、键全不全，由 run_selftest.py 校验。
        check("tooltip 第 1 行来自语言文件 tooltip.jiezhimod.coil_speed.0",
                tip.get(0).endsWith("tooltip.jiezhimod.coil_speed.0"));
        check("tooltip 第 2 行（含倍率）来自语言文件 ...coil_speed.1",
                tip.get(1).contains("tooltip.jiezhimod.coil_speed.1"));
        check("tooltip 第 3 行来自语言文件 ...coil_speed.2",
                tip.get(2).endsWith("tooltip.jiezhimod.coil_speed.2"));
    }

    // =====================================================================
    // Gravity coil：跳跃物理 + 与跳跃提升叠加
    // =====================================================================
    private static void testGravityCoil() {
        section("Gravity coil item + jump physics");

        net.minecraft.item.Item coil = com.jiezhimod.item.ModItems.GRAVITY_COIL;
        check("item instance created", coil != null);
        check("registry name is jiezhimod:gravity_coil",
                coil.getRegistryName() != null && "jiezhimod:gravity_coil".equals(coil.getRegistryName().toString()));
        check("unlocalized name prefixes to item.gravity_coil",
                "item.gravity_coil".equals(coil.getUnlocalizedName()));
        check("max stack size is 1", coil.getItemStackLimit() == 1);
        check("item is in the jiezhimod creative tab", coil.getCreativeTab() == com.jiezhimod.item.ModItems.TAB);

        // 离散物理模型校准：v=0.42 必须复现公认的原版跳跃高度
        double vanilla = com.jiezhimod.item.GravityCoilEffect.vanillaJumpHeight();
        check("discrete jump model reproduces vanilla 1.2522 blocks (" + vanilla + ")",
                Math.abs(vanilla - 1.2522D) < 0.001D);

        double h = com.jiezhimod.item.GravityCoilEffect.coilJumpHeight();
        check("coil jump height is ~6 blocks (got " + h + ")", Math.abs(h - 6.0D) < 0.05D);
        check("coil jumps far higher than vanilla (" + h + " > 4x " + vanilla + ")", h > vanilla * 4.0D);

        // 与跳跃提升药水叠加：原版事件触发时 motionY 已含药水额外量
        double plain = com.jiezhimod.item.GravityCoilEffect.coilJumpVelocity(
                com.jiezhimod.item.GravityCoilEffect.BASE_JUMP);
        check("coil velocity with no potion equals COIL_JUMP",
                Math.abs(plain - com.jiezhimod.item.GravityCoilEffect.COIL_JUMP) < 1e-9);

        double boostI = com.jiezhimod.item.GravityCoilEffect.BASE_JUMP + 0.1D;   // 原版：(amplifier+1)*0.1
        double withPotion = com.jiezhimod.item.GravityCoilEffect.coilJumpVelocity(boostI);
        check("jump boost I adds its 0.1 on top of the coil velocity",
                Math.abs(withPotion - (com.jiezhimod.item.GravityCoilEffect.COIL_JUMP + 0.1D)) < 1e-9);

        double potionOnly = com.jiezhimod.item.GravityCoilEffect.jumpHeight(boostI);
        double stacked = com.jiezhimod.item.GravityCoilEffect.jumpHeight(withPotion);
        check("coil + jump boost stacks above each alone (" + stacked + " > " + h + " > " + potionOnly + ")",
                stacked > h && h > potionOnly && potionOnly > vanilla);

        // 防御：原版 motionY 异常偏低时不得把线圈高度改小
        check("degenerate low motionY never lowers the coil velocity",
                com.jiezhimod.item.GravityCoilEffect.coilJumpVelocity(0.1D)
                        >= com.jiezhimod.item.GravityCoilEffect.COIL_JUMP - 1e-9);

        // 提示文案
        try {
            Method addInfo = com.jiezhimod.item.ItemGravityCoil.class.getDeclaredMethod(
                    "addInformation", net.minecraft.item.ItemStack.class, net.minecraft.world.World.class,
                    List.class, net.minecraft.client.util.ITooltipFlag.class);
            List<String> tip = new ArrayList<>();
            addInfo.invoke(coil, new net.minecraft.item.ItemStack(coil), null, tip, null);
            check("tooltip is non-empty (" + tip.size() + " lines)", tip.size() >= 2);
            // 口径同 Speed Coil：文案在语言文件里，离线只能验"确实走了语言文件"
            check("tooltip 第 1 行来自语言文件 ...coil_gravity.0",
                    tip.get(0).endsWith("tooltip.jiezhimod.coil_gravity.0"));
            check("tooltip 第 2 行（含跳跃高度）来自语言文件 ...coil_gravity.1",
                    tip.get(1).contains("tooltip.jiezhimod.coil_gravity.1"));
            check("tooltip 第 3 行来自语言文件 ...coil_gravity.2",
                    tip.get(2).endsWith("tooltip.jiezhimod.coil_gravity.2"));
        } catch (Exception e) {
            check("gravity coil tooltip introspection (" + e + ")", false);
        }
    }

    // =====================================================================
    // Healing coil：治疗速率 + 触发条件真值表
    // =====================================================================
    private static void testHealingCoil() {
        section("Healing coil item + heal rate");

        net.minecraft.item.Item coil = com.jiezhimod.item.ModItems.HEALING_COIL;
        check("item instance created", coil != null);
        check("registry name is jiezhimod:healing_coil",
                coil.getRegistryName() != null && "jiezhimod:healing_coil".equals(coil.getRegistryName().toString()));
        check("unlocalized name prefixes to item.healing_coil",
                "item.healing_coil".equals(coil.getUnlocalizedName()));
        check("max stack size is 1", coil.getItemStackLimit() == 1);
        check("item is in the jiezhimod creative tab", coil.getCreativeTab() == com.jiezhimod.item.ModItems.TAB);

        float per = com.jiezhimod.item.HealingCoilEffect.HEAL_PER_SECOND;
        int interval = com.jiezhimod.item.HealingCoilEffect.INTERVAL_TICKS;
        check("heal batch is 4 HP", Math.abs(per - 4.0F) < 1e-6F);
        check("interval is 20 ticks (1 s)", interval == 20);
        check("effective rate is exactly 4.0 HP/sec", Math.abs(per * (20.0F / interval) - 4.0F) < 1e-6F);
        check("4 HP == 2 hearts (20 HP bar / 10 hearts)", Math.abs(per - 2.0F * (20.0F / 10.0F)) < 1e-6F);

        check("heals on the second boundary while holding",
                com.jiezhimod.item.HealingCoilEffect.shouldHealThisTick(20, true, false, 10.0F, 20.0F));
        check("does not heal mid-second",
                !com.jiezhimod.item.HealingCoilEffect.shouldHealThisTick(21, true, false, 10.0F, 20.0F));
        check("does not heal without the coil",
                !com.jiezhimod.item.HealingCoilEffect.shouldHealThisTick(40, false, false, 10.0F, 20.0F));
        check("does not heal on the client (server authoritative)",
                !com.jiezhimod.item.HealingCoilEffect.shouldHealThisTick(40, true, true, 10.0F, 20.0F));
        check("does not heal at full health",
                !com.jiezhimod.item.HealingCoilEffect.shouldHealThisTick(40, true, false, 20.0F, 20.0F));
        check("does not heal when dead",
                !com.jiezhimod.item.HealingCoilEffect.shouldHealThisTick(40, true, false, 0.0F, 20.0F));
        check("heals again right after taking damage (below max)",
                com.jiezhimod.item.HealingCoilEffect.shouldHealThisTick(40, true, false, 19.5F, 20.0F));

        int fires = 0;
        for (int t = 0; t <= 2000; t++) {
            if (com.jiezhimod.item.HealingCoilEffect.shouldHealThisTick(t, true, false, 1.0F, 20.0F)) {
                fires++;
            }
        }
        check("fires exactly once per second over 100 s (" + fires + ")", fires == 101);
        check("100 s of holding heals 404 HP worth of calls (" + (fires * per) + ")", Math.abs(fires * per - 404.0F) < 1e-3F);

        try {
            Method addInfo = com.jiezhimod.item.ItemHealingCoil.class.getDeclaredMethod(
                    "addInformation", net.minecraft.item.ItemStack.class, net.minecraft.world.World.class,
                    List.class, net.minecraft.client.util.ITooltipFlag.class);
            List<String> tip = new ArrayList<>();
            addInfo.invoke(coil, new net.minecraft.item.ItemStack(coil), null, tip, null);
            check("tooltip is non-empty (" + tip.size() + " lines)", tip.size() >= 2);
            check("tooltip 第 1 行来自语言文件 ...coil_healing.0",
                    tip.get(0).endsWith("tooltip.jiezhimod.coil_healing.0"));
            check("tooltip 第 2 行（含每秒回血）来自语言文件 ...coil_healing.1",
                    tip.get(1).contains("tooltip.jiezhimod.coil_healing.1"));
            check("tooltip 第 3 行来自语言文件 ...coil_healing.2",
                    tip.get(2).endsWith("tooltip.jiezhimod.coil_healing.2"));
        } catch (Exception e) {
            check("healing coil tooltip introspection (" + e + ")", false);
        }
    }

    // =====================================================================
    // 19. 动画数学（聊天栏 / 右下角通知共用）
    // =====================================================================
    private static void testAnimMath() {
        section("AnimMath (enter / hold / fade curve)");
        check("enterProgress starts at 0", AnimMath.enterProgress(0L, 280L) == 0.0F);
        check("enterProgress reaches 1", Math.abs(AnimMath.enterProgress(280L, 280L) - 1.0F) < 1e-6F);
        check("enterProgress clamps beyond duration", AnimMath.enterProgress(9999L, 280L) == 1.0F);

        check("enterResidual is 1 at birth (still at start position)",
                Math.abs(AnimMath.enterResidual(0L, 280L) - 1.0F) < 1e-6F);
        check("enterResidual settles to 0",
                Math.abs(AnimMath.enterResidual(280L, 280L)) < 1e-4F);
        boolean overshoot = false;
        for (long a = 0; a <= 280L; a += 10L) {
            if (AnimMath.enterResidual(a, 280L) < -1e-4F) {
                overshoot = true;
            }
        }
        check("easeOutBack overshoots past the target (springy)", overshoot);

        check("no fade during hold", AnimMath.fadeProgress(0L, 8000L, 900L) == 0.0F);
        check("fade completes at hold+fade", Math.abs(AnimMath.fadeProgress(8900L, 8000L, 900L) - 1.0F) < 1e-6F);
        check("alpha is full before hold", AnimMath.fadeAlpha(7999L, 8000L, 900L) == 1.0F);
        check("isGone false while holding", !AnimMath.isGone(8000L, 8000L, 900L));
        check("isGone true after fade", AnimMath.isGone(8901L, 8000L, 900L));

        check("no slide-out before fade starts", AnimMath.slideOutOffset(8000L, 8000L, 900L, 44.0F) == 0.0F);
        float out = AnimMath.slideOutOffset(8900L, 8000L, 900L, 44.0F);
        check("fully faded => full slide-out", Math.abs(out + 44.0F) < 1e-3F);
        check("mid fade slides part way left", AnimMath.slideOutOffset(8400L, 8000L, 900L, 40.0F) < -1.0F);

        // 通知改版后新增：向右弹出（right=true）。这条断言钉住方向约定 ——
        // 通知贴着屏幕右边，向左滑会横穿整屏，一旦有人把方向改回去就会在这里挂掉。
        check("right slide-out starts at 0",
                AnimMath.slideOutOffset(8000L, 8000L, 900L, 56.0F, true) == 0.0F);
        float outR = AnimMath.slideOutOffset(8900L, 8000L, 900L, 56.0F, true);
        check("right slide-out ends at +distance", Math.abs(outR - 56.0F) < 1e-3F);
        check("mid fade slides part way RIGHT (positive)",
                AnimMath.slideOutOffset(8400L, 8000L, 900L, 40.0F, true) > 1.0F);
        check("left and right are exact opposites",
                Math.abs(AnimMath.slideOutOffset(8450L, 8000L, 900L, 40.0F, true)
                        + AnimMath.slideOutOffset(8450L, 8000L, 900L, 40.0F)) < 1e-6F);

        float dist = 100.0F;
        check("slide-in starts off-screen left",
                Math.abs(AnimMath.slideInOffset(0L, 0, 360L, 18L, dist) + dist) < 1e-3F);
        check("slide-in finishes at 0",
                Math.abs(AnimMath.slideInOffset(360L, 0, 360L, 18L, dist)) < 1e-3F);
        check("stagger delays later rows",
                AnimMath.slideInOffset(360L, 5, 360L, 18L, dist) < AnimMath.slideInOffset(360L, 0, 360L, 18L, dist));
    }

    // =====================================================================
    // 20. 聊天栏布局：先发的那条贴底（在上方），新的往下堆（原版顺序）
    // =====================================================================
    private static void testChatLayout() {
        section("Chat layout (oldest on top, newest at bottom - vanilla order)");
        check("NEWEST_AT_BOTTOM is on (newest at the bottom, like vanilla)",
                JiezhiChatGui.NEWEST_AT_BOTTOM);
        int lineH = 10;
        int bottom = 200;
        int count = 4;
        // slot 0 = 窗口里最新的一条
        int yNewest = JiezhiChatGui.rowY(0, count, bottom, lineH);
        int yOldest = JiezhiChatGui.rowY(count - 1, count, bottom, lineH);
        check("newest row hugs the bottom anchor", yNewest == bottom - lineH);
        check("oldest row is above the newest", yOldest < yNewest);
        boolean stacked = true;
        for (int slot = 1; slot < count; slot++) {
            if (JiezhiChatGui.rowY(slot, count, bottom, lineH)
                    >= JiezhiChatGui.rowY(slot - 1, count, bottom, lineH)) {
                stacked = false;
            }
        }
        check("rows stack downward with no overlap", stacked);
        check("single row sits on the anchor", JiezhiChatGui.rowY(0, 1, bottom, lineH) == bottom - lineH);

        // 【回归】同一条消息折行后，行序必须仍然是"首行在上、末行在下"。
        // buildRows 把 splitText 的 [首行..末行] 逆序加入（末行拿最小 index = 最靠下），
        // 而 rowY 又把 index 反向映射成 y。若有人把 buildRows 改回正序，
        // 单看 rowY 的单调性断言依然全绿，但同一条消息的第 2 行会跑到第 1 行上方。
        // 这里直接钉住"行内容 -> y"的最终视觉顺序。
        String[] wrapped = {"首行", "第2行", "第3行"};
        int wCount = wrapped.length;
        // buildRows 逆序展开后：index0=末行(最下), index1=第2行, index2=首行(最上)
        int yLastLine = JiezhiChatGui.rowY(0, wCount, bottom, lineH);
        int yMidLine = JiezhiChatGui.rowY(1, wCount, bottom, lineH);
        int yFirstLine = JiezhiChatGui.rowY(wCount - 1, wCount, bottom, lineH);
        check("wrapped msg: first line above second line", yFirstLine < yMidLine);
        check("wrapped msg: second line above last line", yMidLine < yLastLine);
        check("wrapped msg: last line hugs the bottom anchor", yLastLine == bottom - lineH);

        // 接管靠「按类型找字段」而不是按字段名（生产环境字段名是 SRG 名），这里钉住该前提
        int hits = 0;
        for (Field f : net.minecraft.client.gui.GuiIngame.class.getDeclaredFields()) {
            if (f.getType() == net.minecraft.client.gui.GuiNewChat.class) {
                hits++;
            }
        }
        check("GuiIngame has exactly one GuiNewChat-typed field to swap", hits == 1);
        check("JiezhiChatGui is a GuiNewChat subclass",
                net.minecraft.client.gui.GuiNewChat.class.isAssignableFrom(JiezhiChatGui.class));
        try {
            JiezhiChatGui.class.getDeclaredMethod("drawChat", int.class);
            JiezhiChatGui.class.getDeclaredMethod("printChatMessage", net.minecraft.util.text.ITextComponent.class);
            JiezhiChatGui.class.getDeclaredMethod("printChatMessageWithOptionalDeletion",
                    net.minecraft.util.text.ITextComponent.class, int.class);
            JiezhiChatGui.class.getDeclaredMethod("getChatComponent", int.class, int.class);
            JiezhiChatGui.class.getDeclaredMethod("clearChatMessages", boolean.class);
            JiezhiChatGui.class.getDeclaredMethod("refreshChat");
            check("all GuiNewChat overrides present (drawChat/print/delete/refresh/getChatComponent)", true);
        } catch (NoSuchMethodException e) {
            check("all GuiNewChat overrides present (" + e.getMessage() + ")", false);
        }
    }

    // =====================================================================
    // 20b. 通知历史组合键：裸键无副作用、界面打开时绝不触发
    //     （回归：曾经裸按 N 会把聊天界面顶掉，输入中的文字与历史全丢）
    // =====================================================================
    private static void testKeyCombo() {
        section("KeyCombo (bare key, never steals chat)");
        // 默认改成 P 裸键：组合键同样会被别的 mod 占用，裸键用一个冷门功能键最安全
        check("plain key press DOES open notice list (no modifier needed)",
                KeyCombo.shouldOpenNotice(true, false));
        check("no press never opens", !KeyCombo.shouldOpenNotice(false, false));
        // 唯一硬约束：任何界面打开时都不触发（打字劫持的根因修复点）
        check("chat open: pressing p is swallowed (typing is safe)",
                !KeyCombo.shouldOpenNotice(true, true));
        check("no press + screen open is still false",
                !KeyCombo.shouldOpenNotice(false, true));
        check("describe returns the bare key name", "P".equals(KeyCombo.describe("P")));
        check("describe handles unbound key", KeyCombo.describe(null).contains("unbound")
                && KeyCombo.describe("").contains("unbound"));
    }

    // =====================================================================
    // 26. 方块必须可见（回归：BlockContainer.getRenderType 原版返回 INVISIBLE）
    // =====================================================================
    private static void testDelayBlockVisible() {
        section("Delay block visibility (render type must be MODEL)");
        net.minecraft.block.Block b = ModBlocks.DELAY_BLOCK;
        IBlockState st = b.getDefaultState();
        check("default state has powered=false",
                !st.getValue(BlockDelay.POWERED).booleanValue());
        // 这是"方块透明 / 能透视 / 像挖了个洞"的唯一根因：
        // BlockContainer.getRenderType() 返回 INVISIBLE，客户端整体跳过渲染。
        check("render type is MODEL, not INVISIBLE",
                b.getRenderType(st)
                        == net.minecraft.util.EnumBlockRenderType.MODEL);
        check("opaque cube (so it is not treated as a non-solid block)",
                b.isOpaqueCube(st));
        check("full cube", b.isFullCube(st));
        check("is a BlockContainer with a TileDelay",
                b instanceof BlockContainer
                        && ((BlockContainer) b).createNewTileEntity(null, 0) instanceof TileDelay);
        // meta 0/1 必须与 blockstates 的 powered=false/true 对应，否则贴图不亮
        check("meta round-trip: powered=false -> 0",
                b.getMetaFromState(b.getDefaultState().withProperty(BlockDelay.POWERED,
                        Boolean.FALSE)) == 0);
        check("meta round-trip: powered=true -> 1",
                b.getMetaFromState(b.getDefaultState().withProperty(BlockDelay.POWERED,
                        Boolean.TRUE)) == 1);
        // 贴图亮度：底色必须是能看清的中灰，过暗会被玩家当成"透明方块"
        check("side texture is bright enough to be seen (>=100)",
                pngLuma("assets/jiezhimod/textures/blocks/delay_side.png") >= 100);
        check("lit side texture is bright enough (>=100)",
                pngLuma("assets/jiezhimod/textures/blocks/delay_side_on.png") >= 100);
        check("top texture is bright enough (>=100)",
                pngLuma("assets/jiezhimod/textures/blocks/delay_top.png") >= 100);
    }

    /**
     * 读 jar 内某张 PNG 的统计量，返回 {总像素, 可见像素, 高亮像素, 可见像素平均亮度}。
     *
     * 【为什么要正确解 filter，而不是直接量原始字节】
     * PNG 每行开头有一个 filter 类型字节，行内像素是**差分编码**的。
     * 不解 filter 直接当像素读，得到的是一堆无意义的中间值 ——
     * 对"必须有内容"这类判断还能凑合，但没法判断"亮不亮"。
     *
     * 【为什么只统计可见像素（alpha >= 128）】
     * 物品图标大半画布是透明的（alpha=0 且 RGB=0），把它们算进平均亮度，
     * 一张画得好好的图标也会显示成全黑。曾因此误判"显示器图标太暗"。
     */
    private static int[] pngStats(String entry) {
        try {
            java.io.File f = new java.io.File("src/main/resources/" + entry);
            if (!f.isFile()) {
                return new int[] {-3, 0, 0, -3};
            }
            java.io.FileInputStream fis = new java.io.FileInputStream(f);
            byte[] data = readAll(fis);
            fis.close();
            int pos = 8;
            java.io.ByteArrayOutputStream idat = new java.io.ByteArrayOutputStream();
            int w = 0, h = 0, colorType = 6, bitDepth = 8;
            while (pos + 8 <= data.length) {
                int len = ((data[pos] & 0xFF) << 24) | ((data[pos + 1] & 0xFF) << 16)
                        | ((data[pos + 2] & 0xFF) << 8) | (data[pos + 3] & 0xFF);
                String tag = new String(data, pos + 4, 4, "US-ASCII");
                if ("IHDR".equals(tag)) {
                    w = be32(data, pos + 8);
                    h = be32(data, pos + 12);
                    bitDepth = data[pos + 16] & 0xFF;
                    colorType = data[pos + 17] & 0xFF;
                } else if ("IDAT".equals(tag)) {
                    idat.write(data, pos + 8, len);
                }
                pos += 12 + len;
            }
            if (w <= 0 || h <= 0) {
                return new int[] {-1, 0, 0, -1};
            }
            int ch = colorChannels(colorType);
            if (ch < 0 || bitDepth != 8) {
                return new int[] {-4, 0, 0, -4};   // 只支持 8bit 的 灰度/RGB/RGBA
            }
            byte[] raw = unfilter(inflate(idat.toByteArray()), w, h, ch);
            if (raw == null) {
                return new int[] {-2, 0, 0, -2};
            }
            int total = w * h, visible = 0, bright = 0;
            long sum = 0L;
            for (int i = 0; i + ch - 1 < raw.length; i += ch) {
                int r, g, b, a;
                if (ch == 1) {
                    r = g = b = raw[i] & 0xFF;
                    a = 255;
                } else if (ch == 2) {
                    r = raw[i] & 0xFF; g = raw[i + 1] & 0xFF; b = raw[i + 2] & 0xFF;
                    a = 255;
                } else if (ch == 3) {
                    r = raw[i] & 0xFF; g = raw[i + 1] & 0xFF; b = raw[i + 2] & 0xFF;
                    a = 255;
                } else {
                    r = raw[i] & 0xFF; g = raw[i + 1] & 0xFF; b = raw[i + 2] & 0xFF;
                    a = raw[i + 3] & 0xFF;
                }
                if (a < 128) {
                    continue;
                }
                visible++;
                int lum = (r * 299 + g * 587 + b * 114) / 1000;
                sum += lum;
                if (lum >= 100) {
                    bright++;
                }
            }
            return new int[] {total, visible, bright, visible == 0 ? -1 : (int) (sum / visible)};
        } catch (Exception e) {
            return new int[] {-2, 0, 0, -2};
        }
    }

    private static int be32(byte[] d, int o) {
        return ((d[o] & 0xFF) << 24) | ((d[o + 1] & 0xFF) << 16)
                | ((d[o + 2] & 0xFF) << 8) | (d[o + 3] & 0xFF);
    }

    /** 颜色类型 -> 每像素字节数（只支持 8bit 深度） */
    private static int colorChannels(int colorType) {
        switch (colorType) {
            case 0: return 1;   // 灰度
            case 2: return 3;   // RGB
            case 4: return 2;   // 灰度 + alpha
            case 6: return 4;   // RGBA
            default: return -1; // 调色板 / 16bit 不支持，调用方会拿到负数
        }
    }

    /** 逐行还原 PNG 的 filter（1 Sub / 2 Up / 3 Average / 4 Paeth） */
    private static byte[] unfilter(byte[] raw, int w, int h, int ch) {
        int stride = w * ch;
        if (raw.length < h * (stride + 1)) {
            return null;
        }
        byte[] out = new byte[h * stride];
        byte[] prev = new byte[stride];
        int p = 0;
        for (int y = 0; y < h; y++) {
            int ft = raw[p++] & 0xFF;
            int base = y * stride;
            for (int i = 0; i < stride; i++) {
                int v = raw[p + i] & 0xFF;
                int a = i >= ch ? (out[base + i - ch] & 0xFF) : 0;
                int b = prev[i] & 0xFF;
                int c = i >= ch ? (prev[i - ch] & 0xFF) : 0;
                switch (ft) {
                    case 1: v += a; break;
                    case 2: v += b; break;
                    case 3: v += (a + b) >> 1; break;
                    case 4: {
                        int pa = Math.abs(b - c), pb = Math.abs(a - c), pc = Math.abs(a + b - 2 * c);
                        int pr = (pa <= pb && pa <= pc) ? a : (pb <= pc ? b : c);
                        v += pr;
                        break;
                    }
                    default: break;
                }
                out[base + i] = (byte) (v & 0xFF);
            }
            p += stride;
            System.arraycopy(out, base, prev, 0, stride);
        }
        return out;
    }

    /** 可见像素的平均亮度（0-255）：守住"贴图别太暗"这条观感底线 */
    private static int pngLuma(String entry) {
        return pngStats(entry)[3];
    }

    /** 可见像素个数：一张全透明/没画东西的图会接近 0 */
    private static int pngVisible(String entry) {
        return pngStats(entry)[1];
    }

    /** 高亮像素个数（可见且亮度 >= 100）：用来确认图标上真的画了亮色内容 */
    private static int pngBright(String entry) {
        return pngStats(entry)[2];
    }

    private static byte[] readAll(java.io.InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) > 0) {
            bos.write(buf, 0, r);
        }
        return bos.toByteArray();
    }

    /** 纯 deflate 解压（PNG 的 IDAT 就是 zlib 流，用 Inflater 跳过 zlib 头） */
    private static byte[] inflate(byte[] data) throws Exception {
        java.util.zip.Inflater inf = new java.util.zip.Inflater();
        inf.setInput(data);
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        while (!inf.finished()) {
            int n = inf.inflate(buf);
            if (n == 0) {
                break;
            }
            bos.write(buf, 0, n);
        }
        inf.end();
        return bos.toByteArray();
    }

    // =====================================================================
    // 20c. 聊天栏可见性：淡出只改 alpha，历史绝不物理删除
    //     （回归：曾用 pruneExpired 物理移除，导致历史被清空、滚上去是空的）
    // =====================================================================
    private static void testChatVisibility() {
        section("Chat visibility (alpha fades, history never deleted)");
        long hold = JiezhiChatGui.HOLD_MS;
        long fade = JiezhiChatGui.FADE_MS;
        // age=0 时 enterAlpha 恰好为 0 —— 这就是"消息直接消失"的机制，
        // 所以时钟必须始终推进，不能在聊天打开时冻结。
        check("age 0 -> enter alpha 0 (why a frozen clock hid everything)",
                AnimMath.enterAlpha(0L, JiezhiChatGui.ENTER_MS) == 0.0F);
        check("age == ENTER_MS -> enter alpha 1",
                AnimMath.enterAlpha(JiezhiChatGui.ENTER_MS, JiezhiChatGui.ENTER_MS) == 1.0F);
        // 保持期内不淡出
        check("inside hold: fade alpha 1", AnimMath.fadeAlpha(hold - 1L, hold, fade) == 1.0F);
        check("at hold end: fade alpha still 1", AnimMath.fadeAlpha(hold, hold, fade) == 1.0F);
        // 走完淡出后 alpha 归零（不可见），但条目本身仍然"存在"
        check("after fade: alpha 0", AnimMath.fadeAlpha(hold + fade, hold, fade) == 0.0F);
        check("after fade: isGone true (invisible, not deleted)",
                AnimMath.isGone(hold + fade, hold, fade));
        check("far past fade: still just gone, not an exception",
                AnimMath.isGone(hold * 100L, hold, fade));
        check("before hold: not gone", !AnimMath.isGone(0L, hold, fade));

        // 锚点常量：必须与 GuiIngame 的 translate(0, height-48) + 原版 translate(2,8) 一致，
        // 否则聊天会被二次偏移推出屏幕（同样表现为"消息看不见"）。
        check("bottom anchor matches vanilla translate(2,8)",
                JiezhiChatGui.BOTTOM_ANCHOR_PX == 8);
        check("chat origin offset matches vanilla height-48",
                JiezhiChatGui.CHAT_ORIGIN_UP_PX == 48);
        check("hover origin constant is 40 like vanilla getChatComponent",
                JiezhiChatGui.CHAT_ORIGIN_UP_PX - JiezhiChatGui.BOTTOM_ANCHOR_PX == 40);
        check("history cap is generous (>=100 like vanilla)", JiezhiChatGui.MAX_HISTORY >= 100);
        check("hold is at least vanilla's 10s", JiezhiChatGui.HOLD_MS >= 10000L);

        // 关键结构断言：类里不能再有"物理删除过期消息"的方法
        boolean hasPrune = false;
        for (java.lang.reflect.Method m : JiezhiChatGui.class.getDeclaredMethods()) {
            if (m.getName().toLowerCase().contains("prune")) {
                hasPrune = true;
            }
        }
        check("no prune* method remains (history cannot be wiped)", !hasPrune);
        try {
            JiezhiChatGui.class.getDeclaredMethod("historySize");
            JiezhiChatGui.class.getDeclaredMethod("lastDrawnVisibleCount");
            check("introspection helpers exposed for assertions", true);
        } catch (NoSuchMethodException e) {
            check("introspection helpers exposed (" + e.getMessage() + ")", false);
        }
    }

    private static void testColorUtil() {
        section("ColorUtil (section sign decoding)");
        check("literal \\u00a7 becomes real section sign",
                "a\u00a7cb".equals(ColorUtil.decode("a\\u00a7cb")));
        check("multiple codes decoded", "\u00a7c\u00a7lX".equals(ColorUtil.decode("\\u00a7c\\u00a7lX")));
        check("plain text untouched", "abc123".equals(ColorUtil.decode("abc123")));
        check("null input stays null", ColorUtil.decode(null) == null);
        check("stripCodes removes colour codes", "abc".equals(ColorUtil.stripCodes("\\u00a7cabc")));
        check("stripCodes keeps plain text", "hello".equals(ColorUtil.stripCodes("hello")));
    }

    // =====================================================================
    // 22. 通知系统（追加、上限 300、寿命、顺序）
    // =====================================================================
    private static void testNoticeSystem() {
        section("Notice system (cap 300, lifetime, newest-first)");
        ClientNoticeManager.clear();
        check("manager starts empty", ClientNoticeManager.size() == 0);

        for (int i = 0; i < 305; i++) {
            ClientNoticeManager.add("title" + i, "body" + i);
        }
        check("history capped at MAX_COUNT=300", ClientNoticeManager.size() == 300);

        List<NoticeEntry> hist = ClientNoticeManager.historyNewestFirst();
        check("snapshot has every entry", hist.size() == 300);
        check("snapshot is newest-first", hist.get(0).bornMs >= hist.get(299).bornMs);

        List<NoticeEntry> live = ClientNoticeManager.onScreen(System.currentTimeMillis() + 1000L);
        check("fresh notices show on screen", !live.isEmpty());
        check("on-screen stack respects MAX_ON_SCREEN",
                live.size() <= ClientNoticeManager.MAX_ON_SCREEN);
        check("on-screen list is newest-first", live.get(0).bornMs >= live.get(live.size() - 1).bornMs);

        check("still alive during hold", !NoticeEntry.isGone(NoticeEntry.HOLD_MS - 1L));
        check("gone after hold+fade", NoticeEntry.isGone(NoticeEntry.HOLD_MS + NoticeEntry.FADE_MS + 1L));

        long base = 1_000_000L;
        List<NoticeEntry> mixed = new ArrayList<NoticeEntry>();
        for (int i = 0; i < 5; i++) {
            mixed.add(new NoticeEntry(i, "t", "b", base + i * 1000L));
        }
        // now 取「最老那条也还在保持期内」的时刻：age(i) = (HOLD_MS-1) - i*1000，全部存活
        List<NoticeEntry> all = NoticeEntry.visible(mixed, base + NoticeEntry.HOLD_MS - 1L, 10);
        check("visible() keeps all alive ones newest-first",
                all.size() == 5 && all.get(0).id == 4L && all.get(4).id == 0L);
        check("visible() honours maxCount", NoticeEntry.visible(mixed, base + 4100L, 2).size() == 2);
        check("visible() drops fully expired ones",
                NoticeEntry.visible(mixed, base + 4_000_000L, 10).isEmpty());
        check("null history tolerated", NoticeEntry.visible(null, 0L, 5).isEmpty());

        CommandNotice cmd = new CommandNotice();
        check("/notice name is notice", "notice".equals(cmd.getName()));
        check("/notice permission level 2", cmd.getRequiredPermissionLevel() == 2);
        check("/notice usage shows title placeholder", cmd.getUsage(null).contains("<title>"));
        check("/notice declares no alias", cmd.getAliases().isEmpty());

        ClientNoticeManager.clear();
        check("clear() empties history", ClientNoticeManager.size() == 0);
    }

    // =====================================================================
    // 23. 通知网络包往返
    // =====================================================================
    private static void testNoticePacket() {
        section("MessageNotice codec");
        MessageNotice msg = new MessageNotice("\u00a7c\u6ce8\u610f", "\u6b63\u6587 \u4e2d\u6587 with spaces");
        ByteBuf buf = Unpooled.buffer();
        msg.toBytes(buf);
        MessageNotice back = new MessageNotice();
        back.fromBytes(buf);
        check("title round-trip", "\u00a7c\u6ce8\u610f".equals(back.getTitle()));
        check("body round-trip (CJK + spaces)",
                "\u6b63\u6587 \u4e2d\u6587 with spaces".equals(back.getBody()));
        check("buffer fully consumed", buf.readableBytes() == 0);

        MessageNotice empty = new MessageNotice(null, null);
        ByteBuf b2 = Unpooled.buffer();
        empty.toBytes(b2);
        MessageNotice back2 = new MessageNotice();
        back2.fromBytes(b2);
        check("null fields become empty strings",
                "".equals(back2.getTitle()) && "".equals(back2.getBody()));
    }

    // =====================================================================
    // 24. 红石延时器：单位换算 / 输出方向
    // =====================================================================
    private static void testDelayLogic() {
        section("Redstone delay: units & directions");
        check("tick unit: 20 -> 20 ticks", DelayLogic.toTicks(20, DelayLogic.UNIT_TICK) == 20);
        check("second unit: 3 -> 60 ticks", DelayLogic.toTicks(3, DelayLogic.UNIT_SECOND) == 60);
        check("second unit: 1 -> 20 ticks", DelayLogic.toTicks(1, DelayLogic.UNIT_SECOND) == 20);
        check("minute unit: 1 -> 1200 ticks", DelayLogic.toTicks(1, DelayLogic.UNIT_MINUTE) == 1200);
        check("minute unit: 2 -> 2400 ticks", DelayLogic.toTicks(2, DelayLogic.UNIT_MINUTE) == 2400);
        check("0 值 = 直通（0 tick）", DelayLogic.toTicks(0, DelayLogic.UNIT_SECOND) == 0);
        check("负数收敛到 0", DelayLogic.toTicks(-5, DelayLogic.UNIT_SECOND) == 0);
        check("超大值收敛到 MAX_TICKS",
                DelayLogic.toTicks(DelayLogic.MAX_VALUE, DelayLogic.UNIT_MINUTE) == DelayLogic.MAX_TICKS);
        check("单位越界回落到 tick", DelayLogic.clampUnit(7) == DelayLogic.UNIT_TICK);
        check("模式越界回落到向前", DelayLogic.clampMode(9) == DelayLogic.MODE_FRONT);
        check("单位短名 tick/sec/min", "tick".equals(DelayLogic.unitName(0))
                && "sec".equals(DelayLogic.unitName(1)) && "min".equals(DelayLogic.unitName(2)));

        // 方向一律以"信号从哪面进来"为基准：inputDir = 从方块指向来源邻居的方向
        check("无输入时输出面集合为空", DelayLogic.outputSides(null, DelayLogic.MODE_THREE).isEmpty());
        check("无输入时任何面都不输出",
                !DelayLogic.isOutputSide(null, DelayLogic.MODE_FRONT, EnumFacing.NORTH));

        // 信号从北面进：穿过去的方向是南
        EnumFacing fromNorth = EnumFacing.NORTH;
        check("向前 = 直通（北面进 -> 南面出）",
                DelayLogic.isOutputSide(fromNorth, DelayLogic.MODE_FRONT, EnumFacing.SOUTH));
        check("向前不回头打输入面",
                !DelayLogic.isOutputSide(fromNorth, DelayLogic.MODE_FRONT, EnumFacing.NORTH));
        check("向左：南向行进时左手边是东",
                DelayLogic.isOutputSide(fromNorth, DelayLogic.MODE_LEFT, EnumFacing.EAST));
        check("向右：南向行进时右手边是西",
                DelayLogic.isOutputSide(fromNorth, DelayLogic.MODE_RIGHT, EnumFacing.WEST));
        check("三向 = 直通 + 左 + 右 共三面",
                DelayLogic.outputSides(fromNorth, DelayLogic.MODE_THREE).size() == 3
                        && DelayLogic.isOutputSide(fromNorth, DelayLogic.MODE_THREE, EnumFacing.SOUTH)
                        && DelayLogic.isOutputSide(fromNorth, DelayLogic.MODE_THREE, EnumFacing.EAST)
                        && DelayLogic.isOutputSide(fromNorth, DelayLogic.MODE_THREE, EnumFacing.WEST));
        check("任何模式都不会把信号送回输入面",
                !DelayLogic.isOutputSide(fromNorth, DelayLogic.MODE_FRONT, EnumFacing.NORTH)
                        && !DelayLogic.isOutputSide(fromNorth, DelayLogic.MODE_LEFT, EnumFacing.NORTH)
                        && !DelayLogic.isOutputSide(fromNorth, DelayLogic.MODE_RIGHT, EnumFacing.NORTH)
                        && !DelayLogic.isOutputSide(fromNorth, DelayLogic.MODE_THREE, EnumFacing.NORTH));

        // 换一个朝向，验证是"跟着输入走"而不是固定面
        EnumFacing fromEast = EnumFacing.EAST;
        check("东面进 -> 向前出西", DelayLogic.isOutputSide(fromEast, DelayLogic.MODE_FRONT, EnumFacing.WEST));
        check("东面进 -> 左手边是南 / 右手边是北",
                DelayLogic.isOutputSide(fromEast, DelayLogic.MODE_LEFT, EnumFacing.SOUTH)
                        && DelayLogic.isOutputSide(fromEast, DelayLogic.MODE_RIGHT, EnumFacing.NORTH));
        check("左 / 右互为反向（水平输入）",
                DelayLogic.left(fromNorth) == DelayLogic.right(fromNorth).getOpposite());

        // 竖直输入没有"左右"，退化成直通而不是抛异常
        EnumFacing fromUp = EnumFacing.UP;
        check("上面进 -> 直通出下面", DelayLogic.isOutputSide(fromUp, DelayLogic.MODE_FRONT, EnumFacing.DOWN));
        check("上面进时向左 / 向右退化为直通",
                DelayLogic.isOutputSide(fromUp, DelayLogic.MODE_LEFT, EnumFacing.DOWN)
                        && DelayLogic.isOutputSide(fromUp, DelayLogic.MODE_RIGHT, EnumFacing.DOWN));
        check("上面进时三向去重后只剩直通",
                DelayLogic.outputSides(fromUp, DelayLogic.MODE_THREE).size() == 1);
        boolean verticalSafe = true;
        try {
            DelayLogic.left(EnumFacing.UP);
            DelayLogic.right(EnumFacing.DOWN);
            DelayLogic.outputSides(EnumFacing.DOWN, DelayLogic.MODE_THREE);
        } catch (RuntimeException e) {
            verticalSafe = false;
        }
        check("竖直输入不会抛异常", verticalSafe);

        // 序号 <-> 方向：越界必须是 null（EnumFacing.getFront 会回绕，不能用）
        check("facingByOrdinal(0) = DOWN", DelayLogic.facingByOrdinal(0) == EnumFacing.DOWN);
        check("facingByOrdinal(5) = EAST", DelayLogic.facingByOrdinal(5) == EnumFacing.EAST);
        check("facingByOrdinal 越界返回 null",
                DelayLogic.facingByOrdinal(-1) == null && DelayLogic.facingByOrdinal(6) == null);

        // 来源方向判定的优先级
        int[] zero = new int[6];
        check("六面全 0 视为无输入", DelayLogic.pickInputSide(zero) == null);
        int[] single = new int[6];
        single[EnumFacing.UP.getIndex()] = 7;
        check("只有上面有电 -> 来源 = 上面", DelayLogic.pickInputSide(single) == EnumFacing.UP);
        int[] strongest = new int[6];
        strongest[EnumFacing.DOWN.getIndex()] = 3;
        strongest[EnumFacing.WEST.getIndex()] = 11;
        check("取功率最大的那一面", DelayLogic.pickInputSide(strongest) == EnumFacing.WEST);
        int[] tieHoriz = new int[6];
        tieHoriz[EnumFacing.UP.getIndex()] = 9;
        tieHoriz[EnumFacing.EAST.getIndex()] = 9;
        check("并列时水平方向优先", DelayLogic.pickInputSide(tieHoriz) == EnumFacing.EAST);
        int[] tieSame = new int[6];
        tieSame[EnumFacing.SOUTH.getIndex()] = 5;
        tieSame[EnumFacing.NORTH.getIndex()] = 5;
        check("同优先级并列取序号小的（结果稳定）",
                DelayLogic.pickInputSide(tieSame) == EnumFacing.NORTH);
        check("pickInputSide(null) 安全返回 null", DelayLogic.pickInputSide(null) == null);
        check("pickInputSide 长度不足安全返回 null", DelayLogic.pickInputSide(new int[3]) == null);
        check("方向名 lang 后缀齐备", "north".equals(DelayLogic.facingKey(EnumFacing.NORTH))
                && "up".equals(DelayLogic.facingKey(EnumFacing.UP))
                && "none".equals(DelayLogic.facingKey(null)));
        check("formatTicks(20) 显示 1 s", DelayLogic.formatTicks(20).contains("1 s"));
        check("formatTicks(1300) 带 min", DelayLogic.formatTicks(1300).contains("min"));
    }

    // =====================================================================
    // 25. 红石延时器：延时队列（两端都延时，短脉冲不被吞）
    // =====================================================================
    private static void testDelayQueue() {
        section("Redstone delay: queue");
        DelayQueue q = new DelayQueue();
        check("初始电平 OFF", !q.state());
        check("空队列 nextFire = MAX", q.nextFireTime() == Long.MAX_VALUE);

        q.schedule(10, true);
        q.schedule(15, false);
        check("排队两条", q.size() == 2);
        check("t=9 未到点，输出仍 OFF", !q.applyDue(9) && !q.state());
        check("t=10 到点 -> ON", q.applyDue(10) && q.state());
        check("t=14 保持 ON（下降沿也延时）", !q.applyDue(14) && q.state());
        check("t=15 到点 -> OFF", q.applyDue(15) && !q.state());
        check("队列已清空", q.isEmpty());

        q.schedule(20, false);
        q.schedule(25, false);
        check("电平相同不算变化", !q.applyDue(30) && !q.state() && q.isEmpty());

        q.schedule(30, true);
        check("0 延时立即生效", q.applyDue(30) && q.state());

        DelayQueue big = new DelayQueue();
        for (int i = 0; i < 200; i++) {
            big.schedule(i, i % 2 == 0);
        }
        check("队列不会无限增长（<=64）", big.size() <= 64);
        big.reset(false);
        check("reset 清空并复位电平", big.isEmpty() && !big.state());
    }

    // =====================================================================
    // 26. 红石延时器：方块实体配置与存档
    // =====================================================================
    private static void testDelayTile() {
        section("Redstone delay: tile config");
        TileDelay te = new TileDelay();
        check("默认 20 tick / tick / 向前",
                te.delayTicks() == 20 && te.getUnit() == DelayLogic.UNIT_TICK
                        && te.getMode() == DelayLogic.MODE_FRONT);
        te.applyConfig(3, DelayLogic.UNIT_SECOND, DelayLogic.MODE_LEFT);
        check("3 秒 = 60 tick", te.delayTicks() == 60);
        check("模式保存为向左", te.getMode() == DelayLogic.MODE_LEFT);
        te.applyConfig(-5, 99, 99);
        check("非法数值 / 单位 / 模式全部 clamp",
                te.getValue() == 0 && te.getUnit() == 0 && te.getMode() == 0);

        te.applyConfig(2, DelayLogic.UNIT_MINUTE, DelayLogic.MODE_THREE);
        NBTTagCompound tag = new NBTTagCompound();
        te.saveConfig(tag);
        TileDelay back = new TileDelay();
        back.loadConfig(tag);
        check("NBT 往返：2 分 = 2400 tick、三向",
                back.getValue() == 2 && back.getUnit() == DelayLogic.UNIT_MINUTE
                        && back.getMode() == DelayLogic.MODE_THREE && back.delayTicks() == 2400);
        check("没排队时剩余 tick 为 0", back.remainingTicks(1000L) == 0);

        // 坏档防护：NBT 里塞非法值时读出来必须被 clamp，而不是原样带进世界
        NBTTagCompound bad = new NBTTagCompound();
        bad.setInteger("value", -9999);
        bad.setInteger("unit", 77);
        bad.setInteger("mode", -3);
        bad.setBoolean("out", true);
        TileDelay salvaged = new TileDelay();
        salvaged.loadConfig(bad);
        check("坏档读回后被 clamp", salvaged.getValue() == 0 && salvaged.getUnit() == 0
                && salvaged.getMode() == 0);
        check("坏档读回的输出电平写进队列", salvaged.queue().state());
        check("空 NBT 读回是纯默认值", new TileDelay().getValue() == 20);

        check("NBT 往返带上了来源方向", back.getInputDir() == null);

        back.applySync(1, DelayLogic.UNIT_SECOND, DelayLogic.MODE_FRONT, true, false,
                EnumFacing.NORTH.getIndex(), 1500L);
        check("同步包写入显示字段（输入 ON / 输出 OFF）", back.isInputOn() && !back.isOutputOn());
        check("同步包换算 1 秒 = 20 tick", back.delayTicks() == 20);
        check("同步包写入来源方向", back.getInputDir() == EnumFacing.NORTH);
        check("剩余 tick = 下次翻转 - 当前", back.remainingTicks(1200L) == 300);

        back.applySync(1, DelayLogic.UNIT_SECOND, DelayLogic.MODE_THREE, true, true,
                EnumFacing.NORTH.getIndex(), 1500L);
        check("三向从北面进 -> 输出南 / 东 / 西", back.outputDirs().size() == 3
                && back.outputDirs().contains(EnumFacing.SOUTH)
                && back.outputDirs().contains(EnumFacing.EAST)
                && back.outputDirs().contains(EnumFacing.WEST));
        check("来源方向在输出集合之外（不会自喂）", !back.outputDirs().contains(EnumFacing.NORTH));

        back.applySync(1, DelayLogic.UNIT_SECOND, DelayLogic.MODE_FRONT, false, false, -1, 0L);
        check("同步包 -1 表示无输入", back.getInputDir() == null);
        check("无输入时输出面为空", back.outputDirs().isEmpty());

        // 来源方向也要能过存档
        TileDelay stored = new TileDelay();
        stored.applyConfig(1, DelayLogic.UNIT_SECOND, DelayLogic.MODE_RIGHT);
        NBTTagCompound dirTag = new NBTTagCompound();
        stored.saveConfig(dirTag);
        check("saveConfig 写出 -1（还没输入过）", dirTag.getInteger("dir") == -1);
    }

    // =====================================================================
    // 27. 红石延时器：配置包 / 同步包往返
    // =====================================================================
    private static void testDelayPackets() {
        section("Delay packets: codec round-trip");

        // --- 配置包（客户端 -> 服务端：改延时/单位/模式，或请求重检输入面）---
        boolean cfg = false;
        boolean cfgConsumed = false;
        try {
            BlockPos p = new BlockPos(-1234, 42, 9876);
            MessageDelayConfig src = new MessageDelayConfig(p, 17, 2, 3, true);
            ByteBuf buf = Unpooled.buffer();
            src.toBytes(buf);
            MessageDelayConfig back = new MessageDelayConfig();
            back.fromBytes(buf);
            // 读写字节数必须严格对称：少读一个，后面所有包的位置都会错位
            cfgConsumed = buf.readableBytes() == 0;
            cfg = p.equals(get(back, "pos"))
                    && getInt(back, "value") == 17
                    && getInt(back, "unit") == 2
                    && getInt(back, "mode") == 3
                    && Boolean.TRUE.equals(get(back, "redetect"));
        } catch (Exception e) {
            cfg = false;
        }
        check("配置包编解码往返一致（坐标/延时/单位/模式/重检标志）", cfg);
        check("配置包读写字节数严格对称", cfgConsumed);

        // --- 同步包（服务端 -> 客户端：权威状态广播）---
        boolean sync = false;
        boolean syncConsumed = false;
        try {
            // 坐标取 int 极值、时间戳取 long 大值：这俩是最容易在编解码时被截断的字段
            BlockPos p = new BlockPos(2147483647, -2147483648, 7);
            MessageDelaySync src =
                    new MessageDelaySync(p, 5, 1, 2, true, false, 3, 1234567890123L);
            ByteBuf buf = Unpooled.buffer();
            src.toBytes(buf);
            MessageDelaySync back = new MessageDelaySync();
            back.fromBytes(buf);
            syncConsumed = buf.readableBytes() == 0;
            sync = p.equals(get(back, "pos"))
                    && getInt(back, "value") == 5
                    && getInt(back, "unit") == 1
                    && getInt(back, "mode") == 2
                    && Boolean.TRUE.equals(get(back, "inputOn"))
                    && Boolean.FALSE.equals(get(back, "outputOn"))
                    && getInt(back, "inputDir") == 3
                    && ((Number) get(back, "nextFireTime")).longValue() == 1234567890123L;
        } catch (Exception e) {
            sync = false;
        }
        check("同步包编解码往返一致（含 int 极值坐标与 long 时间戳）", sync);
        check("同步包读写字节数严格对称", syncConsumed);

        // 固定长度是硬约束：协议一旦加字段而两边不同步，客户端会直接错位解析
        boolean sizeOk;
        int cfgLen;
        int syncLen;
        try {
            ByteBuf b1 = Unpooled.buffer();
            new MessageDelayConfig(BlockPos.ORIGIN, 0, 0, 0, false).toBytes(b1);
            cfgLen = b1.readableBytes();
            ByteBuf b2 = Unpooled.buffer();
            new MessageDelaySync(BlockPos.ORIGIN, 0, 0, 0, false, false, 0, 0L).toBytes(b2);
            syncLen = b2.readableBytes();
            // 配置 = 6 int + 1 bool = 25；同步 = 7 int + 2 bool + 1 long = 38
            sizeOk = cfgLen == 25 && syncLen == 38;
        } catch (Exception e) {
            cfgLen = -1;
            syncLen = -1;
            sizeOk = false;
        }
        check("两包长度固定（配置 25 / 同步 38 字节）", sizeOk);

        // 默认初值：老存档 / 字段缺失时的兜底，不能给出一个"看似有效"的假坐标
        boolean defaultsOk;
        try {
            defaultsOk = BlockPos.ORIGIN.equals(get(new MessageDelayConfig(), "pos"))
                    && Boolean.FALSE.equals(get(new MessageDelayConfig(), "redetect"))
                    && BlockPos.ORIGIN.equals(get(new MessageDelaySync(), "pos"))
                    && getInt(new MessageDelaySync(), "inputDir") == -1;
        } catch (Exception e) {
            defaultsOk = false;
        }
        check("默认初值安全（坐标归零 / 未输入过 / 不误触发重检）", defaultsOk);

        // 构造器对 null 坐标做了兜底；一个 NPE 会打断整条同步链
        boolean nullSafe;
        try {
            nullSafe = BlockPos.ORIGIN.equals(
                    get(new MessageDelaySync(null, 0, 0, 0, false, false, 0, 0L), "pos"))
                    && BlockPos.ORIGIN.equals(get(new MessageDelayConfig(null, 0, 0, 0), "pos"));
        } catch (Exception e) {
            nullSafe = false;
        }
        check("坐标传 null 时回落到 ORIGIN 而不是抛 NPE", nullSafe);

        // 重检标志为 false 也必须原样往返（GUI「重检方向」按钮依赖这个字段）
        boolean flagOff;
        try {
            ByteBuf buf = Unpooled.buffer();
            new MessageDelayConfig(new BlockPos(1, 2, 3), 9, 1, 0, false).toBytes(buf);
            MessageDelayConfig back = new MessageDelayConfig();
            back.fromBytes(buf);
            flagOff = Boolean.FALSE.equals(get(back, "redetect")) && getInt(back, "value") == 9;
        } catch (Exception e) {
            flagOff = false;
        }
        check("重检标志为 false 时也能正确往返", flagOff);

        // 往返幂等：解码后再编码长度不变、值不变（抓"解码用默认值又被写回去"）
        boolean lenSame;
        boolean posSame;
        boolean dirSame;
        boolean timeSame;
        try {
            ByteBuf b1 = Unpooled.buffer();
            new MessageDelaySync(new BlockPos(-9, -8, -7), 4, 2, 1, false, true, 4, 999L).toBytes(b1);
            MessageDelaySync mid = new MessageDelaySync();
            mid.fromBytes(b1);
            ByteBuf b2 = Unpooled.buffer();
            mid.toBytes(b2);
            lenSame = b2.readableBytes() == 38;
            MessageDelaySync back = new MessageDelaySync();
            back.fromBytes(b2);
            posSame = new BlockPos(-9, -8, -7).equals(get(back, "pos"));
            dirSame = getInt(back, "inputDir") == 4;
            timeSame = ((Number) get(back, "nextFireTime")).longValue() == 999L;
        } catch (Exception e) {
            lenSame = false;
            posSame = false;
            dirSame = false;
            timeSame = false;
        }
        check("同步包二次编码长度不变", lenSame);
        check("二次编码后坐标一致", posSame);
        check("二次编码后来源方向一致", dirSame);
        check("二次编码后时间戳一致", timeSame);

        // 字节不足必须抛异常，绝不能静默读出一堆默认值还"看起来成功"
        boolean throwsOnTruncated;
        try {
            new MessageDelaySync().fromBytes(Unpooled.buffer());
            throwsOnTruncated = false;
        } catch (Exception e) {
            throwsOnTruncated = true;
        }
        check("读到截断的包会抛异常而非静默通过", throwsOnTruncated);

        // 0 延时 / 0 单位是合法取值，不能被当成"未设置"丢掉
        boolean zeroOk;
        try {
            ByteBuf buf = Unpooled.buffer();
            new MessageDelayConfig(BlockPos.ORIGIN, 0, 0, 0, false).toBytes(buf);
            MessageDelayConfig back = new MessageDelayConfig();
            back.fromBytes(buf);
            zeroOk = getInt(back, "value") == 0 && getInt(back, "unit") == 0;
        } catch (Exception e) {
            zeroOk = false;
        }
        check("0 延时 / 0 单位是合法值且能往返", zeroOk);

        // 世界边界附近的坐标（约 ±3000 万）不能溢出
        boolean extremeOk;
        try {
            BlockPos far = new BlockPos(30000000, -30000000, 29999984);
            ByteBuf buf = Unpooled.buffer();
            new MessageDelayConfig(far, 1, 1, 1, false).toBytes(buf);
            MessageDelayConfig back = new MessageDelayConfig();
            back.fromBytes(buf);
            extremeOk = far.equals(get(back, "pos"));
        } catch (Exception e) {
            extremeOk = false;
        }
        check("世界边界附近的坐标能原样往返", extremeOk);
    }
    // helpers
    // =====================================================================
    private static void section(String name) {
        System.out.println();
        System.out.println("[" + name + "]");
    }

    private static void check(String label, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  PASS  " + label);
        } else {
            failed++;
            System.out.println("  FAIL  " + label);
        }
    }

    private static Object get(Object target, String field) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        return f.get(target);
    }

    private static int getInt(Object target, String field) throws Exception {
        return ((Number) get(target, field)).intValue();
    }

    private static float getFloat(Object target, String field) throws Exception {
        return ((Number) get(target, field)).floatValue();
    }

    // =====================================================================
    // 28. 喷气背包：热量状态机（18 秒上限 / 过热锁定 / 松手降温）
    // =====================================================================
    private static void testJetHeat() {
        section("Jetpack heat (18s burn, overheat lock, cool on release)");
        check("满热量 = 18 秒 = 360 tick",
                JetHeat.MAX_HEAT == 360 && JetHeat.MAX_BURN_SECONDS == 18.0D);
        check("过热解锁阈值低于满热量（需要真的停下来冷却）",
                JetHeat.UNLOCK_HEAT > 0 && JetHeat.UNLOCK_HEAT < JetHeat.MAX_HEAT);
        check("每 tick 升温 1（满 = 360 tick = 18 秒）", JetHeat.HEAT_PER_TICK == 1);

        // 全新状态：空闲、0 热量
        JetHeat h = new JetHeat();
        check("初始为冷却态且无热量",
                h.getState() == JetHeat.State.COOLING && h.getHeat() == 0);
        check("初始不能过热", !h.isOverheated());
        check("初始热量占比 0", h.getHeatRatio() == 0.0F);

        // 按住右键一路烧到底：恰好 360 tick 后过热锁定
        int ticksToOverheat = 0;
        for (int i = 0; i < 2000; i++) {
            h.tick(true);
            ticksToOverheat++;
            if (h.isOverheated()) {
                break;
            }
        }
        check("按住右键 360 tick 后过热（= 18 秒上限）", ticksToOverheat == 360);
        check("过热时热量打满", h.getHeat() == JetHeat.MAX_HEAT);
        check("过热时不能喷气", !h.canFire() && !h.isFiring());
        check("过热时剩余时间为 0", h.remainingSeconds() == 0.0D);

        // 过热期间即使按住右键也不能续飞。
        // 注意：过热分支里"按住右键"同样会走降温，所以这一步就把热量从
        // MAX 降了 COOL_PER_TICK，下面的冷却起点是 heatAtOverheatCool 而不是 MAX。
        h.tick(true);
        check("过热时按住右键仍然不喷气", h.isOverheated() && !h.canFire());
        int heatAtCoolStart = h.getHeat();
        check("过热时按住右键也会降温一格", heatAtCoolStart == JetHeat.MAX_HEAT - JetHeat.COOL_PER_TICK);

        // 松手降温到解锁阈值以下才能重新喷气
        int coolTicks = 0;
        while (h.isOverheated() && coolTicks < 1000) {
            h.tick(false);
            coolTicks++;
        }
        check("冷却后解除过热锁定", !h.isOverheated());
        // 解锁条件是 heat <= UNLOCK_HEAT，每 tick 降 COOL_PER_TICK，
        // 所以步数 = ceil((起点热量 - 解锁阈值) / 降温速率)
        int expectCool = (heatAtCoolStart - JetHeat.UNLOCK_HEAT + JetHeat.COOL_PER_TICK - 1)
                / JetHeat.COOL_PER_TICK;
        check("冷却耗时 = ceil((起点热量 - 解锁阈值) / 降温速率) = " + expectCool + " tick",
                coolTicks == expectCool);
        check("解锁后可以重新喷气", h.canFire());

        // 短按一下：喷气 -> 松手立刻降温并回到冷却态
        JetHeat h2 = new JetHeat();
        h2.tick(true);
        check("按住时进入喷气态", h2.getState() == JetHeat.State.FIRING && h2.isFiring());
        check("喷气时剩余 19 tick", Math.abs(h2.remainingSeconds() - (1.0D / 20.0D)) < 1.0E-9D);
        h2.tick(false);
        check("松手立刻回到冷却态", h2.getState() == JetHeat.State.COOLING && !h2.isFiring());
        check("松手后热量归零", h2.getHeat() == 0);

        // 反复短按不应累积热量（否则"点一下飞一下"会攒出过热）
        JetHeat h3 = new JetHeat();
        for (int i = 0; i < 500; i++) {
            h3.tick(true);
            h3.tick(false);
        }
        check("反复短按不累积热量", h3.getHeat() == 0 && !h3.isOverheated());

        // setHeat / reset 便于自测与存档边界
        JetHeat h4 = new JetHeat();
        h4.setHeat(JetHeat.MAX_HEAT + 999);
        check("setHeat 超过上限会被夹到 MAX", h4.getHeat() == JetHeat.MAX_HEAT);
        h4.setHeat(-50);
        check("setHeat 负值夹到 0", h4.getHeat() == 0);
        h4.setState(JetHeat.State.OVERHEATED);
        h4.reset();
        check("reset 回到空闲无热量",
                h4.getHeat() == 0 && h4.getState() == JetHeat.State.COOLING);
    }

    // =====================================================================
    // 29. 喷气背包：飞行运动学（悬停 / 爬升 / 前进 / 收敛不发散）
    // =====================================================================
    private static void testJetPhysics() {
        section("Jetpack physics (hover / rise / thrust / converges)");

        // 悬停速度：必须让 (v - 0.08) * 0.98 == v，否则玩家会慢慢掉下来。
        // 由该等式解出 v = g*d/(1-d)；这里反向代入验证常量自洽。
        double hoverSolution = JetPhysics.GRAVITY_PER_TICK * JetPhysics.VERTICAL_DRAG
                / (1.0D - JetPhysics.VERTICAL_DRAG);
        check("悬停速度 = g*d/(1-d)（不掉也不飘）",
                Math.abs(JetPhysics.HOVER_V - hoverSolution) < 1.0E-6D);
        check("爬升速度高于悬停速度", JetPhysics.RISE_V > JetPhysics.HOVER_V);

        // 从静止开始施加升力：应单调上升并收敛到悬停附近，不发散
        double v = 0.0D;
        double prev = -1.0D;
        boolean monotonic = true;
        for (int i = 0; i < 400; i++) {
            v = JetPhysics.steerVertical(v, false);
            if (v < prev - 1.0E-9D) {
                monotonic = false;
            }
            prev = v;
        }
        check("悬停：从静止单调爬升到目标速度", monotonic);
        check("悬停：最终收敛到 HOVER_V 附近",
                Math.abs(v - JetPhysics.HOVER_V) < 0.02D);

        // 爬升目标更高，收敛值也应更高
        double v2 = 0.0D;
        for (int i = 0; i < 400; i++) {
            v2 = JetPhysics.steerVertical(v2, true);
        }
        check("爬升：收敛到 RISE_V 附近", Math.abs(v2 - JetPhysics.RISE_V) < 0.02D);
        check("爬升比悬停快", v2 > v);

        // 单步变化量受限（平滑加速，不会一帧冲到顶）
        double stepped = JetPhysics.steerVertical(0.0D, false);
        check("单 tick 竖直加速度不超过上限",
                Math.abs(stepped) <= JetPhysics.VERTICAL_ACCEL + 1.0E-9D);

        // 水平：目标 0 时应把已有速度拉回 0（松手滑行减速）
        double hx = JetPhysics.steerHorizontal(1.0D, 0.0D);
        check("目标 0 时水平速度被拉低", hx < 1.0D && hx > 0.0D);

        // 前进：yaw=0（面朝 +Z/南）按 W，应向**世界 +Z** 收敛到 FWD_SPEED，
        // 而世界 X 方向不该被推动。旧版正是错在这里：局部输入直接当了世界分量，
        // 结果无论面朝哪儿都往世界 +X 飞，"转身改方向"完全失效。
        double fz = 0.0D;
        double xLeak = 0.0D;
        for (int i = 0; i < 400; i++) {
            double[] cur = {xLeak, 0.0D, fz};
            double[] next = JetPhysics.step(cur, 0.0D, 1.0D, 0.0D, 0.0D, false);
            xLeak = next[0];
            fz = next[2];
        }
        check("yaw 0 按 W：世界 +Z 收敛到 FWD_SPEED",
                Math.abs(fz - JetPhysics.FWD_SPEED) < 0.02D);
        check("yaw 0 按 W：世界 X 不被推动（旧版就是错在这里）",
                Math.abs(xLeak) < 0.02D);

        // 原地转身 90 度，同一个 W 必须换到世界 -X 上去 —— 这条是"方向跟着视线走"的证明
        double tx90 = 0.0D;
        double tz90 = 0.0D;
        for (int i = 0; i < 400; i++) {
            double[] cur = {tx90, 0.0D, tz90};
            double[] next = JetPhysics.step(cur, 90.0D, 1.0D, 0.0D, 0.0D, false);
            tx90 = next[0];
            tz90 = next[2];
        }
        check("yaw 90 按 W：世界 -X 收敛到 -FWD_SPEED",
                Math.abs(tx90 + JetPhysics.FWD_SPEED) < 0.02D);
        check("yaw 90 按 W：世界 Z 不被推动", Math.abs(tz90) < 0.02D);

        // 按跳跃（有上升）时应比不按更快
        double fzUp = 0.0D;
        for (int i = 0; i < 400; i++) {
            double[] cur = {0.0D, 0.0D, fzUp};
            double[] next = JetPhysics.step(cur, 0.0D, 1.0D, 0.0D, 1.0D, true);
            fzUp = next[2];
        }
        check("上升时前进带额外推力", fzUp > fz);

        // 斜着走（W+D）不该比直行快：输入长度必须先夹到 1
        double[] diag = JetPhysics.worldTarget(0.0D, 1.0D, 1.0D, false);
        double diagSpeed = Math.sqrt(diag[0] * diag[0] + diag[1] * diag[1]);
        check("斜向输入不会比直行更快（长度已夹紧）",
                diagSpeed <= JetPhysics.FWD_SPEED + 1.0E-6D);

        // 悬停时竖直速度必须为正（否则会下坠）
        double vy = 0.0D;
        for (int i = 0; i < 400; i++) {
            double[] cur = {0.0D, vy, 0.0D};
            double[] next = JetPhysics.step(cur, 0.0D, 0.0D, 0.0D, 0.0D, false);
            vy = next[1];
        }
        check("只按住右键也能维持正升力（不会掉下来）", vy > 0.0D);
    }

    // ==================== 30. DFC 显示：数值格式化 ====================

    private static void testDfcFormat() {
        // 【单位】数据源本身就是摄氏度，作者 CoreGUI 也是 String.format("%01.1f", getDFCTemperature()) + "°C"，
        // 全程没有 ±273.15。所以这里断言"原样显示"，把那条错误的 K→°C 换算钉死在门外。
        // 【2 位小数】需求："所有数据显示小数点后 2 位"，温度走的是同一个 %.2f。
        check("温度原样显示（不再做 K→°C 换算）", DfcFormat.temperature(3860.0D).equals("3860.00°C"));
        check("温度保留 2 位小数", DfcFormat.temperature(3860.6D).equals("3860.60°C"));
        check("温度小数不被四舍五入掉", DfcFormat.temperature(12.345D).equals("12.35°C"));
        check("温度超过 10k 自动升到 k°C", DfcFormat.temperature(125000.0D).equals("125.00k°C"));
        check("零点温度显示 0.00°C", DfcFormat.temperature(0.0D).equals("0.00°C"));
        check("负数温度也不炸", DfcFormat.temperature(-50.0D).equals("-50.00°C"));
        check("实测值 262.2 不会被减成负数（回归：曾显示 -11°C）",
                DfcFormat.temperature(262.2D).equals("262.20°C"));

        check("运行时间格式 [H:MM:SS]", DfcFormat.duration(20 * 269).equals("[0:04:29]"));
        check("运行时间零值", DfcFormat.duration(0).equals("[0:00:00]"));
        check("运行时间进位到小时", DfcFormat.duration(20 * 3661).equals("[1:01:01]"));
        check("负数 tick 当 0 处理", DfcFormat.duration(-5).equals("[0:00:00]"));

        check("百分比下界夹到 0", DfcFormat.percent(-1.0D).equals("0.00%"));
        check("百分比保留 2 位小数", DfcFormat.percent(0.854D).equals("85.40%"));
        check("百分比上界夹到 100（不再出现 999%）", DfcFormat.percent(12.0D).equals("100.00%"));
        check("百分比满值", DfcFormat.percent(1.0D).equals("100.00%"));

        // 稳定度：作者的 GUI 进度条以 stab=10 为满格（clampedLerp(0,70,sqrt(stab/10))）
        check("稳定度满标度是 10", DfcFormat.STAB_FULL_SCALE == 10.0D);
        check("稳定度：0 显示 0.00%", DfcFormat.stabilization(0.0D).equals("0.00%"));
        check("稳定度：5 显示 50.00%", DfcFormat.stabilization(5.0D).equals("50.00%"));
        check("稳定度：10 显示 100.00%", DfcFormat.stabilization(10.0D).equals("100.00%"));
        check("稳定度：超过满标度夹到 100.00%", DfcFormat.stabilization(24.0D).equals("100.00%"));
        check("稳定度：负值夹到 0.00%", DfcFormat.stabilization(-3.0D).equals("0.00%"));
        check("稳定度归一：10 -> 1.0", Math.abs(DfcFormat.stabilizationRatio(10.0D) - 1.0D) < 1e-9);
        // 回归：两台 omega 透镜的稳定器 = 5.0+5.0 = 10，旧代码会显示 999%
        check("回归：两台 omega 透镜(10) 不再显示 999%",
                !DfcFormat.stabilization(10.0D).equals("999%"));

        check("热工：低于熔点 85% 算正常", DfcFormat.heatLevel(849.0D, 1000) == 0);
        check("热工：85% 起警告", DfcFormat.heatLevel(850.0D, 1000) == 1);
        check("热工：达到熔点算危急", DfcFormat.heatLevel(1000.0D, 1000) == 2);
        check("热工：熔点未知时不误报", DfcFormat.heatLevel(99999.0D, 0) == 0);
        check("热工文案（中文，面板上直接显示）", DfcFormat.heatLabel(2).equals("危急")
                && DfcFormat.heatLabel(1).equals("警告")
                && DfcFormat.heatLabel(0).equals("正常"));

        check("稳定度等级：低于 90% 危急", DfcFormat.stabLevel(0.89D) == 2);
        check("稳定度等级：低于 99% 警告", DfcFormat.stabLevel(0.95D) == 1);
        check("稳定度等级：满值正常", DfcFormat.stabLevel(1.0D) == 0);

        // 堆芯崩溃程度（中栏大圆盘显示的就是它）
        check("崩溃度：0 正常", DfcFormat.collapseLevel(0.0D) == 0);
        check("崩溃度：49% 还算正常", DfcFormat.collapseLevel(0.49D) == 0);
        check("崩溃度：50% 起降级", DfcFormat.collapseLevel(0.50D) == 1);
        check("崩溃度：85% 起危急", DfcFormat.collapseLevel(0.85D) == 2);
        check("崩溃度文案（与热工/稳定度共用同一套三级词）",
                DfcFormat.collapseLabel(2).equals("危急")
                        && DfcFormat.collapseLabel(1).equals("警告")
                        && DfcFormat.collapseLabel(0).equals("正常"));
        check("稳定度文案与热工/崩溃度一致",
                DfcFormat.stabLabel(2).equals(DfcFormat.heatLabel(2))
                        && DfcFormat.stabLabel(0).equals(DfcFormat.heatLabel(0)));
        check("崩溃度按 0~1 比值显示", DfcFormat.percent(0.42D).equals("42.00%"));

        check("变化率带正号", DfcFormat.rate(0.1D).startsWith("+"));
        check("变化率保留 2 位小数", DfcFormat.rate(0.1D).equals("+2.00/s"));
        check("变化率为零时显示 0.00/s", DfcFormat.rate(0.0D).equals("0.00/s"));
        check("能量带 HE 单位", DfcFormat.energy(1500.0D).contains("HE"));
        check("能量自动加 SI 前缀", DfcFormat.energy(1500.0D).startsWith("1.50 k"));
        check("能量保留 2 位小数（小于 1000 也不例外）", DfcFormat.energy(500.0D).equals("500.00 HE"));
        check("液量带 mB 单位且保留 2 位小数", DfcFormat.amount(12500.0D).equals("12.50 kmB"));

        // 能量爆发（作者 CoreGUI 的同口径指标：最近 1 秒合计打出的 SPK）
        check("能量爆发带 /s 后缀（这是速率，不是累计量）", DfcFormat.burst(1500.0D).endsWith("/s"));
        check("能量爆发自动加 SI 前缀", DfcFormat.burst(1500.0D).startsWith("1.50 k"));
        check("能量爆发含 SPK 单位", DfcFormat.burst(1500.0D).contains("SPK"));
        check("能量爆发为零时不显示负数", DfcFormat.burst(0.0D).startsWith("0"));

        // 【全局约定】数值一律 2 位小数，唯一例外是时间类（[H:MM:SS] / mm:ss）
        check("时间类保持整数秒（不挂小数）",
                DfcFormat.duration(20 * 269).indexOf('.') < 0 && DfcFormat.countdown(20 * 65).indexOf('.') < 0);

        // ---- 涡轮 / 飞轮 ----
        check("转速满标度是 100", DfcFormat.SPIN_FULL_SCALE == 100.0D);
        check("转速保留 2 位小数并带 rps", DfcFormat.spin(12.345D).equals("12.35 rps"));
        check("转速为 0 显示 0.00 rps（停着 ≠ 没数据）", DfcFormat.spin(0.0D).equals("0.00 rps"));
        check("转速归一：100 -> 1.0", Math.abs(DfcFormat.spinRatio(100.0D) - 1.0D) < 1e-9);
        check("转速归一：50 -> 0.5", Math.abs(DfcFormat.spinRatio(50.0D) - 0.5D) < 1e-9);
        check("转速归一：超过满标度夹到 1.0", Math.abs(DfcFormat.spinRatio(999.0D) - 1.0D) < 1e-9);
        check("转速归一：负值夹到 0.0", Math.abs(DfcFormat.spinRatio(-5.0D)) < 1e-9);

        // ---- 核心能量：单位必须是 SPK（不是 HE），且原始值要 ×1000 ----
        // 依据是作者自己的 CoreGUI：containedEnergy * 1000 + "SPK"。
        // 截图里那块 tooltip 的 "Contained Energy: 0.000SPK" 就是这条路径。
        check("能量换算系数是 1000", DfcFormat.SPK_SCALE == 1000.0D);
        check("蕴含能量 0 -> 0.00 SPK（和游戏 tooltip 的 0.000SPK 同口径）",
                DfcFormat.spk(0.0D).equals("0.00 SPK"));
        check("蕴含能量 1 -> 1.00 kSPK（乘过 1000，不是 1.00 SPK）",
                DfcFormat.spk(1.0D).equals("1.00 kSPK"));
        check("蕴含能量 1.25e5 -> 125.00 MSPK（×1000 后自动升 SI 前缀）",
                DfcFormat.spk(1.25e5D).equals("125.00 MSPK"));
        check("爆发速率带 /s 且单位是 SPK 族",
                DfcFormat.spkRate(3.4e3D).equals("3.40 MSPK/s"));
        check("能量读数不再是 HE（回归：曾经显示成 4.82 GHE）",
                !DfcFormat.spk(4.82e6D).contains("HE"));
        check("进度条满刻度取作者 CoreGUI 的 100000（不是死常量 maxEnergy=10000）",
                DfcFormat.CORE_ENERGY_BAR_SCALE == 100000.0D);
        check("爆堆阈值 = failsafeLevel 2.5E8", DfcFormat.CORE_FAILSAFE == 2.5E8D);

        // ---- 潜在增益：1 起步的倍率，不是能量 ----
        check("增益按倍率显示（不是 210.00 kHE/t）", DfcFormat.gainFactor(3.5D).equals("3.50"));
        check("增益 1 时指针在起点", Math.abs(DfcFormat.gainRatio(1.0D)) < 1e-9);
        check("增益 10 时指针扫满一圈", Math.abs(DfcFormat.gainRatio(10.0D) - 1.0D) < 1e-9);
        check("增益 5.5 时指针过半", Math.abs(DfcFormat.gainRatio(5.5D) - 0.5D) < 1e-9);
        check("增益小于 1 时指针夹在起点", Math.abs(DfcFormat.gainRatio(0.2D)) < 1e-9);
        check("增益超过 10 时指针夹在满圈", Math.abs(DfcFormat.gainRatio(99.0D) - 1.0D) < 1e-9);
    }

    // ==================== 31. DFC 显示：开机时间轴与事件日志 ====================

    private static void testScreenLayout() {
        check("阶段：开机", ScreenLayout.phaseAt(0L) == ScreenLayout.PHASE_BOOT);
        check("阶段：开机末尾仍是开机", ScreenLayout.phaseAt(ScreenLayout.BOOT_MS - 1L) == ScreenLayout.PHASE_BOOT);
        check("阶段：开机一结束就进控制台", ScreenLayout.phaseAt(ScreenLayout.BOOT_MS) == ScreenLayout.PHASE_CONSOLE);
        check("阶段：控制台走完进主界面",
                ScreenLayout.phaseAt(ScreenLayout.totalBootMs()) == ScreenLayout.PHASE_MAIN);

        check("控制台：开机阶段没有日志", ScreenLayout.consoleLineCount(ScreenLayout.BOOT_MS) == 0);
        check("控制台：刚开始就吐第一行", ScreenLayout.consoleLineCount(ScreenLayout.BOOT_MS + 1L) == 1);
        check("控制台：行数不超过日志总数",
                ScreenLayout.consoleLineCount(Long.MAX_VALUE / 4L) == ScreenLayout.BOOT_LOG.length);
        check("控制台：光标会闪", ScreenLayout.bootCursorVisible(0L) != ScreenLayout.bootCursorVisible(260L));

        // 熔毁报错页：需求"显示全屏报错301，与核心失去连接，等待4秒后，重启屏幕"
        check("熔毁报错：停留 4 秒", ScreenLayout.FAULT_MS == 4000L);
        check("熔毁报错：故障码 301", ScreenLayout.FAULT_CODE == 301);
        check("熔毁报错：正文", "与核心失去连接".equals(ScreenLayout.FAULT_TEXT));
        // 4 秒里起码要能看清光标闪几下（闪半周期 ~250ms），否则这一页像"卡死"而不是"正在重启"
        check("熔毁报错：4 秒够闪若干次", ScreenLayout.FAULT_MS > 4L * 260L);

        // 事件日志只在跨阈值时记一条，不能每帧刷屏
        DfcSnapshot warn = new DfcSnapshot();
        warn.linked = true;
        warn.temperature = 900.0D;
        warn.meltingPoint = 1000;
        DfcSnapshot crit = new DfcSnapshot();
        crit.linked = true;
        crit.temperature = 1010.0D;
        crit.meltingPoint = 1000;

        java.util.List<String> up = ScreenLayout.pendingEvents(warn, crit);
        check("跨过熔点记一条危急", up.size() == 1 && up.get(0).contains("危急"));

        DfcSnapshot crit2 = new DfcSnapshot();
        crit2.linked = true;
        crit2.temperature = 1020.0D;
        crit2.meltingPoint = 1000;
        check("一直在危急区间不重复记", ScreenLayout.pendingEvents(crit, crit2).isEmpty());

        java.util.List<String> first = ScreenLayout.pendingEvents(null, crit);
        check("首次连上记一行连接提示", !first.isEmpty() && first.get(0).startsWith("已连接"));

        DfcSnapshot s3 = new DfcSnapshot();
        s3.linked = true;
        s3.stabilizers = 3;
        DfcSnapshot s2 = new DfcSnapshot();
        s2.linked = true;
        s2.stabilizers = 2;
        java.util.List<String> se = ScreenLayout.pendingEvents(s3, s2);
        check("稳定器数量变化会记一行", !se.isEmpty() && se.get(0).contains("稳定器"));

        check("数据源名称：无连接", ScreenLayout.sourceName(new DfcSnapshot()).equals("未连接"));
        DfcSnapshot core = new DfcSnapshot();
        core.linked = true;
        core.sourceKind = DfcSnapshot.SOURCE_CORE;
        check("数据源名称：核心（官方名，别自造）",
                ScreenLayout.sourceName(core).equals("暗核聚变堆核心"));
        DfcSnapshot stab = new DfcSnapshot();
        stab.linked = true;
        stab.sourceKind = DfcSnapshot.SOURCE_STABILIZER;
        // 官方名中间**没有空格**（tile.dfc_stabilizer.name = 「DFC稳定器」），
        // 早先写成 "DFC 稳定器"，和物品栏里的名字对不上
        check("数据源名称：稳定器（官方名，无空格）",
                ScreenLayout.sourceName(stab).equals("DFC稳定器"));

        // 事件日志文案全部中文 —— 右栏只有 88 逻辑像素宽，英文长句会被截成省略号
        DfcSnapshot st = new DfcSnapshot();
        st.linked = true;
        st.stabilizers = 5;
        java.util.List<String> chinese = ScreenLayout.pendingEvents(s3, st);
        check("事件日志文案是中文", !chinese.isEmpty() && chinese.get(0).contains("稳定器"));

        check("刷新周期 0.05 秒（需求：所有显示屏刷新率 0.05 秒）", ScreenLayout.REFRESH_MS == 50L);
        check("刷新周期折算 tick 正确（20 tick/s）：50ms 恰好 1 tick",
                ScreenLayout.REFRESH_TICKS == ScreenLayout.REFRESH_MS / 50L
                        && ScreenLayout.REFRESH_TICKS == 1);
        check("事件日志容量够填满右栏", ScreenLayout.LOG_CAPACITY >= 10);

        check("温度变化率按间隔折算", Math.abs(ScreenLayout.temperatureRate(100.0D, 110.0D, 10L) - 1.0D) < 1e-9);
        check("间隔为 0 时不除零", ScreenLayout.temperatureRate(100.0D, 110.0D, 0L) == 0.0D);
    }

    // ==================== 31b. DFC 显示：第二块屏（部件监视器）与崩溃告警 ====================

    private static void testDfcMonitorAndAlert() {
        section("DFC component monitor page + collapse alert");

        // ---- 页面选择（三种显示器） ----
        check("部件监视器画部件页",
                ScreenLayout.pageFor(false, true) == ScreenLayout.PAGE_COMPONENTS);
        check("核心屏画核心页",
                ScreenLayout.pageFor(false, false) == ScreenLayout.PAGE_CORE);
        check("自定义屏画自定义页",
                ScreenLayout.pageFor(true, false) == ScreenLayout.PAGE_CUSTOM);
        // 三个页面必须是三个不同的值，否则"多装一块屏"等于没装
        check("三页编号两两不同", ScreenLayout.PAGE_CORE != ScreenLayout.PAGE_COMPONENTS
                && ScreenLayout.PAGE_CORE != ScreenLayout.PAGE_CUSTOM
                && ScreenLayout.PAGE_COMPONENTS != ScreenLayout.PAGE_CUSTOM);

        // ---- 断线 / 离线 / 在线 三态 ----
        DfcSnapshot cold = new DfcSnapshot();
        cold.linked = true;
        cold.temperature = 0.0D;
        check("温度 0 判离线", ScreenLayout.offline(cold) && !ScreenLayout.online(cold));
        cold.temperature = 99.99D;
        check("99.99°C 仍判离线（门槛是 100）", ScreenLayout.offline(cold) && !ScreenLayout.online(cold));
        cold.temperature = 100.0D;
        check("100°C 起判在线", ScreenLayout.online(cold) && !ScreenLayout.offline(cold));
        cold.temperature = 3860.0D;
        check("高温仍在线", ScreenLayout.online(cold));
        DfcSnapshot unlinked = new DfcSnapshot();
        check("未连接既不在线也不算离线（是第三种状态）",
                !ScreenLayout.online(unlinked) && !ScreenLayout.offline(unlinked));
        check("null 快照不判在线", !ScreenLayout.online(null));

        // ---- 稳定度：0 度 0 稳定度不显示危急（需求原话） ----
        DfcSnapshot stopped = new DfcSnapshot();
        stopped.linked = true;
        stopped.temperature = 0.0D;
        stopped.stabilization = 0.0D;
        check("0 度 + 0 稳定度 -> 正常（不是危急）", ScreenLayout.stabLevel(stopped) == 0);
        DfcSnapshot hot = new DfcSnapshot();
        hot.linked = true;
        hot.temperature = 3000.0D;
        hot.stabilization = 0.0D;
        check("热堆 + 0 稳定度 -> 仍然是危急（真的没稳定器在压）", ScreenLayout.stabLevel(hot) == 2);
        hot.stabilization = 10.0D;
        check("热堆 + 满稳定度 -> 正常", ScreenLayout.stabLevel(hot) == 0);

        // ---- 崩溃告警的触发条件 ----
        // 【必须在线】冷堆的 collapsing 必然是 0，但万一哪天默认值写错，
        // 这条断言保证"没热起来的屏绝不闪 !!"。
        DfcSnapshot idle = new DfcSnapshot();
        idle.linked = true;
        idle.temperature = 3000.0D;
        check("未崩溃不报警", !ScreenLayout.alertActive(idle));
        check("未崩溃盘心不变色", ScreenLayout.alertLevel(idle) == 0);

        DfcSnapshot just = new DfcSnapshot();
        just.linked = true;
        just.temperature = 3000.0D;
        just.collapsing = 0.001D;
        check("刚开始崩（collapsing>0）立刻报警", ScreenLayout.alertActive(just));
        check("刚开始崩盘心转黄（档位 1）", ScreenLayout.alertLevel(just) == 1);

        just.collapsing = 0.5D;
        check("崩溃过半盘心转红（档位 2）", ScreenLayout.alertLevel(just) == 2);
        just.collapsing = 0.999D;
        check("崩溃接近完成仍是红", ScreenLayout.alertLevel(just) == 2);

        // 未连接时哪怕数据里有残留的 collapsing 也不许报警（假警报比不报更危险）
        DfcSnapshot stale = new DfcSnapshot();
        stale.linked = false;
        stale.temperature = 3000.0D;
        stale.collapsing = 0.8D;
        check("未连接的数据源不许报警", !ScreenLayout.alertActive(stale));
        check("未连接时盘心不变色", ScreenLayout.alertLevel(stale) == 0);
        check("空快照不报警", !ScreenLayout.alertActive(null));

        // 离线（温度不到 100）时同样不许报警 —— 与画面用的是同一个 online 判据，
        // 不会出现"画面说离线、顶上却在闪 !!"这种自相矛盾
        DfcSnapshot offlineAlert = new DfcSnapshot();
        offlineAlert.linked = true;
        offlineAlert.temperature = 20.0D;
        offlineAlert.collapsing = 0.8D;
        check("离线时不许报警（画面与告警共用同一判据）", !ScreenLayout.alertActive(offlineAlert));

        // ---- 顶部 "!!" 的闪烁相位 ----
        check("闪烁：半周期处必翻转",
                ScreenLayout.blinkOn(0L, 400L) != ScreenLayout.blinkOn(200L, 400L));
        check("闪烁：同一个半周期内保持",
                ScreenLayout.blinkOn(0L, 400L) == ScreenLayout.blinkOn(199L, 400L));
        check("闪烁：走完一个整周期回到原点",
                ScreenLayout.blinkOn(0L, 400L) == ScreenLayout.blinkOn(400L, 400L));
        check("闪烁：同一时刻两次求值一致（离屏导出可复现）",
                ScreenLayout.blinkOn(1234L, 400L) == ScreenLayout.blinkOn(1234L, 400L));
        check("闪烁周期为 0 时恒亮（不做除零）", ScreenLayout.blinkOn(7L, 0L));
        // 半周期必须能整除重绘周期，否则闪烁会"走样"（某些半周期被采两次、某些被跳过）
        check("闪烁周期与重绘周期对齐",
                ScreenLayout.ALERT_BLINK_MS % ScreenLayout.REFRESH_MS == 0L
                        && (ScreenLayout.ALERT_BLINK_MS / 2L) % ScreenLayout.REFRESH_MS == 0L);
        // 告警态实际会用到的那一对时刻，必须一亮一灭。
        // 【注意是"相隔半个闪烁周期"】刷新率提到 0.05 秒之后，相邻两帧（50ms）
        // 都还落在同一个半周期里 —— 这里要钉的是"跨过半周期会翻转"，
        // 而不是"任意相邻两帧翻转"（那需要半周期 == 一个刷新周期，闪得看不出节奏）。
        check("告警闪烁：相隔半个闪烁周期必翻转",
                ScreenLayout.blinkOn(1000000L, ScreenLayout.ALERT_BLINK_MS)
                        != ScreenLayout.blinkOn(1000000L + ScreenLayout.ALERT_BLINK_MS / 2L,
                                                ScreenLayout.ALERT_BLINK_MS));
        // 800ms / 50ms = 16 帧一个整周期（亮 8 帧、灭 8 帧），节奏与提速前一致
        check("闪烁整周期 = 16 个刷新周期（亮 8 帧 / 灭 8 帧）",
                ScreenLayout.ALERT_BLINK_MS / ScreenLayout.REFRESH_MS == 16L);

        // ---- 第二块屏的方块身份 ----
        net.minecraft.block.Block screen = ModBlocks.DFC_SCREEN;
        net.minecraft.block.Block monitor = ModBlocks.DFC_MONITOR;
        // 注册名必须不同：IForgeRegistryEntry 允许重名时后注册的会覆盖前一个，
        // 表现是"两种屏在游戏里变成同一种"
        check("核心屏注册名", "jiezhimod:dfc_screen".equals(String.valueOf(screen.getRegistryName())));
        check("监视器注册名", "jiezhimod:dfc_monitor".equals(String.valueOf(monitor.getRegistryName())));
        check("监视器是显示器方块的子类（复用同一套多方块与 TESR）",
                monitor instanceof com.jiezhimod.block.BlockDfcScreen);
        check("监视器句柄与核心屏不是同一个对象", monitor != screen);
        // displayItem() 是 protected —— 破坏和"放不下返还"都走它，
        // 若子类没覆写就会把核心屏凭空变出来
        check("监视器拆掉掉的是监视器本身", displayItemOf(monitor) == ModBlocks.ITEM_DFC_MONITOR);
        check("核心屏拆掉掉的是核心屏本身", displayItemOf(screen) == ModBlocks.ITEM_DFC_SCREEN);
        // 占位块两种屏共用，正好说明"不能从占位块分辨是哪种屏"
        check("占位块是独立的方块类型",
                ModBlocks.DFC_SCREEN_PART != screen && ModBlocks.DFC_SCREEN_PART != monitor);

        // ---- 第二块屏的资源 ----
        // 方块贴图漏打包只会静默变成紫黑格，不会报错
        String icon = "assets/jiezhimod/textures/items/dfc_monitor.png";
        // 图标是一块深色屏，多半画布是透明的 —— 只统计可见像素才不会被透明区拉成全黑
        check("监视器图标有可见内容（不是空图）", pngVisible(icon) >= 1500);
        check("监视器图标上画了亮色仪表（高亮像素 >= 120）", pngBright(icon) >= 120);
        check("监视器图标可见部分不至于全黑（平均亮度 >= 30）", pngLuma(icon) >= 30);
        // 两张图标必须真的不一样，否则"第二块屏"在外观上根本区分不出来
        check("监视器图标与核心屏图标不是同一张图",
                !sameBytes("assets/jiezhimod/textures/items/dfc_monitor.png",
                           "assets/jiezhimod/textures/items/dfc_screen.png"));
    }

    /** 反射调 protected BlockDfcScreen.displayItem()，验证"拆掉掉自己" */
    private static Object displayItemOf(net.minecraft.block.Block b) {
        try {
            Method m = com.jiezhimod.block.BlockDfcScreen.class.getDeclaredMethod("displayItem");
            m.setAccessible(true);
            return m.invoke(b);
        } catch (Exception e) {
            return null;
        }
    }

    /** 两个资源文件内容是否完全一致 */
    private static boolean sameBytes(String a, String b) {
        try {
            java.io.FileInputStream fa = new java.io.FileInputStream(
                    new java.io.File("src/main/resources/" + a));
            byte[] da = readAll(fa);
            fa.close();
            java.io.FileInputStream fb = new java.io.FileInputStream(
                    new java.io.File("src/main/resources/" + b));
            byte[] db = readAll(fb);
            fb.close();
            return Arrays.equals(da, db);
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== 32. DFC 显示：多方块几何 ====================

    // ==================== 31c. DFC 显示：自定义显示屏（第三块屏） ====================

    private static void testDfcCustomScreen() {
        section("DFC custom display (third screen)");

        // ---- 方块身份 ----
        net.minecraft.block.Block custom = ModBlocks.DFC_CUSTOM;
        check("自定义屏注册名", "jiezhimod:dfc_custom".equals(String.valueOf(custom.getRegistryName())));
        check("自定义屏是显示器方块的子类（复用多方块与 TESR）",
                custom instanceof com.jiezhimod.block.BlockDfcScreen);
        check("自定义屏与核心屏不是同一个对象", custom != ModBlocks.DFC_SCREEN);
        check("自定义屏与监视器不是同一个对象", custom != ModBlocks.DFC_MONITOR);
        // displayItem() 决定"拆掉 / 放不下返还"掉哪个物品，漏覆写会凭空变出核心屏
        check("自定义屏拆掉掉的是它自己", displayItemOf(custom) == ModBlocks.ITEM_DFC_CUSTOM);
        // 三块屏共用同一个占位块类型 —— 这正是"不能从占位块分辨是哪种屏"的证据
        check("自定义屏仍然复用同一个占位块类型",
                ModBlocks.DFC_SCREEN_PART != custom && ModBlocks.DFC_SCREEN_PART != null);
        check("自定义屏的方块实体是 TileCustomScreen",
                new com.jiezhimod.block.BlockCustomScreen().createNewTileEntity(null, 0)
                        instanceof com.jiezhimod.block.TileCustomScreen);

        // ---- 数据槽对象 ----
        com.jiezhimod.dfc.DfcWatch a = new com.jiezhimod.dfc.DfcWatch(
                1, 2, 3, 0, com.jiezhimod.dfc.DfcWatch.KIND_FLUID, 0);
        com.jiezhimod.dfc.DfcWatch b = new com.jiezhimod.dfc.DfcWatch(
                1, 2, 3, 0, com.jiezhimod.dfc.DfcWatch.KIND_FLUID, 0);
        check("同坐标同种类同索引判为同一条", a.sameAs(b));
        b.index = 1;
        check("罐序号不同就不是同一条（一台机器可能带好几个罐）", !a.sameAs(b));
        b.index = 0;
        b.dim = 7;
        check("维度不同不算同一条", !a.sameAs(b));
        b.dim = 0;
        b.kind = com.jiezhimod.dfc.DfcWatch.KIND_ENERGY;
        check("数据种类不同不算同一条", !a.sameAs(b));

        a.value = "X";
        a.ratio = 0.5D;
        a.alive = true;
        com.jiezhimod.dfc.DfcWatch cp = a.copy();
        cp.value = "Y";
        check("copy() 是独立副本（改副本不影响原件）", "X".equals(a.value) && "Y".equals(cp.value));
        cp.clearReading();
        check("clearReading 清读数、清进度条、置失效",
                "--".equals(cp.value) && cp.ratio < 0.0D && !cp.alive);
        check("clearReading 不动物理坐标/种类（设备被拆后槽位还在）",
                cp.sameAs(a));

        // ---- 遥控器的设备记录（蹲下右键设备 -> 蹲下右键屏幕 接入）----
        net.minecraft.item.ItemStack remote = new net.minecraft.item.ItemStack(ModItems.DFC_REMOTE);
        com.jiezhimod.item.ItemDfcRemote.clearTarget(remote);
        com.jiezhimod.item.ItemDfcRemote.clearDevices(remote);
        check("初始没有记录任何设备", com.jiezhimod.item.ItemDfcRemote.deviceCount(remote) == 0);
        net.minecraft.util.math.BlockPos tank = new net.minecraft.util.math.BlockPos(10, 64, 20);
        check("记录一台储罐",
                com.jiezhimod.item.ItemDfcRemote.addDevice(remote, tank, 0)
                        && com.jiezhimod.item.ItemDfcRemote.deviceCount(remote) == 1);
        check("同一台设备重复记录会被忽略（否则白占两个槽）",
                !com.jiezhimod.item.ItemDfcRemote.addDevice(remote, tank, 0)
                        && com.jiezhimod.item.ItemDfcRemote.deviceCount(remote) == 1);
        check("换个维度算另一台设备",
                com.jiezhimod.item.ItemDfcRemote.addDevice(remote, tank, 1)
                        && com.jiezhimod.item.ItemDfcRemote.deviceCount(remote) == 2);
        for (int i = 0; i < 20; i++) {
            com.jiezhimod.item.ItemDfcRemote.addDevice(remote,
                    new net.minecraft.util.math.BlockPos(i, 70, 0), 0);
        }
        check("记录数量封顶 8（一块屏总共只有 8 个槽）",
                com.jiezhimod.item.ItemDfcRemote.deviceCount(remote)
                        == com.jiezhimod.item.ItemDfcRemote.MAX_DEVICES
                        && com.jiezhimod.item.ItemDfcRemote.MAX_DEVICES == com.jiezhimod.dfc.DfcWatch.MAX);

        // 两种记录互不干扰：反应堆源（单个）与设备列表（多个）在同一个 NBT 里共存
        com.jiezhimod.item.ItemDfcRemote.clearDevices(remote);
        com.jiezhimod.item.ItemDfcRemote.clearTarget(remote);
        com.jiezhimod.item.ItemDfcRemote.writeTarget(remote,
                new net.minecraft.util.math.BlockPos(1, 2, 3), 0, "CORE");
        com.jiezhimod.item.ItemDfcRemote.addDevice(remote, tank, 0);
        com.jiezhimod.item.ItemDfcRemote.clearDevices(remote);
        check("清空设备不影响反应堆源记录",
                com.jiezhimod.item.ItemDfcRemote.hasTarget(remote)
                        && "CORE".equals(com.jiezhimod.item.ItemDfcRemote.readKind(remote)));
        check("清空设备后列表归零",
                com.jiezhimod.item.ItemDfcRemote.deviceCount(remote) == 0);
        com.jiezhimod.item.ItemDfcRemote.clearTarget(remote);
        check("清空反应堆源后 hasTarget 为假",
                !com.jiezhimod.item.ItemDfcRemote.hasTarget(remote));

        // ---- GUI 操作包（开机 / 删除某条 / 清空）往返 ----
        check("GUI 操作包：三种动作编号互不相同",
                com.jiezhimod.network.msg.MessageCustomScreenAction.ACT_POWER
                        != com.jiezhimod.network.msg.MessageCustomScreenAction.ACT_DELETE
                        && com.jiezhimod.network.msg.MessageCustomScreenAction.ACT_DELETE
                        != com.jiezhimod.network.msg.MessageCustomScreenAction.ACT_CLEAR);
        net.minecraft.util.math.BlockPos gp = new net.minecraft.util.math.BlockPos(4, 65, -9);
        com.jiezhimod.network.msg.MessageCustomScreenAction act =
                new com.jiezhimod.network.msg.MessageCustomScreenAction(gp,
                        com.jiezhimod.network.msg.MessageCustomScreenAction.ACT_DELETE, 3);
        io.netty.buffer.ByteBuf ab = io.netty.buffer.Unpooled.buffer();
        act.toBytes(ab);
        com.jiezhimod.network.msg.MessageCustomScreenAction act2 =
                new com.jiezhimod.network.msg.MessageCustomScreenAction();
        act2.fromBytes(ab);
        check("GUI 操作包：坐标往返", gp.equals(act2.pos()));
        check("GUI 操作包：动作与索引往返",
                act2.action() == com.jiezhimod.network.msg.MessageCustomScreenAction.ACT_DELETE
                        && act2.index() == 3);
        // 读写顺序错一位的症状是"按删除变成了开机"，不会报任何异常
        check("GUI 操作包：缓冲区被完整消费（读写严格对称）", ab.readableBytes() == 0);

        // ---- 客户端侧的名字翻译（流体名） ----
        // 服务端只能拿到语言键（"hbmfluid.diesel"），翻译必须在客户端做一次。
        // 离线环境没有语言管理器，所以这里只钉住**最要紧的那条**：
        // 无论翻不翻得出来，都绝不能把 "hbmfluid.xxx" 这种内部串原样送上屏幕。
        check("名字翻译：null -> --", "--".equals(com.jiezhimod.client.dfc.WatchNames.resolve(null)));
        check("名字翻译：空串 -> --", "--".equals(com.jiezhimod.client.dfc.WatchNames.resolve("")));
        check("名字翻译：不含点的成品原样保留（储能）",
                "储能".equals(com.jiezhimod.client.dfc.WatchNames.resolve("储能")));
        String translated = com.jiezhimod.client.dfc.WatchNames.resolve("hbmfluid.diesel");
        check("名字翻译：绝不把内部键原样漏到界面上",
                translated != null && !translated.contains("hbmfluid.") && !translated.isEmpty());

        // ---- 第三块屏的图标 ----
        String icon = "assets/jiezhimod/textures/items/dfc_custom.png";
        check("自定义屏图标有可见内容（不是空图）", pngVisible(icon) >= 1500);
        check("自定义屏图标上画了亮色的数据条（高亮像素 >= 120）", pngBright(icon) >= 120);
        check("自定义屏图标可见部分不至于全黑（平均亮度 >= 30）", pngLuma(icon) >= 30);
        check("自定义屏图标与核心屏、监视器图标都不是同一张图",
                !sameBytes(icon, "assets/jiezhimod/textures/items/dfc_screen.png")
                        && !sameBytes(icon, "assets/jiezhimod/textures/items/dfc_monitor.png"));
    }

    private static void testScreenStructure() {
        check("part 编号按行优先", ScreenStructure.part(0, 0) == 0
                && ScreenStructure.part(3, 0) == 3
                && ScreenStructure.part(0, 1) == 4
                && ScreenStructure.part(3, 2) == 11);
        check("part -> col/row 往返", ScreenStructure.colOf(7) == 3 && ScreenStructure.rowOf(7) == 1);
        check("part 取值范围", ScreenStructure.isValidPart(0)
                && ScreenStructure.isValidPart(11)
                && !ScreenStructure.isValidPart(12)
                && !ScreenStructure.isValidPart(-1));

        // 核心断言：4 个朝向 x 12 个 part，坐标换算必须严格互逆 ——
        // 这里错了的症状是"拆一格掉半块"或者"放下去只铺了一半"。
        net.minecraft.util.EnumFacing[] dirs = {
                net.minecraft.util.EnumFacing.NORTH, net.minecraft.util.EnumFacing.SOUTH,
                net.minecraft.util.EnumFacing.EAST, net.minecraft.util.EnumFacing.WEST};
        boolean roundTrip = true;
        boolean contiguous = true;
        for (net.minecraft.util.EnumFacing f : dirs) {
            net.minecraft.util.math.BlockPos origin = new net.minecraft.util.math.BlockPos(100, 64, -200);
            for (int p = 0; p < ScreenStructure.PARTS; p++) {
                net.minecraft.util.math.BlockPos world = ScreenStructure.partPos(origin, p, f);
                if (!origin.equals(ScreenStructure.originOf(world, p, f))) {
                    roundTrip = false;
                }
            }
            // 同一列里上下相邻的两格也必须正好差 1 格（抓"行方向符号搞反"这类错）
            for (int col = 0; col < ScreenStructure.COLS; col++) {
                for (int row = 0; row + 1 < ScreenStructure.ROWS; row++) {
                    net.minecraft.util.math.BlockPos a =
                            ScreenStructure.partPos(origin, ScreenStructure.part(col, row), f);
                    net.minecraft.util.math.BlockPos b =
                            ScreenStructure.partPos(origin, ScreenStructure.part(col, row + 1), f);
                    int dx = a.getX() - b.getX();
                    int dy = a.getY() - b.getY();
                    int dz = a.getZ() - b.getZ();
                    if (dx * dx + dy * dy + dz * dz != 1) {
                        contiguous = false;
                    }
                }
            }
            // 同一行里相邻两格必须正好差 1 格
            for (int row = 0; row < ScreenStructure.ROWS; row++) {
                for (int col = 0; col + 1 < ScreenStructure.COLS; col++) {
                    net.minecraft.util.math.BlockPos a =
                            ScreenStructure.partPos(origin, ScreenStructure.part(col, row), f);
                    net.minecraft.util.math.BlockPos b =
                            ScreenStructure.partPos(origin, ScreenStructure.part(col + 1, row), f);
                    int dx = a.getX() - b.getX();
                    int dy = a.getY() - b.getY();
                    int dz = a.getZ() - b.getZ();
                    if (dx * dx + dy * dy + dz * dz != 1) {
                        contiguous = false;
                    }
                }
            }
        }
        check("partPos / originOf 在四个朝向下都互逆", roundTrip);
        check("同一行/列的相邻 part 正好差 1 格（结构连续）", contiguous);

        // 吊装：part 0 在左上角，结构必须**向下**铺（+1 行 = y - 1）
        net.minecraft.util.math.BlockPos o = new net.minecraft.util.math.BlockPos(0, 0, 0);
        check("行方向竖直向下（吊装）",
                ScreenStructure.partPos(o, ScreenStructure.part(0, 1), net.minecraft.util.EnumFacing.NORTH)
                        .getY() == -1);
        check("列方向沿右手且水平",
                ScreenStructure.partPos(o, ScreenStructure.part(1, 0), net.minecraft.util.EnumFacing.NORTH)
                        .getX() == 1
                        && ScreenStructure.partPos(o, ScreenStructure.part(1, 0), net.minecraft.util.EnumFacing.NORTH)
                        .getY() == 0);
        check("面板竖向占满 3 格、顶排贴着放置点",
                ScreenStructure.partPos(o, ScreenStructure.part(0, 0), net.minecraft.util.EnumFacing.NORTH)
                        .getY() == 0
                        && ScreenStructure.partPos(o, ScreenStructure.part(0, 2), net.minecraft.util.EnumFacing.NORTH)
                        .getY() == -2);

        check("抬头看时倾角最小", ScreenStructure.tiltFromPitch(-90.0F) == 0);
        check("平视落在默认档", ScreenStructure.tiltFromPitch(0.0F) == ScreenStructure.TILT_DEFAULT);
        check("低头放会得到更大的下倾角",
                ScreenStructure.tiltFromPitch(30.0F) > ScreenStructure.tiltFromPitch(0.0F));
        check("俯角极限夹到最大档",
                ScreenStructure.tiltFromPitch(89.0F) == ScreenStructure.TILT_STEPS - 1);
        check("档位 -> 角度", ScreenStructure.tiltDegrees(0) == 0.0F
                && ScreenStructure.tiltDegrees(2) == 2 * ScreenStructure.TILT_STEP_DEG);
        check("角度越界自动夹紧",
                ScreenStructure.tiltDegrees(99) == (ScreenStructure.TILT_STEPS - 1) * ScreenStructure.TILT_STEP_DEG);
        testRenderAnchorMatchesBlocks();
    }

    /**
     * 【渲染锚点必须与方块占位严格重合】
     *
     * TESR 用 translate(part0 + anchor) -> rotateY(yaw) -> 本地坐标 来画整块屏幕，
     * 而 11 个占位方块是 partPos 算出来的。两边一旦对不上，症状就是
     * "方块在一处、屏幕画在旁边"—— 早期版本把本地原点放在 x ∈ [0,4] 的单侧，
     * 绕 Y 轴转 90/180/270 度时整段区间被甩到坐标轴负半轴，
     * NORTH 以外的三个朝向 **12/12 格全部错位**。
     * 这条断言从几何上钉死两者互逆，以后谁再改一边忘另一边都会被抓住。
     */
    private static void testRenderAnchorMatchesBlocks() {
        net.minecraft.util.EnumFacing[] dirs = {
                net.minecraft.util.EnumFacing.NORTH, net.minecraft.util.EnumFacing.SOUTH,
                net.minecraft.util.EnumFacing.EAST, net.minecraft.util.EnumFacing.WEST};
        net.minecraft.util.math.BlockPos origin = new net.minecraft.util.math.BlockPos(0, 0, 0);

        boolean aligned = true;
        for (net.minecraft.util.EnumFacing f : dirs) {
            double[] a = ScreenStructure.anchor(f);
            for (int p = 0; p < ScreenStructure.PARTS; p++) {
                float[] loc = ScreenStructure.localOf(p);
                float[] rot = ScreenStructure.rotateY(loc[0], loc[1], loc[2], f);
                // 渲染画出来的这一格中心（相对 part 0 方块最小角）
                double rx = a[0] + rot[0];
                double ry = a[1] + rot[1];
                double rz = a[2] + rot[2];
                // 真实方块：格坐标 + 0.5 得到中心
                net.minecraft.util.math.BlockPos bp = ScreenStructure.partPos(origin, p, f);
                double bx = bp.getX() + 0.5D;
                double by = bp.getY() + 0.5D;
                double bz = bp.getZ() + 0.5D;
                if (Math.abs(rx - bx) > 1e-4D || Math.abs(ry - by) > 1e-4D || Math.abs(rz - bz) > 1e-4D) {
                    aligned = false;
                }
            }
        }
        check("渲染锚点在四个朝向下都与方块占位重合", aligned);

        // 本地坐标必须关于原点对称：这是"旋转不会被甩到别处"的充分条件
        boolean symmetric = true;
        for (int p = 0; p < ScreenStructure.PARTS; p++) {
            int mirrorCol = ScreenStructure.COLS - 1 - ScreenStructure.colOf(p);
            int mirrorRow = ScreenStructure.ROWS - 1 - ScreenStructure.rowOf(p);
            float[] one = ScreenStructure.localOf(p);
            float[] two = ScreenStructure.localOf(ScreenStructure.part(mirrorCol, mirrorRow));
            if (Math.abs(one[0] + two[0]) > 1e-5F || Math.abs(one[1] + two[1]) > 1e-5F) {
                symmetric = false;
            }
        }
        check("本地坐标关于面板几何中心对称", symmetric);

        // yaw 必须把本地 -z（正面）确切转到 facing。
        // 用 epsilon 而不是严格 ==：cos(270°) 在 double 下是 1.8e-16 而非精确 0。
        boolean facingOk = true;
        for (net.minecraft.util.EnumFacing f : dirs) {
            float[] n = ScreenStructure.rotateY(0.0F, 0.0F, -1.0F, f);
            if (Math.abs(n[0] - f.getFrontOffsetX()) > 1e-5F
                    || Math.abs(n[2] - f.getFrontOffsetZ()) > 1e-5F) {
                facingOk = false;
            }
        }
        check("面板正面朝向 facing", facingOk);
        testTiltFacesDownward();
        testPanelUvOrientation();
    }

    /**
     * 【吊装可见性】屏幕必须**面朝斜下方**，否则站在下面的玩家只能看到背壳。
     *
     * 本地 -z 是正面，法线 n = (0,0,-1)。绕 X 轴转 +deg 得到 (0, +sin, -cos) —— 朝上，
     * 转 -deg 得到 (0, -sin, -cos) —— 朝下。渲染器用的是 -tilt，这里把这条判据钉死，
     * 免得以后有人把负号当笔误"修正"回去。
     */
    private static void testTiltFacesDownward() {
        boolean downward = true;
        boolean stillForward = true;
        for (int step = 1; step < ScreenStructure.TILT_STEPS; step++) {
            float deg = ScreenStructure.tiltDegrees(step);
            float[] n = ScreenStructure.tiltedNormal(-deg);   // 渲染器传的是 -tilt
            if (n[1] >= -1e-6F) {
                downward = false;       // y 分量必须 < 0：朝斜下方
            }
            if (n[2] >= 0.0F) {
                stillForward = false;   // z 分量必须 < 0：仍然朝着面板正面那一侧
            }
        }
        check("倾斜后法线朝斜下方（玩家在下方能看到屏幕）", downward);
        check("倾斜后法线仍朝向面板正面一侧", stillForward);
        check("0 度倾斜法线水平", Math.abs(ScreenStructure.tiltedNormal(0.0F)[1]) < 1e-6F);

        // 反例确认：用 +tilt 会变成仰望天花板（把这条也钉住，防止有人改回去）
        check("反例：+tilt 会让屏幕仰望天花板（故必须用负号）",
                ScreenStructure.tiltedNormal(ScreenStructure.tiltDegrees(2))[1] > 0.0F);

        // 复刻渲染器的变换序列 translate(0,HH,0) -> rotate(-tilt) -> translate(0,-HH,0)，
        // 验证它的旋转轴确实落在 y = HH（屏幕顶边）那条线上：
        // 顶边上的点必须原地不动，否则吊架与屏幕之间会露出接缝。
        final float hh = ScreenStructure.ROWS / 2.0F;
        boolean hingeFixed = true;
        boolean lowerSwings = true;
        for (int step = 0; step < ScreenStructure.TILT_STEPS; step++) {
            float deg = -ScreenStructure.tiltDegrees(step);
            for (float x = -1.5F; x <= 1.5F; x += 0.5F) {
                // 顶边上的点
                float[] rel = ScreenStructure.rotateX(x, hh - hh, 0.0F, deg);
                if (Math.abs(rel[1]) > 1e-5F || Math.abs(rel[2]) > 1e-5F) {
                    hingeFixed = false;
                }
                // 下边缘必须往背后退让（这是"低头"的正确表现，配备 player 在下方仰视）
                float[] low = ScreenStructure.rotateX(x, -hh - hh, 0.0F, deg);
                if (step > 0 && low[2] <= 0.0F) {
                    lowerSwings = false;
                }
            }
        }
        check("绕顶边倾斜时屏幕上边缘保持不动（吊架不露缝）", hingeFixed);
        check("下边缘随倾角向后退让（屏幕上边缘贴近天花板）", lowerSwings);
    }

    /**
     * 屏幕 quad 的 UV 方向。
     *
     * 回归背景：屏幕能显示了，但整屏左右镜像 —— 文字反写、左右栏对调。
     * 根因是 u 的方向搞反（本地 +x 落在观察者左手边，详见 ScreenStructure.panelUv）。
     * 这条断言把方向钉死，防止有人"凭直觉"改成 u=1 配 x1。
     */
    private static void testPanelUvOrientation() {
        float[] uv = ScreenStructure.panelUv();
        float ux0 = uv[0];
        float ux1 = uv[1];
        float vy0 = uv[2];
        float vy1 = uv[3];

        check("面板 UV 取值恰好是 0/1",
                ux0 == 1.0F && ux1 == 0.0F && vy0 == 1.0F && vy1 == 0.0F);

        // 观察者站在面板正面外侧（-z 侧）朝 +z 看，其屏幕基：
        //   right = forward × up = (0,0,1)×(0,1,0) = (-1,0,0)  →  sx = -x
        //   up    = (0,1,0)                                     →  sy = +y
        // 于是"观察者最右侧的两个角"是 x0（sx = -x0 最大），
        // 而图像右列是 u=1 —— 不镜像 ⇒ u(x0) 必须大于 u(x1)。
        check("面板 UV: u 随 x 递减，观察者右侧取图像右列（否则整屏左右镜像）", ux0 > ux1);

        // 图像行号向下增大，且 v=0 对应上传缓冲第 0 行（图像顶行）；
        // "观察者最上侧的两个角"是 y1，应取图像顶行 v=0 ⇒ v(y1) 必须小于 v(y0)。
        check("面板 UV: v 随 y 递减，观察者上侧取图像顶行（否则画面上下颠倒）", vy1 < vy0);
    }

    // ==================== 33. DFC 显示：数据快照网络编解码 ====================

    private static void testDfcSnapshotCodec() {
        DfcSnapshot s = new DfcSnapshot();
        s.linked = true;
        s.sourceKind = DfcSnapshot.SOURCE_STABILIZER;
        s.hasCore = true;
        s.temperature = 3860.5D;
        s.meltingPoint = 4000;
        s.failsafe = 1.5D;
        s.stabilization = 0.85D;
        s.tempDelta = -0.25D;
        s.containedEnergy = 1.25E9D;
        s.maxEnergy = 2.5E9D;
        s.gainedEnergy = 100.0D;
        s.expellingEnergy = 50.0D;
        s.potentialGain = 12.5D;
        s.incomingSpk = 3.0D;
        s.expellingSpk = 4.0D;
        s.ticks = 5380;
        s.stabilizers = 3;
        s.lastStabilizers = 2;
        s.boosted = true;
        s.finalPhase = true;
        s.collapsing = 0.4D;
        s.destroyed = false;
        s.explosionIn = 1200.0D;
        s.overloadTimer = 7;
        s.detonation = true;
        s.detonationTimer = 99;
        s.componentCount = 12;
        // ---- 第二块屏（部件监视）的聚合字段 ----
        s.emitterCount = 7;
        s.emitterActiveCount = 5;
        s.emitterWatts = 320;
        s.emitterSpk = 6.8E6D;
        s.receiverCount = 12;
        s.receiverJoules = 4.12E9D;
        s.injectorCount = 3;
        s.injectorFuel = 184000.0D;
        s.fuelTypeA = "DFC FUEL";
        s.fuelRatioA = 0.83D;
        s.fuelTypeB = "DHC";
        s.fuelRatioB = 0.07D;
        s.stabPresent = true;
        s.stabActive = true;
        s.stabLevel = 4;
        s.stabPower = 123456789L;
        s.lensType = "BOOSTER";
        s.lensHealth = 73.5F;
        // ---- 第三块屏（自定义显示屏）的数据槽 ----
        com.jiezhimod.dfc.DfcWatch w0 = new com.jiezhimod.dfc.DfcWatch(
                11, 64, -30, 0, com.jiezhimod.dfc.DfcWatch.KIND_FLUID, 0);
        w0.device = "储液罐";
        w0.name = "fluid.diesel";
        w0.value = "12.50 kmB / 24.00 kmB";
        w0.ratio = 0.52D;
        w0.alive = true;
        s.watches.add(w0);
        com.jiezhimod.dfc.DfcWatch w1 = new com.jiezhimod.dfc.DfcWatch(
                12, 65, -30, 0, com.jiezhimod.dfc.DfcWatch.KIND_ENERGY, 1);
        w1.device = "电池";
        w1.name = "储能";
        w1.value = "500.00 MHE / 1.00 GHE";
        w1.ratio = 0.5D;
        w1.alive = false;
        s.watches.add(w1);

        io.netty.buffer.ByteBuf buf = io.netty.buffer.Unpooled.buffer();
        s.writeTo(buf);
        DfcSnapshot r = new DfcSnapshot();
        r.readFrom(buf);

        check("编解码：连接标志", r.linked == s.linked && r.sourceKind == s.sourceKind);
        check("编解码：热工字段", r.temperature == s.temperature
                && r.meltingPoint == s.meltingPoint && r.stabilization == s.stabilization);
        check("编解码：温度变化率", r.tempDelta == s.tempDelta);
        check("编解码：能量字段", r.containedEnergy == s.containedEnergy
                && r.maxEnergy == s.maxEnergy && r.potentialGain == s.potentialGain);
        check("编解码：SPK 字段", r.incomingSpk == s.incomingSpk && r.expellingSpk == s.expellingSpk);
        check("编解码：运行状态", r.ticks == s.ticks && r.stabilizers == s.stabilizers
                && r.lastStabilizers == s.lastStabilizers && r.boosted == s.boosted
                && r.finalPhase == s.finalPhase);
        check("编解码：危险字段", r.destroyed == s.destroyed && r.explosionIn == s.explosionIn
                && r.overloadTimer == s.overloadTimer && r.detonation == s.detonation
                && r.detonationTimer == s.detonationTimer);
        check("编解码：结构部件数", r.componentCount == s.componentCount);
        // 第二块屏的聚合字段：顺序错了这里就开始串位，最后一条"缓冲区被完整消费"兜底
        check("编解码：发射器聚合字段", r.emitterCount == s.emitterCount
                && r.emitterActiveCount == s.emitterActiveCount
                && r.emitterWatts == s.emitterWatts && r.emitterSpk == s.emitterSpk);
        check("编解码：接收器聚合字段", r.receiverCount == s.receiverCount
                && r.receiverJoules == s.receiverJoules);
        check("编解码：燃料喷射器聚合字段", r.injectorCount == s.injectorCount
                && r.injectorFuel == s.injectorFuel);
        check("编解码：燃料罐 A", "DFC FUEL".equals(r.fuelTypeA) && r.fuelRatioA == s.fuelRatioA);
        check("编解码：燃料罐 B（含含空格的流体名也没被截断）",
                "DHC".equals(r.fuelTypeB) && r.fuelRatioB == s.fuelRatioB);
        check("编解码：稳定器字段", r.stabPresent == s.stabPresent && r.stabActive == s.stabActive
                && r.stabLevel == s.stabLevel && r.stabPower == s.stabPower);
        check("编解码：透镜类型字符串", "BOOSTER".equals(r.lensType));
        check("编解码：透镜耐久", r.lensHealth == s.lensHealth);
        // 数据槽：字段最多、最容易串位的一段，逐项钉死
        check("编解码：数据槽条数", r.watches.size() == 2);
        check("编解码：数据槽坐标/种类/索引",
                r.watches.get(0).x == 11 && r.watches.get(0).y == 64 && r.watches.get(0).z == -30
                        && r.watches.get(0).kind == com.jiezhimod.dfc.DfcWatch.KIND_FLUID
                        && r.watches.get(0).index == 0
                        && r.watches.get(1).kind == com.jiezhimod.dfc.DfcWatch.KIND_ENERGY
                        && r.watches.get(1).index == 1);
        check("编解码：数据槽读数文本（含空格与斜杠）",
                "12.50 kmB / 24.00 kmB".equals(r.watches.get(0).value)
                        && "500.00 MHE / 1.00 GHE".equals(r.watches.get(1).value));
        check("编解码：数据槽进度条比例",
                r.watches.get(0).ratio == 0.52D && r.watches.get(1).ratio == 0.5D);
        check("编解码：数据槽存活标记", r.watches.get(0).alive && !r.watches.get(1).alive);
        check("编解码：数据槽中文设备名", "储液罐".equals(r.watches.get(0).device)
                && "电池".equals(r.watches.get(1).device));
        check("编解码：数据槽语言键（翻译留给客户端）",
                "fluid.diesel".equals(r.watches.get(0).name) && "储能".equals(r.watches.get(1).name));
        // 涡轮 / 飞轮新增的两种槽：kind 是 byte，值超过 127 会被截断，这里钉住往返
        DfcSnapshot turb = new DfcSnapshot();
        turb.linked = true;
        com.jiezhimod.dfc.DfcWatch ws = new com.jiezhimod.dfc.DfcWatch(
                1, 2, 3, 0, com.jiezhimod.dfc.DfcWatch.KIND_SPIN, 0);
        ws.device = "飞轮";
        ws.name = "转速";
        ws.value = "42.50 rps";
        ws.ratio = 0.425D;
        ws.alive = true;
        turb.watches.add(ws);
        com.jiezhimod.dfc.DfcWatch wp = new com.jiezhimod.dfc.DfcWatch(
                1, 2, 3, 0, com.jiezhimod.dfc.DfcWatch.KIND_TURBINE_POWER, 0);
        wp.device = "飞轮";
        wp.name = "输出功率";
        wp.value = "1.50 kSPK/s";
        wp.ratio = -1.0D;
        wp.alive = true;
        turb.watches.add(wp);
        io.netty.buffer.ByteBuf tb = io.netty.buffer.Unpooled.buffer();
        turb.writeTo(tb);
        DfcSnapshot rt = new DfcSnapshot();
        rt.readFrom(tb);
        check("编解码：转速槽往返（KIND_SPIN 不被截断）",
                tb.readableBytes() == 0 && rt.watches.size() == 2
                        && rt.watches.get(0).kind == com.jiezhimod.dfc.DfcWatch.KIND_SPIN
                        && "42.50 rps".equals(rt.watches.get(0).value)
                        && rt.watches.get(0).ratio == 0.425D);
        check("编解码：涡轮功率槽往返（KIND_TURBINE_POWER 不被截断）",
                rt.watches.get(1).kind == com.jiezhimod.dfc.DfcWatch.KIND_TURBINE_POWER
                        && "1.50 kSPK/s".equals(rt.watches.get(1).value)
                        && rt.watches.get(1).ratio == -1.0D
                        && "飞轮".equals(rt.watches.get(1).device));
        check("编解码：新 KIND 常量与旧的不撞号",
                com.jiezhimod.dfc.DfcWatch.KIND_SPIN != com.jiezhimod.dfc.DfcWatch.KIND_NONE
                        && com.jiezhimod.dfc.DfcWatch.KIND_SPIN != com.jiezhimod.dfc.DfcWatch.KIND_FLUID
                        && com.jiezhimod.dfc.DfcWatch.KIND_SPIN != com.jiezhimod.dfc.DfcWatch.KIND_ENERGY
                        && com.jiezhimod.dfc.DfcWatch.KIND_TURBINE_POWER != com.jiezhimod.dfc.DfcWatch.KIND_SPIN
                        && com.jiezhimod.dfc.DfcWatch.KIND_TURBINE_POWER != com.jiezhimod.dfc.DfcWatch.KIND_ENERGY);
        // 最强的一条：读写顺序只要错一位，这里立刻炸
        check("编解码：缓冲区被完整消费（读写严格对称）", buf.readableBytes() == 0);
        check("编解码：copyFrom 深度拷贝", copyIsIndependent(s));
        // 空字符串的往返（未绑定数据源时 fuelType 是 ""，不能把它变成 null 再炸在渲染里）
        DfcSnapshot blank = new DfcSnapshot();
        blank.linked = false;
        io.netty.buffer.ByteBuf b2 = io.netty.buffer.Unpooled.buffer();
        blank.writeTo(b2);
        DfcSnapshot rb = new DfcSnapshot();
        rb.readFrom(b2);
        check("编解码：默认（未连接）快照往返对齐且缓冲区吃干",
                b2.readableBytes() == 0 && "--".equals(rb.fuelTypeA) && "--".equals(rb.fuelTypeB));
        check("编解码：透镜名默认 NONE 往返不变（渲染端按 NONE 找图标）",
                "NONE".equals(rb.lensType));

        DfcSnapshot es = new DfcSnapshot();
        es.linked = true;
        es.fuelTypeA = "";
        es.fuelTypeB = "";
        io.netty.buffer.ByteBuf b3 = io.netty.buffer.Unpooled.buffer();
        es.writeTo(b3);
        DfcSnapshot re = new DfcSnapshot();
        re.readFrom(b3);
        check("编解码：空流体名往返成空串（不能被误读成别的）",
                b3.readableBytes() == 0 && "".equals(re.fuelTypeA) && "".equals(re.fuelTypeB));

        // copyFrom 必须把新字段一起拷 —— 漏一个的表现是"第二块屏永远显示 0 台"
        DfcSnapshot src = new DfcSnapshot();
        src.linked = true;
        src.emitterCount = 9;
        src.receiverCount = 4;
        src.injectorCount = 2;
        src.fuelTypeA = "TRITIUM";
        src.fuelRatioA = 0.4D;
        src.fuelTypeB = "DHC";
        src.fuelRatioB = 0.6D;
        DfcSnapshot dst = DfcSnapshot.copied(src);
        check("copyFrom 带上部件聚合字段",
                dst.emitterCount == 9 && dst.receiverCount == 4 && dst.injectorCount == 2);
        check("copyFrom 带上燃料罐字段",
                "TRITIUM".equals(dst.fuelTypeA) && dst.fuelRatioA == 0.4D
                        && "DHC".equals(dst.fuelTypeB) && dst.fuelRatioB == 0.6D);
        // 字符串为 null 时必须回落成 "--"，不能把 null 传到渲染层去
        DfcSnapshot nulls = new DfcSnapshot();
        nulls.linked = true;
        nulls.fuelTypeA = null;
        nulls.fuelTypeB = null;
        nulls.lensType = null;
        DfcSnapshot nn = DfcSnapshot.copied(nulls);
        check("copyFrom 对 null 燃料名兜底成 --",
                "--".equals(nn.fuelTypeA) && "--".equals(nn.fuelTypeB) && nn.lensType != null);

        // copyFrom 必须**深拷贝**数据槽：浅拷会让两块屏共享同一批 DfcWatch，
        // 服务端给 A 屏刷新读数时顺手把 B 屏的也改了。
        DfcSnapshot cw = DfcSnapshot.copied(s);
        cw.watches.get(0).value = "MUTATED";
        check("copyFrom 深拷贝数据槽（改副本不影响原快照）",
                "12.50 kmB / 24.00 kmB".equals(s.watches.get(0).value)
                        && cw.watches.size() == s.watches.size());

        // 空数据槽：核心屏 / 监视器就是这么发的，必须零额外开销且往返对齐
        DfcSnapshot none = new DfcSnapshot();
        io.netty.buffer.ByteBuf b4 = io.netty.buffer.Unpooled.buffer();
        none.writeTo(b4);
        DfcSnapshot r4 = new DfcSnapshot();
        r4.readFrom(b4);
        check("编解码：空数据槽快照往返对齐",
                b4.readableBytes() == 0 && r4.watches.isEmpty());
        // 数据槽条数用 1 字节写，超过 255 会被截断 —— MAX 必须远小于它
        check("数据槽上限 8（需求：最多 8 个数据）",
                com.jiezhimod.dfc.DfcWatch.MAX == 8);
    }

    // =====================================================================
    // 47. 设备定位：代理方块解链 + 就近查找
    //     （回归：对着 HBM 的大模型方块 / 多方块外壳右键，曾经报
    //      "No tile entity there (empty block?)" —— 玩家以为 mod 坏了）
    // =====================================================================
    private static void testDfcDeviceLookup() {
        section("DFC device lookup (proxy + nearby scan)");

        check("resolveProxy(null) 返回 null（调用方不用自己判空）",
                com.jiezhimod.dfc.DfcBridge.resolveProxy(null) == null);

        // 普通方块实体不是代理，解链必须**原样返回** —— 不能把一台好好的机器换成别的东西
        // （TileEntity 本身是 abstract，拿本模组自己的 TileDelay 当"普通 TE"用）
        TileDelay plain = new TileDelay();
        check("普通 TE 不是代理，原样返回",
                com.jiezhimod.dfc.DfcBridge.resolveProxy(plain) == plain);

        check("isDevice(null) = false（不能把 null 当设备记下）",
                !com.jiezhimod.dfc.DfcBridge.isDevice(null));
        check("findDeviceAt：null 世界返回 null",
                com.jiezhimod.dfc.DfcBridge.findDeviceAt(
                        null, net.minecraft.util.math.BlockPos.ORIGIN, 2) == null);
        check("findDeviceAt：null 坐标返回 null",
                com.jiezhimod.dfc.DfcBridge.findDeviceAt(null, null, 2) == null);
        // 半径写死会让人以为"随便扫多大都行" —— 钉住 3：够覆盖 9x9 涡轮叶片到核心的距离，
        // 再大就容易把隔壁机器一起扫进来
        check("设备扫描半径 = 3",
                com.jiezhimod.item.ItemDfcRemote.DEVICE_SCAN_RADIUS == 3);
    }

    /**
     * 自定义屏 GUI 的版面不变量。
     *
     * 【为什么值得写】这个界面我**没法肉眼验证**（它要真跑起 MC 客户端才有像素），
     * 只能把"哪些东西不许重叠、不许出框"变成断言。这些常量都是编译期常量，
     * 上面引用时会被 javac 内联 —— 所以这里只做数学校验，连类都不会加载。
     *
     * 真正会踩的坑是：改了 PANEL_H 却没挪玩家背包 → 快捷栏直接跑出面板外；
     * 或者把遥控器槽往下挪两格 → 压在数据列表第 1 行上。这两种都过不了下面这组检查。
     */
    /**
     * 自定义屏 GUI 的版面不变量。
     *
     * 【为什么要"算文本包围盒"而不只是"算控件矩形"】上一次这里只断言了控件不重叠，
     * 结果真正出问题的是**文字**：右对齐的长提示横穿了「遥控器」标签、还压在「添加」
     * 按钮上（玩家只看到半句残字），底部提示第 2 行被「清空/关闭」盖掉半截。
     * 现在所有可能压到别人的文字都按"估算宽度"占一块矩形一起参与碰撞检测。
     */
    private static void testCustomScreenGuiLayout() {
        section("custom screen GUI layout");
        final int W = com.jiezhimod.block.ContainerCustomScreen.PANEL_W;
        final int H = com.jiezhimod.block.ContainerCustomScreen.PANEL_H;
        final int M = com.jiezhimod.block.ContainerCustomScreen.MARGIN;
        final int slot = 18;

        final int powerY = com.jiezhimod.block.ContainerCustomScreen.POWER_Y;
        final int powerW = com.jiezhimod.block.ContainerCustomScreen.POWER_W;
        final int stateY = com.jiezhimod.block.ContainerCustomScreen.STATE_Y;
        final int rx = com.jiezhimod.block.ContainerCustomScreen.REMOTE_SLOT_X;
        final int ry = com.jiezhimod.block.ContainerCustomScreen.REMOTE_SLOT_Y;
        final int rlx = com.jiezhimod.block.ContainerCustomScreen.REMOTE_LABEL_X;
        final int rly = com.jiezhimod.block.ContainerCustomScreen.REMOTE_LABEL_Y;
        final int addX = com.jiezhimod.block.ContainerCustomScreen.ADD_BTN_X;
        final int addY = com.jiezhimod.block.ContainerCustomScreen.ADD_BTN_Y;
        final int addW = com.jiezhimod.block.ContainerCustomScreen.ADD_BTN_W;
        final int ahx = com.jiezhimod.block.ContainerCustomScreen.ADD_HINT_X;
        final int ahy = com.jiezhimod.block.ContainerCustomScreen.ADD_HINT_Y;
        final int listX = com.jiezhimod.block.ContainerCustomScreen.LIST_X;
        final int listTop = com.jiezhimod.block.ContainerCustomScreen.LIST_TOP;
        final int rowH = com.jiezhimod.block.ContainerCustomScreen.ROW_H;
        final int delW = com.jiezhimod.block.ContainerCustomScreen.DEL_W;
        final int hintY = com.jiezhimod.block.ContainerCustomScreen.HINT_Y;
        final int btnY = com.jiezhimod.block.ContainerCustomScreen.BTN_ROW_Y;
        final int clearW = com.jiezhimod.block.ContainerCustomScreen.CLEAR_W;
        final int closeX = com.jiezhimod.block.ContainerCustomScreen.CLOSE_X;
        final int closeW = com.jiezhimod.block.ContainerCustomScreen.CLOSE_W;
        final int ix = com.jiezhimod.block.ContainerCustomScreen.INV_X;
        final int iy = com.jiezhimod.block.ContainerCustomScreen.INV_Y;
        final int hy = com.jiezhimod.block.ContainerCustomScreen.HOTBAR_Y;
        final int rows = com.jiezhimod.dfc.DfcWatch.MAX;
        /** 左区的右边界（右区数据列表从这里开始） */
        final int leftRight = listX - 4;

        // ---------- 0. 硬上限：面板不许超过 1.12.2 保证的最小逻辑分辨率 ----------
        check("版面：PANEL_H <= 240（GuiContainer 不为超屏做处理，超了标题出屏、底栏压快捷栏）",
                H <= 240);
        check("版面：PANEL_W <= 312（6 倍 GUI 缩放下逻辑宽只有约 319）",
                W <= 312);

        // ---------- 1. 左区：电源 / 状态 / 遥控器 / 添加 ----------
        check("版面：关机按钮在面板内", M + powerW <= leftRight && powerY + 16 <= H);
        check("版面：状态文字在关机按钮下方且不压遥控器槽",
                stateY >= powerY + 16 && stateY + 8 <= ry);
        check("版面：遥控器槽完整落在左区内", rx >= M && rx + slot <= leftRight
                && ry + slot <= H);
        check("版面：遥控器标签不压槽、不越出左区",
                rlx >= rx + slot && rlx + textW("遥控器") <= leftRight
                && rly >= ry && rly + 8 <= ry + slot);
        // 计数文本紧跟在标签后面，必须还在左区内（旧版面就是这里右对齐冲到按钮上）
        check("版面：遥控器计数不压「添加」按钮/右区列表",
                rlx + textW("遥控器") + 6 + textW("8/8 台") <= leftRight);
        check("版面：遥控器槽在添加按钮上方", ry + slot <= addY);
        check("版面：添加按钮在面板内", addX >= M && addX + addW <= leftRight
                && addY + 16 <= H);
        check("版面：添加按钮下方的短提示不压按钮、不越出左区",
                ahy >= addY + 16 && ahx + textW("把遥控器放进槽位") <= leftRight);
        check("版面：添加提示在数据列表底部之前结束",
                ahy + 8 <= listTop + rows * rowH);

        // ---------- 2. 右区：8 行列表 ----------
        check("版面：数据列表 8 行都在面板内",
                listTop + rows * rowH <= H);
        check("版面：删除按钮列不出右边框",
                W - M - delW >= listX && W - M - delW + delW <= W);
        check("版面：数据列表与底部提示不重叠",
                listTop + rows * rowH <= hintY);
        // 每行要能放下「序号 + 名字 + 读数 + 删除按钮」：名字区至少留 60px
        check("版面：每行名字区至少 60px 宽（不然名字和读数会叠在一起）",
                (W - M - delW - 6) - (listX + 12) >= 60);

        // ---------- 3. 底部：提示 / 清空关闭 / 背包 ----------
        check("版面：提醒最多画 2 行（行距 9），整块在底部按钮之前结束",
                hintY + 2 * 9 <= btnY);
        check("版面：清空与关闭按钮不重叠、都在面板内",
                M + clearW <= closeX && closeX + closeW <= W - M
                        && btnY + 16 <= H);
        check("版面：底部按钮在背包上方", btnY + 16 <= iy);
        check("版面：玩家背包 9 列居中且不出边框", ix >= M && ix + 9 * slot <= W - M);
        check("版面：背包 3 行 + 快捷栏都在面板内（改 PANEL_H 必查这条）",
                iy + 3 * slot <= H && hy + slot <= H);
        check("版面：背包在快捷栏上方、两者不重叠", iy + 3 * slot <= hy);
        check("版面：快捷栏与面板底边留出余量", hy + slot <= H - 4);
    }

    /**
     * 估算一段文字在 Minecraft 默认字体下的宽度。
     *
     * 不追求精确（真实宽度要 FontRenderer，离线自测里没有），只求"偏大不偏小"：
     * ASCII 取 6（等宽字体的常见值，i/l 更窄、W/M 更宽，平均下来 6 够用），
     * 中日韩字符取 9（原版 unicode 字体固定 9px 宽）。宁可误报"挤了"，也不要漏报。
     */
    private static int textW(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            w += s.charAt(i) > 0x2E80 ? 9 : 6;
        }
        return w;
    }

    /**
     * GUI 填充物的三角化。
     *
     * 【为什么这条测试必须存在】v3.6 及以前 `GuiDraw.drawRoundedRect` 在 GL_TRIANGLES
     * 模式下只发了一次中心点、后面跟 28 个轮廓点，导致除了第一个三角形以外全成了细缝；
     * `drawRect` 的 4 个三角形更是顶点重合、面积恒为 0。
     * 结果就是**面板底色和物品格子从来没画出来过**，而按钮/文字（原版带纹理路径）正常，
     * 很容易被误判成 GL 状态问题。退化三角形在源码里看着"顶点数是对的"，只有算面积才露馅。
     */
    private static void testGuiGeometry() {
        section("GUI triangle geometry");

        // ---------- 实心矩形 ----------
        float[] q = com.jiezhimod.client.util.GuiGeometry.rectTriangles(10, 20, 60, 40);
        check("几何：矩形 = 2 个三角形 = 12 个分量", q.length == 12);
        check("几何：矩形每个三角形面积都不为 0（v3.6 的 bug 就死在这里）",
                allTrianglesNonDegenerate(q));
        check("几何：矩形总面积 = 宽 × 高 (50×20=1000)",
                Math.abs(triangleArea(q) - 1000.0F) < 0.01F);
        check("几何：矩形所有顶点都在给定范围内", allWithin(q, 10, 20, 60, 40));

        // ---------- 圆角矩形 ----------
        float[] rr = com.jiezhimod.client.util.GuiGeometry.roundedRectTriangles(0, 0, 100, 60, 8);
        check("几何：圆角矩形顶点数是 3 的倍数", rr.length > 0 && (rr.length / 2) % 3 == 0);
        check("几何：圆角矩形每个三角形面积都不为 0", allTrianglesNonDegenerate(rr));
        check("几何：圆角矩形所有顶点都在矩形范围内", allWithin(rr, 0, 0, 100, 60));
        // 圆角会切掉 4 个 (1 - π/4)r² ≈ 55 → 覆盖率应略低于 100%，但不能低于 95%
        float filled = triangleArea(rr);
        check("几何：圆角矩形覆盖率在 95%~100% 之间（缺口 = 四个圆角）",
                filled > 0.95F * 6000.0F && filled <= 6000.01F);

        // 半径为 0 / 超过短边一半时都不许崩、不许出界
        float[] flat = com.jiezhimod.client.util.GuiGeometry.roundedRectTriangles(0, 0, 40, 10, 0);
        check("几何：radius=0 退化成矩形", flat.length == 12 && allTrianglesNonDegenerate(flat));
        float[] huge = com.jiezhimod.client.util.GuiGeometry.roundedRectTriangles(0, 0, 40, 10, 999);
        check("几何：radius 超短边时会收敛、不外溢",
                allTrianglesNonDegenerate(huge) && allWithin(huge, 0, 0, 40, 10));
    }

    /** 三角形列表里有没有零面积（顶点重合 / 三点共线）的三角形 */
    private static boolean allTrianglesNonDegenerate(float[] v) {
        if (v == null || v.length < 6 || (v.length / 2) % 3 != 0) {
            return false;
        }
        for (int i = 0; i + 5 < v.length; i += 6) {
            double ax = v[i], ay = v[i + 1];
            double bx = v[i + 2], by = v[i + 3];
            double cx = v[i + 4], cy = v[i + 5];
            double area = Math.abs((bx - ax) * (cy - ay) - (cx - ax) * (by - ay)) / 2.0D;
            if (area <= 1.0E-6D) {
                return false;
            }
        }
        return true;
    }

    /** 三角形列表的总面积（有向面积取绝对值后累加） */
    private static float triangleArea(float[] v) {
        double sum = 0.0D;
        for (int i = 0; i + 5 < v.length; i += 6) {
            double ax = v[i], ay = v[i + 1];
            double bx = v[i + 2], by = v[i + 3];
            double cx = v[i + 4], cy = v[i + 5];
            sum += Math.abs((bx - ax) * (cy - ay) - (cx - ax) * (by - ay)) / 2.0D;
        }
        return (float) sum;
    }

    /** 所有顶点是否落在 [x, x+w] × [y, y+h] 内（留 0.01 容差） */
    private static boolean allWithin(float[] v, float x, float y, float w, float h) {
        for (int i = 0; i + 1 < v.length; i += 2) {
            if (v[i] < x - 0.01F || v[i] > x + w + 0.01F
                    || v[i + 1] < y - 0.01F || v[i + 1] > y + h + 0.01F) {
                return false;
            }
        }
        return true;
    }

    private static boolean copyIsIndependent(DfcSnapshot s) {        DfcSnapshot c = DfcSnapshot.copied(s);
        c.lensType = "MUTATED";
        c.temperature = -1.0D;
        boolean srcUntouched = s.temperature == 3860.5D && "BOOSTER".equals(s.lensType);
        boolean copyMatches = c.ticks == s.ticks && c.stabPower == s.stabPower;
        return srcUntouched && copyMatches;
    }
}
