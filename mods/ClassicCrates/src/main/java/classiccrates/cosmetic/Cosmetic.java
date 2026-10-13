package classiccrates.cosmetic;

import classiccrates.ClassicCrates;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.codec.HolderSetCodec;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.equipment.EquipmentAssets;
import net.minecraft.world.item.equipment.Equippable;

import java.util.Optional;

/**
 * What a cosmetic token does. Everything is optional, so one definition covers item skins (a different item model),
 * coloured wearables (equipment asset + dye colour) and particle trails. Defined in the crate loot table, e.g.
 * <pre>
 * {"name": "Ruby Sword Skin", "applies_to": "#minecraft:swords", "item_model": "classiccrates:skin/ruby_sword"}
 * {"name": "Crimson Elytra", "applies_to": "minecraft:elytra", "item_model": "classiccrates:skin/dyed_elytra",
 *  "equipment_asset": "classiccrates:dyed_elytra", "dyed_color": 14163004}
 * {"name": "Flame Trail", "trail": {"type": "minecraft:flame"}}
 * </pre>
 *
 * @param name           Shown on the token and on the item it was applied to
 * @param appliesTo      Items (or an item tag) the cosmetic fits. Absent for trails
 * @param itemModel      Item definition (assets/&lt;ns&gt;/items/...) the item is drawn with
 * @param equipmentAsset Equipment asset (assets/&lt;ns&gt;/equipment/...) the item is drawn with when worn
 * @param dyedColor      RGB colour for dyeable models / equipment layers
 * @param trail          Particle that follows the player
 * @param rarity         Optional rarity name (common ... mythic); colours the name and is shown on the token
 */
public record Cosmetic(String name, Optional<HolderSet<Item>> appliesTo, Optional<Identifier> itemModel, Optional<Identifier> equipmentAsset,
                       Optional<Integer> dyedColor, Optional<ParticleOptions> trail, Optional<String> rarity) {

    public static final Codec<Cosmetic> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("name").forGetter(Cosmetic::name),
            HolderSetCodec.create(Registries.ITEM, Item.CODEC, false).optionalFieldOf("applies_to").forGetter(Cosmetic::appliesTo),
            Identifier.CODEC.optionalFieldOf("item_model").forGetter(Cosmetic::itemModel),
            Identifier.CODEC.optionalFieldOf("equipment_asset").forGetter(Cosmetic::equipmentAsset),
            Codec.INT.optionalFieldOf("dyed_color").forGetter(Cosmetic::dyedColor),
            ParticleTypes.CODEC.optionalFieldOf("trail").forGetter(Cosmetic::trail),
            Codec.STRING.optionalFieldOf("rarity").forGetter(Cosmetic::rarity)
    ).apply(i, Cosmetic::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, Cosmetic> STREAM_CODEC = ByteBufCodecs.fromCodecWithRegistries(CODEC);

    /** Name colour for the rarity; tokens without a rarity keep the original light purple. */
    public net.minecraft.ChatFormatting color() {
        return switch (rarity.orElse("").toLowerCase(java.util.Locale.ROOT)) {
            case "common" -> net.minecraft.ChatFormatting.WHITE;
            case "uncommon" -> net.minecraft.ChatFormatting.GREEN;
            case "rare" -> net.minecraft.ChatFormatting.AQUA;
            case "epic" -> net.minecraft.ChatFormatting.LIGHT_PURPLE;
            case "legendary" -> net.minecraft.ChatFormatting.GOLD;
            case "mythic" -> net.minecraft.ChatFormatting.RED;
            default -> net.minecraft.ChatFormatting.LIGHT_PURPLE;
        };
    }

    public boolean isTrail() {
        return trail.isPresent();
    }

    public boolean fits(ItemStack stack) {
        return appliesTo.isPresent() && stack.is(appliesTo.get());
    }

    /**
     * Changes the look of the stack. Only components that describe appearance are touched.
     */
    public void applyTo(ItemStack stack) {
        itemModel.ifPresent(model -> stack.set(DataComponents.ITEM_MODEL, model));
        dyedColor.ifPresent(rgb -> stack.set(DataComponents.DYED_COLOR, new DyedItemColor(rgb)));
        equipmentAsset.ifPresent(asset -> {
            Equippable old = stack.get(DataComponents.EQUIPPABLE);
            if (old != null) {
                stack.set(DataComponents.EQUIPPABLE, new Equippable(old.slot(), old.equipSound(),
                        Optional.of(ResourceKey.create(EquipmentAssets.ROOT_ID, asset)), old.cameraOverlay(), old.allowedEntities(),
                        old.dispensable(), old.swappable(), old.damageOnHurt(), old.equipOnInteract(), old.canBeSheared(), old.shearingSound()));
            }
        });
        stack.set(ClassicCrates.APPLIED_COSMETIC.get(), name);
    }

    /**
     * Puts the item's own look back.
     *
     * @return False when the stack had no cosmetic
     */
    public static boolean removeFrom(ItemStack stack) {
        if (!stack.has(ClassicCrates.APPLIED_COSMETIC.get())) {
            return false;
        }
        var prototype = stack.getPrototype();
        stack.set(DataComponents.ITEM_MODEL, prototype.get(DataComponents.ITEM_MODEL));
        stack.set(DataComponents.DYED_COLOR, prototype.get(DataComponents.DYED_COLOR));
        stack.set(DataComponents.EQUIPPABLE, prototype.get(DataComponents.EQUIPPABLE));
        stack.remove(ClassicCrates.APPLIED_COSMETIC.get());
        return true;
    }
}
