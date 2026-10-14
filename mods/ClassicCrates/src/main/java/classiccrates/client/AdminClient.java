package classiccrates.client;

import classiccrates.admin.AdminPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;

import java.util.HashMap;
import java.util.Map;

/** Client-side copy of the game rule states shown on the admin page, and the request that fills it. */
final class AdminClient {

    /** Rule id to value, as last reported by the server. Empty until an answer arrives (or on an older server). */
    static final Map<String, Boolean> rules = new HashMap<>();
    private static int ticksUntilRequest = -1;

    private AdminClient() {
    }

    /** True when the server runs a Classic Crates that answers rule queries. */
    static boolean supported() {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        return connection != null && connection.hasChannel(AdminPayloads.State.TYPE);
    }

    static void request() {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection != null && connection.hasChannel(AdminPayloads.State.TYPE)) {
            connection.send(new AdminPayloads.Query());
        }
    }

    /** Ask again after a command had time to run on the server. */
    static void requestLater(int ticks) {
        ticksUntilRequest = ticks;
    }

    static void tick() {
        if (ticksUntilRequest >= 0 && ticksUntilRequest-- == 0) {
            request();
        }
    }

    static void receive(Map<String, Boolean> state) {
        rules.clear();
        rules.putAll(state);
        if (Minecraft.getInstance().gui.screen() instanceof SwitchyardScreen screen) {
            screen.refresh();
        }
    }

    static void clear() {
        rules.clear();
        ticksUntilRequest = -1;
    }
}
