# 💬 Stack Overflow / Q&A

Questions, answers, comments, voting, reputation, and tag search.

## Design highlights
- **`Votable` base class** — Questions and Answers both support up/down votes with **one vote per user** (`userId → +1/-1`), so a user can't vote twice and can switch their vote.
- **Reputation** — voting adjusts the *author's* reputation in one place: **+10** upvote, **−2** downvote. Switching a vote correctly undoes the previous effect before applying the new one.
- **Accepted answer** — grants the answerer **+15**.
- **Tag index** — `tag → questionIds` enables `search(tag)`.

## Design patterns
| Pattern | Where | Why |
| --- | --- | --- |
| Template/inheritance | `Votable` → Question/Answer | Share voting logic |
| Inverted index | `byTag` | Search questions by tag |

## Run
```bash
cd java && javac Main.java && java Main
cd cpp && g++ -std=c++17 main.cpp -o main && ./main
```
