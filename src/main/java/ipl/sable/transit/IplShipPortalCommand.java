package ipl.sable.transit;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import qouteall.imm_ptl.core.commands.PortalCommand;
import qouteall.imm_ptl.core.portal.Portal;

/**
 * Atlas M6: {@code /iplsable_portal anchor|unanchor|list} — glue the portal the
 * player is looking at to the sub-level under its origin (and release it).
 * See {@link IplShipPortalAnchor}.
 *
 * <p>Plus the two runtime tuning knobs of the faster-than-tick crossing path
 * ({@code early_open_segments}) and the sweep-detector
 * debug overlay ({@code visualize_sweep_detector}). Both tuning values are live:
 * they take effect on the next tick, no restart, no reload.
 */
public final class IplShipPortalCommand {

    private IplShipPortalCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("iplsable_portal")
            .requires(source -> source.hasPermission(2))
            .then(Commands.literal("anchor").executes(context -> {
                ServerPlayer player = context.getSource().getPlayerOrException();
                Portal portal = PortalCommand.getPlayerPointingPortal(player, false);
                if (portal == null) {
                    context.getSource().sendFailure(Component.literal("no portal targeted"));
                    return 0;
                }
                String result = IplShipPortalAnchor.anchor(portal);
                context.getSource().sendSuccess(() -> Component.literal(result), false);
                return 1;
            }))
            .then(Commands.literal("unanchor").executes(context -> {
                ServerPlayer player = context.getSource().getPlayerOrException();
                Portal portal = PortalCommand.getPlayerPointingPortal(player, false);
                if (portal == null) {
                    context.getSource().sendFailure(Component.literal("no portal targeted"));
                    return 0;
                }
                String result = IplShipPortalAnchor.unanchor(portal);
                context.getSource().sendSuccess(() -> Component.literal(result), false);
                return 1;
            }))
            .then(Commands.literal("list").executes(context -> {
                context.getSource().sendSuccess(
                    () -> Component.literal(IplShipPortalAnchor.count() + " anchored portal(s)"),
                false);
                return 1;
            }))
            // PREDICTIVE SEAM OPENING (was PREARM_LOOKAHEAD_SEGMENTS). How far ahead of
            // the body's current motion the detector looks before opening the portal seam.
            .then(Commands.literal("early_open_segments")
                .executes(context -> {
                    context.getSource().sendSuccess(() -> Component.literal(
                        "early_open_segments = " + SableTransitController.getEarlyOpenSegments()
                            + " (default " + SableTransitController.DEFAULT_EARLY_OPEN_SEGMENTS
                            + "): how many physics segments of the body's own motion are"
                            + " extrapolated forward to open the portal seam BEFORE contact."
                            + " 0 = off, higher = opens earlier for faster bodies"),
                        false);
                    return 1;
                })
                .then(Commands.argument("value", DoubleArgumentType.doubleArg(0.0, 16.0))
                    .suggests((context, builder) -> {
                        java.util.LinkedHashMap<String, String> opts = new java.util.LinkedHashMap<>();
                        opts.put(String.valueOf(SableTransitController.DEFAULT_EARLY_OPEN_SEGMENTS),
                            "default " + SableTransitController.DEFAULT_EARLY_OPEN_SEGMENTS);
                        opts.putIfAbsent(String.valueOf(SableTransitController.getEarlyOpenSegments()),
                            "current " + SableTransitController.getEarlyOpenSegments());
                        opts.putIfAbsent("0.0", "off (no predictive seam opening)");
                        opts.forEach((v, tip) -> builder.suggest(v, Component.literal(tip)));
                        return builder.buildFuture();
                    })
                    .executes(context -> {
                        double applied = SableTransitController.setEarlyOpenSegments(
                            DoubleArgumentType.getDouble(context, "value"));
                        context.getSource().sendSuccess(() -> Component.literal(
                            "early_open_segments = " + applied), true);
                        return 1;
                    })))
            // Renamed from the unpronounceable
            // `volumetricenteringdetectionvisualization`.
            .then(Commands.literal("visualize_sweep_detector")
                .then(Commands.literal("on").executes(context -> setVolumeMode(context.getSource(),
                    IplEnteringVolumeVisualization.Mode.ON)))
                .then(Commands.literal("off").executes(context -> setVolumeMode(context.getSource(),
                    IplEnteringVolumeVisualization.Mode.OFF)))));
    }

    private static int setVolumeMode(CommandSourceStack source, IplEnteringVolumeVisualization.Mode mode) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("this command requires a player"));
            return 0;
        }
        if (!IplEnteringVolumeVisualization.setMode(source.getServer(), player, mode)) {
            source.sendFailure(Component.literal("look directly at a sub-level block first"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
            "sweep detector visualization: " + mode.name().toLowerCase()
                + " (swept volume: blue=no hit, green=entry, yellow=exit; aperture: green=source, yellow=destination)"), false);
        return 1;
    }
}
