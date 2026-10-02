package io.codiqo.core.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.collections4.MultiValuedMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.event.Level;

import io.codiqo.api.ProjectSpec;
import io.codiqo.api.RunArgs;
import io.codiqo.api.code.CodeBlockInfo;
import io.codiqo.api.code.ParsedSources;
import io.codiqo.api.code.PreviousRevision;
import io.codiqo.api.logging.Log;
import io.codiqo.api.logging.LogFactory;
import io.codiqo.util.Fetch;

/**
 * Which method and constructor bodies the index walk reaches. Every type declaration, anonymous class and lambda is a
 * "find boundary" in PMD's Java AST, so a traversal that does not cross them reaches nothing at all — a failure that is
 * silent (zero code units, no error) and would let a whole commit score as if it changed no code.
 */
class JavaLanguageSpecParseTest {
    private static final String SOURCE = """
            package com.example;
            import java.util.concurrent.Callable;
            public class Sample {
                public String top() {
                    return "top";
                }
                Sample() {
                    System.out.println("ctor");
                }
                void empty() {
                }
                static class Nested {
                    void inNested() {
                        System.out.println("nested");
                    }
                }
                Callable<String> anonymous() {
                    return new Callable<String>() {
                        @Override
                        public String call() {
                            return "anon";
                        }
                    };
                }
                Runnable lambda() {
                    return () -> System.out.println("lambda");
                }
                interface Api {
                    void noBody();
                }
            }
            """;

    @TempDir
    Path workTree;

