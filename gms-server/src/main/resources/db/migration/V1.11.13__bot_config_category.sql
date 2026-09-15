-- bot_spawn_on_startup 从独立的 Bot 子类并入 游戏机制(Game Mechanics) 分类。
-- 后台参数管理页：类型=server(界面显示"全局")，子类 Bot 仅此一条，独立分类无意义；
-- 挪入后下拉里不再出现孤立的 Bot 项。UPDATE 天然幂等。
UPDATE game_config
SET config_sub_type = 'Game Mechanics'
WHERE config_code = 'bot_spawn_on_startup';
