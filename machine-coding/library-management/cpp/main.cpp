// LIBRARY MANAGEMENT — books/copies, members, borrow/return, overdue fines (C++17)
//
// A Book (ISBN) has many BookItems (physical copies). Members borrow up to a
// limit; each loan has a due day; returning late incurs a per-day fine.
// "Time" is an integer day counter (no date libraries needed).

#include <iostream>
#include <string>
#include <vector>
#include <unordered_map>
#include <unordered_set>
#include <cstdio>
using namespace std;

struct Book { string isbn, title, author; };
struct BookItem { string barcode; Book book; bool borrowed = false; };
struct Loan { string barcode, memberId; int borrowedDay, dueDay; };
struct Member {
    string id, name;
    unordered_set<string> borrowed;
    double outstandingFine = 0;
};

class Library {
    static const int LOAN_DAYS = 14;
    static constexpr double FINE_PER_DAY = 5.0;
    static const int MAX_BORROW = 3;

    unordered_map<string, vector<BookItem*>> copiesByIsbn;
    unordered_map<string, BookItem*> itemsByBarcode;
    unordered_map<string, Member> members;
    unordered_map<string, Loan> activeLoans; // barcode -> loan
    vector<BookItem*> owned;                 // ownership
public:
    ~Library() { for (auto* it : owned) delete it; }

    void addMember(const Member& m) { members[m.id] = m; }
    void addCopy(const string& barcode, const Book& book) {
        auto* item = new BookItem{barcode, book, false};
        owned.push_back(item);
        copiesByIsbn[book.isbn].push_back(item);
        itemsByBarcode[barcode] = item;
    }

    BookItem* borrow(const string& memberId, const string& isbn, int today) {
        Member& m = members[memberId];
        if ((int)m.borrowed.size() >= MAX_BORROW) { cout << "  ! " << m.name << " hit borrow limit\n"; return nullptr; }
        for (auto* item : copiesByIsbn[isbn]) {
            if (!item->borrowed) {
                item->borrowed = true;
                m.borrowed.insert(item->barcode);
                activeLoans[item->barcode] = {item->barcode, memberId, today, today + LOAN_DAYS};
                printf("  %s borrowed '%s' [%s], due day %d\n",
                       m.name.c_str(), item->book.title.c_str(), item->barcode.c_str(), today + LOAN_DAYS);
                return item;
            }
        }
        cout << "  ! no free copy of " << isbn << "\n";
        return nullptr;
    }

    void returnItem(const string& barcode, int today) {
        auto it = activeLoans.find(barcode);
        if (it == activeLoans.end()) { cout << "  ! not on loan: " << barcode << "\n"; return; }
        Loan loan = it->second; activeLoans.erase(it);
        BookItem* item = itemsByBarcode[barcode];
        item->borrowed = false;
        Member& m = members[loan.memberId];
        m.borrowed.erase(barcode);
        double fine = 0;
        if (today > loan.dueDay) { fine = (today - loan.dueDay) * FINE_PER_DAY; m.outstandingFine += fine; }
        printf("  %s returned '%s' on day %d%s\n", m.name.c_str(), item->book.title.c_str(), today,
               fine > 0 ? (" (fine Rs " + to_string((int)fine) + ")").c_str() : "");
    }

    double fineOf(const string& memberId) { return members[memberId].outstandingFine; }
};

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
