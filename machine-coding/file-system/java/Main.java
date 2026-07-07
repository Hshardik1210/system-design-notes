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

    static abstract class Node {
        final String name;
        Node(String name) { this.name = name; }
        abstract boolean isDir();
        abstract int size(); // bytes; dir = sum of children
    }

    static class FileNode extends Node {
        StringBuilder content = new StringBuilder();
        FileNode(String name) { super(name); }
        boolean isDir() { return false; }
        int size() { return content.length(); }
    }

    static class DirNode extends Node {
        // Sorted map so ls output is alphabetical.
        final TreeMap<String, Node> children = new TreeMap<>();
        DirNode(String name) { super(name); }
        boolean isDir() { return true; }
        int size() { int s = 0; for (Node n : children.values()) s += n.size(); return s; }
    }

    static class FileSystem {
        private final DirNode root = new DirNode("/");

        // Split "/a/b" -> [a, b], ignoring empty parts.
        private String[] parts(String path) {
            List<String> p = new ArrayList<>();
            for (String s : path.split("/")) if (!s.isEmpty()) p.add(s);
            return p.toArray(new String[0]);
        }

        // Create all directories along the path (mkdir -p).
        DirNode mkdirs(String path) {
            DirNode cur = root;
            for (String part : parts(path)) {
                Node next = cur.children.get(part);
                if (next == null) { DirNode d = new DirNode(part); cur.children.put(part, d); cur = d; }
                else if (next.isDir()) cur = (DirNode) next;
                else throw new IllegalArgumentException(part + " is a file, not a dir");
            }
            return cur;
        }

        // Create/overwrite a file and set its content (parent dirs auto-created).
        void writeFile(String path, String content) {
            String[] p = parts(path);
            String fname = p[p.length - 1];
            // Walk (creating) all parent directories.
            DirNode dir = root;
            for (int i = 0; i < p.length - 1; i++) {
                Node next = dir.children.get(p[i]);
                if (next == null) { DirNode d = new DirNode(p[i]); dir.children.put(p[i], d); dir = d; }
                else if (next.isDir()) dir = (DirNode) next;
                else throw new IllegalArgumentException(p[i] + " is a file, not a dir");
            }
            FileNode f = new FileNode(fname);
            f.content.append(content);
            dir.children.put(fname, f);
        }

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
        Node resolve(String path) {
            if (path.equals("/")) return root;
            Node cur = root;
            for (String part : parts(path)) {
                if (!cur.isDir()) return null;
                cur = ((DirNode) cur).children.get(part);
                if (cur == null) return null;
            }
            return cur;
        }

        // Recursively find files/dirs whose name contains a substring.
        List<String> find(String substring) {
            List<String> hits = new ArrayList<>();
            for (Node child : root.children.values()) dfs(child, "", substring, hits);
            return hits;
        }
        // 'parentPath' is the absolute path of the node's parent (root = "").
        private void dfs(Node node, String parentPath, String needle, List<String> hits) {
            String path = parentPath + "/" + node.name;
            if (node.name.contains(needle)) hits.add(path);
            if (node.isDir())
                for (Node child : ((DirNode) node).children.values()) dfs(child, path, needle, hits);
        }

        int size(String path) { Node n = resolve(path); return n == null ? 0 : n.size(); }
    }

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
