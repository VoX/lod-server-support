package dev.vox.lss.config.menu;

import com.google.common.collect.ImmutableList;
import dev.vox.lss.common.Brand;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.config.LSSClientConfig;
import net.minecraft.network.chat.Component;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntFunction;

/**
 * The legacy Sodium (0.6/0.7 — and 0.5 modulo its static enabling) renderer
 * (sodium-options-page-generations-plan.md D4): walks {@link ClientOptionCatalog} into
 * Sodium's INTERNAL {@code client.gui.options} page objects with ZERO compile
 * dependency — every class and member is resolved by name under the probed package
 * prefix through {@link MethodHandle}s, the two interfaces Sodium needs us to
 * implement ({@code OptionStorage}, {@code ControlValueFormatter}) are
 * {@link Proxy} instances, and the whole thing degrades to "no page + one WARN" on any
 * failure — the Sodium screen must always open. Called from the constructor mixin on
 * that screen ({@code SodiumLegacyOptionsHook}), i.e. AFTER the target class is loaded
 * and transformed, which is the one place a {@code Class.forName} of Sodium's classes
 * is safe (see {@link SodiumGeneration}).
 *
 * <p>Why reflective rather than a {@code modCompileOnly} on 0.6/0.7: the 1.21.1 line
 * carries BOTH Sodium generations and Gradle cannot put two versions of one artifact
 * on a compile classpath — this file compiles on every line, including lines where no
 * legacy Sodium exists at all.
 *
 * <p>Semantics parity with the 0.8+ walker (plan D8): {@code enabledBy} is a
 * {@code BooleanSupplier} over the dependency option's STAGED value (Sodium's
 * {@code getValue()} returns the pending edit — the sliders grey the moment "Receive
 * Server LODs" is unticked, before Apply); the two {@link SaveHook}s are two storage
 * proxies — the legacy screen saves once per DISTINCT storage per Apply, collecting
 * them in a {@code HashSet}, which is why the proxies answer {@code hashCode}/
 * {@code equals} (identity) — and the far-player page's push hook fires once.
 *
 * <p>{@link #SURFACE} is the resolved-member table as DATA: the resolver consumes it
 * and {@code SodiumLegacySurfaceResolvesTest} checks every row (name + arity) against
 * the real Sodium bytecode, so the table cannot drift from the code.
 *
 * <p>Same-FQN TWIN in the neoforge tree — keep byte-identical (pinned).
 */
public final class LegacySodiumPage {

    // ---- the internal surface, relative to the probed package prefix ----
    public static final String OPTION_IMPL = ".client.gui.options.OptionImpl";
    public static final String OPTION_IMPL_BUILDER = ".client.gui.options.OptionImpl$Builder";
    public static final String OPTION_GROUP = ".client.gui.options.OptionGroup";
    public static final String OPTION_GROUP_BUILDER = ".client.gui.options.OptionGroup$Builder";
    public static final String OPTION_PAGE = ".client.gui.options.OptionPage";
    public static final String OPTION_IMPACT = ".client.gui.options.OptionImpact";
    public static final String OPTION_STORAGE = ".client.gui.options.storage.OptionStorage";
    public static final String TICK_BOX_CONTROL = ".client.gui.options.control.TickBoxControl";
    public static final String SLIDER_CONTROL = ".client.gui.options.control.SliderControl";
    public static final String VALUE_FORMATTER = ".client.gui.options.control.ControlValueFormatter";
    /** The screen itself — bound by the constructor hook ({@code pages}, the one ctor) and
     *  the ModMenu deep-link ({@code createScreen}, {@code currentPage}), not by this builder. */
    public static final String OPTIONS_SCREEN = SodiumGeneration.LEGACY_SCREEN_SUFFIX;

    public enum MemberKind { STATIC_METHOD, METHOD, CONSTRUCTOR, INTERFACE_METHOD, ENUM, FIELD }

    /** One resolved member: owner (relative class name), kind, name, parameter count. */
    public record Member(String owner, MemberKind kind, String name, int arity) {
    }

