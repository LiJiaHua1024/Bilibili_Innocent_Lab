package com.bilibili.app.comm.comment2.model;

import java.util.HashMap;

public final class BiliComment {
    public Content mContent;
    public BiliComment(Content content) { mContent = content; }
    public static final class Content {
        public String mMsg;
        public HashMap<String, Object> emote = new HashMap<>();
        public Content(String message) { mMsg = message; }
    }
}
