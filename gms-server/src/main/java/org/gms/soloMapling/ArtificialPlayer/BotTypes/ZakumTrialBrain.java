package org.gms.soloMapling.ArtificialPlayer.BotTypes;

import org.gms.client.Character;
import org.gms.scripting.event.EventInstanceManager;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Portal;
import org.gms.server.maps.Reactor;
import org.gms.soloMapling.ArtificialPlayer.BotCommandsPack.BotAttack;
import org.gms.soloMapling.MapVFX.CustomReactor;
import org.gms.soloMapling.ArtificialPlayer.BotMovementSystem.MovementCommands;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import static org.gms.soloMapling.ArtificialPlayer.BotGeneration.warpBotToLocation;

/**
 * 扎昆前置试炼脑（死矿区 280010000~280011006）：让进本的陪打 bot 真正"做任务"——
 * 走到岩石反应堆前挥击砸开（文件/火岩石/药水按真实掉落表落地，队友可拾取），
 * 本图石头清完沿 portal 向深处推进，事件结束（完成/超时）自动回门口站位。
 *
 * 关键机制对齐：
 * - 岩石反应堆 2112000~2112017 是链式状态（0→1→…→终态），终态判定沿用真实击打管线的
 *   "nextState < state 即回卷"规则；终结击由 bot 手动补掉落（bot 无客户端，真实
 *   dropItems 管线不会为 bot 触发——与 OPQ 云朵同一套 CustomReactor 范式）。
 * - 宝箱 2110000 是陷阱（踩了整队传回起点），白名单只收 2112xxx 岩石，绝不碰。
 * - 掉落归属砸开的 bot：与其同队的玩家（队长）可立即拾取，正好满足"队长带火岩石
 *   交 Aura"的任务链。
 */
final class ZakumTrialBrain {

    static final int TRIAL_MIN_MAP = 280010000;   // Unknown Dead Mine（阶段1：死矿区）
    static final int TRIAL_MAX_MAP = 280011006;   // Breath of Lava（阶段2图也在窗口内，行为同样适用）

    private static final int ROCK_MIN_ID = 2112000, ROCK_MAX_ID = 2112017;
    private static final int HIT_RANGE_PX = 70;      // 与岩石的判定水平距离
    private static final int SAME_FLOOR_PX = 45;     // 垂直距离容差（岩石在脚下平台则不追）
    private static final int STUCK_TICK_LIMIT = 8;   // 同一目标走不过去的放弃阈值（tick≈2s）

    // 同一目标（岩石/portal）连续走了多少 tick 没到位
    private int stuckTicks;
    private Point walkTarget;

    // 已到访过的图（探索记忆）：死矿区是枢纽结构（大厅 280010000 15 个门通 16 条支走廊，
    // 其中 100→101 串到第二大厅 280011000，其 6 个小房藏着火岩石 2112014）——
    // 没"更深优先"可依赖，用随机+未到访优先做覆盖式探索。
    private final Set<Integer> visitedMaps = new HashSet<>();

    static boolean inTrial(int mapId) {
        return mapId >= TRIAL_MIN_MAP && mapId <= TRIAL_MAX_MAP;
    }

    /**
     * 每 tick（约 2 秒）调用；在试炼图内完全接管 bot 行为。
     * 返回后调用方（BossRaidBot.updateState）直接 return，不再走站桩/战斗分支。
     */
    void tick(BossRaidBot bot, Character chr) {
        MapleMap map = chr.getMap();
        if (map == null) {
            return;
        }

        // 事件已结束（队长交完火岩石 / 30 分钟超时 / 被移出）→ 回门口站位待下一场
        EventInstanceManager eim = chr.getEventInstance();
        if (eim == null || eim.isEventCleared()) {
            bot.warpBackToStation(chr);
            bot.waitFor(3000);
            return;
        }

        // 1) 本图还有没砸的岩石 → 走过去砸
        Reactor rock = pickRock(map, chr.getPosition());
        if (rock != null) {
            handleRock(bot, chr, rock);
            return;
        }

        // 2) 本图石头清完 → 沿 portal 向更深的图推进
        Portal next = pickAdvancePortal(map);
        if (next != null) {
            handlePortal(bot, chr, next);
            return;
        }

        // 3) 尽头图（无更深 portal）→ 原地小踱步，等同行的队友/等结束
        idleWander(bot, chr);
    }

