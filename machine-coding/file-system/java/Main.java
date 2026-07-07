import java.util.*;

/**
 * IN-MEMORY FILE SYSTEM — Composite pattern.
 *
 * A directory and a file are both "nodes" (Composite pattern): a File is a leaf,
 * a Directory holds children (files or sub-directories). This lets us treat the
 * whole tree uniformly for traversal (ls, find, size).
 *
 * Supports: mkdir -p, addFile, ls, read/write file content, recursive size, find.
 * Paths are absolute like "/a/b/c".
 */
public class Main {

    // Composite "component": the common abstraction for both files and directories.
    // Because everything is a Node with size()/isDir(), callers can treat leaves and
    // composites the same way when walking the tree.
    static abstract class Node {
        final String name;
        Node(String name) { this.name = name; }
        abstract boolean isDir();
        abstract int size(); // bytes; dir = sum of children
    }

    // Composite "leaf": a file. It has no children, just text content.
    static class FileNode extends Node {
        StringBuilder content = new StringBuilder();
        FileNode(String name) { super(name); }
        boolean isDir() { return false; }
        // A file's size is simply the number of characters it holds.
        int size() { return content.length(); }
    }

    // Composite "composite": a directory that holds child nodes (files or sub-dirs).
    static class DirNode extends Node {
        // Sorted map so ls output is alphabetical.
        final TreeMap<String, Node> children = new TreeMap<>();
        DirNode(String name) { super(name); }
        boolean isDir() { return true; }
        // Recursive size: a directory's size is the sum of all its children's sizes.
        int size() { int s = 0; for (Node n : children.values()) s += n.size(); return s; }
    }

    // The high-level API over the node tree: the operations a user actually calls
    // (mkdirs, writeFile, ls, size, find, ...). It hides tree-walking details.
    static class FileSystem {
        private final DirNode root = new DirNode("/");

        // Split "/a/b" -> [a, b], ignoring empty parts.
        private String[] parts(String path) {
            List<String> p = new ArrayList<>();
            for (String s : path.split("/")) if (!s.isEmpty()) p.add(s);
            return p.toArray(new String[0]);
        }

        // Create all directories along the path (mkdir -p).
        // Walk each path segment: create the dir if missing, descend if it already
        // exists as a dir, or fail if a file blocks the way.
        DirNode mkdirs(String path) {
            DirNode cur = root;
            for (String part : parts(path)) {
                Node next = cur.children.get(part);
                if (next == null) { DirNode d = new DirNode(part); cur.children.put(part, d); cur = d; }  // missing -> create and step in
                else if (next.isDir()) cur = (DirNode) next;                                              // exists as dir -> step in
                else throw new IllegalArgumentException(part + " is a file, not a dir");                  // a file blocks the path
            }
            return cur;
        }

        // Create/overwrite a file and set its content (parent dirs auto-created).
        void writeFile(String path, String content) {
            String[] p = parts(path);
            String fname = p[p.length - 1];  // last segment is the file name; the rest are parent dirs
            // Walk (creating) all parent directories, stopping before the file name.
            DirNode dir = root;
            for (int i = 0; i < p.length - 1; i++) {
                Node next = dir.children.get(p[i]);
                if (next == null) { DirNode d = new DirNode(p[i]); dir.children.put(p[i], d); dir = d; }
                else if (next.isDir()) dir = (DirNode) next;
                else throw new IllegalArgumentException(p[i] + " is a file, not a dir");
            }
            // Put a fresh file node into the parent dir (overwrites any existing entry).
            FileNode f = new FileNode(fname);
            f.content.append(content);
            dir.children.put(fname, f);
        }

        // Return a file's text content; error if the path is missing or is a directory.
        String readFile(String path) {
            Node n = resolve(path);
            if (n == null || n.isDir()) throw new NoSuchElementException("not a file: " + path);
            return ((FileNode) n).content.toString();
        }

        // List names directly under a directory path.
        List<String> ls(String path) {
            Node n = resolve(path);
            if (n == null) throw new NoSuchElementException("no such path: " + path);
            if (!n.isDir()) return List.of(n.name);        // ls on a file -> its name
            return new ArrayList<>(((DirNode) n).children.keySet());
        }

        // Resolve a path to a node, or null.
        // Start at root and step down one segment at a time; bail out (null) if a
        // segment is missing or if we try to descend into a file.
        Node resolve(String path) {
            if (path.equals("/")) return root;
            Node cur = root;
            for (String part : parts(path)) {
                if (!cur.isDir()) return null;                 // can't descend into a file
                cur = ((DirNode) cur).children.get(part);
                if (cur == null) return null;                  // segment doesn't exist
            }
            return cur;
        }

        // Recursively find files/dirs whose name contains a substring.
        // Kicks off a depth-first search from each top-level child.
        List<String> find(String substring) {
            List<String> hits = new ArrayList<>();
            for (Node child : root.children.values()) dfs(child, "", substring, hits);
            return hits;
        }
        // Depth-first walk: 'parentPath' is the absolute path of the node's parent (root = "").
        private void dfs(Node node, String parentPath, String needle, List<String> hits) {
            String path = parentPath + "/" + node.name;                    // build this node's full path
            if (node.name.contains(needle)) hits.add(path);                // record a match
            if (node.isDir())                                              // recurse into sub-directories
                for (Node child : ((DirNode) node).children.values()) dfs(child, path, needle, hits);
        }

        // Recursive byte size of a path (0 if the path doesn't exist).
        int size(String path) { Node n = resolve(path); return n == null ? 0 : n.size(); }
    }

    // Demo: build a small tree, then exercise ls / read / size / find.
    public static void main(String[] args) {
        FileSystem fs = new FileSystem();
        fs.mkdirs("/home/alice/docs");
        fs.writeFile("/home/alice/docs/resume.txt", "Experienced engineer...");
        fs.writeFile("/home/alice/notes.md", "# TODO\n- practice LLD");
        fs.mkdirs("/home/bob");
        fs.writeFile("/home/bob/hello.txt", "hi");

        System.out.println("ls /home           : " + fs.ls("/home"));
        System.out.println("ls /home/alice     : " + fs.ls("/home/alice"));
        System.out.println("ls /home/alice/docs: " + fs.ls("/home/alice/docs"));
        System.out.println("read resume.txt    : '" + fs.readFile("/home/alice/docs/resume.txt") + "'");
        System.out.println("size /home/alice   : " + fs.size("/home/alice") + " bytes");
        System.out.println("find '.txt'        : " + fs.find(".txt"));
    }
}
