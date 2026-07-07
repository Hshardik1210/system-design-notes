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

struct Document {
    string text;
    void insert(size_t pos, const string& s) { text.insert(pos, s); }
    void erase(size_t pos, size_t len) { text.erase(pos, len); }
};

struct Command {
    virtual ~Command() = default;
    virtual void execute() = 0;
    virtual void undo() = 0;
};

struct InsertCommand : Command {
    Document& doc; size_t pos; string s;
    InsertCommand(Document& d, size_t p, string str) : doc(d), pos(p), s(move(str)) {}
    void execute() override { doc.insert(pos, s); }
    void undo() override { doc.erase(pos, s.size()); }
};

struct DeleteCommand : Command {
    Document& doc; size_t pos; size_t len; string removed;
    DeleteCommand(Document& d, size_t p, size_t l) : doc(d), pos(p), len(l) {}
    void execute() override { removed = doc.text.substr(pos, len); doc.erase(pos, len); }
    void undo() override { doc.insert(pos, removed); }
};

class Editor {
    Document doc;
    vector<shared_ptr<Command>> undoStack, redoStack;
public:
    void run(shared_ptr<Command> c) {
        c->execute();
        undoStack.push_back(c);
        redoStack.clear();
    }
    void undo() {
        if (undoStack.empty()) { cout << "  (nothing to undo)\n"; return; }
        auto c = undoStack.back(); undoStack.pop_back();
        c->undo();
        redoStack.push_back(c);
    }
    void redo() {
        if (redoStack.empty()) { cout << "  (nothing to redo)\n"; return; }
        auto c = redoStack.back(); redoStack.pop_back();
        c->execute();
        undoStack.push_back(c);
    }
    void type(const string& s) { run(make_shared<InsertCommand>(doc, doc.text.size(), s)); }
    void deleteAt(size_t pos, size_t len) { run(make_shared<DeleteCommand>(doc, pos, len)); }
    string text() const { return doc.text; }
};

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
