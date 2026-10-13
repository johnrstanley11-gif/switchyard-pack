/* Added 2026-10-11: private Minecraft 26.3 regression tests for the recovered MIT mod. */
package net.mcreator.gohomealready.gametest;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import net.mcreator.gohomealready.GoHomeAlreadyMod;
import net.mcreator.gohomealready.HomeConfig;
import net.mcreator.gohomealready.HomeCooldown;
import net.mcreator.gohomealready.SetHome;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** One sequential required test prevents races over the author's global config/maps. */
public final class GoHomeAlreadyPortTests {
    private static final Logger LOGGER = LoggerFactory.getLogger("GoHomeAlready/PortTests");
    private static final DeferredRegister<Consumer<GameTestHelper>> FUNCTIONS = DeferredRegister.create(Registries.TEST_FUNCTION, GoHomeAlreadyMod.MODID);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> COMMANDS =
        FUNCTIONS.register("commands_persistence_warmup_cooldown_permissions", () -> GoHomeAlreadyPortTests::commandsAndState);
    private static Scenario active;

    private GoHomeAlreadyPortTests() {}

    public static void init(IEventBus modEventBus) {
        FUNCTIONS.register(modEventBus);
        modEventBus.addListener(GoHomeAlreadyPortTests::registerTests);
        NeoForge.EVENT_BUS.addListener(GoHomeAlreadyPortTests::onServerStopping);
    }

    private static void registerTests(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(id("port_tests"));
        event.registerTest(COMMANDS.getId(), new FunctionGameTestInstance(COMMANDS.getKey(), new TestData<>(
            environment, Level.OVERWORLD, id("port_test_empty"), 240, 0, true, Rotation.NONE, false, 1, 1, false, 0)));
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(GoHomeAlreadyMod.MODID, path);
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        if (active != null) active.close();
    }

    private static void commandsAndState(GameTestHelper helper) {
        Scenario test = new Scenario(helper);
        active = test;
        test.run(test::configurationAndRegistrations);
        test.run(test::homeBackAndDeletion);
        test.run(test::bedAndAdministration);
        test.run(test::startMovementWarmup);
        helper.startSequence()
            .thenIdle(4)
            .thenExecute(() -> test.run(test::atMovementBoundary))
            .thenIdle(2)
            .thenExecute(() -> test.run(test::moveBeyondBoundary))
            .thenIdle(2)
            .thenExecute(() -> test.run(test::movementCancelledAndStartHealth))
            .thenIdle(2)
            .thenExecute(() -> test.run(test::healthCancelledAndStartCompletion))
            .thenIdle(25)
            .thenExecute(() -> test.run(test::warmupCompletedAndStartDisabledCancellation))
            .thenIdle(25)
            .thenExecute(() -> test.run(test::sharedCooldownAndBypass))
            .thenExecute(() -> test.run(test::logoutAndSaveSchema))
            .thenExecute(test::close)
            .thenSucceed();
    }

    private static final class Scenario {
        private final GameTestHelper helper;
        private final ServerLevel level;
        private final ServerPlayer player;
        private final List<Runnable> restore = new ArrayList<>();
        private final Vec3 home;
        private final Vec3 departure;
        private boolean closed;

