package kmossfixture;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** `MainListReply` 形状夹具：主楼列表 + 置顶位 UpTop + 不可删的 Root。 */
public final class MainListReply {
    private final List<ReplyInfo> replies;
    private final ReplyInfo upTop;
    private final ReplyInfo root;

    public MainListReply(List<ReplyInfo> replies, ReplyInfo upTop, ReplyInfo root) {
        this.replies = Collections.unmodifiableList(new ArrayList<>(replies));
        this.upTop = upTop;
        this.root = root;
    }

    public List<ReplyInfo> getRepliesList() { return replies; }
    public boolean hasUpTop() { return upTop != null; }
    public ReplyInfo getUpTop() { return upTop; }
    public boolean hasRoot() { return root != null; }
    public ReplyInfo getRoot() { return root; }

    public static Builder newBuilder(MainListReply prototype) { return new Builder(prototype); }

    public static final class Builder {
        private final List<ReplyInfo> replies;
        private ReplyInfo upTop;
        private ReplyInfo root;

        Builder(MainListReply prototype) {
            replies = new ArrayList<>(prototype.replies);
            upTop = prototype.upTop;
            root = prototype.root;
        }

        public Builder clearReplies() { replies.clear(); return this; }

        public Builder addAllReplies(Iterable<? extends ReplyInfo> values) {
            for (ReplyInfo value : values) replies.add(value);
            return this;
        }

        public Builder setUpTop(ReplyInfo value) { upTop = value; return this; }
        public Builder clearUpTop() { upTop = null; return this; }
        public Builder setRoot(ReplyInfo value) { root = value; return this; }
        public Builder clearRoot() { root = null; return this; }

        public MainListReply build() { return new MainListReply(replies, upTop, root); }
    }
}
