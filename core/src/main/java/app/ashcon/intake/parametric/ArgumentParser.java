/*
 * Intake, a command processing library
 * Copyright (C) sk89q <http://www.sk89q.com>
 * Copyright (C) Intake team and contributors
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package app.ashcon.intake.parametric;

import app.ashcon.intake.ImmutableParameter;
import app.ashcon.intake.OptionType;
import app.ashcon.intake.Parameter;
import app.ashcon.intake.argument.ArgumentException;
import app.ashcon.intake.argument.ArgumentParseException;
import app.ashcon.intake.argument.Arguments;
import app.ashcon.intake.argument.CommandArgs;
import app.ashcon.intake.argument.CommandContext;
import app.ashcon.intake.argument.MissingArgumentException;
import app.ashcon.intake.argument.Namespace;
import app.ashcon.intake.argument.UnusedArgumentException;
import app.ashcon.intake.parametric.annotation.Classifier;
import app.ashcon.intake.parametric.annotation.Default;
import app.ashcon.intake.parametric.annotation.Switch;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.reflect.TypeToken;

import javax.annotation.Nullable;
import java.lang.annotation.Annotation;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * An argument parser takes in a list of tokenized arguments and parses
 * them, converting them into appropriate Java objects using a provided
 * {@link Injector}.
 */
public final class ArgumentParser {

    private final Map<Parameter, ParameterEntry> parameters;
    private final List<Parameter> userParams;
    private final Set<Character> valueFlags;

    private ArgumentParser(Map<Parameter, ParameterEntry> parameters, List<Parameter> userParams, Set<Character> valueFlags) {
        this.parameters = ImmutableMap.copyOf(parameters);
        this.userParams = ImmutableList.copyOf(userParams);
        this.valueFlags = ImmutableSet.copyOf(valueFlags);
    }

    /**
     * Get a list of parameters that are user-provided and not provided.
     *
     * @return A list of user parameters
     */
    public List<Parameter> getUserParameters() {
        return userParams;
    }

    /**
     * Get a list of value flags that have been requested by the parameters.
     *
     * @return A list of value flags
     */
    public Set<Character> getValueFlags() {
        return valueFlags;
    }

    /**
     * Parse the given arguments into Java objects.
     *
     * @param args The tokenized arguments
     * @return The list of Java objects
     * @throws ArgumentException If there is a problem with the provided arguments
     * @throws ProvisionException If there is a problem with the binding itself
     */
    public Object[] parseArguments(CommandArgs args) throws ArgumentException, ProvisionException {
        return parseArguments(args, false, Collections.<Character>emptySet());
    }

    /**
     * Parse the given arguments into Java objects.
     *
     * @param args The tokenized arguments
     * @param ignoreUnusedFlags Whether unused flags should not throw an exception
     * @param unusedFlags List of flags that can be unconsumed
     * @return The list of Java objects
     * @throws ArgumentException If there is a problem with the provided arguments
     * @throws ProvisionException If there is a problem with the binding itself
     */
    public Object[] parseArguments(CommandArgs args, boolean ignoreUnusedFlags, Set<Character> unusedFlags) throws ArgumentException, ProvisionException {
        Object[] parsedObjects = new Object[parameters.size()];
        List<ParameterEntry> entries = Lists.newArrayList(parameters.values());

        for (int i = 0; i < parameters.size(); i++) {
            ParameterEntry entry = entries.get(i);
            OptionType optionType = entry.getParameter().getOptionType();
            CommandArgs argsForParameter = optionType.transform(args);

            try {
                parsedObjects[i] = entry.getBinding().getProvider().get(argsForParameter, entry.getModifiers());
            } catch (ArgumentParseException e) {
                throw new ArgumentParseException(e.getMessage(), e, entry.getParameter());
            } catch (MissingArgumentException e) {
                if (!optionType.isOptional()) {
                    throw new MissingArgumentException(e, entry.getParameter());
                }

                parsedObjects[i] = getDefaultValue(entry, args);
            }

            if (entry.getParameter().isOptional()) {
                parsedObjects[i] = Optional.ofNullable(parsedObjects[i]);
            }
        }

        // Check for unused arguments
        checkUnconsumed(args, ignoreUnusedFlags, unusedFlags);

        return parsedObjects;
    }

