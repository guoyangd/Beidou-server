-- SoloMapling 机器人框架种子数据：fmbot 账号 + Bot 克隆模板角色。
--
-- BotGeneration.createBot() 与 getConsoleBot() 从 BOT_TEMPLATE_CID (= 10000，
-- 见 org.gms.soloMapling.server.SoloMaplingConstants) 加载模板并克隆出所有 Bot。
--
-- 模板必须是素角色：gm = 0（非管理员）、level 1、无装备——Bot 克隆后再按职业/等级装扮。
-- 模板 CID 取 10000：高于真实角色自增范围（admin 种子占 id=1，玩家量级 << 1000），
-- 低于内存 Bot ID 基数（BOT_BASE_ID = 20000），三段 ID 空间互不重叠。
-- 账号密码与 SoloMapling 侧一致：fmbot / password（BCrypt）。
-- INSERT IGNORE：手工建过同名账号/同 ID 角色的库直接跳过，不阻断迁移。

INSERT IGNORE INTO `accounts` (`name`, `password`, `pin`, `pic`, `birthday`, `nxCredit`, `maplePoint`,
                               `nxPrepaid`, `characterslots`, `gender`, `tos`)
VALUES ('fmbot', '$2y$12$xS3xZTX5hSU8v0SvC4h1FewFeK4Lx0q6kXoqv/bFJu6Hr3Wuimr9q', '0000', '000000',
        '2005-05-11', 0, 0, 0, 3, 0, 1);

INSERT IGNORE INTO `characters` (`id`, `accountid`, `world`, `name`, `level`, `exp`,
                                 `str`, `dex`, `luk`, `int`, `hp`, `mp`, `maxhp`, `maxmp`, `meso`, `job`,
                                 `skincolor`, `gender`, `hair`, `face`, `ap`, `map`, `spawnpoint`, `gm`,
                                 `equipslots`, `useslots`, `setupslots`, `etcslots`)
VALUES (10000, (SELECT `id` FROM `accounts` WHERE `name` = 'fmbot'), 0, 'fmbot', 1, 0,
        12, 5, 4, 4, 50, 5, 50, 5, 0, 0,
        0, 0, 30030, 20000, 0, 10000, 0, 0,
        96, 96, 96, 96);
