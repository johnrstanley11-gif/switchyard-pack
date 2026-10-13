Classic Castle Guard private 26.3 source port, based on genuine owned 1.1.0 source.
Server-only; all original five classes, protection hooks and /spawn behavior retained.
/spawn arrival remains overworld (0.5,71,5.5), including dead/removed/obstruction checks.
Uses reconstructed genuine Go Home Already 26.3-1.2.0-port263.1 HomeTeleport.request
without changing the shared warmup/cooldown/move/health cancellation or saved schema.
QuarryPlus dependency is the exact private AdditionalEnchantedMiner 26.3 port.
Protection remains overworld X/Z[-48,47], inclusive, all heights: mutual player PvP,
quarry frame placement and direct fluid drain only. It is not a generic write hook.
Inherited makeFrame cancellation occurs after upstream energy use; target can stall
while energy is spent. No change to that original behavior is claimed.
The target world must provide the intended unobstructed fixed arrival; this source
does not build a castle or relocate the hard-coded original arrival.
Author MIT license packaged. Historical builder is preserved with the immutable
author baseline and is not executed for this port. Use the private Gradle build.
