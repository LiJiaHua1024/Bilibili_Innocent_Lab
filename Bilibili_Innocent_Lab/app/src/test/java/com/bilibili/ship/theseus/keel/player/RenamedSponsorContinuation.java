package com.bilibili.ship.theseus.keel.player;

public final class RenamedSponsorContinuation extends kotlin.coroutines.jvm.internal.ContinuationImpl {
    public final TheseusKeelPlayer owner;
    public int label;
    public RenamedSponsorContinuation(TheseusKeelPlayer owner, kotlin.coroutines.Continuation<Object> continuation) {
        super(continuation);
        this.owner = owner;
    }
    @Override protected Object invokeSuspend(Object result) { return kotlin.Unit.INSTANCE; }
}
