package com.billialpha.discord.transfer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.Function;

public class Parameters {
    private final LinkedHashMap<String, Parameter<?>> paramsByName;
    private final Map<String, Parameter<?>> paramsByShortName;

    public static class Builder {
        private final List<Parameter<?>> params = new ArrayList<>();
        private boolean hasLastArg = false;

        public Builder withFlag(String name, String shortName, String desc) {
            params.add(new Flag(name, shortName, desc));
            return this;
        }

        public <T> Builder withOption(
                String name, String shortName, String desc, Function<String, T> valueParser
        ) {
            return withOption(name, shortName, desc, valueParser, null);
        }

        public <T> Builder withOption(
                String name, String shortName, String desc, Function<String, T> valueParser, T def
        ) {
            List<T> defList = def != null ? Collections.singletonList(def) : null;
            params.add(new Option<>(name, shortName, desc, valueParser, false, defList, false));
            return this;
        }

        public <T> Builder withArrayOption(
                String name, String shortName, String desc, Function<String, T> valueParser, T... def
        ) {
            params.add(new Option<>(name, shortName, desc, valueParser, true, Arrays.asList(def), false));
            return this;
        }

        public <T> Builder withMandatoryOption(
                String name, String shortName, String desc, Function<String, T> valueParser
        ) {
            params.add(new Option<>(name, shortName, desc, valueParser, false, null, true));
            return this;
        }

        public <T> Builder withMandatoryArrayOption(
                String name, String shortName, String desc, Function<String, T> valueParser
        ) {
            params.add(new Option<>(name, shortName, desc, valueParser, true, null, true));
            return this;
        }

        public <T> Builder withArgument(
                String name, String desc, Function<String, T> valueParser
        ) {
            if (hasLastArg) throw new IllegalStateException("variable and optional arguments should be last");
            params.add(new SimpleArgument<>(name, desc, valueParser));
            return this;
        }

        public <T> Builder withVariableArgument(
                String name, String desc, Function<String, T> valueParser
        ) {
            if (hasLastArg) throw new IllegalStateException("variable and optional arguments should be last");
            params.add(new MultivaluedArgument<>(name, desc, valueParser));
            hasLastArg = true;
            return this;
        }

        public <T> Builder withOptionalArgument(
                String name, String desc, Function<String, T> valueParser
        ) {
            return withOptionalArgument(name, desc, valueParser, null);
        }

        public <T> Builder withOptionalArgument(
                String name, String desc, Function<String, T> valueParser, T def
        ) {
            if (hasLastArg) throw new IllegalStateException("variable and optional arguments should be last");
            params.add(new OptionalArgument<>(name, desc, valueParser, def));
            hasLastArg = true;
            return this;
        }

        public Parameter<?>[] buildParams() {
            return params.toArray(new Parameter[0]);
        }

        public Parameters build() {
            return new Parameters(buildParams());
        }
    }

    public Parameters(Parameter<?>... params) {
        paramsByName = new LinkedHashMap<>();
        paramsByShortName = new HashMap<>();
        boolean hasMultivaluedArg = false;
        for (Parameter<?> p : params) {
            paramsByName.put(p.name, p);
            if (p.shortName != null) paramsByShortName.put(p.shortName, p);
            if (p instanceof Argument) {
                if (hasMultivaluedArg)
                    throw new IllegalArgumentException("Multivalued argument should be the last");
                if (((Argument<?>) p).multiValued)
                    hasMultivaluedArg = true;
            }
        }
    }

    public static Builder create() {
        return new Builder();
    }

    public Parameters extend(Parameter<?>... params) {
        List<Parameter<?>> newParams = new ArrayList<>(paramsByName.values());
        newParams.addAll(Arrays.asList(params));
        return new Parameters(newParams.toArray(Parameter[]::new));
    }

    public Argument<?>[] getArguments() {
        return paramsByName.values().stream()
                .filter(p -> p instanceof Argument)
                .map(p -> (Argument<?>) p)
                .toArray(Argument[]::new);
    }

    public Parameter<?>[] getFlagAndOptions() {
        return paramsByName.values().stream()
                .filter(p -> p instanceof Flag || p instanceof Option)
                .toArray(Parameter[]::new);
    }

    public Command.Invocation parse(String... args) throws ParameterException {
        return parse(false, args);
    }

