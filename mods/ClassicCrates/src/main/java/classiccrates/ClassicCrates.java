package classiccrates;

import classiccrates.cosmetic.Cosmetic;
import classiccrates.cosmetic.CosmeticTokenItem;
import classiccrates.cosmetic.Trail;
import classiccrates.crate.CrateBlock;
import classiccrates.crate.CrateKeyItem;
import classiccrates.vote.VoteHandler;
import com.mojang.logging.LogUtils;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import org.slf4j.Logger;

import java.util.function.Supplier;

/**
 * Vote crates: a Votifier-compatible vote listener hands out crate keys, a crate block turns a key into loot from a
 * data pack loot table, and cosmetic tokens change the look of items or give the player a particle trail.
 */
@Mod(ClassicCrates.MOD_ID)
public class ClassicCrates {

    public static final String MOD_ID = "classiccrates";
    public static final Logger LOGGER = LogUtils.getLogger();

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MOD_ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MOD_ID);
    public static final DeferredRegister.DataComponents COMPONENTS = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, MOD_ID);
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENTS = DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, MOD_ID);
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MOD_ID);

    /** Unbreakable outside creative mode, like bedrock: crates are placed by admins. */
    public static final DeferredBlock<CrateBlock> CRATE = BLOCKS.registerBlock("crate", CrateBlock::new,
            p -> p.mapColor(MapColor.COLOR_PURPLE).strength(-1.0F, 3600000.0F).sound(SoundType.WOOD).noLootTable());
    public static final DeferredItem<BlockItem> CRATE_ITEM = ITEMS.registerSimpleBlockItem("crate", CRATE);
    public static final DeferredItem<CrateKeyItem> CRATE_KEY = ITEMS.registerItem("crate_key", CrateKeyItem::new);
    public static final DeferredItem<CosmeticTokenItem> COSMETIC_TOKEN = ITEMS.registerItem("cosmetic_token", CosmeticTokenItem::new, p -> p.stacksTo(1));

    /** What a cosmetic token gives. Set by the crate loot table. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Cosmetic>> COSMETIC = COMPONENTS.registerComponentType("cosmetic",
            b -> b.persistent(Cosmetic.CODEC).networkSynchronized(Cosmetic.STREAM_CODEC));
    /** Name of the cosmetic applied to an item, so it can be shown and taken off again. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<String>> APPLIED_COSMETIC = COMPONENTS.registerComponentType("applied_cosmetic",
            b -> b.persistent(com.mojang.serialization.Codec.STRING).networkSynchronized(net.minecraft.network.codec.ByteBufCodecs.STRING_UTF8));

    /** The particle trail a player wears. Kept through death. */
    public static final Supplier<AttachmentType<Trail>> TRAIL = ATTACHMENTS.register("trail",
            () -> AttachmentType.builder(() -> Trail.NONE).serialize(Trail.CODEC).copyOnDeath().build());

    public static final Supplier<CreativeModeTab> TAB = TABS.register("classiccrates", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.classiccrates"))
            .icon(() -> new ItemStack(CRATE_KEY.get()))
            .displayItems((params, output) -> {
                output.accept(CRATE_ITEM.get());
                output.accept(CRATE_KEY.get());
            })
            .build());

    public ClassicCrates(IEventBus modBus, ModContainer container) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        COMPONENTS.register(modBus);
        ATTACHMENTS.register(modBus);
        TABS.register(modBus);

        container.registerConfig(ModConfig.Type.LOCAL, Config.SPEC, "classiccrates.toml");
        modBus.addListener(net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent.class, classiccrates.admin.AdminPayloads::register);

        NeoForge.EVENT_BUS.register(VoteHandler.class);
        NeoForge.EVENT_BUS.register(CrateCommands.class);
        NeoForge.EVENT_BUS.register(Trail.Events.class);
        NeoForge.EVENT_BUS.register(classiccrates.cosmetic.CosmeticEvents.class);
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    public static <T> ResourceKey<T> key(ResourceKey<? extends net.minecraft.core.Registry<T>> registry, String path) {
        return ResourceKey.create(registry, id(path));
    }
}
