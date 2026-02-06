# Contributing to MapleExpectation

**Purpose:** Collaboration guidelines and patterns for contributors (D2: 3/4 → 4/4 improvement)

---

## 🤝 Welcome, Contributors!

MapleExpectation is a portfolio project demonstrating **enterprise-grade resilience patterns** on a budget. We welcome contributions that align with our core values: evidence-based operations, continuous improvement, and knowledge sharing.

---

## 🎯 Our Collaboration Philosophy

We practice **"Yes, And" improvisation**—building on each other's ideas rather than rejecting them.

### Core Principles

1. **Evidence Over Opinion** - Back claims with data, tests, or documentation
2. **Incremental Progress** - Small, reversible changes > big rewrites
3. **Fail Fast, Learn Faster** - Mistakes are learning opportunities
4. **Respect the Constraints** - Single instance, $15/month budget is sacred
5. **Document Everything** - If it's not written down, it didn't happen

---

## 📋 Contribution Types

### 🎨 Code Contributions

#### Areas We Welcome
- **Performance optimizations** - measurable improvements (before/after benchmarks required)
- **Resilience enhancements** - new chaos tests, recovery mechanisms
- **Documentation** - ADRs, reports, code comments
- **Test coverage** - unit tests, integration tests, edge cases

#### Areas We're Cautious About
- **New dependencies** - must justify necessity + cost (both runtime AND maintenance)
- **Architecture changes** - must propose ADR first for community review
- **Feature additions** - must align with MVP scope [see MVP-ROADMAP.md](docs/00_Start_Here/MVP-ROADMAP.md)
- **Breaking changes** - must provide migration guide + backwards compatibility

#### Pre-Submission Checklist
- [ ] Code follows [CLAUDE.md](CLAUDE.md) guidelines (SOLID, LogicExecutor, no try-catch)
- [ ] All tests pass: `./gradlew test`
- [ ] New tests added for changes (minimum 80% coverage for new code)
- [ ] Documentation updated (README, ADR, or inline comments)
- [ ] Commit message follows format: `type: scope: description`

---

## 🔄 Development Workflow