    /** Every member the legacy STACK binds by name — this builder, the constructor hook
     *  and the ModMenu deep-link — the resolves-test's checklist against real bytecode. */
    public static final List<Member> SURFACE = List.of(
            new Member(OPTIONS_SCREEN, MemberKind.FIELD, "pages", 0),
            new Member(OPTIONS_SCREEN, MemberKind.FIELD, "currentPage", 0),
            new Member(OPTIONS_SCREEN, MemberKind.CONSTRUCTOR, "<init>", 1),
            new Member(OPTIONS_SCREEN, MemberKind.STATIC_METHOD, "createScreen", 1),
            new Member(OPTION_IMPL, MemberKind.STATIC_METHOD, "createBuilder", 2),
            new Member(OPTION_IMPL, MemberKind.METHOD, "getValue", 0),
            new Member(OPTION_IMPL_BUILDER, MemberKind.METHOD, "setName", 1),
            new Member(OPTION_IMPL_BUILDER, MemberKind.METHOD, "setTooltip", 1),
            new Member(OPTION_IMPL_BUILDER, MemberKind.METHOD, "setBinding", 2),
            new Member(OPTION_IMPL_BUILDER, MemberKind.METHOD, "setControl", 1),
            new Member(OPTION_IMPL_BUILDER, MemberKind.METHOD, "setImpact", 1),
            new Member(OPTION_IMPL_BUILDER, MemberKind.METHOD, "setEnabled", 1),
            new Member(OPTION_IMPL_BUILDER, MemberKind.METHOD, "build", 0),
            new Member(OPTION_GROUP, MemberKind.STATIC_METHOD, "createBuilder", 0),
            new Member(OPTION_GROUP_BUILDER, MemberKind.METHOD, "add", 1),
            new Member(OPTION_GROUP_BUILDER, MemberKind.METHOD, "build", 0),
            new Member(OPTION_PAGE, MemberKind.CONSTRUCTOR, "<init>", 2),
            new Member(TICK_BOX_CONTROL, MemberKind.CONSTRUCTOR, "<init>", 1),
            new Member(SLIDER_CONTROL, MemberKind.CONSTRUCTOR, "<init>", 5),
            new Member(OPTION_IMPACT, MemberKind.ENUM, "valueOf", 1),
            new Member(OPTION_STORAGE, MemberKind.INTERFACE_METHOD, "getData", 0),
            new Member(OPTION_STORAGE, MemberKind.INTERFACE_METHOD, "save", 0),
            new Member(VALUE_FORMATTER, MemberKind.INTERFACE_METHOD, "format", 1));

    /** The resolved handles for one Sodium prefix. */
    record Handles(String prefix,
                   MethodHandle createBuilder, MethodHandle getValue,
                   MethodHandle setName, MethodHandle setTooltip, MethodHandle setBinding,
                   MethodHandle setControl, MethodHandle setImpact, MethodHandle setEnabled,
                   boolean enabledIsStatic, MethodHandle build,
                   MethodHandle groupCreateBuilder, MethodHandle groupAdd, MethodHandle groupBuild,
                   MethodHandle pageCtor, MethodHandle tickBoxCtor, MethodHandle sliderCtor,
                   MethodHandle impactValueOf, Class<?> storageClass, Class<?> formatterClass) {
    }

    private static volatile Handles handles;
    private static volatile boolean resolveFailed;
    private static volatile boolean buildFailureLogged;

    private LegacySodiumPage() {
    }

    /**
     * The production entry, called by the constructor hook: the catalog rendered
     * against the live config and environment, brand-titled. Empty on any failure
     * (logged once) — never throws.
     */
    public static List<Object> build() {
        try {
            Handles h = handles();
            LSSClientConfig cfg = LSSClientConfig.CONFIG;
            return buildWith(h, cfg, MenuContext.current(), Brand.shortName(), hook -> hook.run(cfg));
        } catch (Throwable t) {
            if (!buildFailureLogged) {
                buildFailureLogged = true;
                LSSLogger.warn(Brand.shortName() + " options page: could not build the Sodium settings pages — "
                        + "config files still work (" + t + ")");
            }
            return List.of();
        }
    }

