package com.kevin.tiertagger;

import com.kevin.tiertagger.config.TierTaggerConfig;
import com.kevin.tiertagger.model.PlayerInfo;
import com.mojang.brigadier.context.CommandContext;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.uku3lig.ukulib.config.ConfigManager;
import net.uku3lig.ukulib.utils.PlayerArgumentType;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

@Slf4j
public class TierTagger implements ModInitializer {
    @Getter
    private static final ConfigManager<TierTaggerConfig> manager = ConfigManager.createDefault(TierTaggerConfig.class, "tiertagger");

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





    }

    public static Text appendTier(PlayerEntity player, Text text) {
        MutableText following = switch (manager.getConfig().getShownStatistic()) {
            case TIER -> getPlayerTier(player.getUuid());
            case RANK -> TierCache.getPlayerInfo(player.getUuid())
                    .map(i -> Text.literal("#" + i.overall()))
                    .orElse(null);
        };

        if (following != null) {
            following.append(Text.literal(" | ").formatted(Formatting.GRAY));
            return following.append(text);
        }

        return text;
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

            case "HT1" -> 0xFF4500;
            case "LT1" -> 0xFF6347;
            case "HT2" -> 0xFF7F50;
            case "LT2" -> 0xFFA500;
            case "HT3" -> 0xDAA520;
            case "LT3" -> 0x808000;
            case "HT4" -> 0x228B22;
            case "LT4" -> 0x2E8B57;
            case "HT5" -> 0x008080;
            case "LT5" -> 0x708090;

            case "RLT2" -> 0x4c008a;
            case "RHT2" -> 0x7e008a;
            case "RLT1" -> 0x8a0064;
            case "RHT1" -> 0x8a0032;
            default -> 0xD3D3D3; // DEFAULT: pale grey
        };
    }
}