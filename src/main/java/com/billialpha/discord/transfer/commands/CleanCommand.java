package com.billialpha.discord.transfer.commands;

import com.billialpha.discord.transfer.Command;
import com.billialpha.discord.transfer.Parameters;
import discord4j.common.util.Snowflake;
import discord4j.core.object.entity.Guild;
import discord4j.core.object.entity.Message;
import discord4j.core.object.entity.User;
import discord4j.core.object.entity.channel.Category;
import discord4j.core.object.entity.channel.TextChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

public class CleanCommand extends Command {
    private static final Logger LOGGER = LoggerFactory.getLogger(CleanCommand.class);

    public static final Description DESCRIPTION = new Description(
            "clean",
            "Clean reaction on migrated messages",
            true,
            Parameters.create()
                    .withArgument("server",
                            "The server to clean reactions on", Snowflake::of)
                    .withArrayOption("category", "c",
                            "Limit the migration to specific categories", Snowflake::of)
                    .withArrayOption("skip-channel", "s",
                            "Ignore this channel during migration", Snowflake::of)
                    .withOption("after", "a",
                            "Only migrate messages after the given date", Instant::parse)
                    .withOption("delay", "d",
                            "Pause between each message posted", Integer::parseUnsignedInt, 0)
                    .build(),
            CleanCommand::new
    );

    private final Guild server;
    private final Set<Snowflake> skipChannels;
    private final Set<Snowflake> categories;
    private final Instant afterDate;
    private final int delay;

    public CleanCommand(Invocation params) {
        this.server = params.client.getGuildById(params.get("server")).block();
        this.skipChannels = new HashSet<>(params.getList("skip-channel"));
        this.categories = new HashSet<>(params.getList("category"));
        this.afterDate = params.get("after");
        this.delay = params.get("delay");
    }

    @Override
    public void execute() {
        cleanMigratedEmotes().blockLast();
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
                    if (author.isEmpty()) return;
                    LOGGER.info("Cleaning reaction ("+m.getChannelId().asString()+"/"+m.getId().asString()+"): "+
                            author.get().getUsername()+" at "+m.getTimestamp());
                })
                .flatMap(m -> m.removeSelfReaction(MigrateCommand.MIGRATED_EMOJI));
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
                    .flatMap(server::getChannelById)
                    .ofType(Category.class);
        }
        return server.getChannels().ofType(Category.class);
    }
}