        @SuppressWarnings("removal")
        Scenario(GameTestHelper helper) {
            this.helper = helper;
            this.level = helper.getLevel();
            snapshot(HomeConfig.ENABLE_SETHOME); snapshot(HomeConfig.ENABLE_HOME);
            snapshot(HomeConfig.ENABLE_BED); snapshot(HomeConfig.ENABLE_DELHOME);
            snapshot(HomeConfig.ENABLE_BACK); snapshot(HomeConfig.BACK_ON_DEATH);
            snapshot(HomeConfig.DELHOME_CONFIRM_SECONDS); snapshot(HomeConfig.COOLDOWN_SECONDS);
            snapshot(HomeConfig.BYPASS_LEVEL); snapshot(HomeConfig.WARMUP_SECONDS);
            snapshot(HomeConfig.WARMUP_CANCEL_ON_MOVE); snapshot(HomeConfig.WARMUP_CANCEL_ON_DAMAGE);
            snapshot(HomeConfig.GM_ENABLED); snapshot(HomeConfig.GM_PERMISSION_LEVEL);
            snapshot(HomeConfig.GM_ADMIN_ENABLED);
            player = helper.makeMockServerPlayerInLevel();
            player.setNoGravity(true);
            player.setHealth(20.0F);
            home = helper.absoluteVec(new Vec3(2.25, 2.0, 2.25));
            departure = helper.absoluteVec(new Vec3(4.25, 2.0, 2.25));
            teleport(home);
        }

        private <T> void snapshot(ModConfigSpec.ConfigValue<T> value) {
            T original = value.get();
            restore.add(() -> value.set(original));
        }

        void run(Runnable phase) {
            try {
                phase.run();
            } catch (RuntimeException | Error error) {
                close();
                throw error;
            }
        }

        void configurationAndRegistrations() {
            check(HomeConfig.GM_ENABLED.getPath().equals(List.of("gameMaster", "enabled")), "GM config is in its original section");
            check(HomeConfig.GM_PERMISSION_LEVEL.getPath().equals(List.of("gameMaster", "permissionLevel")), "GM permission config section");
            check(HomeConfig.GM_ADMIN_ENABLED.getPath().equals(List.of("gameMaster", "administration", "enabled")), "nested admin section");
            check(!HomeConfig.GM_ENABLED.getDefault() && HomeConfig.GM_ADMIN_ENABLED.getDefault(), "original nested config defaults");
            check(HomeConfig.GM_PERMISSION_LEVEL.getDefault() == 2 && HomeConfig.COOLDOWN_SECONDS.getDefault() == 5, "original permission/cooldown defaults");
            for (String name : List.of("sethome", "home", "delhome", "back", "bed", "tobed", "gohome")) {
                check(level.getServer().getCommands().getDispatcher().getRoot().getChild(name) != null, "registered command " + name);
            }
            check(level.getServer().getPlayerList().getPlayer(player.getUUID()) == player, "real helper player is online for production tick lookup");
            HomeConfig.ENABLE_SETHOME.set(true); HomeConfig.ENABLE_HOME.set(true);
            HomeConfig.ENABLE_DELHOME.set(true); HomeConfig.ENABLE_BED.set(true);
            HomeConfig.ENABLE_BACK.set(true); HomeConfig.BACK_ON_DEATH.set(true);
            HomeConfig.DELHOME_CONFIRM_SECONDS.set(0); HomeConfig.COOLDOWN_SECONDS.set(0);
            HomeConfig.WARMUP_SECONDS.set(0); HomeConfig.BYPASS_LEVEL.set(4);
            HomeConfig.WARMUP_CANCEL_ON_MOVE.set(true); HomeConfig.WARMUP_CANCEL_ON_DAMAGE.set(true);
            HomeConfig.GM_ENABLED.set(false); HomeConfig.GM_PERMISSION_LEVEL.set(4); HomeConfig.GM_ADMIN_ENABLED.set(false);
            pass("all command roots, nested config/defaults and online player fixture");
        }

