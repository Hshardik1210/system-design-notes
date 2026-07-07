import java.util.*;

/**
 * STACK OVERFLOW / Q&A — questions, answers, comments, voting, reputation, tags.
 *
 * Design highlights:
 *   - Question and Answer share a Votable base (up/down votes, one vote per user).
 *   - Voting adjusts the AUTHOR's reputation (+10 upvote, -2 downvote) — the
 *     Observer-ish reputation update lives in one place.
 *   - Accepting an answer gives the answerer +15.
 *   - Tag index lets you search questions by tag.
 */
public class Main {

    // A person on the site. Their reputation goes up/down based on how the
    // community votes on the questions and answers they wrote.
    static class User {
        final String id, name; int reputation = 0;
        User(String id, String name) { this.id = id; this.name = name; }
    }

    // Common voting behaviour for questions and answers.
    // Both Question and Answer extend this so the voting/scoring logic is
    // written once here and reused (inheritance / template pattern).
    static abstract class Votable {
        final String id; final User author; final String body;
        // userId -> +1/-1 so a user can't vote twice (and can switch vote).
        final Map<String, Integer> votes = new HashMap<>();
        Votable(String id, User author, String body) { this.id = id; this.author = author; this.body = body; }

        // Score is just the sum of every user's vote (+1 up / -1 down).
        int score() { int s = 0; for (int v : votes.values()) s += v; return s; }

        // Apply a vote (+1 up, -1 down); reverses the prior vote's rep effect if any.
        // Because we always undo the old vote before applying the new one, a user
        // switching from up to down (or vice versa) adjusts reputation correctly.
        void vote(User voter, int dir) {
            int prev = votes.getOrDefault(voter.id, 0);
            if (prev == dir) return;             // same vote again -> no-op
            author.reputation -= repDelta(prev); // undo old effect
            votes.put(voter.id, dir);
            author.reputation += repDelta(dir);  // apply new effect
        }
        // Reputation rule in one place: upvote gives +10, downvote gives -2, no vote 0.
        private int repDelta(int dir) { return dir > 0 ? 10 : dir < 0 ? -2 : 0; }
    }

    // An answer to a question; can be marked as the accepted (best) answer.
    static class Answer extends Votable {
        boolean accepted = false;
        Answer(String id, User author, String body) { super(id, author, body); }
    }

    // A short text note attached to a question (not votable, no reputation effect).
    static class Comment {
        final User author; final String text;
        Comment(User author, String text) { this.author = author; this.text = text; }
    }

    // A question, which holds its title, tags, and lists of answers and comments.
    static class Question extends Votable {
        final String title;
        final Set<String> tags;
        final List<Answer> answers = new ArrayList<>();
        final List<Comment> comments = new ArrayList<>();
        Question(String id, User author, String title, String body, Set<String> tags) {
            super(id, author, body); this.title = title; this.tags = tags;
        }
    }

    // The main service: stores all questions and provides ask/answer/accept/search.
    static class QAService {
        private final Map<String, Question> questions = new HashMap<>();
        private final Map<String, Set<String>> byTag = new HashMap<>(); // tag -> questionIds
        private int seq = 1;

        // Create a new question, store it, and index it under each of its tags
        // so search(tag) can find it later (inverted index).
        Question ask(User author, String title, String body, Set<String> tags) {
            Question q = new Question("Q" + seq++, author, title, body, tags);
            questions.put(q.id, q);
            for (String t : tags) byTag.computeIfAbsent(t, k -> new LinkedHashSet<>()).add(q.id);
            return q;
        }
        // Add a new answer to the given question.
        Answer answer(Question q, User author, String body) {
            Answer a = new Answer("A" + seq++, author, body);
            q.answers.add(a);
            return a;
        }
        // Mark an answer as accepted and reward its author with bonus reputation.
        void accept(Question q, Answer a) {
            a.accepted = true;
            a.author.reputation += 15; // accepted-answer bonus
        }
        // Return all questions that were tagged with the given tag.
        List<Question> search(String tag) {
            List<Question> res = new ArrayList<>();
            for (String qid : byTag.getOrDefault(tag, Set.of())) res.add(questions.get(qid));
            return res;
        }
    }

    // Demo: create users, ask a question, add answers, cast votes, accept an
    // answer, then print the resulting scores, reputations, and a tag search.
    public static void main(String[] args) {
        QAService svc = new QAService();
        User alice = new User("u1", "Alice");
        User bob = new User("u2", "Bob");
        User carol = new User("u3", "Carol");

        Question q = svc.ask(alice, "How does HashMap work?", "Internals?", Set.of("java", "collections"));
        Answer a1 = svc.answer(q, bob, "Buckets + linked lists / trees.");
        Answer a2 = svc.answer(q, carol, "Hashing to index, resize at load factor.");

        // Votes
        q.vote(bob, +1);      // Alice +10
        a1.vote(alice, +1);   // Bob +10
        a1.vote(carol, +1);   // Bob +10
        a2.vote(bob, -1);     // Carol -2
        svc.accept(q, a1);    // Bob +15

        System.out.println("Question score: " + q.score());
        System.out.println("A1 score: " + a1.score() + " accepted=" + a1.accepted);
        System.out.println("A2 score: " + a2.score());
        System.out.println("Reputation -> Alice:" + alice.reputation + " Bob:" + bob.reputation + " Carol:" + carol.reputation);

        System.out.print("Search tag 'java': ");
        for (Question r : svc.search("java")) System.out.print(r.title + " | ");
        System.out.println();
    }
}
