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

struct User { string id, name; int reputation = 0; };

struct Votable {
    string id; User* author; string body;
    unordered_map<string, int> votes; // userId -> +1/-1
    Votable(string i, User* a, string b) : id(move(i)), author(a), body(move(b)) {}
    virtual ~Votable() = default;

    int score() const { int s = 0; for (auto& [u, v] : votes) s += v; return s; }
    static int repDelta(int dir) { return dir > 0 ? 10 : dir < 0 ? -2 : 0; }
    void vote(const User& voter, int dir) {
        int prev = votes.count(voter.id) ? votes[voter.id] : 0;
        if (prev == dir) return;
        author->reputation -= repDelta(prev);
        votes[voter.id] = dir;
        author->reputation += repDelta(dir);
    }
};

struct Answer : Votable {
    bool accepted = false;
    Answer(string i, User* a, string b) : Votable(move(i), a, move(b)) {}
};

struct Comment { User* author; string text; };

struct Question : Votable {
    string title;
    set<string> tags;
    vector<shared_ptr<Answer>> answers;
    vector<Comment> comments;
    Question(string i, User* a, string t, string b, set<string> tg)
        : Votable(move(i), a, move(b)), title(move(t)), tags(move(tg)) {}
};

class QAService {
    unordered_map<string, shared_ptr<Question>> questions;
    unordered_map<string, set<string>> byTag; // tag -> question ids
    int seq = 1;
public:
    shared_ptr<Question> ask(User& author, const string& title, const string& body, const set<string>& tags) {
        auto q = make_shared<Question>("Q" + to_string(seq++), &author, title, body, tags);
        questions[q->id] = q;
        for (auto& t : tags) byTag[t].insert(q->id);
        return q;
    }
    shared_ptr<Answer> answer(Question& q, User& author, const string& body) {
        auto a = make_shared<Answer>("A" + to_string(seq++), &author, body);
        q.answers.push_back(a);
        return a;
    }
    void accept(Question&, Answer& a) { a.accepted = true; a.author->reputation += 15; }
    vector<shared_ptr<Question>> search(const string& tag) {
        vector<shared_ptr<Question>> res;
        for (auto& qid : byTag[tag]) res.push_back(questions[qid]);
        return res;
    }
};

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