    private static Handles handles() throws ReflectiveOperationException {
        Handles h = handles;
        if (h != null) {
            return h;
        }
        if (resolveFailed) {
            throw new IllegalStateException("legacy Sodium options surface unresolved (latched)");
        }
        // The hook runs INSIDE the legacy screen's constructor, which is proof enough that
        // the legacy screen exists — never let a foreign 0.8-API jar's presence (a MODERN
        // probe answer) veto the page here (implementation review).
        String prefix = SodiumGeneration.legacyPrefixIgnoringModern();
        if (prefix == null) {
            throw new IllegalStateException("no legacy Sodium options screen on the class path");
        }
        try {
            h = resolve(prefix, LegacySodiumPage.class.getClassLoader());
        } catch (Throwable e) {
            // Errors too (NoClassDefFoundError / IllegalAccessError from a half-present or
            // modularized Sodium): latch, warn once, and let build() stay quiet about it.
            resolveFailed = true;
            buildFailureLogged = true;
            LSSLogger.warn(Brand.shortName() + " options page: this Sodium (" + prefix + ") has a different internal"
                    + " options API shape — settings page skipped, config files still work (" + e + ")");
            throw e;
        }
        handles = h;
        return h;
    }

    /** Resolves every {@link #SURFACE} member under {@code prefix}. All-or-nothing. */
    static Handles resolve(String prefix, ClassLoader loader) throws ReflectiveOperationException {
        Class<?> optionImpl = cls(loader, prefix + OPTION_IMPL);
        Class<?> builder = cls(loader, prefix + OPTION_IMPL_BUILDER);
        Class<?> group = cls(loader, prefix + OPTION_GROUP);
        Class<?> groupBuilder = cls(loader, prefix + OPTION_GROUP_BUILDER);
        Class<?> page = cls(loader, prefix + OPTION_PAGE);
        Class<?> impact = cls(loader, prefix + OPTION_IMPACT);
        Class<?> storage = cls(loader, prefix + OPTION_STORAGE);
        Class<?> tickBox = cls(loader, prefix + TICK_BOX_CONTROL);
        Class<?> slider = cls(loader, prefix + SLIDER_CONTROL);
        Class<?> formatter = cls(loader, prefix + VALUE_FORMATTER);
        if (!storage.isInterface() || !formatter.isInterface() || !impact.isEnum()) {
            throw new NoSuchMethodException("OptionStorage/ControlValueFormatter must be interfaces"
                    + " and OptionImpact an enum under " + prefix);
        }
        var lookup = MethodHandles.publicLookup();
        Method setEnabled = method(builder, "setEnabled", 1, false, BooleanSupplier.class);
        return new Handles(prefix,
                lookup.unreflect(method(optionImpl, "createBuilder", 2, true, Class.class, storage)),
                lookup.unreflect(method(optionImpl, "getValue", 0, false)),
                lookup.unreflect(method(builder, "setName", 1, false, Component.class)),
                lookup.unreflect(method(builder, "setTooltip", 1, false, Component.class)),
                lookup.unreflect(method(builder, "setBinding", 2, false, BiConsumer.class, Function.class)),
                lookup.unreflect(method(builder, "setControl", 1, false, Function.class)),
                lookup.unreflect(method(builder, "setImpact", 1, false, impact)),
                lookup.unreflect(setEnabled),
                setEnabled.getParameterTypes()[0] == boolean.class,
                lookup.unreflect(method(builder, "build", 0, false)),
                lookup.unreflect(method(group, "createBuilder", 0, true)),
                lookup.unreflect(method(groupBuilder, "add", 1, false)),
                lookup.unreflect(method(groupBuilder, "build", 0, false)),
                lookup.unreflectConstructor(ctor(page, 2)),
                lookup.unreflectConstructor(ctor(tickBox, 1)),
                lookup.unreflectConstructor(ctor(slider, 5)),
                lookup.unreflect(method(impact, "valueOf", 1, true, String.class)),
                storage, formatter);
    }

