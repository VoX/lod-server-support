package dev.vox.lss.common.config;

import java.util.function.Function;

/** Compile-time checked stored-value binding. Mutation stays in the existing typed
 * command/UI paths, and effective AUTO values require platform context. */
public record SettingBinding<C>(SettingDescriptor descriptor, Function<C, Object> storedValue) {}
