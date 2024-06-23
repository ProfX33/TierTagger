package com.kevin.tiertagger;

import com.kevin.tiertagger.config.TierTaggerConfig;
import com.kevin.tiertagger.model.PlayerInfo;
import com.mojang.brigadier.context.CommandContext;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.client.toast.ToastManager;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.uku3lig.ukulib.config.ConfigManager;
import net.uku3lig.ukulib.utils.PlayerArgumentType;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.Optional;
import java.util.UUID;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

@Slf4j
public class TierTagger implements ModInitializer {
    @Getter
    private static final ConfigManager<TierTaggerConfig> manager = ConfigManager.createDefault(TierTaggerConfig.class, "tiertagger");
    @Getter
    private static final KeyBinding cycleGamemodeKey = new KeyBinding("key.tiertagger.cyclegamemode", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F9, "category.tiertagger");

    @Override
    public void onInitialize() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) -> {

            dispatcher.register(
                    literal("tt")
                            .then(argument("player", PlayerArgumentType.player())
                                    .executes(TierTagger::displayTierInfo))
            );

            dispatcher.register(
                    literal("tiertagger")
                            .then(argument("player", PlayerArgumentType.player())
                                    .executes(TierTagger::displayTierInfo))
            );

            dispatcher.register(
                    literal("ttdebug")
                            .then(literal("display_tier_colors")
                                    .executes(TierTagger::displayAllTierColors))
                            .then(literal("full_player_info")
                                    .then(argument("player", PlayerArgumentType.player())
                                            .executes(TierTagger::displayFullPlayerInfo)))
            );

        });

        KeyBindingHelper.registerKeyBinding(cycleGamemodeKey);
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (cycleGamemodeKey.wasPressed()) {
                cycleGamemode();
            }
        });

    }

    public static void cycleGamemode() {
        manager.getConfig().setGameMode(manager.getConfig().getGameMode().next());

        ToastManager toastManager = MinecraftClient.getInstance().getToastManager();
        SystemToast.show(toastManager, SystemToast.Type.PERIODIC_NOTIFICATION, Text.of("Switched Gamemode"), Text.of(manager.getConfig().getGameMode().toString()));
    }

    public static Text appendTierInfo(PlayerEntity player, Text text) {
        MutableText prefix = null;

        if (manager.getConfig().isShowRegion()) {
            MutableText playerRegionText = getPlayerRegion(player.getUuid());
            if (playerRegionText != null) {
                prefix = Text.literal("[").formatted(Formatting.GRAY);
                prefix.append(playerRegionText);
                prefix.append(Text.literal("] ").formatted(Formatting.GRAY));
            }
        }

        MutableText tierOrRankText = switch (manager.getConfig().getShownStatistic()) {
            case TIER -> getPlayerTier(player.getUuid());
            case RANK -> TierCache.getPlayerInfo(player.getUuid())
                    .map(i -> Text.literal("#" + i.overall()))
                    .orElse(null);
        };

        if (tierOrRankText != null) {
            if (prefix == null) {
                prefix = Text.literal("");
            }
            prefix.append(tierOrRankText);
            prefix.append(Text.literal(" | ").formatted(Formatting.GRAY));
        }

        if (prefix != null) {
            return Text.literal("").append(prefix).append(text);
        }

        return text;
    }

    @Nullable
    private static MutableText getPlayerRegion(UUID uuid) {
        return TierCache.getPlayerInfo(uuid)
                .map(PlayerInfo::region)
                .map(t -> Text.literal(t).withColor(getRegionColor(t)))
                .orElse(null);
    }

    @Nullable
    private static MutableText getPlayerTier(UUID uuid) {

        String mode = manager.getConfig().getGameMode().getApiKey();

        return TierCache.getPlayerInfo(uuid)
                .map(i -> i.rankings().get(mode))
                .map(TierTagger::getTierText)
                .map(t -> Text.literal(t).styled(s -> s.withColor(getTierColor(t))))
                .orElse(null);
    }

    @Nullable
    private static String getTierText(PlayerInfo.Ranking ranking) {
        if (ranking.retired() && ranking.peakTier() != null && ranking.peakPos() != null) {
            if (!manager.getConfig().isShowRetired()) {
                return null; // don't show retired
            } else {
                return "R" + (ranking.peakPos() == 0 ? "H" : "L") + "T" + ranking.peakTier();
            }
        } else {
            return (ranking.pos() == 0 ? "H" : "L") + "T" + ranking.tier();
        }
    }

    private static int displayTierInfo(CommandContext<FabricClientCommandSource> ctx) {
        PlayerArgumentType.PlayerSelector selector = ctx.getArgument("player", PlayerArgumentType.PlayerSelector.class);

        Optional<PlayerInfo> info = ctx.getSource().getWorld().getPlayers().stream()
                .filter(p -> p.getNameForScoreboard().equalsIgnoreCase(selector.name()) || p.getUuidAsString().equalsIgnoreCase(selector.name()))
                .findFirst()
                .map(Entity::getUuid)
                .flatMap(TierCache::getPlayerInfo);

        if (info.isPresent()) {
            ctx.getSource().sendFeedback(printPlayerInfo(info.get()));
        } else {
            ctx.getSource().sendFeedback(Text.literal("[TierTagger] Searching...").withColor(0xb4e4f0));
            TierCache.searchPlayer(selector.name())
                    .thenAccept(p -> ctx.getSource().sendFeedback(printPlayerInfo(p)))
                    .exceptionally(t -> {
                        ctx.getSource().sendError(Text.of("[TierTagger] Could not find player " + selector.name()));
                        log.error("Error getting player Info", t);
                        return null;
                    });
        }

        return 0;
    }

    private static int displayFullPlayerInfo(CommandContext<FabricClientCommandSource> ctx) {
        PlayerArgumentType.PlayerSelector selector = ctx.getArgument("player", PlayerArgumentType.PlayerSelector.class);

        Optional<PlayerInfo> info = ctx.getSource().getWorld().getPlayers().stream()
                .filter(p -> p.getNameForScoreboard().equalsIgnoreCase(selector.name()) || p.getUuidAsString().equalsIgnoreCase(selector.name()))
                .findFirst()
                .map(Entity::getUuid)
                .flatMap(TierCache::getPlayerInfo);

        if (info.isPresent()) {
            ctx.getSource().sendFeedback(printFullPlayerInfo(info.get()));
        } else {
            ctx.getSource().sendFeedback(Text.literal("[TierTagger] Searching...").withColor(0xb4e4f0));
            TierCache.searchPlayer(selector.name())
                    .thenAccept(p -> ctx.getSource().sendFeedback(printFullPlayerInfo(p)))
                    .exceptionally(t -> {
                        ctx.getSource().sendError(Text.of("[TierTagger] Could not find player " + selector.name()));
                        log.error("Error getting player Info", t);
                        return null;
                    });
        }

        return 0;
    }

    private static int displayAllTierColors(CommandContext<FabricClientCommandSource> ctx) {
        System.out.println("display all tier colors");
        MutableText text = Text.empty().append("Tier Colors:");
        
        String[] tiers = {"LT5", "HT5", "LT4", "HT4", "LT3", "HT3", "LT2", "HT2", "LT1", "HT1", "RLT2", "RHT2", "RLT1", "RHT1"};
        for (String tier: tiers) {
            text.append(Text.literal("\n"));
            text.append(Text.literal(tier).styled(s -> s.withColor(getTierColor(tier))));
        }

        ctx.getSource().sendFeedback(text);

        return 0;
    }

    private static Text printPlayerInfo(PlayerInfo info) {
        MutableText text = Text.empty().append(Text.literal("[TierTagger] Tierlist Info for " + info.name()).withColor(0x65a7e0));

        text.append(Text.literal("\nRegion: ").styled(s -> s.withColor(0xb4e4f0)));
        text.append(Text.literal(info.region()).withColor(getRegionColor(info.region())));

        info.rankings().forEach((m, r) -> {
            String tier = getTierText(r);

            if (tier != null) {
                Text tierText = Text.literal(tier).styled(s -> s.withColor(getTierColor(tier)));
                text.append(Text.literal("\n" + m + ": ").append(tierText));
            }
        });

        return text;
    }

    private static Text printFullPlayerInfo(PlayerInfo info) {
        MutableText text = Text.empty().append(Text.literal("[TierTagger] Full Tierlist Info for " + info.name()).withColor(0x65a7e0));

        text.append(Text.literal("\nRegion: ").withColor(0xb4e4f0));
        text.append(Text.literal(info.region()).withColor(getRegionColor(info.region())));

        text.append(Text.literal("\nPoints: ").withColor(0xb4e4f0));
        text.append(Text.literal(String.valueOf(info.points())).withColor(0x1c7ad9));

        text.append(Text.literal( " [" + info.getPointInfo().getTitle() + "]").withColor(0xb4e4f0));

        text.append(Text.literal("\nOverall: ").withColor(0xb4e4f0));
        text.append(Text.literal(String.valueOf(info.overall())).withColor(0x1c7ad9));

        text.append(Text.literal("\nRankings: ").withColor(0xb4e4f0));

        info.rankings().forEach((m, r) -> {
            String tier = getTierText(r);

            if (tier != null) {
                Text tierText = Text.literal(tier).styled(s -> s.withColor(getTierColor(tier)));
                text.append(Text.literal("\n" + m + ": ").append(tierText));
            }
        });

        return text;
    }

    private static int getRegionColor(String region) {

        return switch (region) {
            case "EU" -> 0x6aff6e;
            case "NA" -> 0xff6a6e;
            case "AS" -> 0xc27ba0;
            case "ME" -> 0xffd966;
            case "AF" -> 0x674ea7;
            case "AU" -> 0xf6b26b;
            case "SA" -> 0xff9900;
            default -> 0xD3D3D3;
        };

    }

    private static int getTierColor(String tier) {

        return switch (tier) {

            //Alternating Tier Colors
            case "HT1" -> 0xCC0000;
            case "LT1" -> 0xFF4B4B;
            case "HT2" -> 0x0FB5C7;
            case "LT2" -> 0xA3F6FF;
            case "HT3" -> 0xFFBD47;
            case "LT3" -> 0xFFE197;
            case "HT4" -> 0x6AA343;
            case "LT4" -> 0xA9D08E;
            case "HT5" -> 0x595959;
            case "LT5" -> 0x868282;

            case "RHT1" -> 0x893B8B;
            case "RLT1" -> 0xAE55B7;
            case "RHT2" -> 0x4C3AB8;
            case "RLT2" -> 0x6E5FDB;

            /*
            //Gradient Tier Colors
            case "HT1" -> 0xB0004B;
            case "LT1" -> 0xED0155;
            case "HT2" -> 0xFF513F;
            case "LT2" -> 0xFEA16E;
            case "HT3" -> 0xFFEEC5;
            case "LT3" -> 0xB3F8FF;
            case "HT4" -> 0x43D2E1;
            case "LT4" -> 0x35937D;
            case "HT5" -> 0x4B6F3D;
            case "LT5" -> 0x4A5B3F;

            case "RHT1" -> 0x1A2552;
            case "RLT1" -> 0x3F1E60;
            case "RHT2" -> 0x571E60;
            case "RLT2" -> 0x5C2250;
            */

            default -> 0xD3D3D3; // DEFAULT: pale grey
        };
    }
}