    @Test
    void everyBodyBehindAFindBoundaryIsIndexed() throws Exception {
        File source = write("Sample.java");

        try (JavaLanguageSpec spec = spec()) {
            List<CodeBlockInfo> blocks = spec.parse(mock(ProjectSpec.class), List.of(source)).getBlocks();

            /**
             * the $Nested and $1 owners are the point: inNested lives inside a nested type and call() inside an
             * anonymous class, both find boundaries. empty() has an empty body and noBody() has none at all, so
             * neither is a unit of work.
             */
            assertEquals(
                    Set.of(
                            "com/example/Sample.top()Ljava/lang/String;",
                            "com/example/Sample.<init>()V",
                            "com/example/Sample$Nested.inNested()V",
                            "com/example/Sample.anonymous()Ljava/util/concurrent/Callable;",
                            "com/example/Sample$1.call()Ljava/lang/String;",
                            "com/example/Sample.lambda()Ljava/lang/Runnable;"),
                    blocks.stream().map(CodeBlockInfo::getSignature).collect(Collectors.toSet()));
        }
    }
    @Test
    void aFileOfAnotherLanguageIsSkipped() throws Exception {
        File source = write("Sample.kt");

        try (JavaLanguageSpec spec = spec()) {
            ParsedSources parsed = spec.parse(mock(ProjectSpec.class), List.of(source));

            assertEquals(List.of(), parsed.getBlocks());
            assertEquals(Set.of(), parsed.getParsedFiles());
        }
    }
    @Test
    void reportsTheMethodsAFileNoLongerHas() throws Exception {
        String before = """
                package com.example;

                public class Shrinking {
                    public int kept(int x) {
                        return x + 1;
                    }
                    public void retired(String s) {
                        System.out.println(s);
                    }
                    public Shrinking(int seed) {
                        System.out.println(seed);
                    }
                }
                """;
        String after = """
                package com.example;

                public class Shrinking {
                    public int kept(int x) {
                        return x + 2;
                    }
                }
                """;
        Path source = workTree.resolve("Shrinking.java");
        Files.writeString(source, after);

        try (JavaLanguageSpec spec = spec()) {
            ProjectSpec owner = mock(ProjectSpec.class);
            List<CodeBlockInfo> removed = List.copyOf(spec.parseRemoved(owner, List.of(new PreviousRevision(source.toFile(), "Shrinking.java", before))).values());

            // kept() changed its body, not its signature, so it is no removal
            assertEquals(
                    Set.of("com/example/Shrinking.retired(Ljava/lang/String;)V", "com/example/Shrinking.<init>(I)V"),
                    removed.stream().map(CodeBlockInfo::getSignature).collect(Collectors.toSet()));
            CodeBlockInfo retired = removed.stream().filter(block -> block.getSignature().contains("retired")).findFirst().orElseThrow();
            assertEquals(7, retired.getLocation().getStartLine(), "located in the previous content");
        }
    }
    /**
     * a parse failure and a file with no code units both yield zero blocks; only the parsed-file set tells them apart,
     * and removal detection depends on the difference
     */
    @Test
    void anUnparseableFileIsNotReportedAsParsed() throws Exception {
        Path broken = workTree.resolve("Broken.java");
        Files.writeString(broken, "package com.example;\npublic class Broken {\n    void f() {\n        int x = ;\n    }\n}\n");
        Path fieldsOnly = workTree.resolve("FieldsOnly.java");
        Files.writeString(fieldsOnly, "package com.example;\npublic class FieldsOnly {\n    int x;\n}\n");

        try (JavaLanguageSpec spec = spec()) {
            ParsedSources parsed = spec.parse(mock(ProjectSpec.class), List.of(broken.toFile(), fieldsOnly.toFile()));

            assertEquals(List.of(), parsed.getBlocks());
            assertEquals(Set.of(fieldsOnly.toFile()), parsed.getParsedFiles());
        }
    }
    @Test
    void unparseablePreviousContentReportsNoRemovals() throws Exception {
        Path source = workTree.resolve("Sample.java");
        Files.writeString(source, "package com.example;\npublic class Sample {\n}\n");

        try (JavaLanguageSpec spec = spec()) {
            assertEquals(List.of(), List.copyOf(spec.parseRemoved(mock(ProjectSpec.class),
                    List.of(new PreviousRevision(source.toFile(), "Sample.java", "public class Sample { void f() { int x = ; } }"))).values()));
        }
    }
    /** an executable that lost its body, or became abstract or compact, is no code unit now but is still declared */
    @Test
    void aMethodThatLostItsBodyIsNoRemoval() throws Exception {
        String before = """
                package p;
                public abstract class A {
                    void stub() {
                        work();
                    }
                    public void gone() {
                        work();
                    }
                    void work() {
                        System.out.println(1);
                    }
                }
                """;
        String after = """
                package p;
                public abstract class A {
                    void stub() {
                    }
                    public abstract void gone();
                    void work() {
                        System.out.println(1);
                    }
                }
                """;
        assertEquals(Set.of(), removed("A.java", before, "A.java", after));

        String recordBefore = """
                package p;
                public record E(int x) {
                    public E(int x) {
                        this.x = x;
                    }
                }
                """;
        String recordAfter = """
                package p;
                public record E(int x) {
                    public E {
                        System.out.println(x);
                    }
                }
                """;
        assertEquals(Set.of(), removed("E.java", recordBefore, "E.java", recordAfter));
    }
    /** the descriptor embeds the package and the primary type's name, so a moved or renamed file must not shed its members */
    @Test
    void aRenamedAndMovedFileKeepsItsMembers() throws Exception {
        String before = """
                package com.a;
                public class Foo {
                    public Foo(int x) {
                        System.out.println(x);
                    }
                    int kept(int y) {
                        return y + 1;
                    }
                    static class Inner {
                        void in() {
                            System.out.println(2);
                        }
                    }
                }
                """;
        String after = """
                package com.b;
                public class Bar {
                    public Bar(int x) {
                        System.out.println(x);
                    }
                    int kept(int y) {
                        return y + 2;
                    }
                    static class Inner {
                        void in() {
                            System.out.println(2);
                        }
                    }
                }
                """;
        assertEquals(Set.of(), removed("com/a/Foo.java", before, "Bar.java", after));
    }
    /** PMD numbers anonymous classes by position, so deleting the first renames every later one */
    @Test
    void deletingAnAnonymousClassDoesNotRenumberTheSurvivors() throws Exception {
        String before = """
                package p;
                public class Outer {
                    Runnable a() {
                        return new Runnable() {
                            public void run() {
                                System.out.println("a");
                            }
                        };
                    }
                    Runnable b() {
                        return new Runnable() {
                            public void run() {
                                System.out.println("b");
                            }
                        };
                    }
                }
                """;
        String after = """
                package p;
                public class Outer {
                    Runnable b() {
                        return new Runnable() {
                            public void run() {
                                System.out.println("b2");
                            }
                        };
                    }
                }
                """;
        assertEquals(Set.of("p/Outer.a()Ljava/lang/Runnable;", "p/Outer$1.run()V"), removed("Outer.java", before, "Outer.java", after));

        String enumBefore = """
                package p;
                public enum Op {
                    ADD {
                        int apply(int a) {
                            return a + 1;
                        }
                    },
                    SUB {
                        int apply(int a) {
                            return a - 1;
                        }
                    };
                    abstract int apply(int a);
                }
                """;
        String enumAfter = """
                package p;
                public enum Op {
                    SUB {
                        int apply(int a) {
                            return a - 2;
                        }
                    };
                    abstract int apply(int a);
                }
                """;
        assertEquals(Set.of("p/Op$1.apply(I)I"), removed("Op.java", enumBefore, "Op.java", enumAfter));
    }
    /** a return type, or a parameter type that resolves differently, changes the descriptor but not the method */
    @Test
    void aDescriptorChangeIsNoRemoval() throws Exception {
        String before = """
                package p;
                import java.util.List;
                import org.apache.commons.lang3.tuple.Pair;
                public class R {
                    public int count(List<String> items) {
                        return items.size();
                    }
                    int size(Pair p) {
                        return 1;
                    }
                }
                """;
        String after = """
                package p;
                import java.util.List;
                import org.apache.commons.lang3.tuple.*;
                public class R {
                    public long count(List<String> items) {
                        return items.size();
                    }
                    int size(Pair p) {
                        return 2;
                    }
                }
                """;
        assertEquals(Set.of(), removed("R.java", before, "R.java", after));
    }
    /** a method whose parameters changed is one edited method, reported under its new signature — never removed plus added */
    @Test
    void aChangedParameterListIsAnEditNotARemoval() throws Exception {
        String before = """
                package p;
                public class Calc {
                    int sum(int a) {
                        return a;
                    }
                    Runnable task(int n) {
                        return new Runnable() {
                            public void run() {
                                System.out.println(n);
                            }
                        };
                    }
                }
                """;
        String after = """
                package p;
                public class Calc {
                    int sum(int a, int b) {
                        return a + b;
                    }
                    Runnable task(int n, String label) {
                        return new Runnable() {
                            public void run() {
                                System.out.println(label + n);
                            }
                        };
                    }
                }
                """;
        // the anonymous run() pairs too: its identity carries task's parameters, its name-only identity does not
        assertEquals(Set.of(), removed("Calc.java", before, "Calc.java", after));
    }
    /** a retyped constructor parameter beside an untouched sibling overload: the sibling pairs exactly, the rest by name */
    @Test
    void aRetypedConstructorParameterIsAnEdit() throws Exception {
        String before = """
                package p;
                public class Endpoint {
                    Endpoint(String name) {
                        this(name, null);
                    }
                    Endpoint(String name, LegacyRegistry registry) {
                        System.out.println(name + registry);
                    }
                }
                """;
        String after = """
                package p;
                public class Endpoint {
                    Endpoint(String name) {
                        this(name, null);
                    }
                    Endpoint(String name, ContributorRegistry registry) {
                        System.out.println(name + registry);
                    }
                }
                """;
        assertEquals(Set.of(), removed("Endpoint.java", before, "Endpoint.java", after));
    }
    /** a deprecated overload deleted while its replacement stays untouched */
    @Test
    void aDeletedOverloadBesideItsSurvivingSiblingIsARemoval() throws Exception {
        String before = """
                package p;
                public class Channel {
                    @Deprecated
                    protected Channel(String props, LegacyRegistry registry) {
                        this(props, new Mirror(registry));
                    }
                    protected Channel(String props, ContributorRegistry registry) {
                        System.out.println(props + registry);
                    }
                }
                """;
        String after = """
                package p;
                public class Channel {
                    protected Channel(String props, ContributorRegistry registry) {
                        System.out.println(props + registry);
                    }
                }
                """;
        assertEquals(Set.of("p/Channel.<init>(Ljava/lang/String;LLegacyRegistry;)V"), removed("Channel.java", before, "Channel.java", after));
    }
    /**
     * the constructor dropped a parameter, which changes the identity of every method
     * of the anonymous class inside it. The class has overloads of equal count, so neither the name nor the name and
     * count can pair them — only the member identity, which keeps each method's own parameters
     */
    @Test
    void anAnonymousClassInsideAConstructorThatLostAParameterKeepsItsMethods() throws Exception {
        String before = """
                package p;
                public class Resolver {
                    private final Lookup dns;
                    public Resolver(String props, Limiter limiter) {
                        dns = new Lookup() {
                            public String resolve(String host) {
                                return host + props;
                            }
                            public String resolve(int address) {
                                return String.valueOf(address);
                            }
                        };
                    }
                }
                """;
        String after = """
                package p;
                public class Resolver {
                    private final Lookup dns;
                    public Resolver(String props) {
                        dns = new Lookup() {
                            public String resolve(String host) {
                                return host + props;
                            }
                            public String resolve(int address) {
                                return String.valueOf(address);
                            }
                        };
                    }
                }
                """;
        assertEquals(Set.of(), removed("Resolver.java", before, "Resolver.java", after));
    }
    /** one overload deleted and its longer sibling retyped — the count tells them apart */
    @Test
    void aRetypedOverloadIsToldFromADeletedOneByParameterCount() throws Exception {
        String before = """
                package p;
                public class Provider {
                    public Provider(String props, Pool pool) {
                        this(props, pool, null);
                    }
                    public Provider(String props, Pool pool, LegacyDns dns) {
                        System.out.println(props + pool + dns);
                    }
                }
                """;
        String after = """
                package p;
                public class Provider {
                    public Provider(String props, Pool pool, NettyDns dns) {
                        System.out.println(props + pool + dns);
                    }
                }
                """;
        assertEquals(Set.of("p/Provider.<init>(Ljava/lang/String;LPool;)V"), removed("Provider.java", before, "Provider.java", after));
    }
    /** one type retyped across two overloads of equal count */
    @Test
    void aTypeRetypedAcrossEqualCountOverloadsPairsEachWithItsClosestSuccessor() throws Exception {
        String before = """
                package p;
                public class Client {
                    public Client(String props, SyncHttp http, Mapper mapper) {
                        System.out.println(props + http + mapper);
                    }
                    public Client(String props, AsyncHttp http, Mapper mapper) {
                        System.out.println(props + http + mapper);
                    }
                }
                """;
        String after = """
                package p;
                public class Client {
                    public Client(String props, SyncHttp http, Mapper3 mapper) {
                        System.out.println(props + http + mapper);
                    }
                    public Client(String props, AsyncHttp http, Mapper3 mapper) {
                        System.out.println(props + http + mapper);
                    }
                }
                """;
        assertEquals(Set.of(), removed("Client.java", before, "Client.java", after));
    }
    /** two old overloads equally close to the one new declaration: a tie is not broken, both stay removals */
    @Test
    void aTieBetweenEqualCountOverloadsIsNotBroken() throws Exception {
        String before = """
                package p;
                public class Tie {
                    void put(String key, int value) {
                        System.out.println(key + value);
                    }
                    void put(String key, long value) {
                        System.out.println(key + value);
                    }
                }
                """;
        String after = """
                package p;
                public class Tie {
                    void put(String key, double value) {
                        System.out.println(key + value);
                    }
                }
                """;
        assertEquals(Set.of("p/Tie.put(Ljava/lang/String;I)V", "p/Tie.put(Ljava/lang/String;J)V"), removed("Tie.java", before, "Tie.java", after));
    }
    /** more than one leftover candidate on either side is not guessed at: the old overloads stay removals */
    @Test
    void ambiguousOverloadsStayRemovals() throws Exception {
        String before = """
                package p;
                public class Overloads {
                    void put(int value) {
                        System.out.println(value);
                    }
                    void put(String value) {
                        System.out.println(value);
                    }
                }
                """;
        String after = """
                package p;
                public class Overloads {
                    void put(int value, int times) {
                        System.out.println(value * times);
                    }
                }
                """;
        assertEquals(Set.of("p/Overloads.put(I)V", "p/Overloads.put(Ljava/lang/String;)V"), removed("Overloads.java", before, "Overloads.java", after));
    }
    /** one module's files share a parser setup, so each file's removals must stay attributed to that file */
    @Test
    void aBatchKeepsEachFilesRemovalsApart() throws Exception {
        Path first = workTree.resolve("First.java");
        Files.writeString(first, "package p;\npublic class First {\n}\n");
        Path second = workTree.resolve("Second.java");
        Files.writeString(second, "package p;\npublic class Second {\n    void kept() {\n        System.out.println(2);\n    }\n}\n");

        try (JavaLanguageSpec spec = spec()) {
            MultiValuedMap<File, CodeBlockInfo> removed = spec.parseRemoved(mock(ProjectSpec.class), List.of(
                    new PreviousRevision(first.toFile(), "First.java", "package p;\npublic class First {\n    void a() {\n        System.out.println(1);\n    }\n}\n"),
                    new PreviousRevision(second.toFile(), "Second.java",
                            "package p;\npublic class Second {\n    void kept() {\n        System.out.println(1);\n    }\n    void b() {\n        System.out.println(1);\n    }\n}\n")));

            assertEquals(Set.of("p/First.a()V"), removed.get(first.toFile()).stream().map(CodeBlockInfo::getSignature).collect(Collectors.toSet()));
            assertEquals(Set.of("p/Second.b()V"), removed.get(second.toFile()).stream().map(CodeBlockInfo::getSignature).collect(Collectors.toSet()));
        }
    }
    private Set<String> removed(String previousPath, String before, String name, String after) throws Exception {
        Path source = workTree.resolve(name);
        Files.writeString(source, after);

        try (JavaLanguageSpec spec = spec()) {
            return spec.parseRemoved(mock(ProjectSpec.class), List.of(new PreviousRevision(source.toFile(), previousPath, before))).values().stream()
                    .map(CodeBlockInfo::getSignature)
                    .collect(Collectors.toSet());
        }
    }
    private File write(String name) throws Exception {
        Path toReturn = workTree.resolve(name);
        Files.writeString(toReturn, SOURCE);
        return toReturn.toFile();
    }
    private static JavaLanguageSpec spec() {
        RunArgs args = new RunArgs();
        return new JavaLanguageSpec(NoopLog.FACTORY, args, new Fetch(args));
    }

    private static class NoopLog implements Log {
        private static final LogFactory FACTORY = clazz -> new NoopLog();

        @Override
        public boolean isLoggable(Level level) {
            return false;
        }
        @Override
        public void logEx(Level level, String message, Object[] formatArgs, Throwable error) {
        }
        @Override
        public int numErrors() {
            return 0;
        }
    }
}