    /**
     * The build body with everything injectable (the unit tests' seam): the resolved
     * handles, the config INSTANCE the bindings read/write, the environment, the brand
     * for the tab titles, and the save observer the storage proxies call (production:
     * {@code hook.run(cfg)}).
     */
    static List<Object> buildWith(Handles h, LSSClientConfig cfg, MenuContext ctx, String brand,
                                  Consumer<SaveHook> onSave) throws Throwable {
        Map<SaveHook, Object> storages = new HashMap<>();
        for (SaveHook hook : SaveHook.values()) {
            storages.put(hook, storageProxy(h, cfg, hook, onSave));
        }
        Map<String, Object> builtById = new HashMap<>();
        List<Object> pages = new ArrayList<>();
        boolean first = true;
        for (PageSpec pageSpec : ClientOptionCatalog.pages()) {
            List<Object> groups = new ArrayList<>();
            for (GroupSpec groupSpec : pageSpec.groups()) {
                Object groupBuilder = h.groupCreateBuilder().invoke();
                boolean any = false;
                for (OptionSpec spec : groupSpec.options()) {
                    if (!spec.visibility().test(ctx)) {
                        continue;
                    }
                    Object option = buildOption(h, spec, cfg, ctx, storages.get(spec.saveHook()), builtById);
                    builtById.put(spec.id(), option);
                    h.groupAdd().invoke(groupBuilder, option);
                    any = true;
                }
                if (any) {
                    groups.add(h.groupBuild().invoke(groupBuilder));
                }
            }
            // Legacy tabs sit beside Sodium's own with no per-mod grouping, so the tab
            // names carry the brand (plan D7): "LSS", then "LSS Far Players".
            Component title = first
                    ? Component.literal(brand)
                    : Component.literal(brand + " ").append(Component.translatable(pageSpec.titleKey()));
            pages.add(h.pageCtor().invoke(title, ImmutableList.copyOf(groups)));
            first = false;
        }
        return pages;
    }

    private static Object buildOption(Handles h, OptionSpec spec, LSSClientConfig cfg, MenuContext ctx,
                                      Object storage, Map<String, Object> builtById) throws Throwable {
        Class<?> valueType = spec instanceof OptionSpec.BoolSpec ? Boolean.class : Integer.class;
        Object b = h.createBuilder().invoke(valueType, storage);
        h.setName().invoke(b, Component.translatable(spec.nameKey()));
        h.setTooltip().invoke(b, Component.translatable(spec.tooltip().resolve(ctx)));
        if (spec.impact() != null) {
            h.setImpact().invoke(b, h.impactValueOf().invoke(spec.impact().name()));
        }
        switch (spec) {
            case OptionSpec.BoolSpec s -> {
                h.setBinding().invoke(b, (BiConsumer<Object, Object>) (data, v) -> s.setter().accept((LSSClientConfig) data, (Boolean) v),
                        (Function<Object, Object>) data -> s.getter().apply((LSSClientConfig) data));
                h.setControl().invoke(b, (Function<Object, Object>) option -> invoke(h.tickBoxCtor(), option));
            }
            case OptionSpec.IntSpec s -> {
                h.setBinding().invoke(b, (BiConsumer<Object, Object>) (data, v) -> s.setter().accept((LSSClientConfig) data, (Integer) v),
                        (Function<Object, Object>) data -> s.getter().apply((LSSClientConfig) data));
                Object formatter = formatterProxy(h, s.label());
                h.setControl().invoke(b, (Function<Object, Object>) option ->
                        invoke(h.sliderCtor(), option, s.min(), s.max(), s.step(), formatter));
            }
        }
        if (spec.enabledBy() != null) {
            String dependency = spec.enabledBy();
            if (h.enabledIsStatic()) {
                // Sodium 0.5's setEnabled(boolean): no live greying — the dependency's
                // value at build time is the best the API offers.
                Object dep = ClientOptionCatalog.find(dependency).map(d -> d.read(cfg)).orElse(Boolean.TRUE);
                h.setEnabled().invoke(b, Boolean.TRUE.equals(dep));
            } else {
                // Resolved LAZILY (review A-8): the dependency is built earlier on the same
                // page by catalog contract, but the supplier must not depend on build order.
                h.setEnabled().invoke(b, (BooleanSupplier) () -> {
                    Object dep = builtById.get(dependency);
                    return dep == null || Boolean.TRUE.equals(invoke(h.getValue(), dep));
                });
            }
        }
        return h.build().invoke(b);
    }

    // The two proxies are the boundaries where SODIUM calls INTO us — from its Apply loop
    // and from every slider render frame, neither of which catches (javap 0.6.13/0.7.3).
    // Nothing shipped can throw here (JsonConfig.save() contains its own IO, the prefs
    // push is guarded, indices are clamp-bounded), so containment + a once-bounded WARN is
    // defense in depth for the house doctrine: nothing optional may crash a client.