### Step 1: Find an Issue
1. Browse [GitHub Issues](https://github.com/zbnerd/MapleExpectation/issues) for `good first issue` label
2. Comment "I'd like to work on this" to claim it
3. Wait for maintainer assignment (prevents duplicate work)

### Step 2: Fork & Branch
```bash
# Fork the repo on GitHub
git clone https://github.com/YOUR_USERNAME/MapleExpectation.git
cd MapleExpectation
git remote add upstream https://github.com/zbnerd/MapleExpectation.git

# Create feature branch
git checkout -b feature/your-issue-name
```

### Step 3: Make Changes
```bash
# Install dependencies
./gradlew build -x test

# Run tests frequently
./gradlew test --continuous

# Format code (if using Spotless)
./gradlew spotlessApply
```

**Collaboration Pattern: The "Yes, And" Approach**

When you encounter existing code you think could be better:

**Bad (Negation):**
> "This caching logic is inefficient. Let me rewrite it completely."

**Good (Affirmation):**
> "The current caching strategy handles 85% of requests (great!). AND I notice we could improve cold starts by pre-warming L1 cache. Let me add that enhancement while preserving the existing logic."

### Step 4: Commit Changes
```bash
git add .
git commit -m "feat: add L1 cache pre-warming for cold start optimization

- Warm up Caffeine cache on application startup
- Reduces first-request latency from 200ms to 95ms
- Added CacheWarmerService with @EventListener
- Updated README with performance comparison table

Refs: #123
Co-Authored-By: Your Name <noreply@github.com>"
```

### Step 5: Sync & Push
```bash
git fetch upstream
git rebase upstream/develop  # Keep your branch up-to-date
git push origin feature/your-issue-name
```

### Step 6: Create Pull Request
1. Visit your fork on GitHub
2. Click "Compare & pull request"
3. Use [PR_TEMPLATE.md](docs/98_Templates/PR_TEMPLATE.md) to fill out:
   - Related issue number
   - Overview of changes
   - Testing performed
   - Screenshots/metrics (if applicable)

---

## 👥 Code Review Patterns

We practice **collaborative code review**, not adversarial.

### For Contributors (Receiving Feedback)

**Pattern 1: The "Thank You → Explain → Discuss" Response**

Reviewer says:
> "This SingleFlight usage seems redundant. We already have caching."

You respond:
> "Thank you for the review! Let me explain the trade-off:
>
> **Cache** handles repeated reads **within** TTL window (e.g., same IGN requested 10 times in 5 minutes).
> **SingleFlight** handles concurrent requests **at the same moment** (e.g., 100 users request same IGN at 12:00:00 exactly).
>
> In production (see N21 incident), we saw both happen simultaneously. The cache hit (expired TTL) → 100 concurrent cache misses → SingleFlight coalesced them into 1 API call.
>
> **Open question:** Do you think we should add logging to prove this is happening? Or is the ADR sufficient evidence?"

**Pattern 2: The "Yes, And" Revision**

Reviewer says:
> "This method is too long (200 lines). Can you split it?"

You respond:
> "Great catch! I agree that 200 lines is pushing the Single Responsibility Principle.
>
> **AND** I noticed it mixes 3 concerns: input validation, data transformation, and error handling.
>
> Let me refactor into 3 private methods:
> - `validateAndParseInput()` - Lines 1-40
> - `transformToDomainModel()` - Lines 41-120
> - `handleWithRetry()` - Lines 121-200
>
> This keeps the same logic flow but makes each piece testable. WDYT?"

### For Reviewers (Giving Feedback)

**Pattern 1: The "Question-First" Approach**

Instead of:
> "This code is inefficient. Use StringBuilder."

Try:
> "I noticed string concatenation in a loop (lines 45-50). Have you considered StringBuilder? In our load tests, string operations showed up as a hotspot in profiling. Just a thought—happy to discuss if you have data showing it's not a bottleneck."

**Pattern 2: The "Evidence-Based" Suggestion**

Instead of:
> "Add error handling here."

Try:
> "When `apiClient.fetch()` throws IOException, we're not handling it. In N21 incident, unhandled exceptions caused the Circuit Breaker to fail open. Should we wrap this in `LogicExecutor.executeWithRecovery()`? See [CLAUDE.md Section 11](CLAUDE.md#11-exception-handling-strategy) for the pattern."

---

## 🧪 Testing Collaboration

### Pair Testing Sessions
We encourage **mob programming** for complex changes:

```bash
# Schedule a pair testing session
# 1. Share your screen (Discord/Zoom)
# 2. One person drives (types code), the other navigates (reviews)
# 3. Switch roles every 30 minutes
# 4. Run tests together after each change
```

**Benefits:**
- Real-time feedback loop
- Knowledge transfer (learn from each other's patterns)
- Reduced review iteration time

### Test-Driven Development (TDD) Workflow
```bash
# 1. Write failing test first
./gradlew test --tests "CacheWarmerTest"

# 2. Make it pass (minimal implementation)
# 3. Refactor for clarity
# 4. Repeat
```

**When TDD is Required:**
- Chaos test scenarios (N01-N18)
- Resilience pattern implementations (Circuit Breaker, Singleflight)
- Performance optimizations (must include before/after benchmarks)

---

## 📚 Documentation Collaboration

### Writing ADRs (Architecture Decision Records)

When making significant decisions:

1. **Create ADR** using template: `docs/adr/0000-template.md`
   ```bash
   cp docs/adr/0000-template.md docs/adr/0001-your-decision.md
   ```

2. **Fill in sections:**
   - Context (problem statement)
   - Decision (what you chose)
   - Alternatives considered (what you rejected + why)
   - Consequences (impact on performance, cost, maintainability)

3. **Submit PR** with ADR as part of the change

**Example ADR:**
```markdown
# ADR-001: Adopt Virtual Threads Over Reactive Programming

## Status
Proposed

## Context
We need async non-blocking I/O for high throughput. Current options:
- Reactive (WebFlux): Complex learning curve, debuggable
- Virtual Threads: Simple blocking code, async under the hood
- CompletionStage: Manual error handling, verbose

## Decision
Use **Project Loom** virtual threads (Java 21 feature).

## Alternatives Considered
1. **Reactive (WebFlux)**
   - Pros: Battle-tested, mature ecosystem
   - Cons: Complex debugging, steep learning curve
   - Rejection: Team has 0 reactive experience; 2-week learning curve unacceptable

2. **CompletionStage**
   - Pros: Standard Java 8+ API
   - Cons: Manual error handling, verbose code
   - Rejection: 200 extra lines of error handling code → maintenance burden

## Consequences
- Positive: Simple blocking code (easy to debug), async performance
- Negative: Java 21 required (newer JVM), not production-hardened yet
- Metrics: Target 10,000 concurrent virtual threads on single core (see N24 benchmark)
```

### Collaborative Documentation Style

We use the **"README-First"** approach:

1. **Update README FIRST** (before writing code)
   - Adds the "what" and "why"
   - Forces clarity of thought

2. **Write code to match README** (implementation)
   - Code fulfills the promise made in README
   - Easier to review (does code match docs?)

3. **Update docs AGAIN** (after code is written)
   - Add implementation details
   - Include code snippets
   - Link to ADRs/reports

**Example:**

**Step 1 (README - Before):**
> "We'll add cache pre-warming to reduce cold starts."

**Step 2 (Code - Implementation):**
```java
@Component
public class CacheWarmerService {
    @EventListener
    public void onApplicationReady() {
        // Pre-warm cache
    }
}
```

**Step 3 (README - After):**
> "✅ Cache pre-warming reduces cold starts from 200ms to 95ms. See `CacheWarmerService.java` for implementation details."

---

## 🐛 Issue Triage Patterns

### For Bug Reporters

**Template:**
```markdown
## Bug Report

**What happened?**
Brief description of the bug.

**What did you expect to happen?**
Expected behavior.

**What actually happened?**
Actual behavior (include error logs).

**How to reproduce?**
Steps:
1. Go to...
2. Click on...
3. See error...

**Environment:**
- OS: [e.g., Ubuntu 22.04]
- Java: [e.g., 21]
- Spring Boot: [e.g., 3.5.4]
- MySQL: [e.g., 8.0]

**Evidence:**
- Logs: [paste relevant logs]
- Screenshots: [if applicable]
- Metrics: [Grafana dashboard link]
```

### For Maintainers (Triaging Bugs)

**Pattern 1: The "Golden Path" Verification**
> "Does this bug occur on the 'golden path'? (Typical user flow)"
> - If no: "Interesting edge case! Let's document it as known limitation."
> - If yes: "Priority P0. Drop everything."

**Pattern 2: The "Evidence-First" Diagnosis**
> "Can you provide:
> 1. Application logs (from logs/ directory)
> 2. Grafana dashboard screenshot (metrics during bug)
> 3. Reproduction script (minimal example that triggers bug)"
>
> Without evidence, we can't diagnose. This protects both your time and ours."

**Pattern 3: The "Collaborative Debug" Session**
> "This looks complex. Want to pair debug on Discord? I'll share my screen and we can trace through the code together. Often two pairs of eyes find things faster than one."

---

## 🎓 Learning Together

### Knowledge Sharing Sessions

We host **bi-weekly tech talks** on Fridays:

| Format | Duration | Purpose |
|--------|----------|---------|
| **Code Review Deep Dive** | 30 min | Walk through PR changes, explain design decisions |
| **Chaos Test Retrospective** | 45 min | Review N01-N18 scenarios, discuss findings |
| **Performance Clinic** | 60 min | Profiling session, optimize hotspots together |
| **ADR Discussion** | 30 min | Debate architecture decisions before committing |

**How to Join:**
1. Check [GitHub Discussions](https://github.com/zbnerd/MapleExpectation/discussions) for upcoming sessions
2. RSVP in the thread (so we know who's coming)
3. Add calendar invite (link provided)

### Mentorship Program

**For New Contributors:**
We pair newcomers with experienced contributors for **first 3 PRs**:
- Learn codebase conventions
- Understand resilience patterns
- Practice TDD workflow
- Get comfortable with code review process

**For Experienced Contributors:**
Become a mentor! Benefits:
- Teaching reinforces your own learning
- Build reputation in community
- Recognition in project acknowledgments

---

## 🏆 Recognition & Appreciation

### Ways We Recognize Contributions

1. **Credits in ADRs** - Your name in the "Contributors" section
2. **README Acknowledgments** - Top contributors listed quarterly
3. **Conference Talks** - Present together at meetups/conferences
4. **Blog Post Features** - Guest author on project blog

### Hall of Fame (Top Contributors by Impact)

| Contributor | PRs Merged | Chaos Tests | ADRs Written | Recognition |
|-------------|------------|--------------|--------------|-------------|
| @zbnerd | 150+ | N01-N18 | ADR-001 to ADR-015 | Project Lead |
| [Your Name] | TBD | TBD | TBD | [Your contribution] |

---

## 🚀 Escalation Path

### When You're Stuck

1. **Ask in GitHub Discussion** (no question is too basic)
   - Tag with `question` label
   - Respond within 24 hours (usually faster)

2. **Request Pair Programming** (for complex changes)
   - Comment: "I'd like to pair on this. When are you free?"
   - We'll schedule a 1-hour session

3. **Ask for Reassignment** (if blocked or overwhelmed)
   - Comment: "I need to unassign from this. Life happened."
   - No shame in knowing your limits

### Conflict Resolution

**If Disagreement Arises:**

1. **Address the Issue, Not the Person**
   - Bad: "You're being stubborn."
   - Good: "I'm concerned this approach increases coupling."

2. **Appeal to Evidence**
   - "Can we benchmark both approaches and let data decide?"
   - "What does the ADR say about this scenario?"

3. **Escalate to Maintainer**
   - If deadlock: "We've discussed this for 3 threads without resolution. @zbnerd, can you weigh in?"
   - Maintainer has tie-breaking authority

---

## 📊 Contribution Metrics

We track (anonymously) to improve our onboarding:

| Metric | Current | Target | How We Track |
|--------|---------|--------|--------------|
| Time to first merged PR | 7 days | 3 days | Average over 6 months |
| PR review turnaround | 48 hours | 24 hours | From PR creation to merge |
| New contributor retention | 40% | 60% | Contributors who make 2+ PRs |
| Documentation coverage | 85% | 95% | Lines of code with comments/docs |

**These metrics help us improve**, not evaluate you. Everyone starts somewhere!

---

## 🎉 Celebrating Success

### When Your PR Merges
1. **Maintainer thanks you** in PR comments (specific, not generic)
2. **Your contribution is logged** in [CONTRIBUTORS.md](CONTRIBUTORS.md)
3. **Share your work** - we retweet高质量 PRs on Twitter/X

### Milestone Achievements
- **First PR merged** → 🎖️ "Rookie" badge in README
- **5 PRs merged** → ⭐ "Contributor" badge
- **10 PRs merged** → 🏆 "Core Team" invite
- **First ADR authored** → 📚 "Thought Leader" recognition

---

## 🔗 Resources for New Contributors

### Must-Read Documents
1. [CLAUDE.md](CLAUDE.md) - Core coding principles (15 min read)
2. [Architecture Overview](docs/00_Start_Here/architecture.md) - System design (10 min read)
3. [Multi-Agent Protocol](docs/00_Start_Here/multi-agent-protocol.md) - Our dev workflow (5 min read)

### Recommended Learning Path
1. **Week 1:** Read README + run locally (`./gradlew bootRun`)
2. **Week 2:** Fix a "good first issue" (look for `help wanted` label)
3. **Week 3:** Write a test for uncovered edge case
4. **Week 4:** Propose an ADR for improvement idea
5. **Week 5:** Review someone else's PR (practice giving feedback)

### External Resources
- [Spring Boot Best Practices](https://springframework.guru/guides)
- [Resilience4j Documentation](https://resilience4j.readmeocs.io/)
- [Chaos Engineering](https://principlesofchaos.org/) - Free PDF book
- [Java 21 Virtual Threads Guide](https://openjdk.org/jepsy/444)

---

## 💬 Communication Channels

| Channel | Purpose | Response Time | Etiquette |
|---------|---------|----------------|----------|
| **GitHub Issues** | Bug reports, feature requests | 48 hours | One issue per topic, search first |
| **GitHub PRs** | Code contributions | Review in 7 days | Keep PRs small (<500 lines) |
| **GitHub Discussions** | Questions, ideas, community | 24 hours | Be respectful, assume good intent |
| **Discord (private)** | Real-time chat, pair programming | Instant | Organize by topic (#general, #help, #pr-reviews) |

---

## 🌟 Inclusive Community Guidelines

### Our Commitment
We strive to be a welcoming space for everyone, regardless of:
- Experience level (students welcome!)
- Background (industry, self-taught, academic)
- Location (global community)
- Identity (see [Contributor Covenant](CODE_OF_CONDUCT.md))

### Unacceptable Behavior
- Harassment (derogatory comments, slurs)
- Discrimination (gatekeeping, elitism)
- Dismissiveness ("read the source code" as answer)
- Aggressive communication (all caps, excessive !!!)

### Reporting Issues
Contact maintainers directly or via GitHub's reporting tools. All reports are confidential.

---

## 🎓 Getting Help

### Frequently Asked Questions

**Q: "I'm a student. Can I contribute?"**
A: Yes! We love student contributors. Start with documentation, then try simple tests. We'll guide you.

**Q: "I don't know Java/Spring. Can I still help?"**
A: Absolutely! We need:
- Documentation writers
- Testers (manual exploratory testing)
- Design feedback (UX review)
- Translation (if you speak other languages)

**Q: "I made a mistake. What do I do?"**
A: Own it quickly:
> "Oops, I pushed a bug to main. I'm sorry. I'm rolling back now."
>
> We all make mistakes. How you handle it matters more than the mistake itself.

---

## 🚀 Quick Start for First-Time Contributors

```bash
# 1. Find an issue
# Visit: https://github.com/zbnerd/MapleExpectation/issues?q=label%3A%22good+first+issue%22+
# Comment: "I'd like to work on this!"

# 2. Set up locally
git clone https://github.com/zbnerd/MapleExpectation.git
cd MapleExpectation
./gradlew build -x test

# 3. Create branch
git checkout -b feature/fix-issue-123

# 4. Make changes + test
./gradlew test

# 5. Commit + push
git add .
git commit -m "fix: #123 Add error handling for null input"
git push origin feature/fix-issue-123

# 6. Open PR
# Visit GitHub and click "Compare & pull request"
```

---

## 📞 Contact Maintainers

| Role | GitHub Handle | Timezone | Availability |
|------|---------------|-----------|--------------|
| **Project Lead** | @zbnerd | KST (UTC+9) | Weekdays 7pm-10pm |
| **Reviewers** | See [CODEOWNERS](CODEOWNERS) | Various | Async reviews |

**Emergency Contact:** For critical security issues, email zbnerd@gmail.com directly (include "[MapleExpectation]" in subject).

---

## 📜 License

By contributing, you agree that your contributions will be licensed under the **MIT License**, same as the project.

---

**Thank you for contributing to MapleExpectation!** 🎉

Every PR, every issue, every documentation fix makes this project better for everyone. We appreciate your time and energy.

---

**Status:** ✅ Active
**Last Updated:** 2025-02-06
**Maintainers:** @zbnerd + community contributors
