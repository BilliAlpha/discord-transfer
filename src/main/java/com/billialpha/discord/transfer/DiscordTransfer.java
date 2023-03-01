package com.billialpha.discord.transfer;

import ch.qos.logback.classic.Level;
import discord4j.common.util.Snowflake;
import discord4j.core.DiscordClient;
import discord4j.core.GatewayDiscordClient;
import discord4j.core.object.Embed;
import discord4j.core.object.entity.Attachment;
import discord4j.core.object.entity.Guild;
import discord4j.core.object.entity.Message;
import discord4j.core.object.entity.User;
import discord4j.core.object.entity.channel.Category;
import discord4j.core.object.entity.channel.TextChannel;
import discord4j.core.object.entity.channel.VoiceChannel;
import discord4j.core.object.reaction.Reaction;
import discord4j.core.object.reaction.ReactionEmoji;
import discord4j.core.spec.*;
import discord4j.discordjson.possible.Possible;
import discord4j.gateway.intent.Intent;
import discord4j.gateway.intent.IntentSet;
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
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Bot invite link:
 * https://discord.com/api/oauth2/authorize?client_id={ CLIENT_ID }&scope=bot
 * @author BilliAlpha <billi.pamege.300@gmail.com>
 */
public class DiscordTransfer {
    public static final String VERSION = "2.3.0";
    private static final Logger LOGGER = LoggerFactory.getLogger(DiscordTransfer.class);
    private GatewayDiscordClient client;
    private Guild srcGuild;
    private Guild destGuild;
    private final Set<Snowflake> skipChannels;
    private Set<Snowflake> categories;
    private Instant afterDate;
    private int delay;
    private Thread thread;
    private Runnable action;
    private final Scheduler scheduler;
    private int verbosity = 0;
    private boolean reUploadFiles = true;

    public DiscordTransfer() {
        this.categories = null;
        this.skipChannels = new HashSet<>(0);
        this.scheduler = Schedulers.parallel();
    }

    public void init(String token) {
        if (client != null) {
            throw new IllegalStateException("Client already initialized");
        }

        DiscordClient discord = DiscordClient.create(token);

        LOGGER.debug("Logging in ...");
        client = Objects.requireNonNull(discord.gateway()
                .setEnabledIntents(IntentSet.of(Intent.GUILD_MESSAGES))
                .login().block(), "Invalid bot token");
        User self = Objects.requireNonNull(client.getSelf().block());
        LOGGER.info("Logged in, user: "+self.getUsername()+"#"+self.getDiscriminator());
    }

    public void start() {
        if (client == null) throw new IllegalStateException("Client not initialized");
        if (thread != null) throw new IllegalStateException("Action already started");
        if (action == null) throw new IllegalStateException("No action to perform");
        thread = new Thread(() -> {
            try {
                this.action.run();
            } catch (Throwable ex) {
                System.err.println("ERROR: "+ex.getMessage());
                if (verbosity > 0) {
                    ex.printStackTrace();
                } else {
                    System.out.println("Run with verbose flag to get stacktrace.");
                }
            }
        });
        thread.start();
    }

    public void run() {
        start();
        try {
            thread.join();
        } catch (InterruptedException ex) {
            return;
        }
        stop();
    }

