/*
 * Copyright (c) 2026 Classic Engineering. MIT License; see packaged LICENSE.
 * Needle textures and behaviour carried over from VanillaBackport 1.1.7.10 by BlackGear (MIT).
 */
package classic.spruceneedles;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

@Mod(SpruceNeedles.MOD_ID)
public final class SpruceNeedles {
  public static final String MOD_ID = "classic_spruce_needles";
  public static final TagKey<Block> SPAWN_FALLING_NEEDLES =
    TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(MOD_ID, "spawn_falling_needles"));

  private static final DeferredRegister<ParticleType<?>> PARTICLES = DeferredRegister.create(Registries.PARTICLE_TYPE, MOD_ID);
  // Same shape as the native minecraft:tinted_leaves particle type.
  public static final DeferredHolder<ParticleType<?>, ParticleType<ColorParticleOption>> TINTED_NEEDLES =
    PARTICLES.register("tinted_needles", () -> new ParticleType<ColorParticleOption>(false) {
      @Override public MapCodec<ColorParticleOption> codec() { return ColorParticleOption.codec(this); }
      @Override public StreamCodec<? super RegistryFriendlyByteBuf, ColorParticleOption> streamCodec() { return ColorParticleOption.streamCodec(this); }
    });

  // Client settings with the names, defaults and range VanillaBackport used.
  public static final ModConfigSpec.BooleanValue FALLING_LEAVES;
  public static final ModConfigSpec.DoubleValue FALLING_LEAVES_FREQUENCY;
  private static final ModConfigSpec CLIENT_SPEC;
  static {
    ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
    FALLING_LEAVES = builder.comment("allow falling spruce needle particles to generate").define("falling_leaves", true);
    FALLING_LEAVES_FREQUENCY = builder.comment("how often should falling spruce needle particles generate")
      .defineInRange("falling_leaves_frequency", 0.01, 0.0, 1.0);
    CLIENT_SPEC = builder.build();
  }

  public SpruceNeedles(IEventBus modBus, ModContainer container) {
    PARTICLES.register(modBus);
    container.registerConfig(ModConfig.Type.CLIENT, CLIENT_SPEC);
    if (FMLEnvironment.getDist() == Dist.CLIENT) {
      modBus.addListener(SpruceNeedlesClient::registerParticleProviders);
    }
  }
}
