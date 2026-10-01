package kmossfixture;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** protobuf 生成代码形状的评论夹具：子回复列表 + builder。 */
public final class ReplyInfo {
    public final String text;
    private final List<ReplyInfo> replies;

    private ReplyInfo(String text, List<ReplyInfo> replies) {
        this.text = text;
        this.replies = Collections.unmodifiableList(new ArrayList<>(replies));
    }

    public static ReplyInfo of(String text, ReplyInfo... children) {
        List<ReplyInfo> list = new ArrayList<>();
        Collections.addAll(list, children);
        return new ReplyInfo(text, list);
    }

    public List<ReplyInfo> getRepliesList() { return replies; }

    public static Builder newBuilder(ReplyInfo prototype) { return new Builder(prototype); }

    public static final class Builder {
        private final String text;
        private final List<ReplyInfo> replies;

        Builder(ReplyInfo prototype) {
            text = prototype.text;
            replies = new ArrayList<>(prototype.replies);
        }

        public Builder clearReplies() { replies.clear(); return this; }

        public Builder addAllReplies(Iterable<? extends ReplyInfo> values) {
            for (ReplyInfo value : values) replies.add(value);
            return this;
        }

        public ReplyInfo build() { return new ReplyInfo(text, replies); }
    }
}