    /**
     * Parse the given arguments into a list of suggestions.
     *
     * @param arguments What the user has typed so far
     * @param namespace The namespace to send to providers
     * @return The list of suggestions
     */
    public List<String> parseSuggestions(String arguments, Namespace namespace) {
        String[] split = CommandContext.split(arguments);

        int argId = split.length - 1;
        String arg = split[argId];

        if(argId > userParams.size()) return ImmutableList.of();
        Parameter parameter = userParams.get(argId);
        if(parameter == null) return ImmutableList.of();

        ParameterEntry entry = parameters.get(parameter);
        return entry.getBinding().getProvider().getSuggestions(arg, namespace, entry.getModifiers());
    }

    private Object getDefaultValue(ParameterEntry entry, CommandArgs arguments) {
        Provider<?> provider = entry.getBinding().getProvider();

        List<String> defaultValue = entry.getParameter().getDefaultValue();
        if (defaultValue.isEmpty()) {
            return null;
        } else {
            try {
                return provider.get(Arguments.copyOf(defaultValue, arguments.getFlags(), arguments.getNamespace()), entry.getModifiers());
            } catch (ProvisionException | ArgumentException e) {
                throw new IllegalParameterException("No value was specified for the '" + entry.getParameter().getName() + "' parameter " +
                        "so the default value '" + Joiner.on(" ").join(defaultValue) + "' was used, but this value doesn't work due to an error: " + e.getMessage());
            }
        }
    }

    private void checkUnconsumed(CommandArgs arguments, boolean ignoreUnusedFlags, Set<Character> unusedFlags) throws UnusedArgumentException {
        List<String> unconsumedArguments = Lists.newArrayList();

        if (!ignoreUnusedFlags) {
            Set<Character> unconsumedFlags = null;

            for (char flag : arguments.getFlags().keySet()) {
                boolean found = false;

                if (unusedFlags.contains(flag)) {
                    break;
                }

                for (ParameterEntry parameter : parameters.values()) {
                    Character paramFlag = parameter.getParameter().getOptionType().getFlag();
                    if (paramFlag != null && flag == paramFlag) {
                        found = true;
                        break;
                    }
                }

                if (!found) {
                    if (unconsumedFlags == null) {
                        unconsumedFlags = new HashSet<Character>();
                    }
                    unconsumedFlags.add(flag);
                }
            }

            if (unconsumedFlags != null) {
                for (Character flag : unconsumedFlags) {
                    unconsumedArguments.add("-" + flag);
                }
            }
        }

        while (true) {
            try {
                unconsumedArguments.add(arguments.next());
            } catch (MissingArgumentException ignored) {
                break;
            }
        }

        if (!unconsumedArguments.isEmpty()) {
            throw new UnusedArgumentException(Joiner.on(" ").join(unconsumedArguments));
        }
    }

    /**
     * Builds instances of ArgumentParser.
     */
    public static class Builder {
        private final Injector injector;
        private final Map<Parameter, ParameterEntry> parameters = Maps.newLinkedHashMap(); // Order matters
        private final List<Parameter> userProvidedParameters = Lists.newArrayList();
        private final Set<Character> valueFlags = Sets.newHashSet();
        private boolean seenOptionalParameter = false;

        /**
         * Create a new instance.
         *
         * @param injector The injector
         */
        public Builder(Injector injector) {
            checkNotNull(injector, "injector");
            this.injector = injector;
        }

        /**
         * Add a parameter to parse.
         *
         * @param type The type of the parameter
         * @throws IllegalParameterException If there is a problem with the parameter
         */
        public void addParameter(Type type) throws IllegalParameterException {
            addParameter(type, ImmutableList.<Annotation>of());
        }

