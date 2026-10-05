package dev.groovyfmt.print;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Constructs that used to throw "not supported yet" and are now formatted. */
class GroovyFormatterRemainingSyntaxTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "def a = new int[]{1, 2}\ndef b = new int[3][]\ndef c = new String[n]\n",
                "def d = Collections.<String>emptyList()\n",
                "@interface Foo {\n    String value() default \"x\";\n}\n",
                "def f(int a, b = 2) {\n    a\n}\n",
                "def (p, q) = [1, 2]\ndef (int u, v) = [1, 2]\n(p, q) = [3, 4]\n",
                "enum E {\n    A {\n        void f() {}\n    },\n    B\n}\n",
                "class K {\n    static {\n        init()\n    }\n}\n",
                "def e = ++i + --j\n",
                "m(*: opts)\ny.with(a -> 1)\n",
                "foo(\n    a, // first\n    // own line\n    b,\n)\n",
                "def l = [\n    1, // one\n    2,\n    // end\n]\n",
                "x.foo()\n    // between\n    .bar() // after\n",
                "def sw(v) {\n    switch (v) {\n        // before\n        case 1:\n            // inside\n            return 1 // trail\n        // between\n        default:\n            return 0\n    }\n}\n",
            })
    void formatsAndIsIdempotent(String source) {
        String once = GroovyFormatter.format(source);
        assertEquals(source, once);
        assertEquals(once, GroovyFormatter.format(once));
    }

    @Test
    void anonymousClassAsArgument() {
        String source = "run(\n    new Runnable() {\n        void run() {}\n    },\n)\n";
        assertEquals(source, GroovyFormatter.format(source));
        assertEquals(source, GroovyFormatter.format("run(new Runnable() { void run() {} })\n"));
    }
}
