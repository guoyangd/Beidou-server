package org.gms.soloMapling.server;

import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;

public class SoloMaplingConstants {

    public static final Channel mainChannel = Server.getInstance().getChannel(0, 1);

    public static class GameConstants {
        public static final int WORLD_SCANIA = 0;
        public static final int CHANNEL_1 = 1;
        // DB character id of the template every bot is cloned from (seed: db/data/163-fmbot-template-cid.sql).
        // Must stay above the real-player auto-increment range and below BOT_BASE_ID so cloned bots
        // can never share an id with a real character or an in-memory bot id.
        public static final int BOT_TEMPLATE_CID = 10000;
        public static final int BOT_BASE_ID = 20000;
    }

}
