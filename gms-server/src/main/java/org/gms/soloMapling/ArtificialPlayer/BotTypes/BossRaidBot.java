package org.gms.soloMapling.ArtificialPlayer.BotTypes;

import org.gms.client.Character;
import org.gms.server.maps.MapleMap;

import static org.gms.soloMapling.ArtificialPlayer.BotGeneration.warpBotToLocation;
import static org.gms.soloMapling.ArtificialPlayer.BotPartySystem.BotPartyLogic.checkPartyQueue;

/**
 * 远征 Boss 战机器人（v1）：入口站桩待命，被 BotExpeditionRecruiter 自动加进报名中的远征队；
 * 队长开战被 eim 传进 Boss 图后，就地复用 TrainingBot 的整套 GrindBrain 战斗（选目标/接近/输出/嗑药），
 * 战斗会话结束或休息触发想离开时，只要图上还有怪就重新进入战斗（Boss 不死不撤退）；
 * 副本结束被传回入口图后回到站桩待命，可参加下一场。
 *
 * v1 边界：不做扎昆手臂分配（GrindBrain 就近选目标）、不做前置任务豁免（Zakum/HT 暂不投放）。
 */
public class BossRaidBot extends TrainingBot {

    public BossRaidBot(Character character) {
        super(character);
        botType = "BossRaidBot";
        homeMapId = character.getMapId();
    }

    // 队长跟随冷却
    private long lastFollowWarpMs = 0;

    @Override
    public void updateState() {
        // 无条件接受组队邀请（与 PartyQuestBot 同路径）：玩家可直接右键邀请
        checkPartyQueue(getChr());
        if (checkIfNotRunningOrPaused()) {
            return;
        }
        Character chr = getChr();
        if (chr == null || chr.getMap() == null) {
            return;
        }

        // ── 组队队长跟随（最优先）：队长不在本图 → 3 秒内 warp 跟过去 ──
        // 解决 PQ 换关时 bot 留在上一关不动的问题。
        if (followLeaderIfNeeded(chr)) {
            return;
        }

        // 入口图：站桩待命（不用 waitFor——它会让整个 updateState 被跳过，
        // 导致 checkPartyQueue 在等待期间不执行，组队邀请无响应）
        if (chr.getMapId() == homeMapId) {
            if (phase == Phase.GRIND) {
                leaveGrind();
            }
            if (phase != Phase.INIT && phase != Phase.IN_TOWN) {
                enterPhase(Phase.IN_TOWN);
            }
            return; // 正常 tick 周期(2s)就是站桩间隔
        }

        // Boss/PQ 图：保持 GRIND 相位战斗
        ensureCombatTicker();
        if (phase != Phase.GRIND) {
            enterPhase(Phase.GRIND);
        }
        doGrind();

        // 会话到期但图上还有怪 → 重返战斗
        if (phase != Phase.GRIND && chr.getMapId() != homeMapId && countHostiles(chr) > 0) {
            enterPhase(Phase.GRIND);
            return;
        }

        // ── Fix8: 不在入口图 + 没怪 + 队长也不在本图 → 自动回站 ──
        // PQ 退出后传到 exitMap（≠入口），bot 冻在原地的问题。
        if (chr.getMapId() != homeMapId && countHostiles(chr) == 0) {
            leaveGrind();
            MapleMap home = chr.getClient().getChannelServer().getMapFactory().getMap(homeMapId);
            if (home != null && home.getPortal(0) != null) {
                try {
                    warpBotToLocation(chr, home.getPortal(0).getPosition(), home);
                } catch (Exception e) {
                    // warp 失败：下一 tick 重试
                }
            }
        }
    }

    // 组队队长跟随：队长不在本图 → warp 到队长所在图。3 秒冷却防横跳。
    private boolean followLeaderIfNeeded(Character chr) {
        var party = chr.getParty();
        if (party == null) return false;
        var leaderPc = party.getLeader();
        if (leaderPc == null) return false;
        Character leader = leaderPc.getPlayer();
        if (leader == null || leader.getMapId() == chr.getMapId()) return false;

        long now = System.currentTimeMillis();
        if (now - lastFollowWarpMs < 3_000) return false;
        lastFollowWarpMs = now;

        // 离开当前战斗状态
        if (phase == Phase.GRIND) {
            leaveGrind();
        }

        var leaderMap = leader.getMap();
        if (leaderMap != null && leaderMap.getPortal(0) != null) {
            try {
                warpBotToLocation(chr, leaderMap.getPortal(0).getPosition(), leaderMap);
            } catch (Exception e) {
                // warp 失败不阻塞
            }
        }
        return true;
    }

    private static int countHostiles(Character chr) {
        MapleMap map = chr.getMap();
        return map == null ? 0 : map.countMonsters();
    }

    // 远征 bot 不参与练级选图/回城购物那套：导航守卫按战斗态常开即可（GRIND 相位内 doGrind 自会设置）。
    @Override
    protected long lowPriorityDelayMs() {
        return 2_000;
    }

    @Override
    public String toString() {
        return getChr().getName() + "@" + homeMapId + "(BossRaidBot)";
    }
}
