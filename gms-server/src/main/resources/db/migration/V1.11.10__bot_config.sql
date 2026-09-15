-- SoloMapling 机器人框架开关：开机自动填充 Bot 世界（9 波，约 2400 个 Bot）。
-- 默认关闭——开启前请先完成灰度验证（见 docs/05-SoloMapling机器人框架移植方案.md S6-S9）。
-- 修改 game_config 后重启生效。
-- game_config 无 config_code 唯一约束，用 NOT EXISTS 保证幂等（避免手工预插后迁移再插一行重复）。
insert into game_config (config_type, config_sub_type, config_clazz, config_code, config_value, config_desc)
select 'server', 'Bot', 'java.lang.Boolean', 'bot_spawn_on_startup', 'false',
       '服务器启动时自动填充机器人世界(Spawn the SoloMapling bot world automatically on server startup)'
from DUAL
where not exists (select 1 from game_config where config_code = 'bot_spawn_on_startup');
