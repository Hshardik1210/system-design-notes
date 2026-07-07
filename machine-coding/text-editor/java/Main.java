import java.util.*;

/**
 * TEXT EDITOR with UNDO / REDO — Command pattern.
 *
 * Every mutation (insert, delete) is a Command that knows how to execute() and
 * undo() itself. Executed commands are pushed onto an undo stack; undo() pops
 * one, reverses it, and pushes it onto the redo stack. A NEW command clears the
 * redo stack (you can't redo after diverging), just like real editors.
 *
 * Each command captures the minimal state needed to reverse itself (a lightweight
 * Memento) — e.g. delete remembers the text it removed so undo can reinsert it.
 */
public class Main {

    // Document: holds the actual text and knows how to insert/delete characters.
    // It is "dumb" on purpose — it does not know about undo/redo. StringBuilder is
    // used because it lets us edit text in place cheaply (unlike an immutable String).
    static class Document {
        final StringBuilder text = new StringBuilder();
        // Insert string s starting at index pos (shifts later characters right).
        void insert(int pos, String s) { text.insert(pos, s); }
        // Remove len characters starting at index pos.
        void delete(int pos, int len)  { text.delete(pos, pos + len); }
        // Return the current text as a plain String.
        String get() { return text.toString(); }
    }

    // ---------- Command pattern ----------
    // Command: the core idea of this design. Every editing action is wrapped in an
    // object that knows both how to do itself (execute) and how to reverse itself
    // (undo). Because each action can undo itself, the editor gets undo/redo for free.
    interface Command {
        void execute(); // apply the change to the document
        void undo();    // reverse that exact change
    }

    // InsertCommand: represents typing/inserting text at a position.
    static class InsertCommand implements Command {
        private final Document doc; private final int pos; private final String s;
        InsertCommand(Document doc, int pos, String s) { this.doc = doc; this.pos = pos; this.s = s; }
        // Do it: put the text into the document.
        public void execute() { doc.insert(pos, s); }
        // Undo it: the reverse of an insert is deleting exactly what we inserted.
        public void undo()    { doc.delete(pos, s.length()); } // reverse = delete what we inserted
    }

    // DeleteCommand: represents removing text. To undo a delete we must remember the
    // text that was removed — so on execute() we save it first (a small Memento).
    static class DeleteCommand implements Command {
        private final Document doc; private final int pos; private final int len;
        private String removed; // captured on execute so undo can restore it (Memento)
        DeleteCommand(Document doc, int pos, int len) { this.doc = doc; this.pos = pos; this.len = len; }
        // Do it: first copy the text we are about to remove, then remove it.
        public void execute() {
            removed = doc.get().substring(pos, pos + len); // snapshot the text before it is gone
            doc.delete(pos, len);
        }
        // Undo it: put the saved text back where it was.
        public void undo() { doc.insert(pos, removed); } // reverse = reinsert removed text
    }

    // ---------- The editor drives the command stacks ----------
    // Editor: the thing a user interacts with. It owns the document and the two
    // history stacks (undo/redo) and coordinates running, undoing, and redoing commands.
    static class Editor {
        private final Document doc = new Document();
        private final Deque<Command> undoStack = new ArrayDeque<>(); // commands already applied (most recent on top)
        private final Deque<Command> redoStack = new ArrayDeque<>(); // commands that were undone and can be replayed

        // Run a brand-new command: apply it and record it so it can be undone later.
        void run(Command c) {
            c.execute();
            undoStack.push(c);
            redoStack.clear(); // diverging from history invalidates redo
        }
        // Undo the most recent command: reverse it and move it to the redo stack.
        void undo() {
            if (undoStack.isEmpty()) { System.out.println("  (nothing to undo)"); return; }
            Command c = undoStack.pop(); // most recently applied command
            c.undo();
            redoStack.push(c); // now available to redo
        }
        // Redo the last undone command: re-apply it and move it back to the undo stack.
        void redo() {
            if (redoStack.isEmpty()) { System.out.println("  (nothing to redo)"); return; }
            Command c = redoStack.pop(); // most recently undone command
            c.execute();
            undoStack.push(c);
        }
        // Convenience API.
        // Type text at the end of the document by running an InsertCommand.
        void type(String s) { run(new InsertCommand(doc, doc.get().length(), s)); }
        // Delete len characters at position pos by running a DeleteCommand.
        void deleteAt(int pos, int len) { run(new DeleteCommand(doc, pos, len)); }
        // Read the current document text.
        String text() { return doc.get(); }
    }

    // Demo: a small script showing typing, deleting, undo, redo, and how a new
    // command wipes the redo history.
    public static void main(String[] args) {
        Editor ed = new Editor();
        ed.type("Hello");
        ed.type(" World");
        System.out.println("after typing : '" + ed.text() + "'"); // Hello World

        ed.deleteAt(0, 6); // delete "Hello "
        System.out.println("after delete : '" + ed.text() + "'"); // World

        ed.undo(); System.out.println("after undo   : '" + ed.text() + "'"); // Hello World
        ed.undo(); System.out.println("after undo   : '" + ed.text() + "'"); // Hello
        ed.redo(); System.out.println("after redo   : '" + ed.text() + "'"); // Hello World

        ed.type("!!!"); // new command -> redo history cleared
        System.out.println("after type   : '" + ed.text() + "'"); // Hello World!!!
        ed.redo();      // nothing to redo now
    }
}
