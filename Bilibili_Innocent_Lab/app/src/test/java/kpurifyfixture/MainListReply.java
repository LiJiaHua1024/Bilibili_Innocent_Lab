package kpurifyfixture;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** `MainListReply` 形状：评论列表 + 可选 Qoe/Operation + SubjectControl。 */
public final class MainListReply {
    private final List<ReplyInfo> replies;
    private final boolean qoe;
    private final boolean operation;
    private final SubjectControl subjectControl;

    public MainListReply(List<ReplyInfo> replies, boolean qoe, boolean operation, SubjectControl subjectControl) {
        this.replies = Collections.unmodifiableList(new ArrayList<>(replies));
        this.qoe = qoe;
        this.operation = operation;
        this.subjectControl = subjectControl;
    }

    public List<ReplyInfo> getRepliesList() { return replies; }
    public boolean hasQoe() { return qoe; }
    public boolean hasOperation() { return operation; }
    public boolean hasSubjectControl() { return subjectControl != null; }
    public SubjectControl getSubjectControl() { return subjectControl; }

    public static Builder newBuilder(MainListReply prototype) { return new Builder(prototype); }

    public static final class Builder {
        private final List<ReplyInfo> replies;
        private boolean qoe;
        private boolean operation;
        private SubjectControl subjectControl;

        Builder(MainListReply prototype) {
            replies = new ArrayList<>(prototype.replies);
            qoe = prototype.qoe;
            operation = prototype.operation;
            subjectControl = prototype.subjectControl;
        }

        public Builder clearReplies() { replies.clear(); return this; }

        public Builder addAllReplies(Iterable<? extends ReplyInfo> values) {
            for (ReplyInfo value : values) replies.add(value);
            return this;
        }

        public Builder clearQoe() { qoe = false; return this; }
        public Builder clearOperation() { operation = false; return this; }
        public Builder setSubjectControl(SubjectControl value) { subjectControl = value; return this; }

        public MainListReply build() { return new MainListReply(replies, qoe, operation, subjectControl); }
    }
}
