# discord-transfer #
_A discord bot for copying messages between guilds_

[![build](https://github.com/BilliAlpha/discord-transfer/actions/workflows/maven.yml/badge.svg)](https://github.com/BilliAlpha/discord-transfer/actions/workflows/maven.yml)

**Current version: [v3.1.0](https://github.com/BilliAlpha/discord-transfer/releases/latest)**

## How to use ? ##

Download the JAR from the releases page.

### Pre-requisites ###

#### Discord bot token ####

1. Create an application on Discord's developer portal: https://discord.com/developers/applications/
2. Create a bot for this application
3. Enable the **`MESSAGE CONTENT`** privileged gateway intent.
4. Copy the bot token and pass it to the application via the `DISCORD_TOKEN` environment variable

#### Invite the bot in guilds ####

In order for the bot to migrate messages it needs to be invited in both the source and destination guild,
to do so you can use the following URL by replacing in your application ID (which can be found on the Discord developer portal)

`https://discord.com/oauth2/authorize?client_id=<CLIENT-ID>&scope=bot`

### Launching ###

Pass the Discord bot token via environment variables.

- On **linux** and **macOS** open a terminal (bash) and simply prefix your command with the token as such:
```bash
DISCORD_TOKEN="MY_TOKEN_HERE" java -jar discord-transfer.jar [arguments...]
```

- On **Windows** you can use `powershell`. Define your environment variable and then run the program:
```powershell
$env:DISCORD_TOKEN="MY_BOT_TOKEN_HERE"
java -jar discord-transfer.jar [arguments...]
```

The bot behaviour depends on the `action` you select.

`java -jar discord-transfer.jar <action> [options...] [arguments...]`

In most cases you'll probably want to use the `migrate` action to transfer messages,
but there is also the `clean` action that removes the reactions used to mark migrated messages

#### `migrate` action ####

`java -jar discord-transfer.jar migrate [options...] <source> <destination>`

The migrate action takes 2 arguments:
1. The Discord ID (Snowflake) of the source Guild (the one you want to copy messages from)
2. The Discord ID of the destination Guild (the one in which messages will be copied)

There are also options to customize the migration behavior:
- `--include-channel`: Specify channels that should be migrated, it's category will automatically be created if missing, expects a Discord channel ID
- `--category`: Specify specific channel categories to migrate, expects a Discord category ID
- `--skip-channel`: Specify channels that should not be migrated, expects a Discord channel ID
- `--text-only`: Will only migrate text channels (skips voice channel creation)
- `--after`: Only migrate messages after the give date (format ISO-8601, ex: `1997−07−16T19:20:30,451Z`)
- `--delay`: Add a delay between each message migration

Example: `java -jar discord-transfer.jar migrate 123456789 987654321 --skip-channel 741852963`

#### `clean` action ####

`java -jar discord-transfer.jar clean [options...] <server>`

The clean action takes a single argument, the Discord ID (Snowflake) of the Guild you want to clean reactions from.

### More info ? ###

If this README does not provide the information you are looking for, try running the `help` action.

## About ##

This bot uses [Discord4J](https://github.com/Discord4J/Discord4J) as a bot library.
