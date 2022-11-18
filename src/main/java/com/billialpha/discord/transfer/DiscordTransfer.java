package com.billialpha.discord.transfer;

import discord4j.common.util.Snowflake;
import discord4j.core.DiscordClient;
import discord4j.core.GatewayDiscordClient;
import discord4j.core.event.domain.message.MessageCreateEvent;
import discord4j.core.object.entity.Attachment;
import discord4j.core.object.entity.Guild;
import discord4j.core.object.entity.Message;
import discord4j.core.object.entity.User;
import discord4j.core.object.entity.channel.Category;
import discord4j.core.object.entity.channel.TextChannel;
import discord4j.core.object.entity.channel.VoiceChannel;
import discord4j.core.object.reaction.Reaction;
import discord4j.core.object.reaction.ReactionEmoji;
import discord4j.core.spec.EmbedCreateSpec;
import discord4j.core.spec.MessageCreateSpec;
import discord4j.core.spec.TextChannelCreateSpec;
import discord4j.core.spec.VoiceChannelCreateSpec;
import discord4j.discordjson.possible.Possible;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.function.TupleUtils;
import reactor.util.annotation.NonNull;
import reactor.util.function.Tuples;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Bot invite link:
 * https://discord.com/api/oauth2/authorize?client_id={ CLIENT_ID }&scope=bot
 * @author BilliAlpha <billi.pamege.300@gmail.com>
 */
public class DiscordTransfer {
    public static final String VERSION = "2.1.0";
    private static final Logger LOGGER = LoggerFactory.getLogger(DiscordTransfer.class);
    private final GatewayDiscordClient client;
    private final Guild srcGuild;
    private final Guild destGuild;
    private final Set<Snowflake> skipChannels;
    private Set<Snowflake> categories;
    private Thread thread;

    public DiscordTransfer(String token, long srcGuild, long destGuild) {
        DiscordClient discord = DiscordClient.create(token);
        LOGGER.info("Logging in ...");
        client = Objects.requireNonNull(discord.login().block(), "Invalid bot token");
        User self = Objects.requireNonNull(client.getSelf().block());
        LOGGER.info("Logged in, user: "+self.getUsername()+"#"+self.getDiscriminator());
        client.on(MessageCreateEvent.class)
                .filter(evt -> evt.getMessage().getContent().equals("migrate stop"))
                .subscribe(e -> {
                    LOGGER.info("Logging out ...");
                    stop();
                });
        Snowflake sourceGuild = Snowflake.of(srcGuild);
        this.srcGuild = Objects.requireNonNull(client.getGuildById(sourceGuild).block(), "Invalid id for source guild");
        LOGGER.info("Loaded source guild: "+this.srcGuild.getName()+" ("+srcGuild+")");
        Snowflake destinationGuild = Snowflake.of(destGuild);
        this.destGuild = Objects.requireNonNull(client.getGuildById(destinationGuild).block(), "Invalid id for dest guild");
        LOGGER.info("Loaded dest guild: "+this.destGuild.getName()+" ("+destGuild+")");
        this.categories = null;
        this.skipChannels = new HashSet<>(0);
    }

    public void start() {
        if (thread != null) throw new IllegalStateException("Migration already started");
        thread = new Thread(this::migrate);
        thread.start();
    }

    public void stop() {
        if (thread != null) thread.interrupt();
        client.logout().block();
    }

    public void await() {
        client.onDisconnect().block();
    }

    public void skipChannel(Snowflake channelId) {
        skipChannels.add(channelId);
    }

    public void addCategory(Snowflake categoryId) {
        if (categories == null) categories = new HashSet<>(1);
        categories.add(categoryId);
    }

    private Flux<Category> getSelectedCategories() {
        if (categories != null) {
            return Flux.fromIterable(categories)
                    .flatMap(srcGuild::getChannelById)
                    .ofType(Category.class);
        }
        return srcGuild.getChannels().ofType(Category.class);
    }

