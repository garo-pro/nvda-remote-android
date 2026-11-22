package app.nvdaremote

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.widget.Button

/**
 * Button that sees key events before the input method does. Without this, the keyboard's IME
 * swallows letters and digits and only keys like Caps Lock and F12 reach the activity.
 */
class KeyCaptureButton @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = android.R.attr.buttonStyle
) : Button(context, attrs, defStyle) {
    var preImeHandler: ((KeyEvent) -> Boolean)? = null

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean =
        preImeHandler?.invoke(event) ?: super.onKeyPreIme(keyCode, event)
}
