package com.billialpha.discord.transfer.commands;

import com.billialpha.discord.transfer.Command;
import com.billialpha.discord.transfer.DiscordTransfer;
import com.billialpha.discord.transfer.Parameters;
import discord4j.common.util.Snowflake;
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
import discord4j.core.spec.EmbedCreateFields;
import discord4j.core.spec.EmbedCreateSpec;
import discord4j.core.spec.MessageCreateSpec;
import discord4j.core.spec.TextChannelCreateSpec;
import discord4j.core.spec.VoiceChannelCreateSpec;
import discord4j.discordjson.json.UserData;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public class MigrateCommand extends Command {
    private static final Logger LOGGER = LoggerFactory.getLogger(MigrateCommand.class);

    public static final ReactionEmoji MIGRATED_EMOJI = ReactionEmoji.unicode("\uD83D\uDD04");
    public static final Description DESCRIPTION = new Description(
            "migrate",
            "Migrates messages from one server to another",
            true,
            Parameters.create()
                    .withArgument("source",
                            "The server to copy messages from", Snowflake::of)
                    .withArgument("destination",
                            "The server to copy messages to", Snowflake::of)
                    .withArrayOption("category", "c",
                            "Limit the migration to specific categories", Snowflake::of)
                    .withArrayOption("skip-channel", "s",
                            "Ignore this channel during migration", Snowflake::of)
                    .withArrayOption("include-channel", "i",
                            "Include this channel during migration", Snowflake::of)
                    .withOption("after", "a",
                            "Only migrate messages after the given date", Instant::parse)
                    .withOption("delay", "d",
                            "Pause between each message posted", Integer::parseUnsignedInt, 0)
                    .withFlag("text-only", null,"Only migrate text channels")
                    .withFlag("no-bot", null,"Do not copy bot messages")
                    .withFlag("no-reupload", null, "Do not re-upload attachments")
                    .build(),
            MigrateCommand::new
    );

    private final GatewayDiscordClient client;
    private final Guild srcGuild;
    private final Guild destGuild;
    private final Set<Snowflake> skipChannels;
    private final Set<Snowflake> includeChannels;
    private final Set<Snowflake> categories;
    private final Instant afterDate;
    private final int delay;
    private final boolean reUploadFiles;
    private final boolean noBotMessages;
    private final boolean textOnly;
    private final int verbosity;
    private final Scheduler scheduler;

    public MigrateCommand(Invocation params) {
        this.client = params.client;
        this.skipChannels = new HashSet<>(params.getList("skip-channel"));
        this.includeChannels = new HashSet<>(params.getList("include-channel"));
        this.categories = new HashSet<>(params.getList("category"));
        this.afterDate = params.get("after");
        this.delay = params.get("delay");
        this.reUploadFiles = !params.hasFlag("no-reupload");
        this.verbosity = params.get("verbose");
        this.scheduler = Schedulers.parallel();
        this.noBotMessages = params.hasFlag("no-bot");
        this.textOnly = params.hasFlag("text-only");

        Snowflake srcGuildId = params.get("source");
        try {
            this.srcGuild = params.client.getGuildById(srcGuildId).blockOptional().orElseThrow();
            LOGGER.info("Loaded source guild: "+srcGuild.getName()+" ("+srcGuildId.asString()+")");
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid source guild: "+srcGuildId.asString(), ex);
        }

        Snowflake dstGuildId = params.get("destination");
        try {
            this.destGuild = params.client.getGuildById(dstGuildId).blockOptional().orElseThrow();
            LOGGER.info("Loaded destination guild: "+destGuild.getName()+" ("+dstGuildId.asString()+")");
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid destination guild: "+dstGuildId.asString(), ex);
        }
    }

    @Override
    public void execute() {
        LOGGER.info("Starting migration ...");

        if (!textOnly) {
            LOGGER.info("Creating categories and voice channels in destination guild");
            long migratedVoiceChans = getSelectedCategories()
                    .parallel()
                    .runOn(scheduler)
                    .flatMap(this::migrateCategory)
                    .reduce(Long::sum)
                    .blockOptional()
                    .orElse(0L);
            if (migratedVoiceChans > 0) {
                LOGGER.info("Successfully created "+migratedVoiceChans+" voice channels");
            } else {
                LOGGER.info("No voice channel created");
            }
        }

        LOGGER.info("Migrating text channels");
        long migratedMessages = getSelectedTextChannels()
                .parallel()
                .runOn(scheduler)
                .flatMap(c -> this.migrateTextChannel(c).onErrorContinue((err, x) ->
                        LOGGER.warn("Error in text channel migration (" + c.getName() + "):", err)))
                .map(TextChannelMigrationResult::messageCount)
                .reduce(Long::sum)
                .blockOptional()
                .orElse(0L);
        if (migratedMessages > 0) {
            LOGGER.info("Successfully migrated "+migratedMessages+" messages");
        } else {
            LOGGER.info("No message migrated");
        }

        client.logout().block();
        LOGGER.debug("Logged out");
    }

    private Mono<Long> migrateCategory(@NonNull Category srcCat) {
        LOGGER.info("Migrating category: "+srcCat.getName()+" ("+srcCat.getId().asString()+")");
        return destGuild.getChannels().ofType(Category.class)
                .filter(c -> c.getName().equals(srcCat.getName()))
                .singleOrEmpty()
                .switchIfEmpty(destGuild.createCategory(srcCat.getName()))
                .flatMapMany(dstCat -> migrateCategoryVoiceChannels(srcCat, dstCat))
                .reduce(Long::sum);
    }

    private Mono<Long> migrateCategoryVoiceChannels(@NonNull Category srcCat, @NonNull Category dstCat) {
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
                .count();
    }

    private Flux<TextChannelMigrationResult> migrateTextChannel(@NonNull TextChannel srcChan) {
        return srcChan.getCategory()
                .flatMapMany(srcCat ->
                    // Find destination category
                    destGuild.getChannels()
                            .ofType(Category.class)
                            // Filter on name
                            .filter(cat -> srcCat.getName().equals(cat.getName()))
                            // Create category if it doesn't exist
                            .switchIfEmpty(destGuild.createCategory(srcCat.getName()))
                            .flatMap(dstCat ->
                                // Find destination channel
                                dstCat.getChannels()
                                        .ofType(TextChannel.class)
                                        // Filter on name
                                        .filter(c -> c.getName().equals(srcChan.getName()))
                                        .singleOrEmpty()
                                        // Create channel if it doesn't exist
                                        .switchIfEmpty(destGuild.createTextChannel(TextChannelCreateSpec.builder()
                                                .name(srcChan.getName())
                                                .topic(srcChan.getTopic().map(Possible::of).orElse(Possible.absent()))
                                                .nsfw(srcChan.isNsfw())
                                                .parentId(dstCat.getId())
                                                .position(srcChan.getRawPosition())
                                                .build()))
                            )
                )
                // Migrate channel messages
                .flatMap(dstChan -> migrateTextChannelMessages(srcChan, dstChan));
    }
    private Mono<TextChannelMigrationResult> migrateTextChannelMessages(
            @NonNull TextChannel srcChan, @NonNull TextChannel dstChan
    ) {
        LOGGER.info("Migrating channel: "+srcChan.getName()+" ("+srcChan.getId().asString()+")");
        Snowflake startDate = getChannelStartDate(srcChan.getId());
        LOGGER.debug("Channel date: "+startDate.getTimestamp());
        Flux<Message> flux = srcChan.getMessagesAfter(startDate);
        if (delay > 0) flux = flux.delayElements(Duration.ofMillis(delay)); // Delay to reduce rate-limiting
        return flux.filter(m -> m.getReactions().stream() // Filter on non migrated messages
                        .filter(Reaction::selfReacted)
                        .noneMatch(r -> r.getEmoji().equals(MIGRATED_EMOJI)))
                .flatMap(m -> migrateMessage(m, dstChan)) // Perform migration
                .count()
                .map(count -> new TextChannelMigrationResult(srcChan, dstChan, count));
    }

    private Mono<Message> migrateMessage(@NonNull Message msg, @NonNull TextChannel dstChan) {
        String logId = msg.getChannelId().asString()+"/"+msg.getId().asString();
        if (msg.getType() != Message.Type.DEFAULT && msg.getType() != Message.Type.REPLY) {
            LOGGER.info("Skipping message ("+logId+"), unknown type: "+msg.getType().name());
            return Mono.empty(); // Not a user message, do not migrate
        }


        User author = msg.getAuthor().orElse(null);
        if (author == null) {
            UserData authorData = msg.getUserData();
            if (authorData.system().toOptional().orElse(false)) {
                return Mono.empty(); // System message, do not migrate
            }
            if (this.noBotMessages && authorData.bot().toOptional().orElse(false)) {
                LOGGER.debug("Skipping message ("+logId+"), not migrating bot messages");
                return Mono.empty(); // Bot message, do not migrate
            }
            author = new User(client, authorData);
        }

        CompletableFuture<Message> fut = new CompletableFuture<>();
        LOGGER.info("Migrating message ("+logId+"): "+author.getUsername()+" at "+msg.getTimestamp());
        MessageCreateSpec.Builder m = MessageCreateSpec.builder();
        if (verbosity >= 2) {
            LOGGER.debug("Raw message:\n\t" + msg.getContent().replaceAll("\n", "\n\t"));
        }

        // Add message info to embed
        String message = msg.getContent().replaceAll("<@&\\d+>", ""); // Remove role mentions
        EmbedCreateSpec.Builder embed = EmbedCreateSpec.builder()
                .author(author.getUsername(), null, author.getAvatarUrl())
                .timestamp(msg.getEditedTimestamp().orElse(msg.getTimestamp()))
                .description(message);

        if (reUploadFiles) {
            // Download and re-upload files
            m.addEmbed(embed.build()); // Send message embed now because we won't need it later
            for (Attachment att : msg.getAttachments()) {
                try {
                    HttpURLConnection conn = (HttpURLConnection) new URL(att.getUrl()).openConnection();
                    conn.setRequestProperty("User-Agent", "DiscordTransfer (v"+DiscordTransfer.VERSION+")");
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
                        LOGGER.warn("Attachment HTTP error:\n\t"+buffer.toString(StandardCharsets.UTF_8)
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
                .onErrorResume(err -> {
                    LOGGER.warn("Error in message migration", err);
                    return Mono.empty();
                })
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

    private Snowflake getChannelStartDate(Snowflake chanId) {
        if (this.afterDate == null)
            return chanId;
        return this.afterDate.isAfter(chanId.getTimestamp())
                ? Snowflake.of(this.afterDate)
                : chanId;
    }

    /**
     * The list of text channels to migrate.
     * <p>
     *     This list includes all text channels from the selected categories
     *     (see {@link MigrateCommand#getSelectedCategories()} excluding skipped channels
     *     to which explicitly included channels are added.
     * </p>
     * @return A flux of selected text channels in the source guild.
     */
    private Flux<TextChannel> getSelectedTextChannels() {
        return Flux.concat(
                getSelectedCategories()
                        .flatMap(Category::getChannels)
                        .ofType(TextChannel.class),
                Mono.justOrEmpty(includeChannels)
                        .flatMapMany(Flux::fromIterable)
                        .flatMap(srcGuild::getChannelById)
                        .ofType(TextChannel.class)
        ).filter(c -> !skipChannels.contains(c.getId()));
    }

    /**
     * The list of categories to migrate.
     * <p>
     *     If at least one category was specified in parameter then only return
     *     explicitly selected categories, otherwise, and if no explicit channel are included,
     *     return all categories present in the source guild.
     * </p>
     * @return A flux of selected categories in the source guild.
     */
    private Flux<Category> getSelectedCategories() {
        return Mono.justOrEmpty(categories)
                .flatMapMany(Flux::fromIterable)
                .flatMap(srcGuild::getChannelById)
                .ofType(Category.class)
                // If no catagory was selected
                .switchIfEmpty(includeChannels != null && includeChannels.size() > 0
                        // But there are included channels, no categories
                        ? Flux.empty()
                        // Otherwise, return all existing categories
                        : srcGuild.getChannels().ofType(Category.class));
    }

    public record TextChannelMigrationResult(TextChannel sourceChan, TextChannel destChan, long messageCount) {}

}