    public Flux<Void> cleanMigratedEmotes() {
        return getSelectedCategories().flatMap(Category::getChannels)
                .ofType(TextChannel.class)
                .filter(c -> !skipChannels.contains(c.getId()))
                .flatMap(c -> c.getMessagesAfter(c.getId()))
                .filter(m -> m.getType() == Message.Type.DEFAULT)
                .delayElements(Duration.ofMillis(500))
                .doOnNext(m -> {
                    Optional<User> author = m.getAuthor();
                    if (!author.isPresent()) return;
                    LOGGER.info("Cleaning reaction ("+m.getChannelId().asString()+"/#"+m.getId().asString()+"): "+
                            author.get().getUsername()+" at "+m.getTimestamp());
                })
                .flatMap(m -> m.removeSelfReaction(MIGRATED_EMOJI));
    }

    public void migrate() {
        LOGGER.info("Starting migration ...");
        try {
            Long migrated = getSelectedCategories().flatMap(srcCat ->
                    Flux.from(Mono.justOrEmpty(destGuild.getChannels()
                            .ofType(Category.class)
                            .filter(c -> c.getName().equals(srcCat.getName()))
                            .blockFirst())
                    .switchIfEmpty(
                            destGuild.createCategory(srcCat.getName())
                    )).flatMap(dstCat -> migrateCategory(srcCat, dstCat))
            ).count().block();
            LOGGER.info("Migration finished successfully ("+migrated+" messages)");
            client.logout().block();
            LOGGER.info("Logged out");
        } catch (Throwable t) {
            LOGGER.error("Error .. Shutting down !");
            t.printStackTrace();
            client.logout().block();
        }
    }

    private Flux<Message> migrateCategory(@NonNull Category srcCat, @NonNull Category dstCat) {
        LOGGER.info("Migrating category: "+srcCat.getName());
        return Flux.concat(
                migrateCategoryVoiceChannels(srcCat, dstCat),
                migrateCategoryTextChannels(srcCat, dstCat)
        ).onErrorResume(err -> {
            LOGGER.warn("Error in category migration", err);
            return Mono.empty();
        });
    }

    private Flux<Message> migrateCategoryVoiceChannels(@NonNull Category srcCat, @NonNull Category dstCat) {
        return srcCat.getChannels().ofType(VoiceChannel.class)
                // Filter on non-migrated channels
                .filter(srcChan -> dstCat.getChannels()
                        .ofType(VoiceChannel.class)
                        .filter(c -> c.getName().equals(srcChan.getName()))
                        .blockFirst() == null
                )
                // Actually create channel
                .flatMap(srcChan -> destGuild.createVoiceChannel(VoiceChannelCreateSpec.builder()
                                .name(srcChan.getName())
                                .parentId(dstCat.getId())
                                .position(srcChan.getRawPosition()).build()
                ).flatMap(h -> Mono.empty()));
    }

    private Flux<Message> migrateCategoryTextChannels(@NonNull Category srcCat, @NonNull Category dstCat) {
        return srcCat.getChannels().ofType(TextChannel.class)
                // Filter on non-skipped channels
                .filter(c -> !skipChannels.contains(c.getId()))
                .flatMap(srcChan -> dstCat.getChannels()
                        .ofType(TextChannel.class)
                        .filter(c -> c.getName().equals(srcChan.getName()))
                        .singleOrEmpty()
                        .switchIfEmpty(destGuild.createTextChannel(TextChannelCreateSpec.builder()
                                .name(srcChan.getName())
                                .topic(srcChan.getTopic().map(Possible::of).orElse(Possible.absent()))
                                .nsfw(srcChan.isNsfw())
                                .parentId(dstCat.getId())
                                .position(srcChan.getRawPosition())
                                .build()))
                        .zipWith(Mono.just(srcChan)))
                .map(tuple -> Tuples.of(tuple.getT2(), tuple.getT1()))
                .flatMap(TupleUtils.function(this::migrateTextChannel))
                .onErrorResume(err -> {
                    LOGGER.warn("Error in channel migration", err);
                    return Mono.empty();
                });
    }

