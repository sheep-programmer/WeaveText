package com.weavetext.ime.voicesmoke;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.view.WindowManager;
import android.widget.EditText;

/** An ordinary editor for driving the public release IME from a separate test application. */
public final class SpeechEditorActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        EditText editor = new EditText(this);
        editor.setContentDescription("voice_smoke_editor");
        editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        editor.setMinLines(3);
        setContentView(editor);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        editor.requestFocus();
    }
}
