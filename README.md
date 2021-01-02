# discord-transfer #
_A discord bot for copying messages between guilds_

## How to use ? ##

### Pre-requisites ###

#### Maven dependencies ####

Download Maven dependencies

#### Discord bot token ####

1. Create an application on Discord's developper portal: https://discord.com/developers/applications/
2. Create a bot for this application
3. Copy the bot token and pass it to the application via the `DISCORD_TOKEN` environnement variable

#### Invite the bot in guilds ####

In order for the bot to migrate messages it needs to be invited in both the source and destination guild, to do so you can use the following URL by replacing in your application ID (which can be found on the Discord developper portal)

`https://discord.com/oauth2/authorize?client_id=<CLIENT-ID>&scope=bot`

#### Customize behavior (Optionnal) ####

By default all messages will be migrated, You can customize the bot behavior by editing the [main function](https://github.com/BilliAlpha/discord-transfer/blob/main/src/main/java/com/billialpha/discord/transfer/DiscordTransfer.java#L281-L283).

To do so it is recommended to import the project in an IDE (IntelliJ IDEA, VS Code, Eclipse).

### Launching ###

The bot takes three arguments.

1. The action to perform, you probably want to use `migrate` to transfer messages, but there is also the `clean` action that removes the reactions used to mark migrated messages
2. The Discord ID (Snowflake) of the source Guild (the one you want to copy messages from)
3. The Discord ID of the destination Guild (the one in which messages will be copied)

Example: `java -cp [...] com.billialpha.discord.transfer.DiscordTransfer migrate 123456789 987654321`

## About ##

This bot uses [Discord4J](https://github.com/Discord4J/Discord4J) as a bot library.
