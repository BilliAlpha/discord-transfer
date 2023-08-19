package com.billialpha.discord.transfer.commands;

import com.billialpha.discord.transfer.Command;
import com.billialpha.discord.transfer.DiscordTransfer;
import com.billialpha.discord.transfer.Parameters;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public class HelpCommand extends Command {
    public static final Description DESCRIPTION = new Description(
            "help",
            "Shows an help message",
            false,
            Parameters.create()
                    .withOptionalArgument("command", "The command to get help on", Function.identity())
                    .build(),
            HelpCommand::new
    );

    private final String command;

    public HelpCommand(Invocation params) {
        command = params.get("command");
    }


    public void execute() {
        if (command == null) printGeneralHelp();
        else {
            Command.Description cmd = DiscordTransfer.ACTIONS.get(command);
            if (cmd == null) {
                System.err.println("Unknown command: "+command);
                System.out.println("Available commands:");
                for (String cmdName : DiscordTransfer.ACTIONS.keySet()) {
                    System.out.println("  - "+cmdName);
                }
                return;
            }
            printCommandHelp(cmd);
        }
    }

    public static void printGeneralHelp() {
        System.out.print("discord-transfer");
        System.out.println(" v"+ DiscordTransfer.VERSION);
        System.out.print("  by BilliAlpha (billi.pamege.300@gmail.com) ");
        System.out.println("-- https://github.com/BilliAlpha/discord-transfer");
        System.out.println();
        System.out.println("A discord bot for copying messages between guilds");
        System.out.println();
        System.out.println("Usage: <action> [arguments..] [options...]");
        System.out.println();

        System.out.println("Actions:");
        for (Map.Entry<String, Description> entry : DiscordTransfer.ACTIONS.entrySet()) {
            Command.Description cmd = entry.getValue();
            getCommandHelp(cmd, true).stream().map(s -> "  "+s).forEach(System.out::println);
            System.out.println();
        }

        System.out.println("Global options:");
        getGlobalOptions().forEach(System.out::println);

        System.out.println("Environment variables:");
        System.out.println("  DISCORD_TOKEN: Required, the Discord bot token");
    }

    public static void printCommandHelp(Command.Description cmd) {
        getCommandHelp(cmd, false).forEach(System.out::println);
        getGlobalOptions().forEach(System.out::println);
    }

    public static List<String> getGlobalOptions() {
        List<String> res = new ArrayList<>();
        for (Parameters.Parameter<?> opt : DiscordTransfer.GLOBAL_OPTIONS) {
            String line = "  ";
            if (opt.shortName != null) line += "-"+opt.shortName+", ";
            line += "--"+opt.name;
            if (opt instanceof Parameters.ValuedParameter) line += " <value>";
            res.add(line);
            res.add("    "+opt.desc);
            res.add("");
        }
        return res;
    }

    public static List<String> getCommandHelp(Command.Description cmd, boolean compact) {
        List<String> res = new ArrayList<>();

        String[] desc = cmd.desc().split("\n", 1);
        res.add(cmd.name()+" - "+desc[0]);
        if (!compact && desc.length > 1) {
            res.addAll(Arrays.stream(desc[1].split("\n")).map(s -> "    "+s).toList());
        }
        if (!compact) res.add("");

        Parameters.Argument<?>[] args = cmd.params().getArguments();
        if (args.length > 0) {
            res.add("Arguments:");
            for (Parameters.Argument<?> arg : args) {
                String line = "  <"+arg.name+">";
                if (!arg.required) line += " (optional)";
                if (compact) {
                    line += ": "+arg.desc;
                    res.add(line);
                } else {
                    res.add(line);
                    res.add("    "+arg.desc);
                    res.add("");
                }
            }
        }

        if (!compact) {
            Parameters.Parameter<?>[] opts = cmd.params().getFlagAndOptions();
            if (opts.length > 0) {
                res.add("Options:");
                for (Parameters.Parameter<?> opt : opts) {
                    String line = "  ";
                    if (opt.shortName != null) line += "-"+opt.shortName+", ";
                    line += "--"+opt.name;
                    if (opt instanceof Parameters.Option) line += " <value>";
                    if (opt.required) line += " (required)";
                    res.add(line);
                    res.add("    "+opt.desc);
                    if (opt instanceof Parameters.ValuedParameter && opt.defaultValue != null) {
                        if (opt.defaultValue instanceof List<?> defaults) {
                            if (defaults.size() == 1) res.add("    default: "+defaults.get(0));
                            else if (defaults.size() > 1) res.add("    default: "+defaults);
                        }
                    }
                    res.add("");
                }
            }
        }

        return res;
    }
}
