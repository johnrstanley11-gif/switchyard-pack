/*
 * Go Home Already! by OmniPraxis; original official binary declares MIT License.
 * Source reconstructed with Vineflower 1.12.0 from the verified 1.2.0 NeoForge binary.
 * Modified 2026-10-11 for the private Minecraft 26.3 / NeoForge source port.
 * This is reconstructed source, not an author-published source distribution.
 */
package net.mcreator.gohomealready;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModContainer;
import net.mcreator.gohomealready.gametest.GoHomeAlreadyPortTests;

@Mod("go_home_already")
public class GoHomeAlreadyMod {
   public static final String MODID = "go_home_already";

   public GoHomeAlreadyMod(IEventBus modEventBus, ModContainer container) {
      HomeConfig.register(container);
      if (Boolean.getBoolean("port.gametest")) {
         GoHomeAlreadyPortTests.init(modEventBus);
      }
   }
}
