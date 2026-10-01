package kpurifyfixture;

/** `SubjectControl` 形状：可选的空评论区引导。 */
public final class SubjectControl {
    private final boolean emptyPage;

    public SubjectControl(boolean emptyPage) { this.emptyPage = emptyPage; }

    public boolean hasEmptyPage() { return emptyPage; }

    public static Builder newBuilder(SubjectControl prototype) { return new Builder(prototype); }

    public static final class Builder {
        private boolean emptyPage;

        Builder(SubjectControl prototype) { emptyPage = prototype.emptyPage; }

        public Builder clearEmptyPage() { emptyPage = false; return this; }

        public SubjectControl build() { return new SubjectControl(emptyPage); }
    }
}
