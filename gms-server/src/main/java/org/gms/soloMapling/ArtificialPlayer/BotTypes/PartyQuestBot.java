package org.gms.soloMapling.ArtificialPlayer.BotTypes;

import org.gms.client.Character;

import static org.gms.soloMapling.ArtificialPlayer.BotPartySystem.BotPartyLogic.checkPartyQueue;

/**
 * 组队任务陪打 bot：BossRaidBot 的全部行为（入口站桩 / 进图战斗 / 怪不清不撤 / 结束回位），
 * 额外每 tick 无条件接受待处理的组队邀请（与 OPQBot 同路径 checkPartyQueue）——
 * 玩家在 PQ 入口直接右键邀请即可组满 3-6 人，队长找 NPC 开本后全队（含 bot）进图。
 *
 * 适用：打怪/防守型 PQ（HPQ 月妙、雪人、蜈蚣、妖僧、BossRush、海盗 PPQ）——
 * 进图后"打掉场上一切怪"即通关要义。谜题型 PQ（KPQ 站位问答/LPQ 平台顺序/LMPQ 迷宫）
 * 的关卡推进依赖 NPC 对话原语（未实现），bot 进去也只能打架，暂不投放。
 */
public class PartyQuestBot extends BossRaidBot {

    public PartyQuestBot(Character character) {
        super(character);
        botType = "PartyQuestBot";
    }

    @Override
    public void updateState() {
        // 无条件接受组队邀请（OPQ 范式）：被邀请 → 下一 tick 入队，之后随队进本/撤场
        checkPartyQueue(getChr());
        super.updateState();
    }
}
