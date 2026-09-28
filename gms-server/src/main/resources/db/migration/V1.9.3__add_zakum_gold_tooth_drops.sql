-- 扎昆阶段3「锻造火眼」需要 30 个僵尸金牙（4000082），但 drop_data 里该物品
-- 只有一个无关怪物（5130108）在掉，死矿的僵尸根本不掉——阶段3 是死路。
-- 补上林中僵尸（3230103，Forest of Dead Trees / 死矿）与地图变体（3230104）的金牙掉落。
-- chance=6（约每 6 只出 1 个，每只 1 个）：120 级玩家秒杀节奏下 ~8 分钟收满 30 个。
INSERT INTO drop_data (dropperid, itemid, minimum_quantity, maximum_quantity, questid, chance)
VALUES (3230103, 4000082, 1, 1, 0, 6),
       (3230104, 4000082, 1, 1, 0, 6);
