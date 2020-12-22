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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.annotation.NonNull;

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
    public static final String VERSION = "1";
    private static final Logger LOGGER = LoggerFactory.getLogger(DiscordTransfer.class);
    private final DiscordClient discord;
    private final GatewayDiscordClient client;
    private final Guild guildA;
    private final Guild guildB;
    private Thread thread;
    private Set<Snowflake> categories;
    private Set<Snowflake> skipChannels;

    public DiscordTransfer(String token, long guildA, long guildB) {
        discord = DiscordClient.create(token);
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
        this.guildA = Objects.requireNonNull(client.getGuildById(Snowflake.of(guildA)).block(), "Invalid id for guild A");
        LOGGER.info("Loaded guild A: "+this.guildA.getName()+" ("+guildA+")");
        this.guildB = Objects.requireNonNull(client.getGuildById(Snowflake.of(guildB)).block(), "Invalid id for guild B");
        LOGGER.info("Loaded guild B: "+this.guildB.getName()+" ("+guildB+")");
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
                    .flatMap(guildA::getChannelById)
                    .ofType(Category.class);
        }
        return guildA.getChannels().ofType(Category.class);
    }

    public Flux<Void> cleanMigratedEmotes() {
        return getSelectedCategories().flatMap(Category::getChannels)
                .ofType(TextChannel.class)
                .filter(c -> !skipChannels.contains(c.getId()))
                .flatMap(c -> c.getMessagesAfter(c.getId()))
                .filter(m -> m.getType() == Message.Type.DEFAULT)
                .delayElements(Duration.ofMillis(500))
                .map(m -> {
                    User author = m.getAuthor().get();
                    LOGGER.info("Cleanning reaction ("+m.getChannelId().asString()+"/#"+m.getId().asString()+"): "+author.getUsername()+" at "+m.getTimestamp().toString());
                    return m;
                })
                .flatMap(m -> m.removeSelfReaction(MIGRATED_EMOJI));
    }

    public void migrate() {
        LOGGER.info("Starting migration ...");
        try {
            Long migrated = getSelectedCategories().flatMap(catA ->
                    Flux.from(Mono.justOrEmpty(guildB.getChannels()
                            .ofType(Category.class)
                            .filter(c -> c.getName().equals(catA.getName()))
                            .blockFirst())
                    .switchIfEmpty(
                            guildB.createCategory(c -> c.setName(catA.getName()))
                    )).flatMap(catB -> migrateCategory(catA, catB))
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

    private Flux<Message> migrateCategory(@NonNull Category catA, @NonNull Category catB) {
        LOGGER.info("Migrating category: "+catA.getName());
        return Flux.concat(
                migrateCategoryVoiceChannels(catA, catB),
                migrateCategoryTextChannels(catA, catB)
        ).onErrorResume(err -> {
            LOGGER.warn("Error in category migration", err);
            return Mono.empty();
        });
    }

    private Flux<Message> migrateCategoryVoiceChannels(@NonNull Category catA, @NonNull Category catB) {
        return catA.getChannels().ofType(VoiceChannel.class)
                // Filter on non-migrated channels
                .filter(chanA -> catB.getChannels()
                        .ofType(VoiceChannel.class)
                        .filter(c -> c.getName().equals(chanA.getName()))
                        .blockFirst() == null
                )
                // Actually create channel
                .flatMap(chanA -> guildB.createVoiceChannel(c -> c
                        .setName(chanA.getName())
                        .setParentId(catB.getId())
                        .setPosition(chanA.getRawPosition())
                ).flatMap(h -> Mono.empty()));
    }

    private Flux<Message> migrateCategoryTextChannels(@NonNull Category catA, @NonNull Category catB) {
        return catA.getChannels().ofType(TextChannel.class)
                // Filter on non-skipped channels
                .filter(c -> !skipChannels.contains(c.getId()))
                .flatMap(chanA -> migrateTextChannel(chanA,
                    Optional.ofNullable(
                            catB.getChannels()
                                    .ofType(TextChannel.class)
                                    .filter(c -> c.getName().equals(chanA.getName()))
                                    .blockFirst()
                    ).orElseGet(() ->
                            guildB.createTextChannel(c -> c
                                    .setName(chanA.getName())
                                    .setTopic(chanA.getTopic().orElse(null))
                                    .setNsfw(chanA.isNsfw())
                                    .setParentId(catB.getId())
                                    .setPosition(chanA.getRawPosition())
                            ).block()
                    )))
                .onErrorResume(err -> {
                    LOGGER.warn("Error in channel migration", err);
                    return Mono.empty();
                });
    }

    private static final ReactionEmoji MIGRATED_EMOJI = ReactionEmoji.unicode("\uD83D\uDD04");
    private Flux<Message> migrateTextChannel(@NonNull TextChannel chanA, @NonNull TextChannel chanB) {
        LOGGER.info("Migrating channel: "+chanA.getName());
        return chanA.getMessagesAfter(chanA.getId())
                .delayElements(Duration.ofMillis(500)) // Delay to reduce rate-limiting
                //*
                .filter(m -> m.getReactions().stream() // Filter on non migrated messages
                                .filter(Reaction::selfReacted)
                                .noneMatch(r -> r.getEmoji().equals(MIGRATED_EMOJI)))
                //*/
                .flatMap(m -> migrateMessage(m, chanB)); // Perform migration
    }

    private Mono<Message> migrateMessage(@NonNull Message msg, @NonNull TextChannel chanB) {
        if (msg.getType() != Message.Type.DEFAULT) return Mono.empty(); // Not a user message, do not migrate
        if (!msg.getAuthor().isPresent()) return Mono.empty(); // No author, do not migrate
        CompletableFuture<Message> fut = new CompletableFuture<>();
        User author = msg.getAuthor().get();
        LOGGER.info("Migrating message ("+chanB.getName()+"/#"+msg.getId().asString()+"): "+author.getUsername()+" at "+msg.getTimestamp().toString());
        chanB.createMessage(m -> {
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
            Attachment finalImage = image;
            m.setEmbed(e -> {
                e.setAuthor(author.getUsername(), "https://discord.com/channels/@me/" + author.getId().asString(), author.getAvatarUrl())
                        .setTimestamp(msg.getEditedTimestamp().orElse(msg.getTimestamp()))
                        .setDescription(message);
                if (finalImage != null) e.setImage(finalImage.getUrl());
            });
        }
        /*
        ).onErrorResume(err -> {
            LOGGER.warn("Error in message migration", err);
            return Mono.empty();}
        //*/
        ).subscribe(
                m -> msg.addReaction(MIGRATED_EMOJI)
                    .doOnSuccess(h -> fut.complete(m))
                    .doOnError(fut::completeExceptionally)
                    .subscribe(),
                fut::completeExceptionally);
        return Mono.fromFuture(fut);
    }

    // =================================================================================================================

    public static void main(String[] args) {
        if (args.length < 3) throw new RuntimeException("Missing arguments (action, token, guildA, guildB)");

        String token = System.getenv("DISCORD_TOKEN");
        if (token == null || token.isEmpty()) throw new IllegalArgumentException("Missing DISCORD_TOKEN");

        long guildA;
        long guildB;
        try {
            guildA = Long.parseLong(args[1]);
            guildB = Long.parseLong(args[2]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid guild IDs");
        }

        DiscordTransfer app = new DiscordTransfer(token, guildA, guildB);

		// Customize transfer (transfer specific categories, ignore channels)
        //app.addCategory(Snowflake.of(123456789123456789L));
        //app.skipChannel(Snowflake.of(123456789123456789L));

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
