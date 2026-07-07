// LIBRARY MANAGEMENT — books/copies, members, borrow/return, overdue fines (C++17)
//
// A Book (ISBN) has many BookItems (physical copies). Members borrow up to a
// limit; each loan has a due day; returning late incurs a per-day fine.
// "Time" is an integer day counter (no date libraries needed).
//
// Key types: Book (catalog title), BookItem (physical copy), Loan (a checkout
// record), Member (a user), and Library (the service tying it all together).
//
// Design patterns / ideas:
//   - Entity modelling with repository-style maps (copiesByIsbn, itemsByBarcode,
//     activeLoans) for quick lookups.
//   - Clear split between catalog item (Book) and inventory item (BookItem):
//     one title, many copies.

#include <iostream>
#include <string>
#include <vector>
#include <unordered_map>
#include <unordered_set>
#include <cstdio>
using namespace std;

// Catalog entry: one title identified by its ISBN (holds no availability state).
struct Book { string isbn, title, author; };
// One physical copy of a Book; 'borrowed' says whether this copy is currently out.
struct BookItem { string barcode; Book book; bool borrowed = false; };
// A borrowing record linking a copy (barcode) to a member, with due-day info.
struct Loan { string barcode, memberId; int borrowedDay, dueDay; };
// A library user: tracks copies they hold and any unpaid fine.
struct Member {
    string id, name;
    unordered_set<string> borrowed;
    double outstandingFine = 0;
};

// The main service: owns the inventory and members and drives borrow/return.
// The maps act as simple repositories (lookup tables) for fast access.
class Library {
    static const int LOAN_DAYS = 14;         // days allowed before a copy is due
    static constexpr double FINE_PER_DAY = 5.0; // penalty per day a copy is late
    static const int MAX_BORROW = 3;         // max copies one member may hold at once

    unordered_map<string, vector<BookItem*>> copiesByIsbn; // ISBN -> all copies of that title
    unordered_map<string, BookItem*> itemsByBarcode;       // barcode -> the one copy
    unordered_map<string, Member> members;                 // memberId -> member
    unordered_map<string, Loan> activeLoans; // barcode -> loan (only copies currently out)
    vector<BookItem*> owned;                 // ownership: every copy we new'd, so we can delete them
public:
    // Destructor frees every heap-allocated copy tracked in 'owned'.
    ~Library() { for (auto* it : owned) delete it; }

    // Register a member so they can borrow later.
    void addMember(const Member& m) { members[m.id] = m; }

    // Add a physical copy to inventory, indexing it by ISBN and by barcode.
    void addCopy(const string& barcode, const Book& book) {
        auto* item = new BookItem{barcode, book, false};
        owned.push_back(item);               // remember it for cleanup in the destructor
        copiesByIsbn[book.isbn].push_back(item);
        itemsByBarcode[barcode] = item;
    }

    // Borrow any free copy of an ISBN. Returns the copy given out, or nullptr if
    // the member is at their limit or no copy is available.
    BookItem* borrow(const string& memberId, const string& isbn, int today) {
        Member& m = members[memberId];
        // Enforce the per-member borrowing cap before handing out a copy.
        if ((int)m.borrowed.size() >= MAX_BORROW) { cout << "  ! " << m.name << " hit borrow limit\n"; return nullptr; }
        for (auto* item : copiesByIsbn[isbn]) {
            if (!item->borrowed) {            // pick the first copy that is not already out
                item->borrowed = true;        // mark this copy as taken
                m.borrowed.insert(item->barcode);
                // Create the loan with due day = today + allowed loan period.
                activeLoans[item->barcode] = {item->barcode, memberId, today, today + LOAN_DAYS};
                printf("  %s borrowed '%s' [%s], due day %d\n",
                       m.name.c_str(), item->book.title.c_str(), item->barcode.c_str(), today + LOAN_DAYS);
                return item;
            }
        }
        cout << "  ! no free copy of " << isbn << "\n"; // every copy of this title is checked out
        return nullptr;
    }

    // Return a copy; charge a fine if it comes back after its due day.
    void returnItem(const string& barcode, int today) {
        auto it = activeLoans.find(barcode); // find the loan; missing means it wasn't out
        if (it == activeLoans.end()) { cout << "  ! not on loan: " << barcode << "\n"; return; }
        Loan loan = it->second; activeLoans.erase(it);
        BookItem* item = itemsByBarcode[barcode];
        item->borrowed = false;              // the copy is available again
        Member& m = members[loan.memberId];
        m.borrowed.erase(barcode);
        double fine = 0;
        // Overdue = returned after the due day; fine grows per day late.
        if (today > loan.dueDay) { fine = (today - loan.dueDay) * FINE_PER_DAY; m.outstandingFine += fine; }
        printf("  %s returned '%s' on day %d%s\n", m.name.c_str(), item->book.title.c_str(), today,
               fine > 0 ? (" (fine Rs " + to_string((int)fine) + ")").c_str() : "");
    }

    // Look up a member's total unpaid fine.
    double fineOf(const string& memberId) { return members[memberId].outstandingFine; }
};

// Demo: set up a title with two copies, one member, then run borrow/return
// scenarios that show the borrow limit and an overdue fine.
int main() {
    Library lib;
    Book clean{"978-0132350884", "Clean Code", "Robert Martin"};
    lib.addCopy("BC-1", clean);
    lib.addCopy("BC-2", clean);
    lib.addMember({"M1", "Alice", {}, 0});

    lib.borrow("M1", "978-0132350884", 0); // BC-1
    lib.borrow("M1", "978-0132350884", 0); // BC-2
    lib.borrow("M1", "978-0132350884", 0); // none free

    lib.returnItem("BC-1", 10); // on time
    lib.returnItem("BC-2", 20); // 6 days late -> Rs 30
    cout << "Alice outstanding fine: Rs " << lib.fineOf("M1") << "\n";
    return 0;
}