    // ── 岩石 ──────────────────────────────────────────────────────────────

    private Reactor pickRock(MapleMap map, Point from) {
        Reactor best = null;
        int bestDx = Integer.MAX_VALUE;
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (Reactor r : map.getAllReactors()) {
            if (r == null || r.getId() < ROCK_MIN_ID || r.getId() > ROCK_MAX_ID) {
                continue; // 只认 2112xxx 岩石（2110000 是传回起点的陷阱宝箱）
            }
            if (!r.isAlive() || isAtTerminalState(r)) {
                continue; // 已砸开（终态休息，等地图超时重置后再可砸）
            }
            int dx = Math.abs(r.getPosition().x - from.x) + rng.nextInt(200); // 距离+抖动，多 bot 自然分散
            if (dx < bestDx) {
                bestDx = dx;
                best = r;
            }
        }
        return best;
    }

    // 真实击打管线的终态规则：nextState < state 说明状态回卷，当前已是终态
    private boolean isAtTerminalState(Reactor r) {
        try {
            byte st = r.getState();
            return r.getStats().getNextState(st, (byte) 0) < st;
        } catch (Exception e) {
            return true; // 状态异常保守跳过
        }
    }

    private void handleRock(BossRaidBot bot, Character chr, Reactor rock) {
        MapleMap map = chr.getMap();
        Point rp = rock.getPosition();
        int dx = Math.abs(chr.getPosition().x - rp.x);
        int dy = Math.abs(chr.getPosition().y - rp.y);

        if (dx <= HIT_RANGE_PX && dy <= SAME_FLOOR_PX) {
            smash(bot, chr, rock);
            return;
        }

        // 还没走到：向岩石走（上层平台的岩石走不过去也没关系，卡住会自动放弃）
        if (!rp.equals(walkTarget)) {
            walkTarget = rp;
            stuckTicks = 0;
        } else if (++stuckTicks > STUCK_TICK_LIMIT) {
            walkTarget = null; // 放弃这块（下一 tick pickRock 的抖动会换一块或推进 portal）
            return;
        }
        MovementCommands.pathFinderBetaAerial(chr, rp);
        bot.waitFor(2000 + ThreadLocalRandom.current().nextInt(800));
    }

    private void smash(BossRaidBot bot, Character chr, Reactor rock) {
        MapleMap map = chr.getMap();
        byte st = rock.getState();
        byte next = rock.getStats().getNextState(st, (byte) 0);

        BotAttack.basicSwing(chr); // 有可见的挥击动画
        if (next < st) {
            // 终结击：手动补掉落（chance=1 的必掉物，来自 reactordrops 表），归属该 bot，
            // 同队玩家（含队长）立即可拾。终态动画用 forceHitReactor(st) 只播不推进，避免越界崩图。
            int item = dropFor(rock.getId());
            if (item != 0) {
                CustomReactor.dropItemAtReactor(map, rock.getObjectId(), item, chr);
            }
            rock.forceHitReactor(st);
            walkTarget = null;
        } else {
            CustomReactor.hitReactor(map, rock.getObjectId()); // 链式中间状态：+1
        }
        bot.waitFor(1200 + ThreadLocalRandom.current().nextInt(600));
    }

