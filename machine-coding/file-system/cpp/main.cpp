// IN-MEMORY FILE SYSTEM — Composite pattern (C++17)
//
// File (leaf) and Directory (composite) are both Nodes, so the tree is traversed
// uniformly (ls, find, size). Paths are absolute like "/a/b/c".

#include <iostream>
#include <string>
#include <vector>
#include <map>
#include <memory>
#include <sstream>
#include <stdexcept>
using namespace std;

// Composite "component": common base for files and directories. Virtual isDir()/size()
// let callers walk the tree uniformly without caring which concrete type they hold.
struct Node {
    string name;
    explicit Node(string n) : name(move(n)) {}
    virtual ~Node() = default;
    virtual bool isDir() const = 0;
    virtual int size() const = 0;
};

// Composite "leaf": a file with text content and no children.
struct FileNode : Node {
    string content;
    explicit FileNode(string n) : Node(move(n)) {}
    bool isDir() const override { return false; }
    // A file's size is just the length of its content.
    int size() const override { return (int)content.size(); }
};

// Composite "composite": a directory holding child nodes (files or sub-dirs).
struct DirNode : Node {
    map<string, shared_ptr<Node>> children; // sorted -> alphabetical ls
    explicit DirNode(string n) : Node(move(n)) {}
    bool isDir() const override { return true; }
    // Recursive size: sum of all children's sizes (recursion via the composite tree).
    int size() const override { int s = 0; for (auto& [k, c] : children) s += c->size(); return s; }
};

// High-level API over the node tree: the operations users call (mkdirs, writeFile,
// ls, size, find). Tree-walking details are kept private.
class FileSystem {
    shared_ptr<DirNode> root = make_shared<DirNode>("/");

    // Split "/a/b" -> ["a","b"], ignoring empty parts (e.g. leading slash).
    vector<string> parts(const string& path) {
        vector<string> p; string tok; stringstream ss(path);
        while (getline(ss, tok, '/')) if (!tok.empty()) p.push_back(tok);
        return p;
    }
public:
    // Create all directories along the path (mkdir -p).
    // For each segment: create if missing, descend if it's a dir, or fail if a file blocks it.
    void mkdirs(const string& path) {
        auto cur = root;
        for (auto& part : parts(path)) {
            auto it = cur->children.find(part);
            if (it == cur->children.end()) {                                  // missing -> create and step in
                auto d = make_shared<DirNode>(part);
                cur->children[part] = d; cur = d;
            } else if (it->second->isDir()) {                                 // exists as dir -> step in
                cur = static_pointer_cast<DirNode>(it->second);
            } else throw invalid_argument(part + " is a file, not a dir");    // a file blocks the path
        }
    }

    // Create/overwrite a file and set its content (parent dirs auto-created).
    void writeFile(const string& path, const string& content) {
        auto p = parts(path);
        string fname = p.back();  // last segment is the file name; the rest are parent dirs
        auto dir = root;
        // Walk (creating) all parent directories, stopping before the file name.
        for (size_t i = 0; i + 1 < p.size(); i++) {
            auto it = dir->children.find(p[i]);
            if (it == dir->children.end()) { auto d = make_shared<DirNode>(p[i]); dir->children[p[i]] = d; dir = d; }
            else if (it->second->isDir()) dir = static_pointer_cast<DirNode>(it->second);
            else throw invalid_argument(p[i] + " is a file, not a dir");
        }
        // Put a fresh file node into the parent dir (overwrites any existing entry).
        auto f = make_shared<FileNode>(fname);
        f->content = content;
        dir->children[fname] = f;
    }

    // Resolve a path to a node, or nullptr.
    // Step down one segment at a time; bail out if a segment is missing or is a file.
    shared_ptr<Node> resolve(const string& path) {
        if (path == "/") return root;
        shared_ptr<Node> cur = root;
        for (auto& part : parts(path)) {
            if (!cur->isDir()) return nullptr;                     // can't descend into a file
            auto& ch = static_pointer_cast<DirNode>(cur)->children;
            auto it = ch.find(part);
            if (it == ch.end()) return nullptr;                    // segment doesn't exist
            cur = it->second;
        }
        return cur;
    }

    // Return a file's content; error if the path is missing or is a directory.
    string readFile(const string& path) {
        auto n = resolve(path);
        if (!n || n->isDir()) throw runtime_error("not a file: " + path);
        return static_pointer_cast<FileNode>(n)->content;
    }

    // List names directly under a directory path (or the file's own name).
    vector<string> ls(const string& path) {
        auto n = resolve(path);
        if (!n) throw runtime_error("no such path: " + path);
        if (!n->isDir()) return {n->name};                         // ls on a file -> its name
        vector<string> out;
        for (auto& [k, c] : static_pointer_cast<DirNode>(n)->children) out.push_back(k);
        return out;
    }

    // Recursively find files/dirs whose name contains a substring (DFS from each top-level child).
    vector<string> find(const string& needle) {
        vector<string> hits;
        for (auto& [k, c] : root->children) dfs(c, "", needle, hits);
        return hits;
    }
    // Depth-first walk: 'parentPath' is the absolute path of the node's parent (root = "").
    void dfs(const shared_ptr<Node>& node, const string& parentPath, const string& needle, vector<string>& hits) {
        string path = parentPath + "/" + node->name;                              // build this node's full path
        if (node->name.find(needle) != string::npos) hits.push_back(path);        // record a match
        if (node->isDir())                                                        // recurse into sub-directories
            for (auto& [k, c] : static_pointer_cast<DirNode>(node)->children) dfs(c, path, needle, hits);
    }

    // Recursive byte size of a path (0 if the path doesn't exist).
    int size(const string& path) { auto n = resolve(path); return n ? n->size() : 0; }
};

// Helper to print a vector of strings as "[a, b, c]".
static string join(const vector<string>& v) {
    string s = "[";
    for (size_t i = 0; i < v.size(); ++i) s += v[i] + (i + 1 < v.size() ? ", " : "");
    return s + "]";
}

// Demo: build a small tree, then exercise ls / read / size / find.
int main() {
    FileSystem fs;
    fs.mkdirs("/home/alice/docs");
    fs.writeFile("/home/alice/docs/resume.txt", "Experienced engineer...");
    fs.writeFile("/home/alice/notes.md", "# TODO\n- practice LLD");
    fs.mkdirs("/home/bob");
    fs.writeFile("/home/bob/hello.txt", "hi");

    cout << "ls /home           : " << join(fs.ls("/home")) << "\n";
    cout << "ls /home/alice     : " << join(fs.ls("/home/alice")) << "\n";
    cout << "ls /home/alice/docs: " << join(fs.ls("/home/alice/docs")) << "\n";
    cout << "read resume.txt    : '" << fs.readFile("/home/alice/docs/resume.txt") << "'\n";
    cout << "size /home/alice   : " << fs.size("/home/alice") << " bytes\n";
    cout << "find '.txt'        : " << join(fs.find(".txt")) << "\n";
    return 0;
}