    public void stop() {
        if (thread != null)  {
            thread.interrupt();
            try {
                thread.join();
            } catch (InterruptedException ex) {
                return;
            }
        }
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
        Optional<Long> migrated = getSelectedCategories()
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
            .blockOptional();
        if (migrated.isPresent())
            LOGGER.info("Migration finished successfully ("+migrated.get()+" messages)");
        else
            LOGGER.info("Migration finished without migrating any message");
        client.logout().block();
        LOGGER.debug("Logged out");
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
                .filterWhen(srcChan -> dstCat.getChannels()
                        .ofType(VoiceChannel.class)
                        .filter(c -> c.getName().equals(srcChan.getName()))
                        .count()
                        .map(c -> c == 0)
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

        // Add message info to embed
        LOGGER.debug("Raw message:\n\t" + msg.getContent().replaceAll("\n", "\n\t"));
        String message = msg.getContent().replaceAll("<@&\\d+>", ""); // Remove role mentions
        EmbedCreateSpec.Builder embed = EmbedCreateSpec.builder()
                .author(
                        author.getUsername(),
                        "https://discord.com/channels/@me/" + author.getId().asString(),
                        author.getAvatarUrl())
                .timestamp(msg.getEditedTimestamp().orElse(msg.getTimestamp()))
                .description(message);

        if (reUploadFiles) {
            // Download and re-upload files
            m.addEmbed(embed.build()); // Send message embed now because we won't need it later
            for (Attachment att : msg.getAttachments()) {
                try {
                    HttpURLConnection conn = (HttpURLConnection) new URL(att.getUrl()).openConnection();
                    conn.setRequestProperty("User-Agent", "DiscordTransfer (v"+VERSION+")");
                    if (conn.getResponseCode()/100 != 2 && conn.getContentLength() > 0) {
                        // Decode error message
                        InputStream stream = conn.getErrorStream();
                        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                        int nRead;
                        byte[] data = new byte[1024];
                        while ((nRead = stream.read(data, 0, data.length)) != -1) {
                            buffer.write(data, 0, nRead);
                        }
                        stream.close();
                        buffer.flush();
                        LOGGER.warn("Attachment HTTP error:\n\t"+
                                new String(buffer.toByteArray(), StandardCharsets.UTF_8)
                                        .replaceAll("\n", "\n\t"));
                    } else {
                        // Upload downloaded data
                        m.addFile(att.getFilename(), conn.getInputStream());
                    }
                } catch (IOException e) {
                    LOGGER.warn("Unable to forward attachment", e);
                }
            }
        } else {
            // Just link to the original files
            boolean firstImage = true;
            List<EmbedCreateSpec> otherEmbeds = new ArrayList<>();
            for (Attachment att : msg.getAttachments()) {
                if (att.getWidth().isPresent()) {
                    // This is an image
                    if (firstImage) {
                        // Include first image in embed
                        embed.image(att.getUrl());
                        firstImage = false;
                    } else {
                        // Create new embeds with following images
                        otherEmbeds.add(EmbedCreateSpec.builder().image(att.getUrl()).build());
                    }
                } else {
                    // This is a file
                    otherEmbeds.add(EmbedCreateSpec.builder().title(att.getFilename()).url(att.getUrl()).build());
                }
            }

            m.addEmbed(embed.build());
            m.addAllEmbeds(otherEmbeds);
        }

        // Clone embeds from source message
        for (Embed sourceEmbed : msg.getEmbeds()) {
            m.addEmbed(cloneEmbed(sourceEmbed));
        }

        // Perform creation
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
                    .doOnError(err -> {
                        LOGGER.warn("Couldn't add migrated emote on: "
                                + dstChan.getName() + "/#" + msg.getId().asString(), err);
                        fut.complete(createdMessage);
                    })
                    .subscribe(),
                fut::completeExceptionally);

        // Return future
        return Mono.fromFuture(fut);
    }

    public EmbedCreateSpec cloneEmbed(Embed sourceEmbed) {
        EmbedCreateSpec.Builder newEmbed = EmbedCreateSpec.builder();
        sourceEmbed.getAuthor().ifPresent(embedAuthor -> newEmbed.author(
                embedAuthor.getName().orElseThrow(() -> new IllegalStateException("Embed author has no name")),
                embedAuthor.getUrl().orElse(null),
                embedAuthor.getIconUrl().orElse(null)));
        sourceEmbed.getTitle().ifPresent(newEmbed::title);
        sourceEmbed.getUrl().ifPresent(newEmbed::url);
        sourceEmbed.getColor().ifPresent(newEmbed::color);
        sourceEmbed.getThumbnail().ifPresent(embedThumbnail -> newEmbed.thumbnail(embedThumbnail.getUrl()));
        sourceEmbed.getDescription().ifPresent(newEmbed::description);
        sourceEmbed.getImage().ifPresent(embedImage -> newEmbed.image(embedImage.getUrl()));
        sourceEmbed.getFields()
                .stream()
                .map(f -> EmbedCreateFields.Field.of(f.getName(), f.getValue(), f.isInline()))
                .forEach(newEmbed::addField);
        sourceEmbed.getTimestamp().ifPresent(newEmbed::timestamp);
        sourceEmbed.getFooter().ifPresent(embedFooter -> newEmbed.footer(
                embedFooter.getText(),
                embedFooter.getIconUrl().orElse(null)));
        return newEmbed.build();

    }

