/*
 * Copyright (c) 2014, 2016, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.truffle.api.vm;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.TruffleLanguage.Env;
import com.oracle.truffle.api.TruffleLanguage.Registration;
import com.oracle.truffle.api.TruffleOptions;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.impl.Accessor;
import com.oracle.truffle.api.impl.FindContextNode;
import com.oracle.truffle.api.instrumentation.TruffleInstrument;
import com.oracle.truffle.api.interop.ForeignAccess;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.java.JavaInterop;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.Source;

/**
 * Gate way into the world of {@link TruffleLanguage Truffle languages}. {@link #buildNew()
 * Instantiate} your own portal into the isolated, multi-language system with all the registered
 * languages ready for your use. A {@link PolyglotEngine} runs inside of a <em>JVM</em>. There can
 * however be multiple instances (some would say tenants) of {@link PolyglotEngine} running next to
 * each other in a single <em>JVM</em> with a complete mutual isolation. There is 1:N mapping
 * between <em>JVM</em> and {@link PolyglotEngine}.
 * <p>
 * It would not be correct to think of a {@link PolyglotEngine} as a runtime for a single
 * {@link TruffleLanguage Truffle language} (Ruby, Python, R, C, JavaScript, etc.) either.
 * {@link PolyglotEngine} can host as many of Truffle languages as {@link Registration registered on
 * a class path} of your <em>JVM</em> application. {@link PolyglotEngine} orchestrates these
 * languages, manages exchange of objects and calls among them. While it may happen that there is
 * just one activated language inside of a {@link PolyglotEngine}, the greatest strength of
 * {@link PolyglotEngine} is in inter-operability between all Truffle languages. There is 1:N
 * mapping between {@link PolyglotEngine} and {@link TruffleLanguage Truffle language
 * implementations}.
 *
 * <h2>Usage</h2>
 *
 * <p>
 * Use {@link #buildNew()} to create a new isolated portal ready for execution of various languages.
 * All the languages in a single portal see each others exported global symbols and can cooperate.
 * Use {@link #buildNew()} multiple times to create different, isolated environments that are
 * completely separated from each other.
 * <p>
 * Once instantiated use {@link #eval(com.oracle.truffle.api.source.Source)} with a reference to a
 * file or URL or directly pass code snippet into the virtual machine via
 * {@link #eval(com.oracle.truffle.api.source.Source)}. Support for individual languages is
 * initialized on demand - e.g. once a file of certain MIME type is about to be processed, its
 * appropriate engine (if found), is initialized. Once an engine gets initialized, it remains so,
 * until the virtual machine is garbage collected.
 * <p>
 * For using a {@link TruffleLanguage language} with a custom setup or configuration, the necessary
 * parameters should be communicated to the {@link PolyglotEngine} - either via
 * {@link PolyglotEngine.Builder#config} as exposed by the language implementation or by evaluating
 * appropriate "prelude" scripts via {@link PolyglotEngine.Language#eval}. Another possibility is to
 * pre-register various {@link PolyglotEngine.Builder#globalSymbol global objects} and make them
 * available to the {@link PolyglotEngine}. Configuration parameters are obtained for instance by
 * parsing command line arguments.
 * <p>
 * The engine is single-threaded and tries to enforce that. It records the thread it has been
 * {@link Builder#build() created} by and checks that all subsequent calls are coming from the same
 * thread. There is 1:1 mapping between {@link PolyglotEngine} and a thread that can tell it what to
 * do.
 *
 * @since 0.9
 */
@SuppressWarnings({"rawtypes", "deprecation"})
public class PolyglotEngine {
    static final boolean JAVA_INTEROP_ENABLED = !TruffleOptions.AOT;
    static final Logger LOG = Logger.getLogger(PolyglotEngine.class.getName());
    private static final SPIAccessor SPI = new SPIAccessor();
    private final Thread initThread;
    private final Executor executor;
    private final Map<String, Language> langs;
    private final InputStream in;
    private final OutputStream err;
    private final OutputStream out;
    private final EventConsumer<?>[] handlers;
    private final Map<String, Object> globals;
    private final Object instrumenter; // old instrumentation
    private final Object instrumentationHandler; // new instrumentation
    private final Map<String, Instrument> instruments;
    private final List<Object[]> config;
    private final Object[] debugger = {null};
    private final ContextStore context;
    private volatile boolean disposed;

    static final boolean JDK8OrEarlier = System.getProperty("java.specification.version").compareTo("1.9") < 0;