        void homeBackAndDeletion() {
            player.getPersistentData().putString("unrelated_root", "retain");
            data().putString("unrelated_persisted", "retain");
            check(command("sethome") == 1, "sethome succeeds");
            assertSaved("home", home);
            teleport(departure);
            // A real camera target verifies the new resetCamera=true argument.
            var camera = helper.spawn(EntityTypes.ARMOR_STAND, new Vec3(4.25, 2.0, 2.25));
            camera.setNoGravity(true);
            player.setCamera(camera);
            check(player.getCamera() == camera, "fixture uses a different camera");
            player.setYRot(71.0F); player.setXRot(-12.0F);
            check(command("home") == 1, "home succeeds");
            at(home, "home exact position");
            check(player.getCamera() == player, "home restores the player camera");
            camera.discard();
            check(player.getYRot() == 71.0F && player.getXRot() == -12.0F, "teleport preserves current rotation");
            assertSaved("back", departure);
            check(command("back") == 1, "back succeeds"); at(departure, "back departure");
            check(command("back") == 1, "back repeats"); at(home, "back toggles rather than losing previous position");
            HomeConfig.DELHOME_CONFIRM_SECONDS.set(60);
            check(command("delhome") == 0 && SetHome.hasHome(player), "first delhome asks confirmation without deleting");
            check(command("delhome") == 1 && !SetHome.hasHome(player), "second delhome confirms");
            for (String key : List.of("homeX", "homeY", "homeZ", "homeDim")) check(!data().contains(key), "delete removes " + key);
            check(data().contains("backX") && data().getStringOr("unrelated_persisted", "").equals("retain"), "delete preserves other persisted fields");
            check(player.getPersistentData().getStringOr("unrelated_root", "").equals("retain"), "root NBT preserved");
            check(command("sethome") == 1, "home recreated");
            HomeConfig.ENABLE_HOME.set(false);
            check(command("home") == 0, "disabled home returns failure");
            HomeConfig.ENABLE_HOME.set(true);
            check(command(level.getServer().createCommandSourceStack(), "home") == 0, "player-only home refuses console");
            pass("actual home/back dispatcher, precision/rotation, deletion confirmation and persistent schema");
        }

        void bedAndAdministration() {
            BlockPos bed = helper.absolutePos(new BlockPos(5, 2, 5));
            player.setRespawnPosition(new ServerPlayer.RespawnConfig(LevelData.RespawnData.of(level.dimension(), bed, 10, 20), false), false);
            Vec3 center = Vec3.atCenterOf(bed);
            check(command("bed") == 1, "bed uses actual RespawnConfig"); at(center, "bed center");
            teleport(departure);
            check(command("tobed") == 1, "tobed alias"); at(center, "tobed center");
            HomeConfig.ENABLE_BED.set(false); check(command("bed") == 0, "bed disabled"); HomeConfig.ENABLE_BED.set(true);
            HomeConfig.ENABLE_BACK.set(false); check(command("back") == 0, "back disabled"); HomeConfig.ENABLE_BACK.set(true);
            String target = player.getGameProfile().name();
            CommandSourceStack admin = level.getServer().createCommandSourceStack().withLevel(level).withPosition(home);
            String here = "gohome gamemaster sethome " + target;
            rejected(admin, here, "GM master switch disabled");
            HomeConfig.GM_ENABLED.set(true);
            rejected(admin, here, "GM administration switch disabled");
            HomeConfig.GM_ADMIN_ENABLED.set(true);
            rejected(player.createCommandSourceStack(), here, "ordinary player lacks level4 GM permission");
            HomeConfig.ENABLE_SETHOME.set(false); HomeConfig.ENABLE_DELHOME.set(false);
            check(command(admin, here) == 1, "admin sethome-here returns target count independently of normal command toggles");
            assertSaved("home", home);
            check(command(admin, here + " 7 4 6") == 1, "admin explicit position");
            assertSaved("home", new Vec3(7.5, 4, 6.5));
            HomeConfig.GM_PERMISSION_LEVEL.set(0);
            check(command(here) == 1, "GM level0 permits ordinary source");
            HomeConfig.GM_PERMISSION_LEVEL.set(4);
            check(command(admin, "gohome gamemaster clearhome " + target) == 1 && !SetHome.hasHome(player), "admin clearhome returns count");
            check(command(admin, "gohome gamemaster clearbed " + target) == 1 && player.getRespawnConfig() == null, "admin clearbed clears native respawn config");
            check(command("bed") == 1, "bed missing-respawn fallback"); at(Vec3.atCenterOf(level.getServer().overworld().getRespawnData().pos()), "overworld spawn fallback");
            HomeConfig.ENABLE_SETHOME.set(true); HomeConfig.ENABLE_DELHOME.set(true);
            HomeConfig.DELHOME_CONFIRM_SECONDS.set(0);
            teleport(home); check(command("sethome") == 1, "warmup home fixture");
            pass("bed/tobed and fallback, both admin gates, level0/4 permissions, every admin command");
        }