    // ====== Internal classes

    public static class HelpRequestedException extends RuntimeException {}

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

    // ====== Command line interface

    private void parseMigrateArguments(String... args) {
        if (args.length != 2) {
            throw new IllegalArgumentException("Wrong arguments (expected: srcGuild, destGuild)");
        }

        long srcGuild;
        long destGuild;
        try {
            srcGuild = Long.parseLong(args[0]);
            destGuild = Long.parseLong(args[1]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid guild IDs");
        }

        Snowflake sourceGuild = Snowflake.of(srcGuild);
        this.srcGuild = client.getGuildById(sourceGuild)
                .onErrorResume((err) -> Mono.empty())
                .blockOptional()
                .orElseThrow(() -> new IllegalArgumentException("Invalid id for source guild: "+srcGuild));
        LOGGER.info("Loaded source guild: "+this.srcGuild.getName()+" ("+srcGuild+")");

        Snowflake destinationGuild = Snowflake.of(destGuild);
        this.destGuild = client.getGuildById(destinationGuild)
                .onErrorResume((err) -> Mono.empty())
                .blockOptional()
                .orElseThrow(() -> new IllegalArgumentException("Invalid id for dest guild: "+destGuild));
        LOGGER.info("Loaded dest guild: "+this.destGuild.getName()+" ("+destGuild+")");

        this.action = this::migrate;
    }

    private void parseCleanArguments(String... args) {
        if (args.length != 1) {
            throw new IllegalArgumentException("Wrong arguments (expected: srcGuild)");
        }

        long srcGuild;
        try {
            srcGuild = Long.parseLong(args[0]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid guild IDs");
        }

        Snowflake sourceGuild = Snowflake.of(srcGuild);
        this.srcGuild = client.getGuildById(sourceGuild)
                .onErrorResume((err) -> Mono.empty())
                .blockOptional()
                .orElseThrow(() -> new IllegalArgumentException("Invalid id for source guild: "+srcGuild));
        LOGGER.info("Loaded source guild: "+this.srcGuild.getName()+" ("+srcGuild+")");

        this.action = () -> cleanMigratedEmotes().blockLast();
    }

    public void parseArguments(String action, String... args) {
        if (action.equals("migrate")) this.parseMigrateArguments(args);
        else if (action.equals("clean")) this.parseCleanArguments(args);
        else throw new IllegalArgumentException("Unknown action: " + action);
    }

    private void parseOption(String opt, Iterator<String> params) {
        LOGGER.debug("Got option: "+opt);
        if ("h".equals(opt) || "?".equals(opt) || "--help".equals(opt)) {
            throw new HelpRequestedException();
        }

        if ("c".equals(opt) || "--category".equals(opt)) {
            String value;
            try {
                value = params.next();
            } catch (NoSuchElementException ex) {
                throw new IllegalArgumentException("Missing category ID");
            }
            try {
                this.addCategory(Snowflake.of(Long.parseLong(value)));
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("Invalid category ID: "+value, ex);
            }
        } else if ("s".equals(opt) || "--skip".equals(opt)) {
            String value;
            try {
                value = params.next();
            } catch (NoSuchElementException ex) {
                throw new IllegalArgumentException("Missing skip channel ID");
            }
            try {
                this.skipChannel(Snowflake.of(Long.parseLong(value)));
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("Invalid skip channel ID: "+value, ex);
            }
        } else if ("a".equals(opt) || "--after".equals(opt)) {
            String value;
            try {
                value = params.next();
            } catch (NoSuchElementException ex) {
                throw new IllegalArgumentException("Missing DATE value for option "+opt);
            }
            try {
                this.afterDate(Instant.parse(value));
            } catch (DateTimeParseException ex) {
                throw new IllegalArgumentException("Invalid DATE format: "+value, ex);
            }
        } else if ("d".equals(opt) || "--delay".equals(opt)) {
            String value;
            try {
                value = params.next();
            } catch (NoSuchElementException ex) {
                throw new IllegalArgumentException("Missing DELAY value");
            }
            try {
                this.widthDelay(Integer.parseUnsignedInt(value));
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("Invalid DELAY: "+value, ex);
            }
        } else if ("--no-reupload".equals(opt)) {
            reUploadFiles = false;
        } else if ("v".equals(opt) || "--verbose".equals(opt)) {
            if (this.verbosity == 0) {
                ((ch.qos.logback.classic.Logger)LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).setLevel(Level.DEBUG);
                LOGGER.debug("Debug logging enabled!");
            }
            this.verbosity++;
        } else if ("q".equals(opt) || "--quiet".equals(opt)) {
            ((ch.qos.logback.classic.Logger)LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).setLevel(Level.ERROR);
        } else {
            throw new IllegalArgumentException("Unsupported option: "+opt);
        }
    }

    public String handleCliCall(String... rawArgs) {
        List<String> positionalArgs = new ArrayList<>();
        Iterator<String> iterArgs =  Arrays.asList(rawArgs).iterator();
        try {
            // If we have nothing to do
            if (rawArgs.length == 0) {
                throw new HelpRequestedException();
            }

            // Parse options
            while (iterArgs.hasNext()) {
                String arg = iterArgs.next();
                if (arg.startsWith("--")) {
                    parseOption(arg, iterArgs);
                } else if (arg.startsWith("-")) {
                    String[] shortArgs = arg.substring(1).split("");
                    for (int i = 0; i < shortArgs.length; i++) {
                        boolean last = (i + 1 == shortArgs.length);
                        parseOption(shortArgs[i], last ? iterArgs : Collections.emptyIterator());
                    }
                } else {
                    positionalArgs.add(arg);
                }
            }

            // Get selected action
            if (positionalArgs.size() == 0) {
                throw new IllegalArgumentException("Missing action");
            }
            String action = positionalArgs.remove(0);
            if ("help".equals(action) || "?".equals(action)) {
                throw new HelpRequestedException();
            }

            // Get token from env and init client
            String token = System.getenv("DISCORD_TOKEN");
            if (token == null || token.isEmpty()) {
                throw new IllegalStateException("Missing DISCORD_TOKEN");
            }
            this.init(token);

            // Parse action args
            this.parseArguments(action, positionalArgs.toArray(new String[0]));
            return action;
        } catch (HelpRequestedException ignored) {
            DiscordTransfer.printHelp();
            return "help";
        }
    }

    public static void printHelp() {
        System.out.print("discord-transfer");
        System.out.println(" v"+DiscordTransfer.VERSION);
        System.out.print("  by BilliAlpha (billi.pamege.300@gmail.com) ");
        System.out.println("-- https://github.com/BilliAlpha/discord-transfer");
        System.out.println();
        System.out.println("A discord bot for copying messages between guilds");
        System.out.println();
        System.out.println("Usage: <action> [arguments..] [options...]");
        System.out.println();
        System.out.println("Actions:");
        System.out.println("  help - Show this message and exit");
        System.out.println();
        System.out.println("  migrate - Migrate messages from source guild to dest guild");
        System.out.println("  Arguments:");
        System.out.println("    <srcGuild>: The Discord ID of the source guild");
        System.out.println("    <destGuild>: The Discord ID of the destination guild");
        System.out.println();
        System.out.println("  clean - Delete migration reactions");
        System.out.println("  Arguments:");
        System.out.println("    <guild>: The Discord ID of the guild to clean");
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
        System.out.println("    --no-reupload");
        System.out.println("      By default all attachments will be downloaded and re-uploaded to prevent");
        System.out.println("      losing them if the original message is deleted. With this option you can");
        System.out.println("      disable re-upload and only link to the files from the original message.");
        System.out.println();
        System.out.println("    -v, --verbose");
        System.out.println("      Can be specified multiple times, increases verbosity.");
        System.out.println();
        System.out.println("    -q, --quiet");
        System.out.println("      Be as silent as possible.");
        System.out.println();
        System.out.println("  Environment variables:");
        System.out.println("    DISCORD_TOKEN: Required, the Discord bot token");
    }

    // =================================================================================================================

    public static void main(String[] args) {
        String action;
        DiscordTransfer app = new DiscordTransfer();
        try {
            action = app.handleCliCall(args);
        } catch (Exception ex) {
            System.err.println(ex.getMessage());
            if (ex.getCause() != null) System.out.println("Caused by: "+ex.getCause().getMessage());
            System.out.println("See 'help' action for help");
            return;
        }

        LOGGER.debug("Action: " + action);
        if ("help".equals(action)) return;

        app.run();
    }
}