    @SuppressWarnings("unchecked")
    public Command.Invocation parse(boolean optionsOnly, String... args) throws ParameterException {
        Iterator<String> iterArgs = Arrays.asList(args).iterator();
        List<String> positionalArgs = new ArrayList<>();
        Map<String, ParamValue> values = new HashMap<>();

        // Parse options
        while (iterArgs.hasNext()) {
            String arg = iterArgs.next();
            // Positional argument
            if (!arg.startsWith("-")) {
                positionalArgs.add(arg);
                continue;
            }
            // Long name option or flag
            if (arg.startsWith("--")) {
                String[] parts = arg.split("=", 1);
                String key = parts[0].substring(2);
                Parameter<?> p = paramsByName.get(key);
                if (p == null) {
                    if (optionsOnly) continue;
                    throw new ParameterException("Unknown option: " + key);
                }
                String value = null;
                if (p instanceof ValuedParameter) {
                    if (parts.length > 1) {
                        value = parts[1];
                    } else try {
                        value = iterArgs.next();
                    } catch (NoSuchElementException ex) {
                        throw new ParameterException("Missing value for parameter: " + key);
                    }
                }
                values.put(p.name, updateParamValue(p, value, values.get(p.name)));
                continue;
            }
            // Short name option or flag
            String[] shortArgs = arg.substring(1).split("");
            for (int i = 0; i < shortArgs.length; i++) {
                String value = null;
                Parameter<?> p = paramsByShortName.get(shortArgs[i]);
                if (p == null) {
                    if (optionsOnly) continue;
                    throw new ParameterException("Unknown option shorthand: " + shortArgs[i]);
                }
                if (p instanceof ValuedParameter) {
                    value = arg.substring(i + 2);
                    if (value.length() == 0) try {
                        value = iterArgs.next();
                    } catch (NoSuchElementException ex) {
                        throw new ParameterException("Missing value for parameter: " + shortArgs[i]);
                    }
                }
                values.put(p.name, updateParamValue(p, value, values.get(p.name)));
            }
        }

        // Parse positional args
        if (!optionsOnly) {
            var posParameters = getArguments();
            var posParams = Arrays.asList(posParameters).iterator();
            Argument<?> posParam = null;
            for (var posArg : positionalArgs) {
                if (posParam == null || !posParam.multiValued) try {
                    posParam = posParams.next();
                } catch (NoSuchElementException ex) {
                    throw new ParameterException("Too many arguments, expected " +
                            posParameters.length + " got " + positionalArgs.size() + ": " + posArg);
                }
                values.put(posParam.name, updateParamValue(posParam, posArg, values.get(posParam.name)));
            }
        }

        // Add defaults
        for (Parameter<?> p : paramsByName.values()) {
            if (values.containsKey(p.name)) continue;
            if (p.required && !optionsOnly)
                throw new ParameterException("Missing required argument: " + p.name);
            values.put(p.name, p.getDefault());
        }

        return new Command.Invocation(null, values.values().toArray(ParamValue[]::new));
    }

    private <T> ParamValue<T> updateParamValue(
            Parameter<T> param, String newValue, ParamValue<T> prevValue
    ) throws ParameterException {
        try {
            return param.value(newValue, prevValue != null ? prevValue.value : null);
        } catch (Exception ex) {
            throw new ParameterException("Invalid value for parameter " + param.name + ": "
                    + ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    public abstract static class Parameter<T> {
        public final String name;
        public final String shortName;
        public final String desc;
        public final T defaultValue;
        public final boolean required;

        public Parameter(String name, String shortName, String desc, T defaultValue, boolean required) {
            this.name = Objects.requireNonNull(name);
            this.shortName = shortName;
            this.desc = desc;
            this.defaultValue = defaultValue;
            this.required = required;
        }

        public abstract ParamValue<T> value(String newValue, T prevValue);

        public ParamValue<T> getDefault() {
            return new ParamValue<>(this, defaultValue);
        }
    }

    public static class Flag extends Parameter<Integer> {

        public Flag(String name, String shortName, String desc) {
            super(name, shortName, desc, 0, false);
        }

        @Override
        public ParamValue<Integer> value(String newValue, Integer prevValue) {
            return new ParamValue<>(this, prevValue != null ? prevValue + 1 : 1);
        }
    }

    public abstract static class ValuedParameter<T> extends Parameter<List<T>> {
        public final Function<String, T> valueParser;
        public final boolean multiValued;

        public ValuedParameter(
                String name, String shortName, String desc,
                Function<String, T> valueParser, boolean multiValued,
                List<T> defaultValue, boolean required
        ) {
            super(name, shortName, desc, defaultValue, required);
            this.valueParser = Objects.requireNonNull(valueParser);
            this.multiValued = multiValued;
        }

        @Override
        public ParamValue<List<T>> value(String newValue, List<T> prevValue) {
            List<T> newList = new ArrayList<>();
            if (prevValue != null) newList.addAll(prevValue);
            newList.add(valueParser.apply(newValue));
            return new ParamValue<>(this, newList);
        }
    }

    public static class Option<T> extends ValuedParameter<T> {
        public Option(
                String name, String shortName, String desc,
                Function<String, T> valueParser, boolean multiValued,
                List<T> defaultValue, boolean required
        ) {
            super(name, shortName, desc, valueParser, multiValued, defaultValue, required);
        }
    }

    public static abstract class Argument<T> extends ValuedParameter<T> {
        public Argument(
                String name, String desc,
                Function<String, T> valueParser, boolean multiValued,
                List<T> defaultValue, boolean required
        ) {
            super(name, null, desc, valueParser, multiValued, defaultValue, required);
        }
    }

    public static class SimpleArgument<T> extends Argument<T> {
        public SimpleArgument(
                String name, String desc,
                Function<String, T> valueParser
        ) {
            super(
                    name,
                    desc,
                    valueParser,
                    false,
                    null,
                    true
            );
        }
    }

    public static class MultivaluedArgument<T> extends Argument<T> {
        public MultivaluedArgument(
                String name, String desc,
                Function<String, T> valueParser
        ) {
            super(
                    name,
                    desc,
                    valueParser,
                    true,
                    null,
                    false
            );
        }
    }

    public static class OptionalArgument<T> extends Argument<T> {
        public OptionalArgument(
                String name, String desc,
                Function<String, T> valueParser,
                T defaultValue
        ) {
            super(
                    name,
                    desc,
                    valueParser,
                    false,
                    defaultValue != null ? Collections.singletonList(defaultValue) : null,
                    false
            );
        }
    }

    public record ParamValue<T> (
            Parameter<T> param,
            T value
    ) {
        public String name() {
            return param().name;
        }
    }

    public static class ParameterException extends Exception {
        public ParameterException(String s) {
            super(s);
        }
    }
}
