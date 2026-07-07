// STACK OVERFLOW / Q&A — voting, reputation, tags, comments (C++17)
//
// Question & Answer share a Votable base (up/down votes, one per user).
// Voting adjusts the AUTHOR's reputation (+10 up, -2 down); accepting an answer
// gives +15. A tag index enables search by tag.

#include <iostream>
#include <string>
#include <vector>
#include <unordered_map>
#include <unordered_set>
#include <set>
#include <memory>
using namespace std;

// A person on the site; reputation changes as others vote on their posts.
struct User { string id, name; int reputation = 0; };

// Common voting behaviour shared by Question and Answer (base class).
// The voting/scoring logic is written once here and reused via inheritance.
struct Votable {
    string id; User* author; string body;
    unordered_map<string, int> votes; // userId -> +1/-1 (one vote per user)
    Votable(string i, User* a, string b) : id(move(i)), author(a), body(move(b)) {}
    virtual ~Votable() = default;

    // Score is the sum of all votes (+1 up / -1 down).
    int score() const { int s = 0; for (auto& [u, v] : votes) s += v; return s; }
    // Reputation rule in one place: upvote +10, downvote -2, no vote 0.
    static int repDelta(int dir) { return dir > 0 ? 10 : dir < 0 ? -2 : 0; }
    // Apply a vote (+1 up, -1 down). We undo the previous vote's reputation
    // effect first, so switching an up/down vote adjusts the author correctly.
    void vote(const User& voter, int dir) {
        int prev = votes.count(voter.id) ? votes[voter.id] : 0;
        if (prev == dir) return;                 // same vote again -> no-op
        author->reputation -= repDelta(prev);    // undo old effect
        votes[voter.id] = dir;
        author->reputation += repDelta(dir);     // apply new effect
    }
};

// An answer to a question; can be marked as the accepted (best) answer.
struct Answer : Votable {
    bool accepted = false;
    Answer(string i, User* a, string b) : Votable(move(i), a, move(b)) {}
};

// A short text note on a question (not votable, no reputation effect).
struct Comment { User* author; string text; };

// A question with its title, tags, and lists of answers and comments.
struct Question : Votable {
    string title;
    set<string> tags;
    vector<shared_ptr<Answer>> answers;
    vector<Comment> comments;
    Question(string i, User* a, string t, string b, set<string> tg)
        : Votable(move(i), a, move(b)), title(move(t)), tags(move(tg)) {}
};

// The main service: stores questions and offers ask/answer/accept/search.
class QAService {
    unordered_map<string, shared_ptr<Question>> questions;
    unordered_map<string, set<string>> byTag; // tag -> question ids (inverted index)
    int seq = 1;
public:
    // Create a question, store it, and index it under each tag for later search.
    shared_ptr<Question> ask(User& author, const string& title, const string& body, const set<string>& tags) {
        auto q = make_shared<Question>("Q" + to_string(seq++), &author, title, body, tags);
        questions[q->id] = q;
        for (auto& t : tags) byTag[t].insert(q->id);
        return q;
    }
    // Add a new answer to the given question.
    shared_ptr<Answer> answer(Question& q, User& author, const string& body) {
        auto a = make_shared<Answer>("A" + to_string(seq++), &author, body);
        q.answers.push_back(a);
        return a;
    }
    // Mark an answer accepted and reward its author with bonus reputation (+15).
    void accept(Question&, Answer& a) { a.accepted = true; a.author->reputation += 15; }
    // Return all questions tagged with the given tag (uses the byTag index).
    vector<shared_ptr<Question>> search(const string& tag) {
        vector<shared_ptr<Question>> res;
        for (auto& qid : byTag[tag]) res.push_back(questions[qid]);
        return res;
    }
};

// Demo: create users, ask a question, add answers, cast votes, accept an
// answer, then print scores, reputations, and a tag search.
int main() {
    QAService svc;
    User alice{"u1", "Alice", 0}, bob{"u2", "Bob", 0}, carol{"u3", "Carol", 0};

    auto q = svc.ask(alice, "How does HashMap work?", "Internals?", {"java", "collections"});
    auto a1 = svc.answer(*q, bob, "Buckets + linked lists / trees.");
    auto a2 = svc.answer(*q, carol, "Hashing to index, resize at load factor.");

    q->vote(bob, +1);    // Alice +10
    a1->vote(alice, +1); // Bob +10
    a1->vote(carol, +1); // Bob +10
    a2->vote(bob, -1);   // Carol -2
    svc.accept(*q, *a1); // Bob +15

    cout << "Question score: " << q->score() << "\n";
    cout << "A1 score: " << a1->score() << " accepted=" << (a1->accepted ? "true" : "false") << "\n";
    cout << "A2 score: " << a2->score() << "\n";
    cout << "Reputation -> Alice:" << alice.reputation << " Bob:" << bob.reputation << " Carol:" << carol.reputation << "\n";

    cout << "Search tag 'java': ";
    for (auto& r : svc.search("java")) cout << r->title << " | ";
    cout << "\n";
    return 0;
}
