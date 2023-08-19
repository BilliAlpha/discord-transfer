package com.billialpha.discord.transfer;

import discord4j.core.GatewayDiscordClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Function;

public abstract class Command {

    public abstract void execute();

    public static class Invocation {
        public final Map<String, Parameters.ParamValue<?>> params;
        public final GatewayDiscordClient client;

        public Invocation(GatewayDiscordClient client, Parameters.ParamValue<?>... params) {
            this.client = client;
            this.params = new HashMap<>();
            for (Parameters.ParamValue<?> v : params) {
                this.params.put(v.name(), v);
            }
        }

        public <T> T get(String key) {
            return get(key, null);
        }

        @SuppressWarnings("unchecked")
        public <T> T get(String key, T def) {
            Parameters.ParamValue<T> p = (Parameters.ParamValue<T>) params.get(key);
            if (p == null) throw new NoSuchElementException("No parameter with this name: "+key);
            if (p.param() instanceof Parameters.ValuedParameter) {
                List<T> list = getList(key);
                if (list == null || list.size() == 0) return def;
                return list.get(0);
            }
            T val = p.value();
            if (val == null) return def;
            return val;
        }

        @SuppressWarnings("unchecked")
        public <T> List<T> getList(String key) {
            Parameters.ParamValue<T> p = (Parameters.ParamValue<T>) params.get(key);
            if (p == null) throw new NoSuchElementException("No parameter with this name: "+key);
            if (!(p.param() instanceof Parameters.ValuedParameter))
                throw new IllegalArgumentException("This parameter is not a list: "+key);
            return (List<T>) p.value();
        }

        public boolean hasFlag(String key) {
            return ((Integer) params.get(key).value()) > 0;
        }

        public Invocation withClient(GatewayDiscordClient newClient) {
            return new Invocation(newClient, params.values().toArray(Parameters.ParamValue[]::new));
        }
    }

    public record Description (
            String name,
            String desc,
            boolean needsClient,
            Parameters params,
            Function<Invocation, Command> constructor
    ) {
        public Command build(Invocation invocation) {
            return constructor.apply(invocation);
        }
    }
}
