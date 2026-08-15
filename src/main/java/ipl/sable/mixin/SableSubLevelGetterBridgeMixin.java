package ipl.sable.mixin;

import dev.ryanhcode.sable.util.SubLevelInclusiveLevelEntityGetter;
import net.minecraft.world.level.entity.EntityLookup;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.LevelEntityGetter;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Implements;
import org.spongepowered.asm.mixin.Interface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import qouteall.imm_ptl.core.mixin.common.mc_util.IELevelEntityGetterAdapter;

/**
 * Adapts Sable's {@link SubLevelInclusiveLevelEntityGetter} wrapper so it implements IP's
 * {@link IELevelEntityGetterAdapter} duck interface, delegating to the wrapped inner
 * {@code LevelEntityGetter}.
 *
 * <p><b>Why this exists.</b> IP performs raw casts of {@code LevelEntityGetter} to
 * {@code IELevelEntityGetterAdapter} at multiple call sites (e.g.
 * {@code McHelper.traverseEntities}, {@code PortalDebugCommands}). Vanilla's entity getter
 * implementation has {@code IELevelEntityGetterAdapter} mixed onto it by IP itself, so the
 * cast normally succeeds. But when Sable is loaded, it wraps the level's entity getter in
 * {@link SubLevelInclusiveLevelEntityGetter} (so entity queries can also see sub-level
 * entities). That wrapper class does NOT implement IP's duck interface -> the cast throws
 * {@link ClassCastException} on the first render frame after world join.
 *
 * <p>This was previously provided by the now-discarded IPLSableBridge mod's mixin of the
 * same name. Without it, IP and Sable cannot coexist regardless of whether the audit's
 * 5 mixin conflicts are resolved -- this is the 6th conflict, missed by the Sable<>IP
 * mixin overlap audit because the original adapter mixin lived in a third (bridge) mod
 * rather than in either Sable or IP.
 *
 * <p>The {@code @Pseudo} annotation makes this a soft mixin: if Sable's class isn't on
 * the runtime classpath (Sable not installed), Mixin silently skips application instead
 * of crashing. Combined with the rest of our soft-dep design ({@link ipl.sable.SableBridge}
 * etc), IP can ship without Sable as a required dep -- the adapter only matters when both
 * are present.
 */
@Pseudo
@Mixin(SubLevelInclusiveLevelEntityGetter.class)
@Implements(@Interface(iface = IELevelEntityGetterAdapter.class, prefix = "ipla$"))
public abstract class SableSubLevelGetterBridgeMixin {

    @Shadow
    @Final
    private LevelEntityGetter<?> delegate;

    @SuppressWarnings("unused")
    public EntitySectionStorage<?> ipla$getCache() {
        return this.delegate instanceof IELevelEntityGetterAdapter adapter ? adapter.getCache() : null;
    }

    @SuppressWarnings("unused")
    public EntityLookup<?> ipla$getIndex() {
        return this.delegate instanceof IELevelEntityGetterAdapter adapter ? adapter.getIndex() : null;
    }
}