    private static Object storageProxy(Handles h, LSSClientConfig cfg, SaveHook hook, Consumer<SaveHook> onSave) {
        return Proxy.newProxyInstance(h.storageClass().getClassLoader(), new Class<?>[]{h.storageClass()},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getData":
                            return cfg;
                        case "save":
                            try {
                                onSave.accept(hook);
                            } catch (Throwable t) {
                                warnOnce("saving from the Sodium settings screen failed", t);
                            }
                            return null;
                        // The screen keeps dirty storages in a HashSet (review A-2): identity
                        // semantics, never the handler's default null.
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == args[0];
                        case "toString":
                            return "LssOptionStorage[" + hook + "]";
                        default:
                            warnOnce("unexpected OptionStorage call " + method.getName(), null);
                            return null;
                    }
                });
    }

    private static Object formatterProxy(Handles h, IntFunction<Label> label) {
        return Proxy.newProxyInstance(h.formatterClass().getClassLoader(), new Class<?>[]{h.formatterClass()},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "format":
                            try {
                                return component(label.apply((Integer) args[0]));
                            } catch (Throwable t) {
                                warnOnce("formatting a slider value failed", t);
                                return Component.literal(String.valueOf(args[0]));
                            }
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == args[0];
                        case "toString":
                            return "LssValueFormatter";
                        default:
                            warnOnce("unexpected ControlValueFormatter call " + method.getName(), null);
                            return null;
                    }
                });
    }

    private static volatile boolean proxyFailureLogged;

    private static void warnOnce(String what, Throwable t) {
        if (proxyFailureLogged) {
            return;
        }
        proxyFailureLogged = true;
        LSSLogger.warn(Brand.shortName() + " options page: " + what + (t == null ? "" : " (" + t + ")"));
    }

    private static Component component(Label label) {
        return label.isKey() ? Component.translatable(label.key()) : Component.literal(label.literal());
    }

    private static Object invoke(MethodHandle handle, Object... args) {
        try {
            return handle.invokeWithArguments(args);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    // ---- resolution helpers (name + arity, with a parameter-type preference for overloads) ----

    private static Class<?> cls(ClassLoader loader, String name) throws ClassNotFoundException {
        return Class.forName(name, false, loader);
    }

    /**
     * A PUBLIC method by name and parameter count. Among several candidates (0.7's two
     * {@code setTooltip} overloads), prefers the one whose parameter types accept the
     * given argument types; otherwise the first match.
     */
    static Method method(Class<?> owner, String name, int arity, boolean isStatic, Class<?>... prefer)
            throws NoSuchMethodException {
        Method fallback = null;
        for (Method m : owner.getMethods()) {
            if (!m.getName().equals(name) || m.getParameterCount() != arity
                    || Modifier.isStatic(m.getModifiers()) != isStatic) {
                continue;
            }
            if (prefer.length == arity && accepts(m.getParameterTypes(), prefer)) {
                return m;
            }
            if (fallback == null) {
                fallback = m;
            }
        }
        if (fallback == null) {
            throw new NoSuchMethodException(owner.getName() + "." + name + "/" + arity
                    + (isStatic ? " (static)" : ""));
        }
        return fallback;
    }

    static Constructor<?> ctor(Class<?> owner, int arity) throws NoSuchMethodException {
        for (Constructor<?> c : owner.getConstructors()) {
            if (c.getParameterCount() == arity) {
                return c;
            }
        }
        throw new NoSuchMethodException(owner.getName() + ".<init>/" + arity);
    }

    private static boolean accepts(Class<?>[] params, Class<?>[] args) {
        for (int i = 0; i < params.length; i++) {
            if (!params[i].isAssignableFrom(args[i])) {
                return false;
            }
        }
        return true;
    }

    /** The constructor hook's containment sink: log once, keep the screen. */
    public static void noteInjectFailure(Throwable t) {
        warnOnce("could not add the pages to Sodium's settings screen", t);
    }

    /** Test seam: forget the memoized handles and latches. */
    static void resetForTests() {
        handles = null;
        resolveFailed = false;
        buildFailureLogged = false;
        proxyFailureLogged = false;
    }
}
