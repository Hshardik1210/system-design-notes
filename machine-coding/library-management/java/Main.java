import java.util.*;

/**
 * LIBRARY MANAGEMENT — books (with multiple physical copies), members,
 * borrow/return, and overdue fines.
 *
 * Modelling notes:
 *   - A Book (by ISBN) can have many BookItems (physical copies with barcodes).
 *   - A member can borrow up to a limit; each loan has a due "day".
 *   - Returning after the due day incurs a per-day fine.
 *
 * "Time" is a simple integer day counter so the demo needs no date libraries.
 *
 * Key classes: Book (catalog title), BookItem (physical copy), Loan (a checkout
 * record), Member (a user), and Library (the service tying it all together).
 *
 * Design patterns / ideas:
 *   - Entity modelling with repository-style maps (copiesByIsbn, itemsByBarcode,
 *     activeLoans) for quick lookups.
 *   - Clear split between catalog item (Book) and inventory item (BookItem):
 *     one title, many copies.
 */
public class Main {

    // Catalog entry: describes a title (identified by its ISBN). One Book can map
    // to many physical copies, so this holds no availability state itself.
    static class Book {
        final String isbn, title, author;
        Book(String isbn, String title, String author) { this.isbn = isbn; this.title = title; this.author = author; }
    }

    // Inventory item: one physical copy of a Book, identified by a unique barcode.
    // The 'borrowed' flag tracks whether this specific copy is currently out.
    static class BookItem {
        final String barcode; final Book book; boolean borrowed = false;
        BookItem(String barcode, Book book) { this.barcode = barcode; this.book = book; }
    }

    // A borrowing record: links one copy to the member who took it, plus the day
    // it was borrowed and the day it is due back (used to compute overdue fines).
    static class Loan {
        final BookItem item; final String memberId; final int borrowedDay; final int dueDay;
        Loan(BookItem item, String memberId, int borrowedDay, int dueDay) {
            this.item = item; this.memberId = memberId; this.borrowedDay = borrowedDay; this.dueDay = dueDay;
        }
    }

    // A library user: remembers which copies they currently hold and any unpaid fine.
    static class Member {
        final String id, name;
        final Set<String> borrowedBarcodes = new HashSet<>();
        double outstandingFine = 0;
        Member(String id, String name) { this.id = id; this.name = name; }
    }

    // The main service. Holds the inventory and members and drives borrow/return.
    // The several maps act as simple repositories (lookup tables) for fast access.
    static class Library {
        static final int LOAN_DAYS = 14;      // how long a copy can be kept before it is due
        static final double FINE_PER_DAY = 5.0; // penalty charged for each day a copy is late
        static final int MAX_BORROW = 3;      // max copies a single member may hold at once

        private final Map<String, List<BookItem>> copiesByIsbn = new HashMap<>(); // ISBN -> all copies of that title
        private final Map<String, BookItem> itemsByBarcode = new HashMap<>();     // barcode -> the one copy
        private final Map<String, Member> members = new HashMap<>();              // memberId -> member
        private final Map<String, Loan> activeLoans = new HashMap<>(); // barcode -> loan (only copies currently out)

        // Register a new member so they can borrow later.
        void addMember(Member m) { members.put(m.id, m); }

        // Add a physical copy to inventory, indexing it both by ISBN and by barcode.
        void addCopy(BookItem item) {
            copiesByIsbn.computeIfAbsent(item.book.isbn, k -> new ArrayList<>()).add(item);
            itemsByBarcode.put(item.barcode, item);
        }

        // Borrow any free copy of an ISBN. Returns the copy given out, or null if
        // the member is at their limit or no copy is available.
        BookItem borrow(String memberId, String isbn, int today) {
            Member m = members.get(memberId);
            // Enforce the per-member borrowing cap before handing out a copy.
            if (m.borrowedBarcodes.size() >= MAX_BORROW) { System.out.println("  ! " + m.name + " hit borrow limit"); return null; }
            for (BookItem item : copiesByIsbn.getOrDefault(isbn, List.of())) {
                if (!item.borrowed) {                 // pick the first copy that is not already out
                    item.borrowed = true;             // mark this copy as taken
                    m.borrowedBarcodes.add(item.barcode);
                    // Create the loan with a due day = today + allowed loan period.
                    activeLoans.put(item.barcode, new Loan(item, memberId, today, today + LOAN_DAYS));
                    System.out.printf("  %s borrowed '%s' [%s], due day %d%n", m.name, item.book.title, item.barcode, today + LOAN_DAYS);
                    return item;
                }
            }
            System.out.println("  ! no free copy of " + isbn); // every copy of this title is checked out
            return null;
        }

        // Return a copy; charge a fine if it comes back after its due day.
        void returnItem(String barcode, int today) {
            Loan loan = activeLoans.remove(barcode); // remove the loan; also tells us if it was actually out
            if (loan == null) { System.out.println("  ! not on loan: " + barcode); return; }
            BookItem item = itemsByBarcode.get(barcode);
            item.borrowed = false;                   // the copy is available again
            Member m = members.get(loan.memberId);
            m.borrowedBarcodes.remove(barcode);
            double fine = 0;
            // Overdue = returned after the due day; fine grows per day late.
            if (today > loan.dueDay) { fine = (today - loan.dueDay) * FINE_PER_DAY; m.outstandingFine += fine; }
            System.out.printf("  %s returned '%s' on day %d%s%n", m.name, item.book.title, today,
                    fine > 0 ? " (fine Rs " + fine + ")" : "");
        }
    }

    // Demo: set up a title with two copies, one member, then run borrow/return
    // scenarios that show the borrow limit and an overdue fine.
    public static void main(String[] args) {
        Library lib = new Library();
        Book clean = new Book("978-0132350884", "Clean Code", "Robert Martin");
        lib.addCopy(new BookItem("BC-1", clean));
        lib.addCopy(new BookItem("BC-2", clean));

        Member alice = new Member("M1", "Alice");
        lib.addMember(alice);

        lib.borrow("M1", "978-0132350884", 0);   // BC-1, due day 14
        lib.borrow("M1", "978-0132350884", 0);   // BC-2, due day 14
        lib.borrow("M1", "978-0132350884", 0);   // no free copy

        lib.returnItem("BC-1", 10);              // on time, no fine
        lib.returnItem("BC-2", 20);              // 6 days late -> Rs 30
        System.out.println("Alice outstanding fine: Rs " + alice.outstandingFine);
    }
}
