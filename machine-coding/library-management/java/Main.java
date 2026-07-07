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
 */
public class Main {

    static class Book {
        final String isbn, title, author;
        Book(String isbn, String title, String author) { this.isbn = isbn; this.title = title; this.author = author; }
    }

    static class BookItem {
        final String barcode; final Book book; boolean borrowed = false;
        BookItem(String barcode, Book book) { this.barcode = barcode; this.book = book; }
    }

    static class Loan {
        final BookItem item; final String memberId; final int borrowedDay; final int dueDay;
        Loan(BookItem item, String memberId, int borrowedDay, int dueDay) {
            this.item = item; this.memberId = memberId; this.borrowedDay = borrowedDay; this.dueDay = dueDay;
        }
    }

    static class Member {
        final String id, name;
        final Set<String> borrowedBarcodes = new HashSet<>();
        double outstandingFine = 0;
        Member(String id, String name) { this.id = id; this.name = name; }
    }

    static class Library {
        static final int LOAN_DAYS = 14;
        static final double FINE_PER_DAY = 5.0;
        static final int MAX_BORROW = 3;

        private final Map<String, List<BookItem>> copiesByIsbn = new HashMap<>();
        private final Map<String, BookItem> itemsByBarcode = new HashMap<>();
        private final Map<String, Member> members = new HashMap<>();
        private final Map<String, Loan> activeLoans = new HashMap<>(); // barcode -> loan

        void addMember(Member m) { members.put(m.id, m); }
        void addCopy(BookItem item) {
            copiesByIsbn.computeIfAbsent(item.book.isbn, k -> new ArrayList<>()).add(item);
            itemsByBarcode.put(item.barcode, item);
        }

        // Borrow any free copy of an ISBN.
        BookItem borrow(String memberId, String isbn, int today) {
            Member m = members.get(memberId);
            if (m.borrowedBarcodes.size() >= MAX_BORROW) { System.out.println("  ! " + m.name + " hit borrow limit"); return null; }
            for (BookItem item : copiesByIsbn.getOrDefault(isbn, List.of())) {
                if (!item.borrowed) {
                    item.borrowed = true;
                    m.borrowedBarcodes.add(item.barcode);
                    activeLoans.put(item.barcode, new Loan(item, memberId, today, today + LOAN_DAYS));
                    System.out.printf("  %s borrowed '%s' [%s], due day %d%n", m.name, item.book.title, item.barcode, today + LOAN_DAYS);
                    return item;
                }
            }
            System.out.println("  ! no free copy of " + isbn);
            return null;
        }

        // Return a copy; charge a fine if overdue.
        void returnItem(String barcode, int today) {
            Loan loan = activeLoans.remove(barcode);
            if (loan == null) { System.out.println("  ! not on loan: " + barcode); return; }
            BookItem item = itemsByBarcode.get(barcode);
            item.borrowed = false;
            Member m = members.get(loan.memberId);
            m.borrowedBarcodes.remove(barcode);
            double fine = 0;
            if (today > loan.dueDay) { fine = (today - loan.dueDay) * FINE_PER_DAY; m.outstandingFine += fine; }
            System.out.printf("  %s returned '%s' on day %d%s%n", m.name, item.book.title, today,
                    fine > 0 ? " (fine Rs " + fine + ")" : "");
        }
    }

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
