// TEXT EDITOR with UNDO / REDO — Command pattern (C++17)
//
// Each mutation is a Command with execute()/undo(). Executed commands go on the
// undo stack; undo pops+reverses and pushes onto redo. A new command clears redo.
// Delete captures the removed substring (a lightweight Memento) to restore on undo.

#include <iostream>
#include <string>
#include <vector>
#include <memory>
using namespace std;

// Document: holds the actual text and knows how to insert/delete characters.
// It is intentionally "dumb" — it has no idea about undo/redo.
struct Document {
    string text;
    // Insert string s starting at index pos (shifts later characters right).
    void insert(size_t pos, const string& s) { text.insert(pos, s); }
    // Remove len characters starting at index pos.
    void erase(size_t pos, size_t len) { text.erase(pos, len); }
};

// Command: the heart of the design. Every editing action is an object that can both
// apply itself (execute) and reverse itself (undo). This is what makes undo/redo work.
struct Command {
    virtual ~Command() = default; // virtual destructor so derived commands clean up correctly
    virtual void execute() = 0;   // apply the change to the document
    virtual void undo() = 0;      // reverse that exact change
};

// InsertCommand: represents typing/inserting text at a position.
struct InsertCommand : Command {
    Document& doc; size_t pos; string s;
    InsertCommand(Document& d, size_t p, string str) : doc(d), pos(p), s(move(str)) {}
    // Do it: put the text into the document.
    void execute() override { doc.insert(pos, s); }
    // Undo it: the reverse of an insert is deleting exactly what we inserted.
    void undo() override { doc.erase(pos, s.size()); }
};

// DeleteCommand: represents removing text. To be able to undo, it remembers the text
// it removed (saved in `removed` during execute — a lightweight Memento).
struct DeleteCommand : Command {
    Document& doc; size_t pos; size_t len; string removed;
    DeleteCommand(Document& d, size_t p, size_t l) : doc(d), pos(p), len(l) {}
    // Do it: first copy the text we are about to remove, then remove it.
    void execute() override { removed = doc.text.substr(pos, len); doc.erase(pos, len); }
    // Undo it: put the saved text back where it was.
    void undo() override { doc.insert(pos, removed); }
};

// Editor: the thing a user interacts with. It owns the document and two history
// stacks (undo/redo) and coordinates running, undoing, and redoing commands.
// shared_ptr is used so the same command object can live safely on either stack.
class Editor {
    Document doc;
    vector<shared_ptr<Command>> undoStack, redoStack; // back() of each vector is the "top" of the stack
public:
    // Run a brand-new command: apply it and record it so it can be undone later.
    void run(shared_ptr<Command> c) {
        c->execute();
        undoStack.push_back(c);
        redoStack.clear(); // diverging from history invalidates any redo path
    }
    // Undo the most recent command: reverse it and move it to the redo stack.
    void undo() {
        if (undoStack.empty()) { cout << "  (nothing to undo)\n"; return; }
        auto c = undoStack.back(); undoStack.pop_back(); // take the most recently applied command
        c->undo();
        redoStack.push_back(c); // now available to redo
    }
    // Redo the last undone command: re-apply it and move it back to the undo stack.
    void redo() {
        if (redoStack.empty()) { cout << "  (nothing to redo)\n"; return; }
        auto c = redoStack.back(); redoStack.pop_back(); // take the most recently undone command
        c->execute();
        undoStack.push_back(c);
    }
    // Type text at the end of the document by running an InsertCommand.
    void type(const string& s) { run(make_shared<InsertCommand>(doc, doc.text.size(), s)); }
    // Delete len characters at position pos by running a DeleteCommand.
    void deleteAt(size_t pos, size_t len) { run(make_shared<DeleteCommand>(doc, pos, len)); }
    // Read the current document text.
    string text() const { return doc.text; }
};

// Demo: types, deletes, then undoes/redoes, and shows how a new command clears redo.
int main() {
    Editor ed;
    ed.type("Hello");
    ed.type(" World");
    cout << "after typing : '" << ed.text() << "'\n"; // Hello World

    ed.deleteAt(0, 6); // delete "Hello "
    cout << "after delete : '" << ed.text() << "'\n"; // World

    ed.undo(); cout << "after undo   : '" << ed.text() << "'\n"; // Hello World
    ed.undo(); cout << "after undo   : '" << ed.text() << "'\n"; // Hello
    ed.redo(); cout << "after redo   : '" << ed.text() << "'\n"; // Hello World

    ed.type("!!!"); // clears redo history
    cout << "after type   : '" << ed.text() << "'\n"; // Hello World!!!
    ed.redo();      // nothing to redo
    return 0;
}
