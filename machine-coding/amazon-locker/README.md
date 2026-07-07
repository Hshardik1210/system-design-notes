# 📦 Amazon Locker

Assign delivered packages to lockers by size, issue a pickup code, and free the locker on collection.

## Best-fit allocation
A package **fits** a locker when `package.size ≤ locker.size` (a small package can use a bigger locker if needed). We pick the **smallest** fitting free locker (**best-fit**) so large lockers stay available for large packages.

```
deposit(SMALL) → smallest free locker that fits → pickup code
pickup(code)   → locker freed
```

If no locker fits, the deposit is rejected. Pickup codes map to locker ids; an invalid code is rejected.

## Design patterns
- Best-fit resource allocation (same idea as the parking-lot spot allocator).
- Code → resource mapping for retrieval.

## Run
```bash
cd java && javac Main.java && java Main
cd cpp && g++ -std=c++17 main.cpp -o main && ./main
```
