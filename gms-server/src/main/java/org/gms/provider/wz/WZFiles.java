package org.gms.provider.wz;

import org.gms.manager.ServerManager;
import org.gms.property.ServiceProperty;

import java.nio.file.Files;
import java.nio.file.Path;

public enum WZFiles {
    QUEST("Quest"),
    ETC("Etc"),
    ITEM("Item"),
    CHARACTER("Character"),
    STRING("String"),
    LIST("List"),
    MOB("Mob"),
    MAP("Map"),
    NPC("Npc"),
    REACTOR("Reactor"),
    SKILL("Skill"),
    SOUND("Sound"),
    UI("UI");

    private final String fileName;
    public static final String DIRECTORY = "wz";

    WZFiles(String name) {
        this.fileName = name + ".wz";
    }

    public Path getFile() {
        Path langPath = getLanguageFile();

        // 兼容旧调用：语言目录存在时优先使用，否则使用原始 WZ。
        return Files.exists(langPath) ? langPath : getBaseFile();
    }

    public Path getBaseFile() {
        return Path.of(DIRECTORY, fileName);
    }

    public Path getLanguageFile() {
        // SoloMapling 移植加固：单元测试/独立工具场景下 Spring 上下文未启动时，
        // 跳过语言目录（getFile 会自动回退到基础 wz/），避免 NPE。
        if (ServerManager.getApplicationContext() == null) {
            return Path.of(DIRECTORY + "-zh-CN", fileName);
        }
        ServiceProperty serviceProperty = ServerManager.getApplicationContext().getBean(ServiceProperty.class);
        return Path.of(DIRECTORY + "-" + serviceProperty.getLanguage(), fileName);
    }

    public String getFilePath() {
        return getFile().toString();
    }
}