    static {
        try {
            // We need to ensure that the Instrumentation class is loaded so accessors are created
            // properly.
            Class.forName(TruffleInstrument.class.getName(), true, TruffleInstrument.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Private & temporary only constructor.
     */
    PolyglotEngine() {
        assertNoTruffle();
        this.initThread = null;
        this.in = null;
        this.err = null;
        this.out = null;
        this.langs = null;
        this.handlers = null;
        this.globals = null;
        this.executor = null;
        this.instrumenter = null;
        this.instrumentationHandler = null;
        this.instruments = null;
        this.config = null;
        this.context = null;
    }

    /**
     * Real constructor used from the builder.
     */
    PolyglotEngine(Executor executor, Map<String, Object> globals, OutputStream out, OutputStream err, InputStream in, EventConsumer<?>[] handlers, List<Object[]> config) {
        assertNoTruffle();
        this.executor = executor;
        this.out = out;
        this.err = err;
        this.in = in;
        this.handlers = handlers;
        this.initThread = Thread.currentThread();
        this.globals = new HashMap<>(globals);
        this.instrumenter = null; // SPI.createInstrumenter(this);
        this.config = config;
        // this.debugger = SPI.createDebugger(this, this.instrumenter);
        // new instrumentation
        this.instrumentationHandler = Access.INSTRUMENT.createInstrumentationHandler(this, out, err, in);
        Map<String, Language> map = new HashMap<>();
        /* We want to create a language instance but per LanguageCache and not per mime type. */
        Set<LanguageCache> uniqueCaches = new HashSet<>(LanguageCache.languages().values());
        for (LanguageCache languageCache : uniqueCaches) {
            Language newLanguage = new Language(languageCache);
            for (String mimeType : newLanguage.getMimeTypes()) {
                map.put(mimeType, newLanguage);
            }
        }
        this.langs = map;
        this.instruments = createAndAutostartDescriptors(InstrumentCache.load(JDK8OrEarlier ? getClass().getClassLoader() : null));
        this.context = ExecutionImpl.createStore(this);
    }

    private Map<String, Instrument> createAndAutostartDescriptors(List<InstrumentCache> instrumentCaches) {
        Map<String, Instrument> instr = new LinkedHashMap<>();
        for (InstrumentCache cache : instrumentCaches) {
            Instrument instrument = new Instrument(cache);
            instr.put(cache.getId(), instrument);
        }
        return Collections.unmodifiableMap(instr);
    }

    /**
     * Creation of new Truffle virtual machine. Use the {@link Builder} methods to configure your
     * virtual machine and then create one using {@link Builder#build()}:
     *
     * <pre>
     * {@link PolyglotEngine} vm = {@link PolyglotEngine}.{@link PolyglotEngine#buildNew() buildNew()}
     *     .{@link Builder#setOut(java.io.OutputStream) setOut}({@link OutputStream yourOutput})
     *     .{@link Builder#setErr(java.io.OutputStream) setErr}({@link OutputStream yourOutput})
     *     .{@link Builder#setIn(java.io.InputStream) setIn}({@link InputStream yourInput})
     *     .{@link Builder#build() build()};
     * </pre>
     *
     * It searches for {@link Registration languages registered} in the system class loader and
     * makes them available for later evaluation via
     * {@link #eval(com.oracle.truffle.api.source.Source)} method.
     *
     * @return new builder to create isolated polyglot engine with pre-registered languages
     * @since 0.10
     */
    public static PolyglotEngine.Builder newBuilder() {
        // making Builder non-static inner class is a
        // nasty trick to avoid the Builder class to appear
        // in Javadoc next to PolyglotEngine class
        PolyglotEngine vm = new PolyglotEngine();
        return vm.new Builder();
    }

    /**
     * @return new builder
     * @deprecated use {@link #newBuilder()}
     * @since 0.9
     */
    @Deprecated
    public static PolyglotEngine.Builder buildNew() {
        return newBuilder();
    }

    /**
     * Builder for a new {@link PolyglotEngine}. Call various configuration methods in a chain and
     * at the end create new {@link PolyglotEngine virtual machine}:
     *
     * <pre>
     * {@link PolyglotEngine} vm = {@link PolyglotEngine}.{@link PolyglotEngine#buildNew() buildNew()}
     *     .{@link Builder#setOut(java.io.OutputStream) setOut}({@link OutputStream yourOutput})
     *     .{@link Builder#setErr(java.io.OutputStream) setErr}({@link OutputStream yourOutput})
     *     .{@link Builder#setIn(java.io.InputStream) setIn}({@link InputStream yourInput})
     *     .{@link Builder#build() build()};
     * </pre>
     *
     * @since 0.9
     */
    public class Builder {
        private OutputStream out;
        private OutputStream err;
        private InputStream in;
        private final List<EventConsumer<?>> handlers = new ArrayList<>();
        private final Map<String, Object> globals = new HashMap<>();
        private Executor executor;
        private List<Object[]> arguments;

        Builder() {
        }

        /**
         * Changes the default output for languages running in <em>to be created</em>
         * {@link PolyglotEngine virtual machine}. The default is to use {@link System#out}.
         *
         * @param os the stream to use as output
         * @return instance of this builder
         * @since 0.9
         */
        public Builder setOut(OutputStream os) {
            out = os;
            return this;
        }

        /**
         * Changes the error output for languages running in <em>to be created</em>
         * {@link PolyglotEngine virtual machine}. The default is to use {@link System#err}.
         *
         * @param os the stream to use as output
         * @return instance of this builder
         * @since 0.9
         */
        public Builder setErr(OutputStream os) {
            err = os;
            return this;
        }

        /**
         * Changes the default input for languages running in <em>to be created</em>
         * {@link PolyglotEngine virtual machine}. The default is to use {@link System#in}.
         *
         * @param is the stream to use as input
         * @return instance of this builder
         * @since 0.9
         */
        public Builder setIn(InputStream is) {
            in = is;
            return this;
        }

        /**
         * Registers another instance of {@link EventConsumer} into the to be created
         * {@link PolyglotEngine}.
         *
         * @param handler the handler to register
         * @return instance of this builder
         * @since 0.9
         */
        public Builder onEvent(EventConsumer<?> handler) {
            Objects.requireNonNull(handler);
            handlers.add(handler);
            return this;
        }

        /**
         * Provide configuration data to initialize the {@link PolyglotEngine} for a specific
         * language. These arguments {@link com.oracle.truffle.api.TruffleLanguage.Env#getConfig()
         * can be used by the language} to initialize and configure their
         * {@link com.oracle.truffle.api.TruffleLanguage#createContext(com.oracle.truffle.api.TruffleLanguage.Env)
         * initial execution state} correctly.
         *
         * {@link com.oracle.truffle.api.vm.PolyglotEngineSnippets#initializeWithParameters}
         *
         * If the same key is specified multiple times for the same language, the previous values
         * are replaced and just the last one remains.
         *
         * @param mimeType identification of the language for which the arguments are - if the
         *            language declares multiple MIME types, any of them can be used
         *
         * @param key to identify a language-specific configuration element
         * @param value to parameterize initial state of a language
         * @return instance of this builder
         * @since 0.11
         */
        public Builder config(String mimeType, String key, Object value) {
            if (this.arguments == null) {
                this.arguments = new ArrayList<>();
            }
            this.arguments.add(new Object[]{mimeType, key, value});
            return this;
        }

        /**
         * Adds global named symbol into the configuration of to-be-built {@link PolyglotEngine}.
         * This symbol will be accessible to all languages via
         * {@link Env#importSymbol(java.lang.String)} and will take precedence over
         * {@link TruffleLanguage#findExportedSymbol symbols exported by languages itself}. Repeated
         * use of <code>globalSymbol</code> is possible; later definition of the same name overrides
         * the previous one.
         *
         * @param name name of the symbol to register
         * @param obj value of the object - expected to be primitive wrapper, {@link String} or
         *            <code>TruffleObject</code> for mutual inter-operability. If the object isn't
         *            of the previous types, the system tries to wrap it using
         *            {@link JavaInterop#asTruffleObject(java.lang.Object)}, if available
         * @return instance of this builder
         * @see PolyglotEngine#findGlobalSymbol(java.lang.String)
         * @throws IllegalArgumentException if the object isn't of primitive type and cannot be
         *             converted to {@link TruffleObject}
         * @since 0.9
         */
        public Builder globalSymbol(String name, Object obj) {
            final Object truffleReady;
            if (obj instanceof TruffleObject || obj instanceof Number || obj instanceof String || obj instanceof Character || obj instanceof Boolean) {
                truffleReady = obj;
            } else {
                if (JAVA_INTEROP_ENABLED) {
                    truffleReady = JavaInterop.asTruffleObject(obj);
                } else {
                    throw new IllegalArgumentException();
                }
            }
            globals.put(name, truffleReady);
            return this;
        }

        /**
         * Provides own executor for running {@link PolyglotEngine} scripts. By default
         * {@link PolyglotEngine#eval(com.oracle.truffle.api.source.Source)} and
         * {@link Value#invoke(java.lang.Object, java.lang.Object[])} are executed synchronously in
         * the calling thread. Sometimes, however it is more beneficial to run them asynchronously -
         * the easiest way to do so is to provide own executor when configuring the {
         * {@link #executor(java.util.concurrent.Executor) the builder}. The executor is expected to
         * execute all {@link Runnable runnables} passed into its
         * {@link Executor#execute(java.lang.Runnable)} method in the order they arrive and in a
         * single (yet arbitrary) thread.
         *
         * @param executor the executor to use for internal execution inside the {@link #build() to
         *            be created} {@link PolyglotEngine}
         * @return instance of this builder
         * @since 0.9
         */
        @SuppressWarnings("hiding")
        public Builder executor(Executor executor) {
            this.executor = executor;
            return this;
        }

        /**
         * Creates the {@link PolyglotEngine Truffle virtual machine}. The configuration is taken
         * from values passed into configuration methods in this class.
         *
         * @return new, isolated virtual machine with pre-registered languages
         * @since 0.9
         */
        public PolyglotEngine build() {
            assertNoTruffle();
            if (out == null) {
                out = System.out;
            }
            if (err == null) {
                err = System.err;
            }
            if (in == null) {
                in = System.in;
            }
            return new PolyglotEngine(executor, globals, out, err, in, handlers.toArray(new EventConsumer[0]), arguments);
        }
    }

    /**
     * Descriptions of languages supported in this Truffle virtual machine.
     *
     * @return an immutable map with keys being MIME types and values the {@link Language
     *         descriptions} of associated languages
     * @since 0.9
     */
    public Map<String, ? extends Language> getLanguages() {
        return Collections.unmodifiableMap(langs);
    }

    /**
     * Returns all instruments <em>loaded</em> in this this {@linkplain PolyglotEngine engine},
     * whether or not they are currently enabled. Some instruments are enabled automatically at
     * startup.
     *
     * @return the set of currently loaded instruments
     * @since 0.9
     */
    public Map<String, Instrument> getInstruments() {
        return instruments;
    }

    /**
     * Evaluates provided source. Chooses language registered for a particular
     * {@link Source#getMimeType() MIME type} (throws {@link IOException} if there is none). The
     * language is then allowed to parse and execute the source.
     *
     * @param source code snippet to execute
     * @return a {@link Value} object that holds result of an execution, never <code>null</code>
     * @throws IOException thrown to signal errors while processing the code
     * @since 0.9
     */
    public Value eval(Source source) throws IOException {
        assertNoTruffle();
        String mimeType = source.getMimeType();
        assert checkThread();
        Language l = langs.get(mimeType);
        if (l == null) {
            throw new IOException("No language for MIME type " + mimeType + " found. Supported types: " + langs.keySet());
        }
        return eval(l, source);
    }

    /**
     * Dispose instance of this engine. A user can explicitly
     * {@link TruffleLanguage#disposeContext(java.lang.Object) dispose all resources} allocated by
     * the languages active in this engine, when it is known the system is not going to be used in
     * the future.
     * <p>
     * Calling any other method of this class after the dispose has been done yields an
     * {@link IllegalStateException}.
     *
     * @since 0.9
     */
    public void dispose() {
        assert checkThread();
        assertNoTruffle();
        disposed = true;
        ComputeInExecutor<Void> compute = new ComputeInExecutor<Void>(executor) {
            @Override
            protected Void compute() throws IOException {
                for (Language language : getLanguages().values()) {
                    TruffleLanguage<?> impl = language.getImpl(false);
                    if (impl != null) {
                        final Env env = language.getEnv(false, true);
                        if (env != null) {
                            try {
                                Access.LANGS.dispose(impl, env);
                            } catch (Exception | Error ex) {
                                LOG.log(Level.SEVERE, "Error disposing " + impl, ex);
                            }
                        }
                    }
                }

                for (Instrument instrument : instruments.values()) {
                    try {
                        /*
                         * TODO (chumer): ideally no cleanup is required for disposing
                         * PolyglotEngine if no ASTs are shared between instances. the anything
                         * might be shared assumption invalidates this optimization we should have a
                         * way to find out if a CallTarget/RootNode is shared across PolyglotEngine
                         * instances.
                         */
                        instrument.setEnabledImpl(false, false);
                    } catch (Exception | Error ex) {
                        LOG.log(Level.SEVERE, "Error disposing " + instrument, ex);
                    }
                }

                return null;
            }
        };
        try {
            compute.perform();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private Value eval(final Language l, final Source s) throws IOException {
        final TruffleLanguage[] lang = {null};
        if (executor == null) {
            Object value = evalImpl(lang, s, l);
            return new DirectValue(lang, value);
        }

        ComputeInExecutor<Object> compute = new ComputeInExecutor<Object>(executor) {
            @Override
            protected Object compute() throws IOException {
                return evalImpl(lang, s, l);
            }
        };
        compute.perform();
        return new ExecutorValue(lang, compute);
    }

    Language createLanguage(Map.Entry<String, LanguageCache> en) {
        return new Language(en.getValue());
    }

    ContextStore context() {
        return context;
    }

    Object[] debugger() {
        return debugger;
    }

    @SuppressWarnings("try")
    private Object evalImpl(TruffleLanguage<?>[] fillLang, Source s, Language l) throws IOException {
        ContextStore prev = ExecutionImpl.executionStarted(context);
        try {
            Access.DEBUG.executionStarted(PolyglotEngine.this, -1, debugger, s);
            TruffleLanguage<?> langImpl = l.getImpl(true);
            fillLang[0] = langImpl;
            return Access.LANGS.eval(langImpl, s, l.cache);
        } finally {
            ExecutionImpl.executionEnded(prev);
            Access.DEBUG.executionEnded(PolyglotEngine.this, debugger);
        }
    }

    @SuppressWarnings({"try"})
    final Object invokeForeign(final Node foreignNode, VirtualFrame frame, final TruffleObject receiver) throws IOException {
        assertNoTruffle();
        Object res;
        CompilerAsserts.neverPartOfCompilation();
        if (executor == null) {
            ContextStore prev = ExecutionImpl.executionStarted(context);
            try {
                Access.DEBUG.executionStarted(PolyglotEngine.this, -1, debugger, null);
                final Object[] args = ForeignAccess.getArguments(frame).toArray();
                res = ForeignAccess.execute(foreignNode, frame, receiver, args);
            } finally {
                ExecutionImpl.executionEnded(prev);
                Access.DEBUG.executionEnded(PolyglotEngine.this, debugger);
            }
        } else {
            res = invokeForeignOnExecutor(foreignNode, frame, receiver);
        }
        if (res instanceof TruffleObject) {
            return new EngineTruffleObject(this, (TruffleObject) res);
        } else {
            return res;
        }
    }

    static void assertNoTruffle() {
        CompilerAsserts.neverPartOfCompilation("Methods of PolyglotEngine must not be compiled by Truffle. Use Truffle interoperability or a @TruffleBoundary instead.");
    }

    @TruffleBoundary
    private Object invokeForeignOnExecutor(final Node foreignNode, VirtualFrame frame, final TruffleObject receiver) throws IOException {
        final MaterializedFrame materialized = frame.materialize();
        ComputeInExecutor<Object> compute = new ComputeInExecutor<Object>(executor) {
            @SuppressWarnings("try")
            @Override
            protected Object compute() throws IOException {
                ContextStore prev = ExecutionImpl.executionStarted(context);
                try {
                    Access.DEBUG.executionStarted(PolyglotEngine.this, -1, debugger, null);
                    final Object[] args = ForeignAccess.getArguments(materialized).toArray();
                    RootNode node = SymbolInvokerImpl.createTemporaryRoot(TruffleLanguage.class, foreignNode, receiver);
                    final CallTarget target = Truffle.getRuntime().createCallTarget(node);
                    return target.call(args);
                } finally {
                    ExecutionImpl.executionEnded(prev);
                    Access.DEBUG.executionEnded(PolyglotEngine.this, debugger);
                }
            }
        };
        return compute.get();
    }

    /**
     * Looks global symbol provided by one of initialized languages up. First of all execute your
     * program via one of your {@link #eval(com.oracle.truffle.api.source.Source)} and then look
     * expected symbol up using this method.
     * <p>
     * The names of the symbols are language dependent, but for example the Java language bindings
     * follow the specification for method references:
     * <ul>
     * <li>"java.lang.Exception::new" is a reference to constructor of {@link Exception}
     * <li>"java.lang.Integer::valueOf" is a reference to static method in {@link Integer} class
     * </ul>
     * Once an symbol is obtained, it remembers values for fast access and is ready for being
     * invoked.
     *
     * @param globalName the name of the symbol to find
     * @return found symbol or <code>null</code> if it has not been found
     * @since 0.9
     */
    public Value findGlobalSymbol(final String globalName) {
        assert checkThread();
        assertNoTruffle();
        final TruffleLanguage<?>[] lang = {null};
        ComputeInExecutor<Object> compute = new ComputeInExecutor<Object>(executor) {
            @Override
            protected Object compute() throws IOException {
                Object obj = globals.get(globalName);
                if (obj == null) {
                    for (Language dl : langs.values()) {
                        TruffleLanguage.Env env = dl.getEnv(false);
                        if (env == null) {
                            continue;
                        }
                        obj = Access.LANGS.findExportedSymbol(env, globalName, true);
                        if (obj != null) {
                            lang[0] = dl.getImpl(true);
                            break;
                        }
                    }
                }
                if (obj == null) {
                    for (Language dl : langs.values()) {
                        TruffleLanguage.Env env = dl.getEnv(false);
                        if (env == null) {
                            continue;
                        }
                        obj = Access.LANGS.findExportedSymbol(env, globalName, true);
                        if (obj != null) {
                            lang[0] = dl.getImpl(true);
                            break;
                        }
                    }
                }
                return obj;
            }
        };
        try {
            compute.perform();
            if (compute.get() == null) {
                return null;
            }
        } catch (IOException ex) {
            // OK, go on
        }
        return new ExecutorValue(lang, compute);
    }

    private boolean checkThread() {
        if (initThread != Thread.currentThread()) {
            throw new IllegalStateException("PolyglotEngine created on " + initThread.getName() + " but used on " + Thread.currentThread().getName());
        }
        if (disposed) {
            throw new IllegalStateException("Engine has already been disposed");
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    void dispatch(Object ev, int type) {
        if (type == Accessor.EngineSupport.EXECUTION_EVENT) {
            dispatchExecutionEvent(ev);
        }
        if (type == Accessor.EngineSupport.SUSPENDED_EVENT) {
            dispatchSuspendedEvent(ev);
        }
        Class clazz = ev.getClass();
        dispatch(clazz, ev);
    }

    /**
     * just to make javac happy.
     *
     * @param event
     */
    void dispatchSuspendedEvent(Object event) {
    }

    /**
     * just to make javac happy.
     *
     * @param event
     */
    void dispatchExecutionEvent(Object event) {
    }

    @SuppressWarnings("unchecked")
    <Event> void dispatch(Class<Event> type, Event event) {
        for (EventConsumer handler : handlers) {
            if (handler.type == type) {
                handler.on(event);
            }
        }
    }

    /**
     * A future value wrapper. A user level wrapper around values returned by evaluation of various
     * {@link PolyglotEngine} functions like
     * {@link PolyglotEngine#findGlobalSymbol(java.lang.String)} and
     * {@link PolyglotEngine#eval(com.oracle.truffle.api.source.Source)} or a value returned by
     * {@link #invoke(java.lang.Object, java.lang.Object...) a subsequent execution}. In case the
     * {@link PolyglotEngine} has been initialized for
     * {@link Builder#executor(java.util.concurrent.Executor) asynchronous execution}, the
     * {@link Value} represents a future - i.e., it is returned immediately, leaving the execution
     * running on behind.
     *
     * @since 0.9
     */
    public abstract class Value {
        private final TruffleLanguage<?>[] language;
        private CallTarget target;

        Value(TruffleLanguage<?>[] language) {
            this.language = language;
        }

        abstract boolean isDirect();

        abstract Object value() throws IOException;

        /**
         * Obtains the object represented by this symbol. The <em>raw</em> object can either be a
         * wrapper about primitive type (e.g. {@link Number}, {@link String}, {@link Character},
         * {@link Boolean}) or a <em>TruffleObject</em> representing more complex object from a
         * language. The method can return <code>null</code>.
         *
         * @return the object or <code>null</code>
         * @throws IOException in case it is not possible to obtain the value of the object
         * @since 0.9
         */
        public Object get() throws IOException {
            assertNoTruffle();
            Object result = waitForSymbol();
            if (executor != null && result instanceof TruffleObject) {
                return new EngineTruffleObject(PolyglotEngine.this, (TruffleObject) result);
            } else {
                return result;
            }
        }

        /**
         * Obtains Java view of the object represented by this symbol. The method basically
         * delegates to
         * {@link JavaInterop#asJavaObject(java.lang.Class, com.oracle.truffle.api.interop.TruffleObject)}
         * . The method handles primitive types (like {@link Number}, etc.) by casting and returning
         * them. When a {@link String}.<code>class</code> is requested, the method let's the
         * language that produced the value to do the
         * {@link TruffleLanguage#toString(java.lang.Object, java.lang.Object) necessary formating}.
         *
         * @param <T> the type of the view one wants to obtain
         * @param representation the class of the view interface (it has to be an interface)
         * @return instance of the view wrapping the object of this symbol
         * @throws IOException in case it is not possible to obtain the value of the object
         * @throws ClassCastException if the value cannot be converted to desired view
         * @since 0.9
         */
        public <T> T as(final Class<T> representation) throws IOException {
            assertNoTruffle();
            final Object obj = get();
            if (obj instanceof EngineTruffleObject) {
                EngineTruffleObject eto = (EngineTruffleObject) obj;
                if (representation.isInstance(eto.getDelegate())) {
                    return representation.cast(eto.getDelegate());
                }
            }
            if (representation == String.class) {
                final Class<? extends TruffleLanguage> clazz = language[0].getClass();
                Object unwrapped = obj;
                while (unwrapped instanceof EngineTruffleObject) {
                    unwrapped = ((EngineTruffleObject) obj).getDelegate();
                }
                return representation.cast(Access.LANGS.toString(language[0], findEnv(clazz), unwrapped));
            }
            if (representation.isInstance(obj)) {
                return representation.cast(obj);
            }
            if (JAVA_INTEROP_ENABLED) {
                return JavaInterop.asJavaObject(representation, (TruffleObject) obj);
            }
            throw new ClassCastException("Value cannot be represented as " + representation.getName());
        }

        /**
         * Invokes the symbol. If the symbol represents a function, then it should be invoked with
         * provided arguments. If the symbol represents a field, then first argument (if provided)
         * should set the value to the field; the return value should be the actual value of the
         * field when the <code>invoke</code> method returns.
         *
         * @param thiz this/self in language that support such concept; use <code>null</code> to let
         *            the language use default this/self or ignore the value
         * @param args arguments to pass when invoking the symbol
         * @return symbol wrapper around the value returned by invoking the symbol, never
         *         <code>null</code>
         * @throws IOException signals problem during execution
         * @since 0.9
         */
        @Deprecated
        public Value invoke(final Object thiz, final Object... args) throws IOException {
            return execute(args);
        }

        /**
         * Executes the symbol. If the symbol represents a function, then it should be invoked with
         * provided arguments. If the symbol represents a field, then first argument (if provided)
         * should set the value to the field; the return value should be the actual value of the
         * field when the <code>invoke</code> method returns.
         *
         * @param args arguments to pass when invoking the symbol; either wrappers of Java primitive
         *            types (e.g. {@link java.lang.Byte}, {@link java.lang.Short},
         *            {@link java.lang.Integer}, {@link java.lang.Long}, {@link java.lang.Float},
         *            {@link java.lang.Double}, {@link java.lang.Character},
         *            {@link java.lang.Boolean}, and {@link java.lang.String}) or a
         *            {@link TruffleObject object created} by one of the languages)
         *
         * @return symbol wrapper around the value returned by invoking the symbol, never
         *         <code>null</code>
         * @throws IOException signals problem during execution
         * @since 0.9
         */
        public Value execute(final Object... args) throws IOException {
            if (isDirect()) {
                Object ret = executeDirect(args);
                return new DirectValue(language, ret);
            }
            assertNoTruffle();

            get();
            ComputeInExecutor<Object> invokeCompute = new ComputeInExecutor<Object>(executor) {
                @SuppressWarnings("try")
                @Override
                protected Object compute() throws IOException {
                    return executeDirect(args);
                }
            };
            invokeCompute.perform();
            return new ExecutorValue(language, invokeCompute);
        }

        @SuppressWarnings("try")
        private Object executeDirect(Object[] args) throws IOException {
            if (target == null) {
                target = SymbolInvokerImpl.createCallTarget(language[0], PolyglotEngine.this, value());
            }
            return target.call(args);
        }

        private Object waitForSymbol() throws IOException {
            assertNoTruffle();
            assert checkThread();
            return value();
        }
    }

    private class DirectValue extends Value {
        private final Object value;

        DirectValue(TruffleLanguage<?>[] language, Object value) {
            super(language);
            this.value = value;
        }

        @Override
        boolean isDirect() {
            return true;
        }

        @Override
        Object value() {
            return value;
        }

        @Override
        public String toString() {
            return "PolyglotEngine.Value[value=" + value + ",computed=true,exception=null]";
        }
    }

    private class ExecutorValue extends Value {
        private final ComputeInExecutor<Object> compute;

        ExecutorValue(TruffleLanguage<?>[] language, ComputeInExecutor<Object> compute) {
            super(language);
            this.compute = compute;
        }

        @Override
        boolean isDirect() {
            return false;
        }

        @Override
        Object value() throws IOException {
            return compute.get();
        }

        @Override
        public String toString() {
            return "PolyglotEngine.Value[" + compute + "]";
        }
    }

    /**
     * Handle for an installed {@linkplain TruffleInstrument instrument}: a client of a running
     * {@linkplain PolyglotEngine engine} that can observe and inject behavior into interpreters
     * written using the Truffle framework. The handle provides access to the instrument's metadata
     * and allows the instrument to be {@linkplain Instrument#setEnabled(boolean) enabled/disabled}.
     *
     * @see PolyglotEngine#getInstruments()
     * @since 0.9
     */
    public final class Instrument {

        private final InstrumentCache info;
        private final Object instrumentLock = new Object();
        private volatile boolean enabled;

        Instrument(InstrumentCache cache) {
            this.info = cache;
        }

        /**
         * @return the id of the instrument
         * @since 0.9
         */
        public String getId() {
            return info.getId();
        }

        /**
         * @return a human readable name of the installed instrument.
         * @since 0.9
         */
        public String getName() {
            return info.getName();
        }

        /**
         * @return the version of the installed instrument.
         * @since 0.9
         */
        public String getVersion() {
            return info.getVersion();
        }

        InstrumentCache getCache() {
            return info;
        }

        /**
         * @return <code>true</code> if the underlying instrument is enabled else <code>false</code>
         * @since 0.9
         */
        public boolean isEnabled() {
            return enabled;
        }

        /**
         * Lookup additional service provided by the instrument. Here is an example how to query for
         * a hypothetical <code>DebuggerController</code>: {@codesnippet DebuggerExampleTest}
         *
         * @param <T> the type of the service
         * @param type class of the service that is being requested
         * @return instance of requested type, or <code>null</code> if no such service is available
         *         for the instrument
         * @since 0.9
         */
        public <T> T lookup(Class<T> type) {
            return Access.INSTRUMENT.getInstrumentationHandlerService(instrumentationHandler, this, type);
        }

        /**
         * Enables/disables the installed instrument in the engine.
         *
         * @param enabled <code>true</code> to enable <code>false</code> to disable
         * @since 0.9
         */
        public void setEnabled(final boolean enabled) {
            if (disposed) {
                throw new IllegalStateException("Engine has already been disposed");
            }
            if (executor == null) {
                setEnabledImpl(enabled, true);
            } else {
                ComputeInExecutor<Void> compute = new ComputeInExecutor<Void>(executor) {
                    @Override
                    protected Void compute() throws IOException {
                        setEnabledImpl(enabled, true);
                        return null;
                    }
                };
                try {
                    compute.perform();
                } catch (IOException ex) {
                    throw new IllegalStateException(ex);
                }
            }
        }

        void setEnabledImpl(final boolean enabled, boolean cleanup) {
            synchronized (instrumentLock) {
                if (this.enabled != enabled) {
                    if (enabled) {
                        Access.INSTRUMENT.addInstrument(instrumentationHandler, this, getCache().getInstrumentationClass());
                    } else {
                        Access.INSTRUMENT.disposeInstrument(instrumentationHandler, this, cleanup);
                    }
                    this.enabled = enabled;
                }
            }
        }

        /** @since 0.9 */
        @Override
        public String toString() {
            return "Instrument [id=" + getId() + ", name=" + getName() + ", version=" + getVersion() + ", enabled=" + enabled + "]";
        }
    }

    /**
     * Description of a language registered in {@link PolyglotEngine Truffle virtual machine}.
     * Languages are registered by {@link Registration} annotation which stores necessary
     * information into a descriptor inside of the language's JAR file. When a new
     * {@link PolyglotEngine} is created, it reads all available descriptors and creates
     * {@link Language} objects to represent them. One can obtain a {@link #getName() name} or list
     * of supported {@link #getMimeTypes() MIME types} for each language. The actual language
     * implementation is not initialized until
     * {@link PolyglotEngine#eval(com.oracle.truffle.api.source.Source) a code is evaluated} in it.
     *
     * @since 0.9
     */
    public class Language {
        private final Map<Source, CallTarget> cache;
        private final LanguageCache info;
        private TruffleLanguage.Env env;

        Language(LanguageCache info) {
            this.cache = new WeakHashMap<>();
            this.info = info;
        }

        /**
         * MIME types recognized by the language.
         *
         * @return returns immutable set of recognized MIME types
         * @since 0.9
         */
        public Set<String> getMimeTypes() {
            return info.getMimeTypes();
        }

        /**
         * Human readable name of the language. Think of C, Ruby, JS, etc.
         *
         * @return string giving the language a name
         * @since 0.9
         */
        public String getName() {
            return info.getName();
        }

        /**
         * Name of the language version.
         *
         * @return string specifying the language version
         * @since 0.9
         */
        public String getVersion() {
            return info.getVersion();
        }

        /**
         * Evaluates provided source. Ignores the particular {@link Source#getMimeType() MIME type}
         * and forces evaluation in the context of <code>this</code> language.
         *
         * @param source code snippet to execute
         * @return a {@link Value} object that holds result of an execution, never <code>null</code>
         * @throws IOException thrown to signal errors while processing the code
         * @since 0.9
         */
        public Value eval(Source source) throws IOException {
            assertNoTruffle();
            assert checkThread();
            return PolyglotEngine.this.eval(this, source);
        }

        /**
         * Returns value representing global object of the language.
         * <p>
         * The object is expected to be <code>TruffleObject</code> (e.g. a native object from the
         * other language) but technically it can be one of Java primitive wrappers ({@link Integer}
         * , {@link Double}, {@link Short}, etc.).
         *
         * @return the global object or <code>null</code> if the language does not support such
         *         concept
         * @since 0.9
         */
        @SuppressWarnings("try")
        public Value getGlobalObject() {
            assert checkThread();
            ContextStore prev = ExecutionImpl.executionStarted(context);
            try {
                Object res = Access.LANGS.languageGlobal(getEnv(true));
                if (res == null) {
                    return null;
                }
                return new DirectValue(new TruffleLanguage[]{info.getImpl(true)}, res);
            } finally {
                ExecutionImpl.executionEnded(prev);
            }
        }

        TruffleLanguage<?> getImpl(boolean create) {
            getEnv(create);
            TruffleLanguage<?> impl = info.getImpl(false);
            return impl;
        }

        private Map<String, Object> getArgumentsForLanguage() {
            if (config == null) {
                return Collections.emptyMap();
            }

            Map<String, Object> forLanguage = new HashMap<>();
            for (Object[] mimeKeyValue : config) {
                if (getMimeTypes().contains(mimeKeyValue[0])) {
                    forLanguage.put((String) mimeKeyValue[1], mimeKeyValue[2]);
                }
            }
            return Collections.unmodifiableMap(forLanguage);
        }

        TruffleLanguage.Env getEnv(boolean create) {
            return getEnv(create, false);
        }

        TruffleLanguage.Env getEnv(boolean create, boolean clear) {
            TruffleLanguage.Env tmp = env;
            if (tmp == null && create) {
                env = tmp = Access.LANGS.attachEnv(PolyglotEngine.this, info.getImpl(true), out, err, in, instrumenter, getArgumentsForLanguage());
            }
            if (clear) {
                env = null;
            }
            return tmp;
        }

        /** @since 0.9 */
        @Override
        public String toString() {
            return "[" + getName() + "@ " + getVersion() + " for " + getMimeTypes() + "]";
        }

    } // end of Language

    //
    // Accessor helper methods
    //

    TruffleLanguage<?> findLanguage(Class<? extends TruffleLanguage> languageClazz) {
        for (Map.Entry<String, Language> entrySet : langs.entrySet()) {
            Language languageDescription = entrySet.getValue();
            final TruffleLanguage<?> impl = languageDescription.getImpl(false);
            if (languageClazz.isInstance(impl)) {
                return impl;
            }
        }
        return null;
    }

    TruffleLanguage<?> findLanguage(String mimeType) {
        Language languageDescription = this.langs.get(mimeType);
        if (languageDescription != null) {
            return languageDescription.getImpl(true);
        }
        return null;
    }

    Env findEnv(Class<? extends TruffleLanguage> languageClazz) {
        for (Map.Entry<String, Language> entrySet : langs.entrySet()) {
            Language languageDescription = entrySet.getValue();
            Env env = languageDescription.getEnv(false);
            if (env != null && languageClazz.isInstance(languageDescription.getImpl(false))) {
                return env;
            }
        }
        throw new IllegalStateException("Cannot find language " + languageClazz + " among " + langs);
    }

    static class Access {
        static final Accessor.LanguageSupport LANGS = SPIAccessor.langs();
        static final Accessor.InstrumentSupport INSTRUMENT = SPIAccessor.instrumentAccess();
        static final Accessor.DebugSupport DEBUG = SPIAccessor.debugAccess();
    }

    private static class SPIAccessor extends Accessor {
        static LanguageSupport langs() {
            return SPI.languageSupport();
        }

        static InstrumentSupport instrumentAccess() {
            return SPI.instrumentSupport();
        }

        static DebugSupport debugAccess() {
            return SPI.debugSupport();
        }

        @Override
        protected EngineSupport engineSupport() {
            return new EngineImpl();
        }

        static final class EngineImpl extends EngineSupport {

            @Override
            public boolean isMimeTypeSupported(Object obj, String mimeType) {
                final PolyglotEngine vm = (PolyglotEngine) obj;
                return vm.findLanguage(mimeType) != null;
            }

            @Override
            public Env findEnv(Object obj, Class<? extends TruffleLanguage> languageClass) {
                PolyglotEngine vm = (PolyglotEngine) obj;
                return vm.findEnv(languageClass);
            }

            @Override
            public Env findEnv(Class<? extends TruffleLanguage> languageClass) {
                final PolyglotEngine vm = (PolyglotEngine) ExecutionImpl.findVM();
                if (vm == null) {
                    throw new IllegalStateException("Accessor.findEnv access to vm");
                }
                return vm.findEnv(languageClass);
            }

            @Override
            public void dispatchEvent(Object obj, Object event, int type) {
                PolyglotEngine vm = (PolyglotEngine) obj;
                vm.dispatch(event, type);
            }

            @Override
            public TruffleLanguage<?> findLanguageImpl(Object obj, Class<? extends TruffleLanguage> languageClazz, String mimeType) {
                final PolyglotEngine vm = (PolyglotEngine) (obj == null ? ExecutionImpl.findVM() : obj);
                if (vm == null) {
                    throw new IllegalStateException("Accessor.findLanguageImpl access to vm");
                }
                TruffleLanguage<?> language = null;
                if (languageClazz != null) {
                    language = vm.findLanguage(languageClazz);
                }
                if (language == null && mimeType != null) {
                    language = vm.findLanguage(mimeType);
                }
                if (language == null) {
                    throw new IllegalStateException("Cannot find language " + languageClazz + " with mimeType" + mimeType + " among " + vm.langs);
                }
                return language;
            }

            @Override
            public Object getInstrumenter(Object obj) {
                final PolyglotEngine vm = (PolyglotEngine) (obj == null ? ExecutionImpl.findVM() : obj);
                return vm == null ? null : vm.instrumenter;
            }

            @Override
            public Object getInstrumentationHandler(Object obj) {
                final PolyglotEngine vm = (PolyglotEngine) (obj == null ? ExecutionImpl.findVM() : obj);
                return vm == null ? null : vm.instrumentationHandler;
            }

            @Override
            public Object importSymbol(Object vmObj, TruffleLanguage<?> ownLang, String globalName) {
                PolyglotEngine vm = (PolyglotEngine) vmObj;
                Object g = vm.globals.get(globalName);
                if (g != null) {
                    return g;
                }
                Set<Language> uniqueLang = new LinkedHashSet<>(vm.langs.values());
                for (Language dl : uniqueLang) {
                    TruffleLanguage<?> l = dl.getImpl(false);
                    TruffleLanguage.Env env = dl.getEnv(false);
                    if (l == ownLang || l == null || env == null) {
                        continue;
                    }
                    Object obj = Access.LANGS.findExportedSymbol(env, globalName, true);
                    if (obj != null) {
                        return obj;
                    }
                }
                for (Language dl : uniqueLang) {
                    TruffleLanguage<?> l = dl.getImpl(false);
                    TruffleLanguage.Env env = dl.getEnv(false);
                    if (l == ownLang || l == null || env == null) {
                        continue;
                    }
                    Object obj = Access.LANGS.findExportedSymbol(env, globalName, false);
                    if (obj != null) {
                        return obj;
                    }
                }
                return null;
            }

            @Override
            public <C> FindContextNode<C> createFindContextNode(TruffleLanguage<C> lang) {
                return new FindContextNodeImpl<>(lang);
            }

            @Override
            public void registerDebugger(Object vm, Object debugger) {
                PolyglotEngine engine = (PolyglotEngine) vm;
                assert engine.debugger()[0] == null || engine.debugger()[0] == debugger;
                engine.debugger()[0] = debugger;
            }
        }

    } // end of SPIAccessor
}

class PolyglotEngineSnippets {
    abstract class YourLang extends TruffleLanguage<Object> {
        public static final String MIME_TYPE = "application/my-test-lang";
    }

    public static PolyglotEngine initializeWithParameters() {
        // @formatter:off
        // BEGIN: com.oracle.truffle.api.vm.PolyglotEngineSnippets#initializeWithParameters
        String[] args = {"--kernel", "Kernel.som", "--instrument", "dyn-metrics"};
        PolyglotEngine.Builder builder = PolyglotEngine.newBuilder();
        builder.config(YourLang.MIME_TYPE, "CMD_ARGS", args);
        PolyglotEngine vm = builder.build();
        // END: com.oracle.truffle.api.vm.PolyglotEngineSnippets#initializeWithParameters
        // @formatter:on
        return vm;
    }
}