        void startMovementWarmup() {
            HomeConfig.WARMUP_SECONDS.set(1);
            teleport(departure);
            check(command("home") == 1, "movement warmup queued");
            check(command("bed") == 0, "pending home prevents a second teleport command");
            at(departure, "warmup is not instant");
        }

        void atMovementBoundary() {
            at(departure, "before warmup deadline");
            teleport(departure.add(0.5, 0, 0));
        }

        void moveBeyondBoundary() {
            check(command("home") == 0, "distance squared exactly .25 does not cancel");
            teleport(departure.add(0.625, 0, 0));
        }

        void movementCancelledAndStartHealth() {
            at(departure.add(0.625, 0, 0), "movement cancellation does not teleport");
            HomeConfig.WARMUP_SECONDS.set(0);
            check(command("home") == 1, "movement cancellation removed pending request"); at(home, "instant request after cancellation");
            HomeConfig.WARMUP_SECONDS.set(1); teleport(departure); player.setHealth(20);
            check(command("home") == 1, "health warmup queued"); player.setHealth(19);
            pass("real server tick movement cancellation and exact boundary");
        }

        void healthCancelledAndStartCompletion() {
            at(departure, "health drop cancelled warmup");
            HomeConfig.WARMUP_SECONDS.set(0);
            check(command("home") == 1, "health cancellation removed pending request"); at(home, "health recovery request");
            HomeConfig.WARMUP_SECONDS.set(1); teleport(departure); player.setHealth(20);
            check(command("home") == 1, "completion warmup queued");
            pass("real server tick health cancellation");
        }

        void warmupCompletedAndStartDisabledCancellation() {
            at(home, "stationary request completed after real server ticks");
            HomeConfig.WARMUP_CANCEL_ON_MOVE.set(false); HomeConfig.WARMUP_CANCEL_ON_DAMAGE.set(false);
            teleport(departure); player.setHealth(20);
            check(command("home") == 1, "disabled-cancellation warmup queued");
            teleport(departure.add(1, 0, 0)); player.setHealth(18);
            pass("real timed warmup completion");
        }

        void sharedCooldownAndBypass() {
            at(home, "disabled cancellation toggles are honored");
            HomeConfig.WARMUP_SECONDS.set(0); HomeConfig.COOLDOWN_SECONDS.set(60);
            check(command("bed") == 0 && command("back") == 0 && command("home") == 0, "completed home shares cooldown with bed/back/home");
            HomeConfig.BYPASS_LEVEL.set(0);
            check(command("back") == 1, "level0 skips cooldown");
            HomeConfig.WARMUP_SECONDS.set(5);
            check(command("home") == 1, "level0 also skips warmup"); at(home, "level0 instant home");
            HomeConfig.BYPASS_LEVEL.set(4);
            level.getServer().getPlayerList().op(player.nameAndId(), Optional.of(LevelBasedPermissionSet.OWNER), Optional.of(false));
            check(command("back") == 1, "real op level4 skips cooldown/warmup");
            check(command("home") == 1, "level4 home instant"); at(home, "level4 instant home");
            level.getServer().getPlayerList().deop(player.nameAndId());
            pass("disabled cancellation, shared completed-teleport cooldown and real level0/4 bypass");
        }

