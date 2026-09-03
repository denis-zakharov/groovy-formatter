package dev.groovyfmt.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ShellFormatterTest {

    @Test
    void simpleCommand() {
        assertEquals("echo hello\n", ShellFormatter.format("echo hello"));
    }

    @Test
    void reindentsPipeline() {
        assertEquals("cat foo.txt | grep bar | sort\n", ShellFormatter.format("cat  foo.txt|grep bar|sort\n"));
    }

    @Test
    void andOr() {
        assertEquals("make build && make test || exit 1\n", ShellFormatter.format("make build&&make test||exit 1"));
    }

    @Test
    void assignmentAndQuoting() {
        String src = "NAME=\"$1\"\necho \"hello, $NAME\" 'literal $x'\n";
        assertEquals(src, ShellFormatter.format(src));
    }

    @Test
    void ifElif() {
        String src =
                "if [ \"$x\" = 1 ]; then\n"
                        + "    echo one\n"
                        + "elif [ \"$x\" = 2 ]; then\n"
                        + "    echo two\n"
                        + "else\n"
                        + "    echo other\n"
                        + "fi\n";
        assertEquals(src, ShellFormatter.format(src));
    }

    @Test
    void ifCollapsesOntoOneLine() {
        String src = "if true\nthen\n  echo yes\nfi\n";
        String expected = "if true; then\n    echo yes\nfi\n";
        assertEquals(expected, ShellFormatter.format(src));
    }

    @Test
    void forLoop() {
        String src = "for f in a b c\ndo\n  echo \"$f\"\ndone\n";
        String expected = "for f in a b c; do\n    echo \"$f\"\ndone\n";
        assertEquals(expected, ShellFormatter.format(src));
    }

    @Test
    void forWithoutIn() {
        String src = "for arg; do\n    echo \"$arg\"\ndone\n";
        assertEquals(src, ShellFormatter.format(src));
    }

    @Test
    void whileLoop() {
        String src = "while read -r line; do\n    echo \"$line\"\ndone\n";
        assertEquals(src, ShellFormatter.format(src));
    }

    @Test
    void caseStatement() {
        String src =
                "case \"$1\" in\n"
                        + "    start | up)\n"
                        + "        echo starting\n"
                        + "        ;;\n"
                        + "    stop)\n"
                        + "        echo stopping\n"
                        + "        ;;\n"
                        + "    *)\n"
                        + "        echo unknown\n"
                        + "        ;;\n"
                        + "esac\n";
        assertEquals(src, ShellFormatter.format(src));
    }

    @Test
    void functionDef() {
        String src = "greet() {\n    echo \"hi, $1\"\n}\n";
        assertEquals(src, ShellFormatter.format(src));
    }

    @Test
    void nestedPipelineInJenkinsfileStyleBlock() {
        String src =
                "set -euo pipefail\n"
                        + "if [ -f VERSION ]; then\n"
                        + "    VERSION=$(cat VERSION)\n"
                        + "    echo \"building $VERSION\"\n"
                        + "    docker build -t \"myapp:$VERSION\" .\n"
                        + "fi\n";
        assertEquals(src, ShellFormatter.format(src));
    }

    @Test
    void commentsPreserved() {
        String src = "# top comment\necho hi # trailing\n\n# another\necho bye\n";
        assertEquals(src, ShellFormatter.format(src));
    }

    @Test
    void hereDoc() {
        String src = "cat <<EOF\nhello\nworld\nEOF\necho done\n";
        assertEquals(src, ShellFormatter.format(src));
    }

    @Test
    void hereDocDash() {
        String src = "cat <<-EOF\n\tindented body\nEOF\n";
        assertEquals(src, ShellFormatter.format(src));
    }

    @Test
    void commandSubstitutionReformatted() {
        assertEquals("x=$(echo hi)\n", ShellFormatter.format("x=$( echo   hi )\n"));
    }

    @Test
    void redirections() {
        assertEquals("cmd > out.txt 2>&1\n", ShellFormatter.format("cmd  >  out.txt  2>&1\n"));
    }

    @Test
    void subshellAndGroup() {
        String src = "(\n    cd /tmp\n    ls\n)\n{\n    echo grouped\n}\n";
        assertEquals(src, ShellFormatter.format(src));
    }

    @Test
    void negatedPipeline() {
        // A space after '!' is required for it to be recognized as the negation reserved word by
        // real shells too — "!grep" (no space) is just a literal word, not negation.
        assertEquals("! grep foo file.txt\n", ShellFormatter.format("! grep foo file.txt\n"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "echo hi",
                "if true; then echo hi; fi",
                "for x in a b; do echo $x; done",
                "case $x in a) echo a ;; *) echo b ;; esac",
                "f() { echo hi; }",
                "a=$(echo b) && echo $a",
                "cat <<EOF\nbody\nEOF\n",
            })
    void formattingIsIdempotent(String source) {
        String once = ShellFormatter.format(source);
        String twice = ShellFormatter.format(once);
        assertEquals(once, twice, "not idempotent for: " + source);
    }

    @Test
    void arrayAssignmentIsUnsupported() {
        assertThrows(UnsupportedOperationException.class, () -> ShellFormatter.format("arr=(a b c)\n"));
    }

    @Test
    void syntaxErrorThrows() {
        ShellParseException ex = assertThrows(ShellParseException.class, () -> ShellFormatter.format("if true; then\n"));
        assertTrue(ex.getMessage().contains("expected"));
    }
}
