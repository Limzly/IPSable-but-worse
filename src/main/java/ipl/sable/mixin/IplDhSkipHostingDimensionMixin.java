package ipl.sable.mixin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import ipl.sable.dh.DhDimensionTracker;

/**
 * Prevents Distant Horizons from creating a DhLevel for the
 * {@code ipl_sable:sublevels} hosting dimension.
 *
 * <p><b>What was wrong before:</b> the original
 * {@code IplDhSkipHostingDimensionMixin} used
 * {@code @Inject(method = "<init>", at = @At("HEAD"), cancellable = true)}
 * on {@code com.seibel.distanthorizons.core.level.AbstractDhLevel.<init>}.
 * Mixin refuses to apply any cancellable {@code @Inject} that targets a
 * constructor — it throws {@code InvalidInjectionException("Found
 * cancellable @Inject targetting a constructor...")} at mixin-apply time,
 * which {@code require = 0} then silently swallows. The cancel never fired
 * and the hosting dimension got its DhLevel created normally. Worse, even
 * if Mixin had applied it, cancelling at {@code <init>} HEAD only skips
 * the body of THAT constructor — {@code super()} already returned and
 * the subclass's {@code <init>} continues, leaving the DhLevel in a
 * half-initialized state that NPEs on every chunk update.
 *
 * <p><b>What we do now:</b> target the public {@code getOrLoadLevel} method
 * on each concrete DH world class. Bytecode inspection of DH 3.2.0-b
 * (verified by the research report) shows that
 * {@code ServerApi.serverLevelLoadEvent} calls
 * {@code SharedApi.getAbstractDhWorld().getOrLoadLevel(wrapper)} and
 * <em>pops the return value</em>. So returning {@code null} is safe —
 * the caller never dereferences it.
 *
 * <p><b>Why two targets in one {@code @Mixin}:</b>
 * <ul>
 *   <li>{@code com.seibel.distanthorizons.core.world.DhServerWorld} —
 *       used on the dedicated server path. Its
 *       {@code getOrLoadLevel(ILevelWrapper)} returns {@code DhServerLevel}.</li>
 *   <li>{@code com.seibel.distanthorizons.core.world.DhClientServerWorld} —
 *       used on the integrated-server (singleplayer) path. Its
 *       {@code getOrLoadLevel(ILevelWrapper)} returns
 *       {@code DhClientServerLevel}.</li>
 * </ul>
 * The remote-client path ({@code DhClientWorld.getOrLoadLevel} on a
 * multiplayer remote server) is not mixed because the hosting dimension
 * is server-side and the remote client never receives a level-load event
 * for it (it doesn't exist on the wire).
 *
 * <p><b>Threading:</b> the whole chain
 * {@code ServerLevel.<init>} → {@code LevelEvent.Load} →
 * {@code ServerApi.serverLevelLoadEvent} → {@code getOrLoadLevel} →
 * {@code new DhServerLevel} is synchronous on the server thread (verified
 * by disassembly). The {@link DhDimensionTracker} flag set by
 * {@link IplServerLevelDhTrackerMixin} at {@code ServerLevel.<init>}
 * RETURN is therefore guaranteed to still be set when this handler fires
 * microseconds later. The 1-second deadline in DhDimensionTracker is a
 * safety net only; it never trips in practice.
 *
 * <p><b>Soft-apply:</b> {@code @Pseudo} + {@code require = 0} ensure the
 * mixin no-ops if DH is absent or the API has changed.
 */
@Pseudo
@Mixin(
    targets = {
        "com.seibel.distanthorizons.core.world.DhServerWorld",
        "com.seibel.distanthorizons.core.world.DhClientServerWorld"
    },
    remap = false
)
public class IplDhSkipHostingDimensionMixin {

    private static final Logger LOG = LoggerFactory.getLogger("ipl-dh-compat");

    /**
     * Cancel {@code getOrLoadLevel} when the hosting dimension is loading.
     * Returns {@code null} — safe because
     * {@code ServerApi.serverLevelLoadEvent} pops the return value without
     * dereferencing it (verified by DH 3.2.0-b bytecode disassembly).
     *
     * <p>The handler signature uses {@code Object} for the level wrapper
     * parameter because {@code @Pseudo} mixins must not reference DH types
     * in the handler signature (the type isn't on our compile classpath).
     * The {@code CallbackInfoReturnable<?>} wildcard matches whatever
     * concrete return type {@code getOrLoadLevel} has on each target
     * ({@code DhServerLevel} or {@code DhClientServerLevel}).
     *
     * <p>{@code require = 0} so we don't crash if DH renames the method
     * or changes its signature in a future version.
     */
    @Inject(
        method = "getOrLoadLevel",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void ip_skipHostingDimensionDhLevel(
        Object levelWrapper,
        CallbackInfoReturnable<?> cir
    ) {
        if (DhDimensionTracker.isHostingDimensionLoading()) {
            LOG.debug(
                "Skipping DhLevel creation for ipl_sable:sublevels (hosting dim is loading)"
            );
            cir.setReturnValue(null);
        }
    }
}