        void logoutAndSaveSchema() {
            CompoundTag expected = player.getPersistentData().copy();
            BlockPos savedBed = helper.absolutePos(new BlockPos(5, 2, 5));
            player.setRespawnPosition(new ServerPlayer.RespawnConfig(LevelData.RespawnData.of(level.dimension(), savedBed, 10, 20), false), false);
            // remove() saves through actual PlayerDataStorage and fires the logout event.
            level.getServer().getPlayerList().remove(player);
            check(level.getServer().getPlayerList().getPlayer(player.getUUID()) == null, "actual player logout removes online entry");
            check(!HomeCooldown.blocked(level.getServer().createCommandSourceStack(), player), "actual logout event clears shared cooldown");
            CompoundTag disk = level.getServer().getPlayerList().loadPlayerData(player.nameAndId()).orElseThrow(
                () -> new IllegalStateException("Engine player data file was not saved"));
            check(disk.getCompoundOrEmpty("NeoForgeData").equals(expected), "disk save preserves complete mod NBT, including unrelated data");
            ServerPlayer reloaded = new ServerPlayer(level.getServer(), level, player.getGameProfile(), ClientInformation.createDefault());
            reloaded.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), disk));
            check(reloaded.getPersistentData().equals(expected), "native entity load restores the original mod NBT");
            check(SetHome.hasHome(reloaded) && SetHome.getPersistedData(reloaded).equals(expected.getCompoundOrEmpty("PlayerPersisted")), "reloaded original home/back coordinates and dimension schema");
            check(reloaded.getRespawnConfig() != null && reloaded.getRespawnConfig().respawnData().pos().equals(savedBed), "native respawn data survives disk save/load");
            pass("actual player disk save/load, legacy home/back schema, bed data and logout cooldown cleanup");
        }

        private int command(String text) { return command(player.createCommandSourceStack(), text); }
        private int command(CommandSourceStack source, String text) {
            try {
                return level.getServer().getCommands().getDispatcher().execute(text, source);
            } catch (CommandSyntaxException error) {
                throw new IllegalStateException("Unexpected command failure: " + text, error);
            }
        }

        private void rejected(CommandSourceStack source, String text, String reason) {
            try {
                level.getServer().getCommands().getDispatcher().execute(text, source);
                helper.fail("Command unexpectedly accepted: " + reason);
            } catch (CommandSyntaxException expected) {
                // Genuine Brigadier requires() denial, not a substituted return value.
            }
        }

        private CompoundTag data() { return SetHome.getPersistedData(player); }
        private void assertSaved(String prefix, Vec3 expected) {
            CompoundTag tag = data();
            check(tag.getDoubleOr(prefix + "X", Double.NaN) == expected.x
                && tag.getDoubleOr(prefix + "Y", Double.NaN) == expected.y
                && tag.getDoubleOr(prefix + "Z", Double.NaN) == expected.z, prefix + " coordinates preserve precision");
            check(tag.getStringOr(prefix + "Dim", "").equals(level.dimension().identifier().toString()), prefix + " dimension schema");
        }
        private void teleport(Vec3 pos) { check(player.teleportTo(level, pos.x, pos.y, pos.z, Set.of(), player.getYRot(), player.getXRot(), true), "fixture teleport succeeded"); }
        private void at(Vec3 pos, String reason) { check(player.position().distanceToSqr(pos) < 0.000001, reason + ": expected " + pos + ", actual " + player.position()); }
        private void check(boolean condition, String reason) { helper.assertTrue(condition, reason); }
        private void pass(String phase) { LOGGER.info("PASS: {}", phase); }

        void close() {
            if (closed) return;
            closed = true;
            level.getServer().getPlayerList().deop(player.nameAndId());
            if (level.getServer().getPlayerList().getPlayer(player.getUUID()) != null) level.getServer().getPlayerList().remove(player);
            for (Runnable action : restore) action.run();
            active = null;
        }
    }
}
