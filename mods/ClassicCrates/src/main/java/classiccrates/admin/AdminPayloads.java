package classiccrates.admin;

import classiccrates.ClassicCrates;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRules;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The admin page of the Switchyard menu shows game rules as on/off toggles. The client cannot read server game rules,
 * so an operator's client asks with {@link Query} and the server answers with {@link State}. Both payloads are
 * optional: a newer client on an older server simply gets no answer and shows the rules without a state.
 */
public final class AdminPayloads {

    /** The rules the admin page offers, in the order they are shown. */
    public static final List<GameRule<Boolean>> RULES = List.of(GameRules.KEEP_INVENTORY, GameRules.PVP, GameRules.SPAWN_MONSTERS,
            GameRules.ADVANCE_TIME, GameRules.ADVANCE_WEATHER, GameRules.MOB_GRIEFING, GameRules.TNT_EXPLODES);

    /** Set by the client side; the server never touches it. */
    public static Consumer<Map<String, Boolean>> clientReceiver = rules -> {
    };

    private AdminPayloads() {
    }

    /** Client to server: "send me the rule states". No content. */
    public record Query() implements CustomPacketPayload {
        public static final Type<Query> TYPE = new Type<>(ClassicCrates.id("admin_query"));
        public static final StreamCodec<ByteBuf, Query> CODEC = StreamCodec.unit(new Query());

        @Override
        public Type<Query> type() {
            return TYPE;
        }
    }

    /** Server to client: rule id (as typed in /gamerule) to its value. */
    public record State(Map<String, Boolean> rules) implements CustomPacketPayload {
        public static final Type<State> TYPE = new Type<>(ClassicCrates.id("admin_state"));
        public static final StreamCodec<ByteBuf, State> CODEC = ByteBufCodecs.<ByteBuf, String, Boolean, Map<String, Boolean>>map(HashMap::new, ByteBufCodecs.STRING_UTF8, ByteBufCodecs.BOOL)
                .map(State::new, State::rules);

        @Override
        public Type<State> type() {
            return TYPE;
        }
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1").optional();
        registrar.playToServer(Query.TYPE, Query.CODEC, (payload, context) -> context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player && player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)) {
                PacketDistributor.sendToPlayer(player, new State(current(player.level())));
            }
        }));
        registrar.playToClient(State.TYPE, State.CODEC, (payload, context) -> context.enqueueWork(() -> clientReceiver.accept(payload.rules())));
    }

    static Map<String, Boolean> current(ServerLevel level) {
        Map<String, Boolean> rules = new LinkedHashMap<>();
        for (GameRule<Boolean> rule : RULES) {
            rules.put(rule.id(), level.getGameRules().get(rule));
        }
        return rules;
    }
}
