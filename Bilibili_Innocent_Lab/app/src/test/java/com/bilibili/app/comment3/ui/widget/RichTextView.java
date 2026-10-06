package com.bilibili.app.comment3.ui.widget;

public class RichTextView extends android.widget.TextView {
    public RichTextView() { super(null); }
    @Override public void setText(CharSequence text, android.widget.TextView.BufferType type) { }
    public void setSpannableText(CharSequence text) { setText(text, android.widget.TextView.BufferType.SPANNABLE); }
}
