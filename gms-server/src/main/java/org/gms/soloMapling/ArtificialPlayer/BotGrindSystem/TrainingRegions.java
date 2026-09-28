package org.gms.soloMapling.ArtificialPlayer.BotGrindSystem;

// Which map regions training bots may grind in: original-MapleStory content only (Victoria Island +
// Sleepywood, Orbis, El Nath, Ludibrium continent, Leafre, Mu Lung/Herb Town). Keeps discovery off
// Aqua Road / Cygnus / new-school maps even when a portal makes them reachable (Aqua excluded because
// its swim maps don't fit the ground-based bot movement system). Checked in TrainingMapFinder before
// the level band. Windows are [min, max) over the 9-digit map-id region prefix (verified vs MapId.java:
// HENESYS 100000000, SLEEPYWOOD 105040300, ORBIS 200000000, EL_NATH 211000000, LUDIBRIUM 220000000,
// LEAFRE 240000000, MU_LUNG 250000000, HERB_TOWN 251000000; excluded anchor AQUARIUM 230000000,
// ELLIN_FOREST 300000000). Ours (SoloMapling).
public final class TrainingRegions {

    private TrainingRegions() {}

    // Each row is [minInclusive, maxExclusive].
    private static final int[][] ALLOWED = {
            {100000000, 110000000}, // Victoria Island + Sleepywood (Henesys/Ellinia/Perion/Kerning/Lith/Sleepywood + fields/dungeons)
            {200000000, 201000000}, // Orbis (town, tower, cloud park, sky fields)
            {211000000, 212000000}, // El Nath (town + dungeon: Ice Valley, Wolf Territory, Sharp Cliff, Dead Mine)
            {220000000, 223000000}, // Ludibrium continent: Ludibrium + Eos Tower + Deep Ludi, plus Omega Sector / Korean Folk Town (original v83)
            {240000000, 240100000}, // Leafre 神木村：城镇 + 米纳尔森林/龙之森林（高级 95-140 练级带，业主 2026-09-26 开放）
            {250000000, 252000000}, // Mu Lung + Herb Town 武陵/百草堂：竹林、稻草人训练场（"少林寺"）、海盗据点（业主 2026-09-26 开放）
    };

    public static boolean isAllowed(int mapId) {
        for (int[] window : ALLOWED) {
            if (mapId >= window[0] && mapId < window[1]) {
                return true;
            }
        }
        return false;
    }
}
