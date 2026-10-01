package kpurifyfixture;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** `ReplyInfo` 形状：正文 + 子回复。 */
public final class ReplyInfo {
    private final Content content;
    private final List<ReplyInfo> replies;

    public ReplyInfo(Content content, ReplyInfo... replies) {
        this.content = content;
        this.replies = Collections.unmodifiableList(new ArrayList<>(Arrays.asList(replies)));
    }

    private ReplyInfo(Content content, List<ReplyInfo> replies) {
        this.content = content;
        this.replies = Collections.unmodifiableList(new ArrayList<>(replies));
    }

    public Content getContent() { return content; }
    public List<ReplyInfo> getRepliesList() { return replies; }

    public static Builder newBuilder(ReplyInfo prototype) { return new Builder(prototype); }

    public static final class Builder {
        private Content content;
        private final List<ReplyInfo> replies;

        Builder(ReplyInfo prototype) { content = prototype.content; replies = new ArrayList<>(prototype.replies); }

        public Builder setContent(Content value) { content = value; return this; }
        public Builder clearReplies() { replies.clear(); return this; }

        public Builder addAllReplies(Iterable<? extends ReplyInfo> values) {
            for (ReplyInfo value : values) replies.add(value);
            return this;
        }

        public ReplyInfo build() { return new ReplyInfo(content, replies); }
    }
}
