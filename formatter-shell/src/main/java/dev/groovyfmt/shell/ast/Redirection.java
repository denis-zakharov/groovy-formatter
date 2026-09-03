package dev.groovyfmt.shell.ast;

/** A redirection attached to a command: either a simple {@link Redirect} or a {@link HereDoc}. */
public sealed interface Redirection permits Redirection.Redirect, Redirection.HereDoc {

    /**
     * {@code [fd]operator target}, e.g. {@code 2>&1}, {@code >file}, {@code <&3}. {@code fd} is
     * the optional explicit file-descriptor prefix (digits, or {@code null}); {@code operator} is
     * one of {@code < > >> >& <& <> >| &> &>>}.
     */
    record Redirect(String fd, String operator, Word target) implements Redirection {}

    /**
     * {@code [fd]<< delimiter} / {@code [fd]<<- delimiter}. {@code body()} is the raw here-doc
     * body text (each line exactly as written, joined by {@code \n}, no trailing newline),
     * printed verbatim — never reindented, even by {@code <<-}, to guarantee content is never
     * silently altered.
     */
    record HereDoc(String fd, String operator, Word delimiter, HereDocBody bodyHolder)
            implements Redirection {
        public String body() {
            return bodyHolder.text;
        }
    }
}
