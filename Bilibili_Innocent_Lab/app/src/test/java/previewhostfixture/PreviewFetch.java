package previewhostfixture;

import kotlin.coroutines.jvm.internal.SuspendLambda;

/** 主列表 coroutine 的状态机形状；无需启动真正的 coroutine 调度器。 */
public final class PreviewFetch extends SuspendLambda {
    public int label;
    public PreviewFetch() { super(2, null); }
    @Override public Object invokeSuspend(Object value) { return value; }
}
