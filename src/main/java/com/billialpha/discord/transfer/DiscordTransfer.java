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
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.util.annotation.NonNull;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Bot invite link:
 * https://discord.com/api/oauth2/authorize?client_id={ CLIENT_ID }&scope=bot
 * @author BilliAlpha <billi.pamege.300@gmail.com>
 */
public class DiscordTransfer {
    public static final String VERSION = "2.2.0";
    private static final Logger LOGGER = LoggerFactory.getLogger(DiscordTransfer.class);
    private final GatewayDiscordClient client;
    private final Guild srcGuild;
    private final Guild destGuild;
    private final Set<Snowflake> skipChannels;
    private Set<Snowflake> categories;
    private Instant afterDate;
    private int delay;
    private Thread thread;
    private final Scheduler scheduler;

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
        this.scheduler = Schedulers.parallel();
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

    public void afterDate(Instant after) {
        this.afterDate = after;
    }

    public void widthDelay(int delay) {
        this.delay = delay;
    }

    private Snowflake getChannelStartDate(Snowflake chanId) {
        if (this.afterDate == null)
            return chanId;
        return this.afterDate.isAfter(chanId.getTimestamp())
                ? Snowflake.of(this.afterDate)
                : chanId;
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
        Flux<Message> flux = getSelectedCategories().flatMap(Category::getChannels)
                .ofType(TextChannel.class)
                .filter(c -> !skipChannels.contains(c.getId()))
                .flatMap(c -> c.getMessagesAfter(getChannelStartDate(c.getId())))
                .filter(m -> m.getType() == Message.Type.DEFAULT || m.getType() == Message.Type.REPLY);
        if (delay > 0) flux = flux.delayElements(Duration.ofMillis(delay));
        return flux.doOnNext(m -> {
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
            Long migrated = getSelectedCategories()
                .parallel()
                .runOn(scheduler)
                .flatMap(srcCat ->
                    destGuild.getChannels().ofType(Category.class)
                            .filter(c -> c.getName().equals(srcCat.getName()))
                            .singleOrEmpty()
                            .switchIfEmpty(
                                    destGuild.createCategory(srcCat.getName()))
                            .flatMapMany(dstCat -> migrateCategory(srcCat, dstCat))
                )
                .reduce(Long::sum)
                .block();
            LOGGER.info("Migration finished successfully ("+migrated+" messages)");
            client.logout().block();
            LOGGER.info("Logged out");
        } catch (Throwable t) {
            LOGGER.error("Error .. Shutting down !");
            t.printStackTrace();
            client.logout().block();
        }
    }

    private Mono<Long> migrateCategory(@NonNull Category srcCat, @NonNull Category dstCat) {
        LOGGER.info("Migrating category: "+srcCat.getName());
        return migrateCategoryVoiceChannels(srcCat, dstCat)
                .then(migrateCategoryTextChannels(srcCat, dstCat)
                        .map(TextChannelMigrationResult::getMessageCount)
                        .reduce(Long::sum))
                .onErrorResume(err -> {
                    LOGGER.warn("Error in category migration", err);
                    return Mono.empty();
                });
    }

    private Mono<Void> migrateCategoryVoiceChannels(@NonNull Category srcCat, @NonNull Category dstCat) {
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
                                .position(srcChan.getRawPosition()).build()))
                .then();
    }

    private Flux<TextChannelMigrationResult> migrateCategoryTextChannels(@NonNull Category srcCat, @NonNull Category dstCat) {
        return srcCat.getChannels().ofType(TextChannel.class)
                .parallel().runOn(scheduler)
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
                // T1 = dest chan, T2 = src chan
                .flatMap(tuple -> migrateTextChannel(tuple.getT2(), tuple.getT1())
                        .count()
                        .map(count -> new TextChannelMigrationResult(tuple.getT2(), tuple.getT1(), count)))
                .groups().flatMap(Flux::collectList).flatMapIterable(Function.identity())
                .onErrorResume(err -> {
                    LOGGER.warn("Error in channel migration", err);
                    return Mono.empty();
                });
    }

    private static final ReactionEmoji MIGRATED_EMOJI = ReactionEmoji.unicode("\uD83D\uDD04");
    private Flux<Message> migrateTextChannel(@NonNull TextChannel srcChan, @NonNull TextChannel dstChan) {
        LOGGER.info("Migrating channel: "+srcChan.getName());
        Snowflake startDate = getChannelStartDate(srcChan.getId());
        LOGGER.debug("Channel date: "+startDate.getTimestamp());
        Flux<Message> flux = srcChan.getMessagesAfter(startDate);
        if (delay > 0) flux = flux.delayElements(Duration.ofMillis(delay)); // Delay to reduce rate-limiting
        return flux.filter(m -> m.getReactions().stream() // Filter on non migrated messages
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
                .author(
                        author.getUsername(),
                        "https://discord.com/channels/@me/" + author.getId().asString(),
                        author.getAvatarUrl())
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

    public static class TextChannelMigrationResult {
        public final TextChannel sourceChan;
        public final TextChannel destChan;
        public final long messageCount;

        public TextChannelMigrationResult(TextChannel sourceChan, TextChannel destChan, long messageCount) {
            this.sourceChan = sourceChan;
            this.destChan = destChan;
            this.messageCount = messageCount;
        }

        public TextChannel getSourceChan() {
            return sourceChan;
        }

        public TextChannel getDestChan() {
            return destChan;
        }

        public long getMessageCount() {
            return messageCount;
        }
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
            System.out.println("    --after <DATE>, -a <DATE>");
            System.out.println("      Only migrate messages sent after the given date.");
            System.out.println("      Date is expected to follow ISO-8601 format (ex: `1997−07−16T19:20:30,451Z`)");
            System.out.println();
            System.out.println("    --delay <DELAY>, -d <DELAY>");
            System.out.println("      Add a delay (in milliseconds) between each message migration.");
            System.out.println("      By default there is no delay.");
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
                } else if ("-a".equals(arg) || "--after".equals(arg)) {
                    try {
                        app.afterDate(Instant.parse(args[++i]));
                    } catch (ArrayIndexOutOfBoundsException ex) {
                        System.err.println("Missing DATE value");
                        return;
                    } catch (DateTimeParseException ex) {
                        System.err.println("Invalid DATE format: "+args[i]);
                        return;
                    }
                } else if ("-d".equals(arg) || "--delay".equals(arg)) {
                    try {
                        app.widthDelay(Integer.parseUnsignedInt(args[++i]));
                    } catch (ArrayIndexOutOfBoundsException ex) {
                        System.err.println("Missing DELAY value");
                        return;
                    } catch (NumberFormatException ex) {
                        System.err.println("Invalid DELAY: "+args[i]);
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
