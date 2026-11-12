package dev.vox.lss.config.menu;

import com.google.common.collect.ImmutableList;
import dev.vox.lss.common.Brand;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.config.LSSClientConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.screens.Screen;

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
 * Server LODs" is unticked, before Apply); the shared {@link SaveHook} has one storage
 * proxies — the legacy screen saves once per DISTINCT storage per Apply, collecting
 * them in a {@code HashSet}, which is why the proxies answer {@code hashCode}/
 * {@code equals} (identity) — and the complete draft writes once.
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

    public enum MemberKind { STATIC_METHOD, METHOD, PRIVATE_METHOD, CONSTRUCTOR, INTERFACE_METHOD, ENUM, FIELD }

    /** One resolved member: owner (relative class name), kind, name, parameter count. */
    public record Member(String owner, MemberKind kind, String name, int arity) {
    }

    /** Every member the legacy STACK binds by name — this builder, the constructor hook,
     *  the ModMenu deep-link and the tab fitting — the resolves-test's checklist against
     *  real bytecode. */
    public static final List<Member> SURFACE = List.of(
            new Member(OPTIONS_SCREEN, MemberKind.FIELD, "pages", 0),
            new Member(OPTIONS_SCREEN, MemberKind.FIELD, "currentPage", 0),
            new Member(OPTIONS_SCREEN, MemberKind.FIELD, "donateButton", 0),
            new Member(OPTIONS_SCREEN, MemberKind.FIELD, "hideDonateButton", 0),
            new Member(OPTIONS_SCREEN, MemberKind.PRIVATE_METHOD, "rebuildGUI", 0),
            new Member(OPTIONS_SCREEN, MemberKind.METHOD, "setPage", 1),
            new Member(".client.gui.widgets.FlatButtonWidget", MemberKind.CONSTRUCTOR, "<init>", 3),
            new Member(".client.gui.widgets.FlatButtonWidget", MemberKind.FIELD, "dim", 0),
            new Member(".client.gui.widgets.FlatButtonWidget", MemberKind.FIELD, "visible", 0),
            new Member(".client.gui.widgets.FlatButtonWidget", MemberKind.METHOD, "setVisible", 1),
            new Member(".client.util.Dim2i", MemberKind.CONSTRUCTOR, "<init>", 4),
            new Member(".client.util.Dim2i", MemberKind.METHOD, "x", 0),
            new Member(".client.util.Dim2i", MemberKind.METHOD, "y", 0),
            new Member(".client.util.Dim2i", MemberKind.METHOD, "width", 0),
            new Member(".client.util.Dim2i", MemberKind.METHOD, "height", 0),
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
     * against the retained disk draft and environment, brand-titled. Empty on any failure
     * (logged once) — never throws.
     */
    public static List<Object> build() {
        try {
            Handles h = handles();
            ClientSettingsEditSession cfg = LSSClientConfig.CONFIG.edits();
            cfg.open(new Object());
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
    static List<Object> buildWith(Handles h, dev.vox.lss.config.menu.ClientSettingsEditSession cfg, MenuContext ctx, String brand,
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
            // names carry the brand. Short labels keep all tabs readable at Auto GUI scale.
            String titleKey = switch (pageSpec.titleKey()) {
                case "lss.config.far_players.page" -> "lss.config.far_players.legacy_tab";
                default -> pageSpec.titleKey();
            };
            Component title = first
                    ? Component.literal(brand)
                    : Component.literal(brand + " ").append(Component.translatable(titleKey));
            pages.add(h.pageCtor().invoke(title, ImmutableList.copyOf(groups)));
            first = false;
        }
        return pages;
    }

    /** Keep Sodium's donation controls usable when the extended tab row reaches them.
     * Each rebuild creates new widgets; move only a colliding pair to the free footer,
     * without changing Sodium's saved donation preference. All optional internals remain
     * reflective and contained so an unsupported screen still opens normally. */
    private static final java.util.concurrent.atomic.AtomicBoolean layoutWarned=new java.util.concurrent.atomic.AtomicBoolean();
    record Bounds(int x,int y,int width,int height) {
        boolean intersects(Bounds other) {return width>0 && height>0 && other.width>0 && other.height>0
                && x<other.x+other.width && other.x<x+width && y<other.y+other.height && other.y<y+height;}
    }
    static boolean footerFree(int screenWidth,int screenHeight,int width,List<Bounds> children) {
        var candidate=new Bounds(6,screenHeight-30,width,20);
        return candidate.x+width<=screenWidth-211 && children.stream().noneMatch(candidate::intersects);
    }
    static int tabStart(int screenWidth,int current,List<Integer> widths) {
        int start=Math.max(0,current-1);
        while(start<current && widths.subList(start,current+1).stream().mapToInt(w->w+6).sum()-6>screenWidth-56)start++;
        return start;
    }
    private static Bounds bounds(Object child) throws ReflectiveOperationException {
        Object dim;
        try {dim=screenField(child,"dim");}
        catch(NoSuchFieldException missing) {
            try {dim=child.getClass().getMethod("getDimensions").invoke(child);}
            catch(NoSuchMethodException absent) {
                var rect=((net.minecraft.client.gui.components.events.GuiEventListener)child).getRectangle();
                return new Bounds(rect.left(),rect.top(),rect.width(),rect.height());
            }
        }
        return new Bounds(dimension(dim,"x"),dimension(dim,"y"),dimension(dim,"width"),dimension(dim,"height"));
    }
    public static void fitDonationButtons(Object target) {
        try {
            Screen screen=(Screen)target;
            Object donate=screenField(target,"donateButton"),dismiss=screenField(target,"hideDonateButton");
            fitTabs(target,donate,dismiss);
            var donation=bounds(donate);var dismissal=bounds(dismiss);
            var occupied=new ArrayList<Bounds>();boolean collision=false;
            for(Object child:screen.children()) {
                if(child==donate || child==dismiss)continue;
                if(child.getClass().getSimpleName().equals("FlatButtonWidget") && !(boolean)screenField(child,"visible"))continue;
                var rectangle=bounds(child);occupied.add(rectangle);
                collision|=donation.intersects(rectangle)||dismissal.intersects(rectangle);
            }
            if(!collision)return;
            int width=donation.width+4+dismissal.width;
            if(!footerFree(screen.width,screen.height,width,occupied)) {
                donate.getClass().getMethod("setVisible",boolean.class).invoke(donate,false);
                dismiss.getClass().getMethod("setVisible",boolean.class).invoke(dismiss,false);return;
            }
            moveButton(donate,6,screen.height-30);
            moveButton(dismiss,6+donation.width+4,screen.height-30);
        } catch(Throwable failure) {
            if(layoutWarned.compareAndSet(false,true))dev.vox.lss.common.LSSLogger.warn("Optional Sodium layout adjustment unavailable",failure);
        }
    }
    /** Paginate overflowing tab rows; arrows select a neighboring real page. */
    static int tabEnd(int screenWidth,int start,List<Integer> widths) {
        int x=28,end=start;
        while(end<widths.size() && x+widths.get(end)<=screenWidth-28){x+=widths.get(end)+6;end++;}
        return end;
    }
    private static void fitTabs(Object target,Object donate,Object dismiss) throws ReflectiveOperationException {
        Screen screen=(Screen)target;
        var tabs=new ArrayList<Object>();
        for(Object child:screen.children())if(child!=donate && child!=dismiss
                && child.getClass().getSimpleName().equals("FlatButtonWidget") && bounds(child).y==6)tabs.add(child);
        tabs.sort(java.util.Comparator.comparingInt(child->{try{return bounds(child).x;}catch(Exception e){return 0;}}));
        if(tabs.isEmpty() || bounds(tabs.get(tabs.size()-1)).x+bounds(tabs.get(tabs.size()-1)).width<=screen.width-12)return;
        var pages=(List<?>)screenField(target,"pages");Object selected=screenField(target,"currentPage");
        if(tabs.size()!=pages.size())return;
        int current=pages.indexOf(selected);
        if(current<0)return;
        var widths=new ArrayList<Integer>();for(Object tab:tabs)widths.add(bounds(tab).width);
        int start=tabStart(screen.width,current,widths),end=tabEnd(screen.width,start,widths),x=28;
        for(int i=0;i<tabs.size();i++) {
            Object tab=tabs.get(i);int width=bounds(tab).width;
            boolean visible=i>=start && i<end;
            tab.getClass().getMethod("setVisible",boolean.class).invoke(tab,visible);
            if(visible){moveButton(tab,x,6);x+=width+6;}
        }
        for(int direction:new int[]{-1,1}) {
            final int next=Math.floorMod(Math.max(0,current)+direction,pages.size());
            Runnable action=()->{try{target.getClass().getMethod("setPage",pages.get(next).getClass()).invoke(target,pages.get(next));}
                catch(ReflectiveOperationException failure){dev.vox.lss.common.LSSLogger.warn("Sodium page navigation unavailable",failure);}};
            Object sample=tabs.get(0),old=screenField(sample,"dim");
            Object dim=old.getClass().getConstructor(int.class,int.class,int.class,int.class).newInstance(direction<0?6:screen.width-24,6,18,18);
            Object button=sample.getClass().getConstructor(old.getClass(),Component.class,Runnable.class)
                    .newInstance(dim,Component.literal(direction<0?"‹":"›"),action);
            ((dev.vox.lss.mixin.AccessorScreenWidgets)screen).lss$addWidget((net.minecraft.client.gui.components.events.GuiEventListener)button);
        }
    }

    private static int dimension(Object bounds, String name) throws ReflectiveOperationException {
        return (int) bounds.getClass().getMethod(name).invoke(bounds);
    }

    private static Object screenField(Object target, String name) throws ReflectiveOperationException {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try { var field = type.getDeclaredField(name); field.setAccessible(true); return field.get(target); }
            catch (NoSuchFieldException absent) { /* walk a compatible screen subclass */ }
        }
        throw new NoSuchFieldException(name);
    }

    private static void moveButton(Object button, int x, int y) throws ReflectiveOperationException {
        var field = button.getClass().getDeclaredField("dim"); field.setAccessible(true);
        Object old = field.get(button);
        field.set(button, old.getClass().getConstructor(int.class, int.class, int.class, int.class)
                .newInstance(x, y, dimension(old, "width"), dimension(old, "height")));
    }

    private static Object buildOption(Handles h, OptionSpec spec, dev.vox.lss.config.menu.ClientSettingsEditSession cfg, MenuContext ctx,
                                      Object storage, Map<String, Object> builtById) throws Throwable {
        Class<?> valueType = switch(spec) {
            case OptionSpec.BoolSpec b -> Boolean.class;
            case OptionSpec.IntSpec i -> Integer.class;
        };
        Object b = h.createBuilder().invoke(valueType, storage);
        h.setName().invoke(b, Component.translatable(spec.nameKey()));
        h.setTooltip().invoke(b, Component.translatable(spec.tooltip().resolve(ctx)));
        if (spec.impact() != null) {
            h.setImpact().invoke(b, h.impactValueOf().invoke(spec.impact().name()));
        }
        switch (spec) {
            case OptionSpec.BoolSpec s -> {
                h.setBinding().invoke(b, (BiConsumer<Object, Object>) (data, v) -> s.setter().accept((dev.vox.lss.config.menu.ClientSettingsEditSession) data, (Boolean) v),
                        (Function<Object, Object>) data -> s.getter().apply((dev.vox.lss.config.menu.ClientSettingsEditSession) data));
                h.setControl().invoke(b, (Function<Object, Object>) option -> invoke(h.tickBoxCtor(), option));
            }
            case OptionSpec.IntSpec s -> {
                h.setBinding().invoke(b, (BiConsumer<Object, Object>) (data, v) -> s.setter().accept((dev.vox.lss.config.menu.ClientSettingsEditSession) data, (Integer) v),
                        (Function<Object, Object>) data -> s.displayValue((dev.vox.lss.config.menu.ClientSettingsEditSession) data));
                Object formatter = formatterProxy(h, s.label());
                h.setControl().invoke(b, (Function<Object, Object>) option ->
                        invoke(h.sliderCtor(), option, s.min(), s.max(), s.step(), formatter));
            }
        }
        if (spec.enabledBy() != null) {
            var dependencies=ClientOptionCatalog.enabledDependencies(spec);
            if (h.enabledIsStatic()) {
                // Sodium 0.5's setEnabled(boolean): no live greying — the dependency's
                // value at build time is the best the API offers.
                boolean enabled=true;
                for(String id:dependencies)enabled &= Boolean.TRUE.equals(ClientOptionCatalog.find(id).orElseThrow().read(cfg));
                h.setEnabled().invoke(b,enabled);
            } else {
                // Resolved LAZILY (review A-8): the dependency is built earlier in the
                // shared catalog, but the supplier must not depend on build order.
                h.setEnabled().invoke(b, (BooleanSupplier) () -> {
                    for(String id:dependencies) {
                        Object dep=builtById.get(id);
                        if(dep!=null && !Boolean.TRUE.equals(invoke(h.getValue(),dep)))return false;
                    }
                    return true;
                });
            }
        }
        return h.build().invoke(b);
    }


    private static Object storageProxy(Handles h, dev.vox.lss.config.menu.ClientSettingsEditSession cfg, SaveHook hook, Consumer<SaveHook> onSave) {
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
