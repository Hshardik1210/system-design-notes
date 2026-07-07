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

    // The document being edited.
    static class Document {
        final StringBuilder text = new StringBuilder();
        void insert(int pos, String s) { text.insert(pos, s); }
        void delete(int pos, int len)  { text.delete(pos, pos + len); }
        String get() { return text.toString(); }
    }

    // ---------- Command pattern ----------
    interface Command {
        void execute();
        void undo();
    }

    static class InsertCommand implements Command {
        private final Document doc; private final int pos; private final String s;
        InsertCommand(Document doc, int pos, String s) { this.doc = doc; this.pos = pos; this.s = s; }
        public void execute() { doc.insert(pos, s); }
        public void undo()    { doc.delete(pos, s.length()); } // reverse = delete what we inserted
    }

    static class DeleteCommand implements Command {
        private final Document doc; private final int pos; private final int len;
        private String removed; // captured on execute so undo can restore it (Memento)
        DeleteCommand(Document doc, int pos, int len) { this.doc = doc; this.pos = pos; this.len = len; }
        public void execute() {
            removed = doc.get().substring(pos, pos + len);
            doc.delete(pos, len);
        }
        public void undo() { doc.insert(pos, removed); } // reverse = reinsert removed text
    }

    // ---------- The editor drives the command stacks ----------
    static class Editor {
        private final Document doc = new Document();
        private final Deque<Command> undoStack = new ArrayDeque<>();
        private final Deque<Command> redoStack = new ArrayDeque<>();

        void run(Command c) {
            c.execute();
            undoStack.push(c);
            redoStack.clear(); // diverging from history invalidates redo
        }
        void undo() {
            if (undoStack.isEmpty()) { System.out.println("  (nothing to undo)"); return; }
            Command c = undoStack.pop();
            c.undo();
            redoStack.push(c);
        }
        void redo() {
            if (redoStack.isEmpty()) { System.out.println("  (nothing to redo)"); return; }
            Command c = redoStack.pop();
            c.execute();
            undoStack.push(c);
        }
        // Convenience API.
        void type(String s) { run(new InsertCommand(doc, doc.get().length(), s)); }
        void deleteAt(int pos, int len) { run(new DeleteCommand(doc, pos, len)); }
        String text() { return doc.get(); }
    }

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
