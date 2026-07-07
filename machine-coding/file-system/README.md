# 📁 In-Memory File System

Directories and files as a tree, with `mkdir -p`, read/write, `ls`, recursive `size`, and `find`.

## Composite pattern
A **File** is a leaf; a **Directory** is a composite holding children (files or sub-dirs). Both are `Node`s with `size()`, so the whole tree is traversed **uniformly**:
- `Directory.size()` = sum of children's sizes (recursion via the composite).
- `find(substring)` = DFS over the tree collecting matching paths.

Paths are absolute (`/home/alice/docs`), split on `/`. Children are stored in a **sorted map** so `ls` output is alphabetical.

## Operations
| Op | Behaviour |
| --- | --- |
| `mkdirs(path)` | create all missing dirs along the path |
| `writeFile(path, content)` | auto-create parent dirs, create/overwrite file |
| `ls(path)` | list immediate children (or the file's own name) |
| `size(path)` | recursive byte size |
| `find(sub)` | all node paths whose name contains `sub` |

## Design patterns
| Pattern | Where | Why |
| --- | --- | --- |
| Composite | `Node` → File / Directory | Uniform tree traversal |

## Run
```bash
cd java && javac Main.java && java Main
cd cpp && g++ -std=c++17 main.cpp -o main && ./main
```
