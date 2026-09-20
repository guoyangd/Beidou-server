package org.gms.soloMapling.ArtificialPlayer.BotExpeditionSystem;

import org.gms.client.Character;
import org.gms.net.server.channel.Channel;
import org.gms.server.expeditions.Expedition;
import org.gms.server.maps.MapleMap;
import org.gms.soloMapling.ArtificialPlayer.BotSM;
import org.gms.soloMapling.ArtificialPlayer.BotTypes.BossRaidBot;
import org.gms.soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import org.gms.soloMapling.server.SoloMaplingUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.gms.soloMapling.server.ExecutorServiceManager.getScheduledExecutorService;

/**
 * 远征自动招募器（v1）：1Hz 扫描 1 频道上所有处于「报名中」的远征，
 * 把远征入口地图上站桩的 BossRaidBot 自动加入成员名单（每队上限 BOTS_PER_EXPEDITION 只）。
 *
 * 玩家流程完全不变：队长找入口 NPC 登记远征 → 名单里自动出现 bot → 队长照常开战，
 * eim.registerExpedition 会把全体成员（含 bot）传进副本。BossRaidBot 负责战斗。
 * 不区分 Boss 类型（Showa/Balrog/Scarga...通用）；入口地图取自远征自身的 getRecruitingMap()。
 */
public final class BotExpeditionRecruiter {

    private static final Logger log = LoggerFactory.getLogger(BotExpeditionRecruiter.class);

    /** 每支远征自动补入的 bot 数上限（3 人门槛的远征 1 队 bot 即可成团） */
    private static final int BOTS_PER_EXPEDITION = 6;

    private static final AtomicBoolean STARTED = new AtomicBoolean(false);

    private BotExpeditionRecruiter() {
    }

    // 幂等启动；由 EnvironmentManager.environmentLoadStartup 在 bot 世界就绪后调用
    public static void start() {
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }
        getScheduledExecutorService().scheduleAtFixedRate(
                BotExpeditionRecruiter::tick, 1_000, 1_000, TimeUnit.MILLISECONDS);
        log.info("[BotExpeditionRecruiter] started - auto-joining raid bots into registering expeditions (cap {})", BOTS_PER_EXPEDITION);
    }

    private static void tick() {
        try {
            Channel channel = SoloMaplingUtilities.channel;
            if (channel == null) {
                return;
            }
            for (Expedition exped : channel.getExpeditions()) {
                if (exped == null || !exped.isRegistering()) {
                    continue;
                }
                joinWaitingBots(exped);
            }
        } catch (Throwable t) {
            // 单次扫描异常绝不能杀死常驻线程
            log.warn("[BotExpeditionRecruiter] tick error: {}", t.getMessage());
        }
    }

    private static void joinWaitingBots(Expedition exped) {
        MapleMap recruitMap = exped.getRecruitingMap();
        if (recruitMap == null) {
            return;
        }
        int joined = 0;
        for (Character chr : recruitMap.getAllPlayers()) {
            if (countOurBots(exped) >= BOTS_PER_EXPEDITION) {
                return;
            }
            if (chr == null) {
                continue;
            }
            BotSM bot = CharacterStorage.getAllBots().get(chr.getId());
            if (!(bot instanceof BossRaidBot) || !bot.getRunning()) {
                continue; // 只收 BossRaidBot：入口的社交/训练 bot 不掺和远征
            }
            if (exped.contains(chr)) {
                continue;
            }
            // addMember 内部含人数上限与当日入场次数（ExpeditionBossLog）校验，失败只返回提示串
            String result = exped.addMember(chr);
            if (joined++ == 0 || log.isDebugEnabled()) {
                log.info("[BotExpeditionRecruiter] {} joined expedition at map {} -> {}",
                        chr.getName(), recruitMap.getId(), result);
            }
        }
    }

    private static int countOurBots(Expedition exped) {
        int n = 0;
        for (Character chr : exped.getActiveMembers()) {
            BotSM bot = chr == null ? null : CharacterStorage.getAllBots().get(chr.getId());
            if (bot instanceof BossRaidBot) {
                n++;
            }
        }
        return n;
    }
}
