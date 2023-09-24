package com.billialpha.discord.transfer;

import ch.qos.logback.classic.Level;
import com.billialpha.discord.transfer.commands.CleanCommand;
import com.billialpha.discord.transfer.commands.HelpCommand;
import com.billialpha.discord.transfer.commands.MigrateCommand;
import discord4j.core.DiscordClient;
import discord4j.core.GatewayDiscordClient;
import discord4j.core.object.entity.User;
import discord4j.gateway.intent.Intent;
import discord4j.gateway.intent.IntentSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * @author BilliAlpha <billi.pamege.300@gmail.com>
 */
public class DiscordTransfer {
    public static final String VERSION = "3.0.2";
    private static final Logger LOGGER = LoggerFactory.getLogger(DiscordTransfer.class);
    public static final Map<String, Command.Description> ACTIONS = new HashMap<>();
    static {
        ACTIONS.put("help", HelpCommand.DESCRIPTION);
        ACTIONS.put("migrate", MigrateCommand.DESCRIPTION);
        ACTIONS.put("clean", CleanCommand.DESCRIPTION);
    }

    public static final Parameters.Parameter<?>[] GLOBAL_OPTIONS = Parameters.create()
            .withFlag("verbose", "v", "Enable debug logs")
            .withFlag("quiet", "q", "Be as silent as possible")
            .withFlag("help", "?", "Get help on the current action")
            .buildParams();

    public static GatewayDiscordClient initClient(String token) {
        DiscordClient discord = DiscordClient.create(token);

        LOGGER.debug("Logging in ...");
        GatewayDiscordClient client = Objects.requireNonNull(discord.gateway()
                .setEnabledIntents(IntentSet.of(
                        Intent.MESSAGE_CONTENT,
                        Intent.GUILD_MESSAGES,
                        Intent.GUILD_MESSAGE_REACTIONS))
                .login().block(), "Invalid bot token");

        User self = Objects.requireNonNull(client.getSelf().block());
        LOGGER.info("Logged in, user: "+self.getUsername());

        return client;
    }

    public static void main(String[] args) {
        Command.Invocation params = null;
        Command.Description command;
        try {
            // Parse call
            Call call = Call.parse(args);

            // Parse global options
            params = new Parameters(GLOBAL_OPTIONS).parse(true, call.arguments());

            // Update logging level
            if (params.hasFlag("quiet")) {
                ((ch.qos.logback.classic.Logger)LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).setLevel(Level.ERROR);
            } else if (params.hasFlag("verbose")) {
                ((ch.qos.logback.classic.Logger)LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).setLevel(Level.DEBUG);
                LOGGER.debug("Debug logging enabled!");
            }

            // Find selected action
            command = ACTIONS.get(call.action());
            if (command == null) throw new Exception("Unknown action: "+ call.action());

            // Print help and exit if requested
            if (params.hasFlag("help")) {
                HelpCommand.printCommandHelp(command);
                return;
            }

            // Parse parameters
            params = command.params().extend(GLOBAL_OPTIONS).parse(call.arguments());
            if (params.<Integer>get("verbose") >= 3) {
                for (var v : params.params.values()) {
                    LOGGER.debug("Got param "+v.name()+": "+v.value());
                }
            }

            // Build client if needed
            if (command.needsClient()) {
                String token = System.getenv("DISCORD_TOKEN");
                if (token == null || token.isEmpty()) throw new Exception("Missing DISCORD_TOKEN !");
                params = params.withClient(initClient(token));
            }
        } catch (Exception ex) {
            System.err.println(ex.getMessage());
            System.out.println("See 'help' action for help");
            if (params != null && params.hasFlag("verbose")) ex.printStackTrace();
            System.exit(1);
            return;
        }

        int exitCode = 0;
        try {
            // Build and execute command
            command.build(params).execute();
        } catch (Throwable ex) {
            System.err.println("ERROR: "+ex.getMessage());
            if (ex.getCause() != null) System.out.println("Caused by: "+ex.getCause().getMessage());
            if (params.hasFlag("verbose")) {
                ex.printStackTrace();
            } else {
                System.out.println("Run with verbose flag to get stacktrace.");
            }
            exitCode = 1;
        }

        // Logout if connected
        if (params.client != null) params.client.logout().block();

        System.exit(exitCode);
    }

    public record Call (
            String action,
            List<String> args
    ) {
        public static Call parse(String... args) {
            // If we have nothing to do
            if (args.length == 0) {
                return new Call("help", Collections.emptyList());
            }

            List<String> argList = new ArrayList<>(Arrays.asList(args));
            Iterator<String> iterArgs = argList.iterator();
            String action = null;

            while (iterArgs.hasNext()) {
                String arg = iterArgs.next();
                if (action != null) continue;
                if (arg.startsWith("-")) continue;
                action = arg;
                iterArgs.remove();
            }

            if (action == null) {
                throw new IllegalArgumentException("Missing action");
            }

            // Get selected action
            return new Call(action, argList);
        }

        public String[] arguments() {
            return args.toArray(new String[0]);
        }
    }
}
