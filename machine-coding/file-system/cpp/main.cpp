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

struct Node {
    string name;
    explicit Node(string n) : name(move(n)) {}
    virtual ~Node() = default;
    virtual bool isDir() const = 0;
    virtual int size() const = 0;
};

struct FileNode : Node {
    string content;
    explicit FileNode(string n) : Node(move(n)) {}
    bool isDir() const override { return false; }
    int size() const override { return (int)content.size(); }
};

struct DirNode : Node {
    map<string, shared_ptr<Node>> children; // sorted -> alphabetical ls
    explicit DirNode(string n) : Node(move(n)) {}
    bool isDir() const override { return true; }
    int size() const override { int s = 0; for (auto& [k, c] : children) s += c->size(); return s; }
};

class FileSystem {
    shared_ptr<DirNode> root = make_shared<DirNode>("/");

    vector<string> parts(const string& path) {
        vector<string> p; string tok; stringstream ss(path);
        while (getline(ss, tok, '/')) if (!tok.empty()) p.push_back(tok);
        return p;
    }
public:
    void mkdirs(const string& path) {
        auto cur = root;
        for (auto& part : parts(path)) {
            auto it = cur->children.find(part);
            if (it == cur->children.end()) {
                auto d = make_shared<DirNode>(part);
                cur->children[part] = d; cur = d;
            } else if (it->second->isDir()) {
                cur = static_pointer_cast<DirNode>(it->second);
            } else throw invalid_argument(part + " is a file, not a dir");
        }
    }

    void writeFile(const string& path, const string& content) {
        auto p = parts(path);
        string fname = p.back();
        auto dir = root;
        for (size_t i = 0; i + 1 < p.size(); i++) {
            auto it = dir->children.find(p[i]);
            if (it == dir->children.end()) { auto d = make_shared<DirNode>(p[i]); dir->children[p[i]] = d; dir = d; }
            else if (it->second->isDir()) dir = static_pointer_cast<DirNode>(it->second);
            else throw invalid_argument(p[i] + " is a file, not a dir");
        }
        auto f = make_shared<FileNode>(fname);
        f->content = content;
        dir->children[fname] = f;
    }

    shared_ptr<Node> resolve(const string& path) {
        if (path == "/") return root;
        shared_ptr<Node> cur = root;
        for (auto& part : parts(path)) {
            if (!cur->isDir()) return nullptr;
            auto& ch = static_pointer_cast<DirNode>(cur)->children;
            auto it = ch.find(part);
            if (it == ch.end()) return nullptr;
            cur = it->second;
        }
        return cur;
    }

    string readFile(const string& path) {
        auto n = resolve(path);
        if (!n || n->isDir()) throw runtime_error("not a file: " + path);
        return static_pointer_cast<FileNode>(n)->content;
    }

    vector<string> ls(const string& path) {
        auto n = resolve(path);
        if (!n) throw runtime_error("no such path: " + path);
        if (!n->isDir()) return {n->name};
        vector<string> out;
        for (auto& [k, c] : static_pointer_cast<DirNode>(n)->children) out.push_back(k);
        return out;
    }

    vector<string> find(const string& needle) {
        vector<string> hits;
        for (auto& [k, c] : root->children) dfs(c, "", needle, hits);
        return hits;
    }
    void dfs(const shared_ptr<Node>& node, const string& parentPath, const string& needle, vector<string>& hits) {
        string path = parentPath + "/" + node->name;
        if (node->name.find(needle) != string::npos) hits.push_back(path);
        if (node->isDir())
            for (auto& [k, c] : static_pointer_cast<DirNode>(node)->children) dfs(c, path, needle, hits);
    }

    int size(const string& path) { auto n = resolve(path); return n ? n->size() : 0; }
};

static string join(const vector<string>& v) {
    string s = "[";
    for (size_t i = 0; i < v.size(); ++i) s += v[i] + (i + 1 < v.size() ? ", " : "");
    return s + "]";
}

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
