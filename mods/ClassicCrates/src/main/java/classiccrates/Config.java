package classiccrates;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class Config {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue LISTENER_ENABLED = BUILDER
            .comment("Listen for votes from server list websites (Votifier protocol, v1 RSA and v2 token).",
                    "The port below must be reachable from the internet (TCP), like the game port.")
            .define("voteListener.enabled", true);

    public static final ModConfigSpec.IntValue LISTENER_PORT = BUILDER
            .comment("TCP port the vote listener binds to. 8192 is the usual Votifier port.")
            .defineInRange("voteListener.port", 8192, 1, 65535);

    public static final ModConfigSpec.ConfigValue<String> LISTENER_HOST = BUILDER
            .comment("Address to bind to. 0.0.0.0 = all addresses.")
            .define("voteListener.host", "0.0.0.0");

    public static final ModConfigSpec.IntValue KEYS_PER_VOTE = BUILDER
            .comment("Crate keys a player receives for one vote.")
            .defineInRange("rewards.keysPerVote", 1, 0, 64);

    public static final ModConfigSpec.BooleanValue BROADCAST_VOTES = BUILDER
            .comment("Tell everyone online when somebody votes.")
            .define("rewards.broadcastVotes", true);

    public static final ModConfigSpec.BooleanValue BROADCAST_WINS = BUILDER
            .comment("Tell everyone online what somebody won from a crate.")
            .define("rewards.broadcastWins", true);

    public static final ModConfigSpec.ConfigValue<String> CRATE_LOOT_TABLE = BUILDER
            .comment("Loot table rolled when a crate is opened. Replace it with a data pack to change the rewards",
                    "(data/classiccrates/loot_table/crates/vote.json).")
            .define("crate.lootTable", "classiccrates:crates/vote");

    public static final ModConfigSpec.IntValue TRAIL_INTERVAL = BUILDER
            .comment("Ticks between particle trail puffs behind a moving player.")
            .defineInRange("cosmetics.trailIntervalTicks", 3, 1, 40);

    static final ModConfigSpec SPEC = BUILDER.build();

    private Config() {
    }
}