    // 岩石 → 必掉物（reactordrops 表 chance=1 行；2112002/2112006/2112013 无掉落）
    private static int dropFor(int reactorId) {
        return switch (reactorId) {
            case 2112004, 2112011 -> 4001016;     // 文件（熔岩之息用）
            case 2112005, 2112012 -> 4001015;     // 纸质文件（30 份找 Aura 换奖励）
            case 2112014 -> 4001018;              // 火岩石——队长交任务的关键物
            case 2112016 -> 4001113;
            case 2112017 -> 4001114;
            case 2112000, 2112008 -> 2000004;     // 药水岩
            case 2112001, 2112009 -> 2020001;
            case 2112003, 2112010 -> 2000005;
            case 2112007 -> 2022001;
            case 2112015 -> 2280000;
            default -> 0;
        };
    }

    // ── 推进 ──────────────────────────────────────────────────────────────

    private Portal pickAdvancePortal(MapleMap map) {
        visitedMaps.add(map.getId());
        List<Portal> deeper = new ArrayList<>();
        List<Portal> fallback = new ArrayList<>();
        for (Portal p : map.getPortals()) {
            int tm = p.getTargetMapId();
            if (tm == 999999999 || tm == map.getId() || !inTrial(tm)) {
                continue;
            }
            if (tm > map.getId()) {
                deeper.add(p);
            } else {
                fallback.add(p); // 回程门（支走廊回大厅、小房回第二大厅）
            }
        }
        // 优先没去过的更深图，全去过了就随机挑；没有更深门才走回头路
        List<Portal> unvisited = new ArrayList<>();
        for (Portal p : deeper) {
            if (!visitedMaps.contains(p.getTargetMapId())) {
                unvisited.add(p);
            }
        }
        List<Portal> pool = !unvisited.isEmpty() ? unvisited : (!deeper.isEmpty() ? deeper : fallback);
        if (pool.isEmpty()) {
            return null;
        }
        return pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
    }

    private void handlePortal(BossRaidBot bot, Character chr, Portal portal) {
        Point pp = portal.getPosition();
        if (Math.abs(chr.getPosition().x - pp.x) <= HIT_RANGE_PX
                && Math.abs(chr.getPosition().y - pp.y) <= SAME_FLOOR_PX + 30) {
            // 到位 → 穿过 portal（getWarpMap 会解析到 eim 实例图，与真人进的是同一张）
            MapleMap target = chr.getWarpMap(portal.getTargetMapId());
            if (target != null) {
                Portal linked = target.getPortal(portal.getTarget());
                Point pos = linked != null ? linked.getPosition() : target.getPortal(0).getPosition();
                try {
                    warpBotToLocation(chr, pos, target);
                    walkTarget = null;
                    stuckTicks = 0;
                    bot.waitFor(1500);
                } catch (Exception ignore) {
                    // warp 失败下一 tick 重试
                }
            }
            return;
        }

        if (!pp.equals(walkTarget)) {
            walkTarget = pp;
            stuckTicks = 0;
        } else if (++stuckTicks > STUCK_TICK_LIMIT * 2) {
            // portal 走不过去（地形隔离）——直接传过去，避免卡死
            MapleMap target = chr.getWarpMap(portal.getTargetMapId());
            if (target != null) {
                try {
                    warpBotToLocation(chr, target.getPortal(0).getPosition(), target);
                    walkTarget = null;
                    stuckTicks = 0;
                    bot.waitFor(1500);
                } catch (Exception ignore) {
                }
            }
            return;
        }
        MovementCommands.pathFinderBetaAerial(chr, pp);
        bot.waitFor(2200 + ThreadLocalRandom.current().nextInt(800));
    }

    // ── 待机 ──────────────────────────────────────────────────────────────

    private void idleWander(BossRaidBot bot, Character chr) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        Point now = chr.getPosition();
        Point dest = new Point(now.x + rng.nextInt(-400, 401), now.y);
        MovementCommands.pathFinderBeta(chr, dest);
        bot.waitFor(2500 + rng.nextInt(1500));
    }
}
