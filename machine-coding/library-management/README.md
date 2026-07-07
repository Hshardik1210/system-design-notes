# 📚 Library Management System

Manage books, members, borrowing/returning, and overdue fines.

## Modelling
- **Book** (by ISBN) vs **BookItem** (a physical copy with a barcode) — one book, many copies. This separation is the key insight interviewers look for.
- **Member** — tracks borrowed copies and outstanding fines; capped at `MAX_BORROW`.
- **Loan** — links a copy to a member with a `borrowedDay` and `dueDay`.
- **Fine** — `(returnDay − dueDay) × FINE_PER_DAY` when returned late.

Borrowing picks **any free copy** of the requested ISBN. Time is an integer day counter to stay dependency-free (swap for real dates).

## Design patterns
- Entity modelling / repository-style maps (`copiesByIsbn`, `itemsByBarcode`, `activeLoans`).
- Clear separation of catalog item (Book) vs inventory item (BookItem).

## Run
```bash
cd java && javac Main.java && java Main
cd cpp && g++ -std=c++17 main.cpp -o main && ./main
```
