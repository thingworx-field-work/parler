package com.thingworx.things.agent.compaction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The only part of a {@code parler.conversation_checkpoint.v1} envelope a model may write
 * ({@code docs/core/advanced-compact.md} §6.2): non-authoritative task navigation — what the user is trying to do,
 * what still constrains it, what is done or blocked, which decisions were made and why, and what comes next.
 *
 * <p>It is <b>not</b> evidence. Nothing here proves a value, a device state, a completeness class, an authorization,
 * or an error class; §5 invariant 5 keeps those with server-authored evidence.
 *
 * <p>Immutable. {@link #shrunkTo(int)} performs the §8.3 deterministic reduction; there is no path that asks a model
 * to "make it shorter".
 */
public final class ConversationCheckpointSemantic {

    /** One decision plus its short business rationale and the alternatives it ruled out. */
    public static final class Decision {
        private final String decision;
        private final String rationale;
        private final List<String> rejectedAlternatives;

        public Decision(String decision, String rationale, List<String> rejectedAlternatives) {
            this.decision = decision != null ? decision : "";
            this.rationale = rationale != null ? rationale : "";
            this.rejectedAlternatives = rejectedAlternatives != null
                    ? Collections.unmodifiableList(new ArrayList<>(rejectedAlternatives))
                    : Collections.emptyList();
        }

        public String decision() {
            return decision;
        }

        public String rationale() {
            return rationale;
        }

        public List<String> rejectedAlternatives() {
            return rejectedAlternatives;
        }
    }

    private final String goal;
    private final List<String> constraints;
    private final List<String> done;
    private final List<String> inProgress;
    private final List<String> blocked;
    private final List<Decision> decisions;
    private final List<String> nextSteps;
    private final List<String> criticalContext;

    public ConversationCheckpointSemantic(String goal, List<String> constraints, List<String> done,
            List<String> inProgress, List<String> blocked, List<Decision> decisions, List<String> nextSteps,
            List<String> criticalContext) {
        this.goal = goal != null ? goal : "";
        this.constraints = copy(constraints);
        this.done = copy(done);
        this.inProgress = copy(inProgress);
        this.blocked = copy(blocked);
        this.decisions = decisions != null
                ? Collections.unmodifiableList(new ArrayList<>(decisions))
                : Collections.emptyList();
        this.nextSteps = copy(nextSteps);
        this.criticalContext = copy(criticalContext);
    }

    private static List<String> copy(List<String> in) {
        return in != null ? Collections.unmodifiableList(new ArrayList<>(in)) : Collections.emptyList();
    }

    public String goal() {
        return goal;
    }

    public List<String> constraints() {
        return constraints;
    }

    public List<String> done() {
        return done;
    }

    public List<String> inProgress() {
        return inProgress;
    }

    public List<String> blocked() {
        return blocked;
    }

    public List<Decision> decisions() {
        return decisions;
    }

    public List<String> nextSteps() {
        return nextSteps;
    }

    public List<String> criticalContext() {
        return criticalContext;
    }

    /** §8.3: {@code goal} may never be empty — a checkpoint that cannot say what the task is has no navigation value. */
    public boolean hasGoal() {
        return !goal.isBlank();
    }

    /**
     * Model-facing rendering. This exact text is what enters the provider request as the injected checkpoint row, so
     * it — not the JSON envelope — is what the §5 invariant 8 8000-char semantic cap bounds.
     */
    public String renderForModel() {
        StringBuilder sb = new StringBuilder();
        sb.append("Goal: ").append(goal);
        appendList(sb, "Constraints", constraints);
        appendList(sb, "Done", done);
        appendList(sb, "In progress", inProgress);
        appendList(sb, "Blocked", blocked);
        if (!decisions.isEmpty()) {
            sb.append("\nDecisions:");
            for (Decision d : decisions) {
                sb.append("\n- ").append(d.decision());
                if (!d.rationale().isBlank()) {
                    sb.append(" (because ").append(d.rationale()).append(')');
                }
                if (!d.rejectedAlternatives().isEmpty()) {
                    sb.append(" [rejected: ").append(String.join("; ", d.rejectedAlternatives())).append(']');
                }
            }
        }
        appendList(sb, "Next steps", nextSteps);
        appendList(sb, "Critical context", criticalContext);
        return sb.toString();
    }

    private static void appendList(StringBuilder sb, String label, List<String> items) {
        if (items.isEmpty()) {
            return;
        }
        sb.append('\n').append(label).append(':');
        for (String s : items) {
            sb.append("\n- ").append(s);
        }
    }

    /** Rendered length under the §5 invariant 8 semantic cap. */
    public int modelFacingChars() {
        return renderForModel().length();
    }

    /** Every string this semantic state carries, for the §8.3 protected-value scan. */
    public List<String> allStrings() {
        List<String> out = new ArrayList<>();
        out.add(goal);
        out.addAll(constraints);
        out.addAll(done);
        out.addAll(inProgress);
        out.addAll(blocked);
        out.addAll(nextSteps);
        out.addAll(criticalContext);
        for (Decision d : decisions) {
            out.add(d.decision());
            out.add(d.rationale());
            out.addAll(d.rejectedAlternatives());
        }
        return out;
    }

    /**
     * §8.3 deterministic shrink: drop lowest-priority detail first, in the fixed order
     * {@code criticalContext → decisions → constraints → nextSteps → progress → goal}, until the rendered text fits
     * {@code maxModelFacingChars}. Within each stage the newest items are kept, because a checkpoint's job is to
     * resume the current task rather than to archive the oldest.
     *
     * <p>{@code goal} is never dropped — it is the last thing standing, and §8.3 treats an empty goal as generation
     * failure rather than as a smaller checkpoint.
     *
     * @return a semantic state fitting the cap, or {@code this} when it already fits; never {@code null}
     */
    public ConversationCheckpointSemantic shrunkTo(int maxModelFacingChars) {
        ConversationCheckpointSemantic current = this;
        if (current.modelFacingChars() <= maxModelFacingChars) {
            return current;
        }
        // Stage order mirrors §8.3 exactly: lowest-value detail first, goal last.
        for (int stage = 0; stage < 6; stage++) {
            while (current.modelFacingChars() > maxModelFacingChars && current.stageHasContent(stage)) {
                current = current.dropOldestFromStage(stage);
            }
            if (current.modelFacingChars() <= maxModelFacingChars) {
                return current;
            }
        }
        return current;
    }

    private boolean stageHasContent(int stage) {
        switch (stage) {
            case 0: return !criticalContext.isEmpty();
            case 1: return !decisions.isEmpty();
            case 2: return !constraints.isEmpty();
            case 3: return !nextSteps.isEmpty();
            case 4: return !done.isEmpty() || !inProgress.isEmpty() || !blocked.isEmpty();
            case 5: return goal.length() > 1;
            default: return false;
        }
    }

    private ConversationCheckpointSemantic dropOldestFromStage(int stage) {
        switch (stage) {
            case 0:
                return withLists(goal, constraints, done, inProgress, blocked, decisions, nextSteps,
                        dropOldest(criticalContext));
            case 1: {
                List<Decision> d = new ArrayList<>(decisions);
                d.remove(0);
                return withLists(goal, constraints, done, inProgress, blocked, d, nextSteps, criticalContext);
            }
            case 2:
                return withLists(goal, dropOldest(constraints), done, inProgress, blocked, decisions, nextSteps,
                        criticalContext);
            case 3:
                return withLists(goal, constraints, done, inProgress, blocked, decisions, dropOldest(nextSteps),
                        criticalContext);
            case 4: {
                // Progress detail: shed completed work before work still in flight, and blockers last.
                if (!done.isEmpty()) {
                    return withLists(goal, constraints, dropOldest(done), inProgress, blocked, decisions, nextSteps,
                            criticalContext);
                }
                if (!inProgress.isEmpty()) {
                    return withLists(goal, constraints, done, dropOldest(inProgress), blocked, decisions, nextSteps,
                            criticalContext);
                }
                return withLists(goal, constraints, done, inProgress, dropOldest(blocked), decisions, nextSteps,
                        criticalContext);
            }
            case 5: {
                // Trim so a cut landing on a space cannot leave a non-canonical goal; the goal is already trimmed
                // and non-blank at this point, so its first character is not whitespace and the result stays non-empty.
                int keep = Math.max(1, goal.length() / 2);
                return withLists(goal.substring(0, keep).trim(), constraints, done, inProgress, blocked, decisions,
                        nextSteps, criticalContext);
            }
            default:
                return this;
        }
    }

    private static List<String> dropOldest(List<String> in) {
        if (in.isEmpty()) {
            return in;
        }
        List<String> out = new ArrayList<>(in);
        out.remove(0);
        return out;
    }

    private static ConversationCheckpointSemantic withLists(String goal, List<String> constraints, List<String> done,
            List<String> inProgress, List<String> blocked, List<Decision> decisions, List<String> nextSteps,
            List<String> criticalContext) {
        return new ConversationCheckpointSemantic(goal, constraints, done, inProgress, blocked, decisions, nextSteps,
                criticalContext);
    }
}