    private static final ReactionEmoji MIGRATED_EMOJI = ReactionEmoji.unicode("\uD83D\uDD04");
    private Flux<Message> migrateTextChannel(@NonNull TextChannel srcChan, @NonNull TextChannel dstChan) {
        LOGGER.info("Migrating channel: "+srcChan.getName());
        LOGGER.debug("Channel date: "+srcChan.getId().getTimestamp());
        return srcChan.getMessagesAfter(srcChan.getId())
                .delayElements(Duration.ofMillis(500)) // Delay to reduce rate-limiting
                .filter(m -> m.getReactions().stream() // Filter on non migrated messages
                                .filter(Reaction::selfReacted)
                                .noneMatch(r -> r.getEmoji().equals(MIGRATED_EMOJI)))
                .flatMap(m -> migrateMessage(m, dstChan)); // Perform migration
    }

    private Mono<Message> migrateMessage(@NonNull Message msg, @NonNull TextChannel dstChan) {
        if (msg.getType() != Message.Type.DEFAULT && msg.getType() != Message.Type.REPLY)
            return Mono.empty(); // Not a user message, do not migrate
        if (!msg.getAuthor().isPresent()) return Mono.empty(); // No author, do not migrate
        CompletableFuture<Message> fut = new CompletableFuture<>();
        User author = msg.getAuthor().get();
        LOGGER.info("Migrating message ("+dstChan.getName()+"/#"+msg.getId().asString()+"): "+
                author.getUsername()+" at "+msg.getTimestamp());
        MessageCreateSpec.Builder m = MessageCreateSpec.builder();
        Attachment image = null;
        for (Attachment att : msg.getAttachments()) {
            if (image == null && att.getWidth().isPresent()) {
                image = att;
                continue;
            }
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(att.getUrl()).openConnection();
                conn.setRequestProperty("User-Agent", "DiscordTransfer (v"+VERSION+")");
                if (conn.getResponseCode()/100 != 2 && conn.getContentLength() > 0) {
                    InputStream stream = conn.getErrorStream();
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    int nRead;
                    byte[] data = new byte[1024];
                    while ((nRead = stream.read(data, 0, data.length)) != -1) {
                        buffer.write(data, 0, nRead);
                    }
                    stream.close();
                    buffer.flush();
                    LOGGER.warn("Attachment HTTP error:\n"+new String(buffer.toByteArray(), StandardCharsets.UTF_8));
                } else m.addFile(att.getFilename(), conn.getInputStream());
            } catch (IOException e) {
                LOGGER.warn("Unable to forward attachment", e);
            }
        }
        LOGGER.debug("Raw message:\n\t"+msg.getContent().replaceAll("\n", "\n\t"));
        String message = msg.getContent().replaceAll("<@&\\d+>", ""); // Remove role mentions
        EmbedCreateSpec.Builder embed = EmbedCreateSpec.builder()
                .author(author.getUsername(), "https://discord.com/channels/@me/" + author.getId().asString(), author.getAvatarUrl())
                .timestamp(msg.getEditedTimestamp().orElse(msg.getTimestamp()))
                .description(message);
        if (image != null) embed.image(image.getUrl());
        m.addEmbed(embed.build());
        dstChan.createMessage(m.build())
        /*
        .onErrorResume(err -> {
            LOGGER.warn("Error in message migration", err);
            return Mono.empty();
        })
        //*/
        .subscribe(
                createdMessage -> msg.addReaction(MIGRATED_EMOJI)
                    .doOnSuccess(h -> fut.complete(createdMessage))
                    .doOnError(fut::completeExceptionally)
                    .subscribe(),
                fut::completeExceptionally);
        return Mono.fromFuture(fut);
    }

    // =================================================================================================================

    public static void main(String[] args) {
        if (args.length == 1 && ("help".equals(args[0]) || "?".equals(args[0]) || "--help".equals(args[0]))) {
            System.out.println("discord-transfer");
            System.out.print("by BilliAlpha (billi.pamege.300@gmail.com) ");
            System.out.println("-- https://github.com/BilliAlpha/discord-transfer");
            System.out.println();
            System.out.println("A discord bot for copying messages between guilds");
            System.out.println();
            System.out.println("Usage: <action> <srcGuild> <destGuild> [options...]");
            System.out.println("  Arguments:");
            System.out.println("    <action>: The action to perform");
            System.out.println("      help - Show this message and exit");
            System.out.println("      migrate - Migrate messages from source guild to dest guild");
            System.out.println("      clean - Delete migration reactions");
            System.out.println("    <srcGuild>: The Discord ID of the source guild");
            System.out.println("    <destGuild>: The Discord ID of the destination guild");
            System.out.println();
            System.out.println("  Options:");
            System.out.println("    --category <ID>, -c <ID>");
            System.out.println("      Limit the migration to specific categories, this option expects a Discord ID.");
            System.out.println("      You can use this option multiple times to migrate multiple categories.");
            System.out.println("      By default, if this option is not present all categories are migrated.");
            System.out.println();
            System.out.println("    --skip <ID>, -s <ID>");
            System.out.println("      Do not migrate a channel, this option expects a Discord ID.");
            System.out.println("      You can use this option multiple times to skip multiple channels.");
            System.out.println();
            System.out.println("  Environment variables:");
            System.out.println("    DISCORD_TOKEN: Required, the Discord bot token");
            return;
        }

        if (args.length < 3) {
            System.err.println("Missing arguments (action, srcGuild, destGuild)");
            System.out.println("See 'help' action for help");
            return;
        }

        String token = System.getenv("DISCORD_TOKEN");
        if (token == null || token.isEmpty()) {
            System.err.println("Missing DISCORD_TOKEN");
            System.out.println("See 'help' action for help");
            return;
        }

        long srcGuild;
        long destGuild;
        try {
            srcGuild = Long.parseLong(args[1]);
            destGuild = Long.parseLong(args[2]);
        } catch (NumberFormatException e) {
            System.err.println("Invalid guild IDs");
            System.out.println("See 'help' action for help");
            return;
        }

        DiscordTransfer app = new DiscordTransfer(token, srcGuild, destGuild);

        if (args.length > 3) {
            for (int i = 3; i < args.length; i++) {
                String arg = args[i];
                if ("-c".equals(arg) || "--category".equals(arg)) {
                    try {
                        app.addCategory(Snowflake.of(Long.parseLong(args[++i])));
                    } catch (ArrayIndexOutOfBoundsException ex) {
                        System.err.println("Missing category ID");
                        return;
                    } catch (NumberFormatException ex) {
                        System.err.println("Invalid category ID: "+args[i]);
                        return;
                    }
                } else if ("-s".equals(arg) || "--skip".equals(arg)) {
                    try {
                        app.skipChannel(Snowflake.of(Long.parseLong(args[++i])));
                    } catch (ArrayIndexOutOfBoundsException ex) {
                        System.err.println("Missing skip channel ID");
                        return;
                    } catch (NumberFormatException ex) {
                        System.err.println("Invalid skip channel ID: "+args[i]);
                        return;
                    }
                } else {
                    System.err.println("Unsupported option: "+arg);
                    System.out.println("See 'help' action for help");
                    return;
                }
            }
        }

        LOGGER.info("Start action '"+args[0]+"'");
        if (args[0].equals("migrate")) {
            app.start();
            app.await();
        } else if (args[0].equals("clean")) {
            app.cleanMigratedEmotes().blockLast();
            app.stop();
        } else {
            app.stop();
            throw new IllegalArgumentException("Unknown action");
        }
    }
}
