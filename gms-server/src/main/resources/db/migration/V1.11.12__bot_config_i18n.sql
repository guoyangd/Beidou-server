-- Bot 开关参数接入管理后台 i18n。
-- 后台参数管理页对描述列做 lang_resources 联查（lang_base='game_config'，config_desc 即 lang_code），
-- 原行 desc 存的是整句话导致联查不上、界面描述为空。改为标准键 + 补中文文案。
UPDATE game_config
SET config_desc = 'bot_spawn_on_startup'
WHERE config_code = 'bot_spawn_on_startup'
  AND config_desc <> 'bot_spawn_on_startup';

INSERT INTO lang_resources (lang_type, lang_base, lang_code, lang_value)
SELECT 'zh-CN', 'game_config', 'bot_spawn_on_startup',
       '服务器启动时自动填充机器人世界（约1100个Bot，修改后需重启生效）'
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM lang_resources
    WHERE lang_base = 'game_config' AND lang_code = 'bot_spawn_on_startup' AND lang_type = 'zh-CN'
);
