package classiccrates.crate;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Opens a vote crate. Handed out for votes (or by /crates givekey).
 */
public class CrateKeyItem extends Item {

    public CrateKeyItem(Properties properties) {
        super(properties);
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return true;
    }
}