        /**
         * Add a parameter to parse.
         *
         * @param type The type of the parameter
         * @param annotations A list of annotations on the parameter
         * @throws IllegalParameterException If there is a problem with the parameter
         */
        public void addParameter(Type type, List<? extends Annotation> annotations) throws IllegalParameterException {
            checkNotNull(type, "type");
            checkNotNull(annotations, "annotations");

            int index = parameters.size();
            OptionType optionType = null;
            List<String> defaultValue = ImmutableList.of();
            Annotation classifier = null;
            List<Annotation> modifiers = Lists.newArrayList();
            boolean seenJavaOptional = false;

            Supplier<IllegalParameterException> exceptionSupplier = new Supplier<IllegalParameterException>() {
                @Override
                public IllegalParameterException get() {
                    return new IllegalParameterException("Optional<?>, @Default, @Nullable, and @Switch cannot be mixed for parameter #" + index);
                }
            };

            if (TypeToken.of(Optional.class).isSupertypeOf(type)) {
                type = ((ParameterizedType) type).getActualTypeArguments()[0];

                seenOptionalParameter = true;
                seenJavaOptional = true;

                optionType = OptionType.optionalPositional();
            }

            for (Annotation annotation : annotations) {
                if (annotation.annotationType().getAnnotation(Classifier.class) != null) {
                    classifier = annotation;
                } else {
                    modifiers.add(annotation);

                    if (annotation instanceof Switch) {
                        if (optionType != null) {
                            throw exceptionSupplier.get();
                        }

                        optionType = (type == boolean.class || type == Boolean.class) ? OptionType.flag(((Switch) annotation).value()) : OptionType.valueFlag(((Switch) annotation).value());

                    } else if (annotation instanceof Default || annotation instanceof Nullable) {
                        if (seenOptionalParameter || optionType != null) {
                            throw exceptionSupplier.get();
                        }

                        seenOptionalParameter = true;

                        optionType = OptionType.optionalPositional();

                        if (annotation instanceof Default) {
                            String[] value = ((Default) annotation).value();
                            if (value.length > 0) {
                                defaultValue = ImmutableList.copyOf(value);
                            }
                        }
                    }
                }
            }

            if (optionType == null) {
                optionType = OptionType.positional();
            }

            if (seenOptionalParameter && !optionType.isOptional()) {
                throw new IllegalParameterException("An non-optional parameter followed an optional parameter at #" + index);
            }

            Key<?> key = Key.get(type, classifier != null ? classifier.annotationType() : null);
            Binding<?> binding = injector.getBinding(key);
            if (binding == null) {
                throw new IllegalParameterException("Can't finding a binding for the parameter type '" + type + "'");
            }

            ImmutableParameter.Builder builder = new ImmutableParameter.Builder();
            builder.setName(getFriendlyName(binding.getProvider(), classifier, index));
            builder.setOptionType(optionType);
            builder.setDefaultValue(defaultValue);
            builder.setOptional(seenJavaOptional);
            Parameter parameter = builder.build();

            ParameterEntry entry = new ParameterEntry(parameter, key, binding, modifiers);

            if (optionType.isValueFlag()) {
                valueFlags.add(optionType.getFlag());
            }

            if (!binding.getProvider().isProvided()) {
                userProvidedParameters.add(parameter);
            }

            parameters.put(parameter, entry);
        }

        /**
         * Create a new argument parser.
         *
         * @return A new argument parser
         */
        public ArgumentParser build() {
            return new ArgumentParser(parameters, userProvidedParameters, valueFlags);
        }

        private static String getFriendlyName(Provider provider, Annotation classifier, int index) {
            if (classifier != null) {
                return classifier.annotationType().getSimpleName().toLowerCase();
            } else {
                return provider.getName();
            }
        }
    }

    private static class ParameterEntry {
        private final Parameter parameter;
        private final Key<?> key;
        private final Binding<?> binding;
        private final List<Annotation> modifiers;

        ParameterEntry(Parameter parameter, Key<?> key, Binding<?> binding, List<Annotation> modifiers) {
            this.parameter = parameter;
            this.key = key;
            this.binding = binding;
            this.modifiers = modifiers;
        }

        public Parameter getParameter() {
            return parameter;
        }

        public Key<?> getKey() {
            return key;
        }

        public Binding<?> getBinding() {
            return binding;
        }

        public List<Annotation> getModifiers() {
            return modifiers;
        }
    }

}
