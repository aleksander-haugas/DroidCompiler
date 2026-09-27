package com.droidx;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.Layout;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.EditText;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight code editor surface for DroidCompiler.
 *
 * It deliberately stays on Android framework widgets: no WebView and no heavy
 * editor dependency.  This keeps startup small and works on the same API 35+
 * devices as the compiler runtime.
 */
public class CodeEditorView extends EditText {
    private final Paint gutterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint numberPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dividerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint activeLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler highlightHandler = new Handler(Looper.getMainLooper());
    private final Runnable highlightRunnable = this::applySyntaxHighlighting;

    private int gutterWidth;
    private int horizontalInnerPadding;
    private boolean syntaxEnabled = true;
    private boolean applyingSpans = false;
    private float editorTextSp = 13.5f;

    private static final int MAX_HIGHLIGHT_CHARS = 180_000;

    private static final Pattern COMMENTS = Pattern.compile("//[^\\n]*|/\\*[\\s\\S]*?\\*/");
    private static final Pattern STRINGS = Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'");
    private static final Pattern PREPROCESSOR = Pattern.compile("(?m)^\\s*#\\s*[A-Za-z_]+[^\\n]*");
    private static final Pattern KEYWORDS = Pattern.compile(
            "\\b(?:alignas|alignof|and|and_eq|asm|auto|bitand|bitor|bool|break|case|catch|char|char8_t|char16_t|char32_t|class|compl|concept|const|consteval|constexpr|constinit|const_cast|continue|co_await|co_return|co_yield|decltype|default|delete|do|double|dynamic_cast|else|enum|explicit|export|extern|false|float|for|friend|goto|if|inline|int|long|mutable|namespace|new|noexcept|not|not_eq|nullptr|operator|or|or_eq|private|protected|public|register|reinterpret_cast|requires|return|short|signed|sizeof|static|static_assert|static_cast|struct|switch|template|this|thread_local|throw|true|try|typedef|typeid|typename|union|unsigned|using|virtual|void|volatile|wchar_t|while|xor|xor_eq)\\b");
    private static final Pattern TYPES = Pattern.compile("\\b(?:std|size_t|int8_t|int16_t|int32_t|int64_t|uint8_t|uint16_t|uint32_t|uint64_t|SDL_[A-Za-z0-9_]+|JNIEnv|JavaVM|jobject|jclass|jstring)\\b");
    private static final Pattern NUMBERS = Pattern.compile("(?<![A-Za-z_])(?:0[xX][0-9A-Fa-f]+|\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?[fFuUlL]*)(?![A-Za-z_])");

    public CodeEditorView(Context context) {
        super(context);
        init();
    }

    public CodeEditorView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public CodeEditorView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        gutterWidth = dp(52);
        horizontalInnerPadding = dp(12);

        setGravity(Gravity.TOP | Gravity.START);
        setTypeface(Typeface.MONOSPACE);
        setTextSize(TypedValue.COMPLEX_UNIT_SP, editorTextSp);
        setTextColor(Color.rgb(222, 236, 246));
        setHintTextColor(Color.rgb(79, 110, 136));
        setLineSpacing(dp(2), 1.0f);
        setHorizontallyScrolling(true);
        setPadding(gutterWidth + horizontalInnerPadding, dp(12), dp(14), dp(18));
        setSelectAllOnFocus(false);
        setTextIsSelectable(true);

        gutterPaint.setColor(Color.rgb(7, 18, 27));
        dividerPaint.setColor(Color.rgb(18, 59, 86));
        dividerPaint.setStrokeWidth(dp(1));
        activeLinePaint.setColor(Color.argb(38, 0, 154, 220));

        numberPaint.setColor(Color.rgb(80, 119, 151));
        numberPaint.setTextAlign(Paint.Align.RIGHT);
        numberPaint.setTypeface(Typeface.MONOSPACE);
        numberPaint.setTextSize(sp(10.5f));

        addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                invalidate();
            }
            @Override public void afterTextChanged(Editable s) {
                if (applyingSpans || !syntaxEnabled) return;
                highlightHandler.removeCallbacks(highlightRunnable);
                highlightHandler.postDelayed(highlightRunnable, 240);
            }
        });
    }

    public void setEditorTextSizeSp(float sp) {
        editorTextSp = Math.max(10f, Math.min(24f, sp));
        setTextSize(TypedValue.COMPLEX_UNIT_SP, editorTextSp);
        numberPaint.setTextSize(this.sp(Math.max(9f, editorTextSp - 3f)));
        invalidate();
    }

    public float getEditorTextSizeSp() {
        return editorTextSp;
    }

    public void setSyntaxHighlightingEnabled(boolean enabled) {
        syntaxEnabled = enabled;
        if (!enabled) clearSyntaxSpans();
        else scheduleHighlightNow();
    }

    public void scheduleHighlightNow() {
        highlightHandler.removeCallbacks(highlightRunnable);
        highlightHandler.post(highlightRunnable);
    }

    @Override
    protected void onSelectionChanged(int selStart, int selEnd) {
        super.onSelectionChanged(selStart, selEnd);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Layout layout = getLayout();
        int scrollX = getScrollX();
        int scrollY = getScrollY();

        if (layout != null && getSelectionStart() >= 0) {
            int line = layout.getLineForOffset(Math.min(getSelectionStart(), length()));
            int top = getTotalPaddingTop() + layout.getLineTop(line);
            int bottom = getTotalPaddingTop() + layout.getLineBottom(line);
            canvas.drawRect(scrollX + gutterWidth, top, scrollX + getWidth(), bottom, activeLinePaint);
        }

        super.onDraw(canvas);

        // Draw the gutter after the text so it remains visually fixed while the
        // source scrolls horizontally.
        canvas.drawRect(scrollX, scrollY, scrollX + gutterWidth, scrollY + getHeight(), gutterPaint);
        canvas.drawLine(scrollX + gutterWidth - dp(1), scrollY,
                scrollX + gutterWidth - dp(1), scrollY + getHeight(), dividerPaint);

        if (layout == null || layout.getLineCount() == 0) return;
        int contentTop = Math.max(0, scrollY - getTotalPaddingTop());
        int contentBottom = Math.max(0, scrollY + getHeight() - getTotalPaddingTop());
        int first = layout.getLineForVertical(contentTop);
        int last = layout.getLineForVertical(contentBottom);
        last = Math.min(last + 1, layout.getLineCount() - 1);

        float x = scrollX + gutterWidth - dp(9);
        for (int line = first; line <= last; line++) {
            float baseline = getTotalPaddingTop() + layout.getLineBaseline(line);
            canvas.drawText(Integer.toString(line + 1), x, baseline, numberPaint);
        }
    }

    private void applySyntaxHighlighting() {
        if (!syntaxEnabled || applyingSpans) return;
        Editable e = getText();
        if (e == null) return;
        clearSyntaxSpans();
        if (e.length() == 0 || e.length() > MAX_HIGHLIGHT_CHARS) return;

        applyingSpans = true;
        try {
            String s = e.toString();
            applyPattern(e, s, NUMBERS, Color.rgb(255, 178, 92));
            applyPattern(e, s, TYPES, Color.rgb(44, 210, 255));
            applyPattern(e, s, KEYWORDS, Color.rgb(80, 150, 255));
            applyPattern(e, s, PREPROCESSOR, Color.rgb(241, 104, 189));
            applyPattern(e, s, STRINGS, Color.rgb(71, 230, 151));
            applyPattern(e, s, COMMENTS, Color.rgb(91, 127, 116));
        } finally {
            applyingSpans = false;
        }
    }

    private void applyPattern(Editable e, String source, Pattern pattern, int color) {
        Matcher m = pattern.matcher(source);
        while (m.find()) {
            e.setSpan(new SyntaxSpan(color), m.start(), m.end(), Editable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private void clearSyntaxSpans() {
        Editable e = getText();
        if (e == null) return;
        SyntaxSpan[] spans = e.getSpans(0, e.length(), SyntaxSpan.class);
        for (SyntaxSpan span : spans) e.removeSpan(span);
    }

    private static final class SyntaxSpan extends ForegroundColorSpan {
        SyntaxSpan(int color) { super(color); }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private float sp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, getResources().getDisplayMetrics());
    }
}
