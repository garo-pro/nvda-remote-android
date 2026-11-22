package app.nvdaremote

import android.view.KeyEvent

/** Maps Android key codes to Windows virtual-key codes, as NVDA's "key" message expects. */
object KeyMap {
    private const val EXTENDED = 0x100
    private val map = HashMap<Int, Int>()

    private fun m(keyCode: Int, vk: Int, extended: Boolean = false) {
        map[keyCode] = vk or (if (extended) EXTENDED else 0)
    }

    init {
        for (i in 0..25) m(KeyEvent.KEYCODE_A + i, 0x41 + i)
        for (i in 0..9) m(KeyEvent.KEYCODE_0 + i, 0x30 + i)
        for (i in 0..9) m(KeyEvent.KEYCODE_NUMPAD_0 + i, 0x60 + i)
        for (i in 0..11) m(KeyEvent.KEYCODE_F1 + i, 0x70 + i)

        m(KeyEvent.KEYCODE_NUMPAD_MULTIPLY, 0x6A)
        m(KeyEvent.KEYCODE_NUMPAD_ADD, 0x6B)
        m(KeyEvent.KEYCODE_NUMPAD_SUBTRACT, 0x6D)
        m(KeyEvent.KEYCODE_NUMPAD_DOT, 0x6E)
        m(KeyEvent.KEYCODE_NUMPAD_DIVIDE, 0x6F, true)
        m(KeyEvent.KEYCODE_NUMPAD_ENTER, 0x0D, true)
        m(KeyEvent.KEYCODE_NUM_LOCK, 0x90, true)

        m(KeyEvent.KEYCODE_ENTER, 0x0D)
        m(KeyEvent.KEYCODE_ESCAPE, 0x1B)
        m(KeyEvent.KEYCODE_TAB, 0x09)
        m(KeyEvent.KEYCODE_SPACE, 0x20)
        m(KeyEvent.KEYCODE_DEL, 0x08) // Backspace
        m(KeyEvent.KEYCODE_FORWARD_DEL, 0x2E, true)
        m(KeyEvent.KEYCODE_INSERT, 0x2D, true)
        m(KeyEvent.KEYCODE_MOVE_HOME, 0x24, true)
        m(KeyEvent.KEYCODE_MOVE_END, 0x23, true)
        m(KeyEvent.KEYCODE_PAGE_UP, 0x21, true)
        m(KeyEvent.KEYCODE_PAGE_DOWN, 0x22, true)
        m(KeyEvent.KEYCODE_DPAD_LEFT, 0x25, true)
        m(KeyEvent.KEYCODE_DPAD_UP, 0x26, true)
        m(KeyEvent.KEYCODE_DPAD_RIGHT, 0x27, true)
        m(KeyEvent.KEYCODE_DPAD_DOWN, 0x28, true)

        m(KeyEvent.KEYCODE_SHIFT_LEFT, 0xA0)
        m(KeyEvent.KEYCODE_SHIFT_RIGHT, 0xA1)
        m(KeyEvent.KEYCODE_CTRL_LEFT, 0xA2)
        m(KeyEvent.KEYCODE_CTRL_RIGHT, 0xA3, true)
        m(KeyEvent.KEYCODE_ALT_LEFT, 0xA4)
        m(KeyEvent.KEYCODE_ALT_RIGHT, 0xA5, true)
        m(KeyEvent.KEYCODE_META_LEFT, 0x5B, true)
        m(KeyEvent.KEYCODE_META_RIGHT, 0x5C, true)
        m(KeyEvent.KEYCODE_CAPS_LOCK, 0x14)
        m(KeyEvent.KEYCODE_SCROLL_LOCK, 0x91)
        m(KeyEvent.KEYCODE_BREAK, 0x13)
        m(KeyEvent.KEYCODE_SYSRQ, 0x2C, true) // Print Screen
        m(KeyEvent.KEYCODE_MENU, 0x5D, true) // Applications key

        m(KeyEvent.KEYCODE_GRAVE, 0xC0)
        m(KeyEvent.KEYCODE_MINUS, 0xBD)
        m(KeyEvent.KEYCODE_EQUALS, 0xBB)
        m(KeyEvent.KEYCODE_LEFT_BRACKET, 0xDB)
        m(KeyEvent.KEYCODE_RIGHT_BRACKET, 0xDD)
        m(KeyEvent.KEYCODE_BACKSLASH, 0xDC)
        m(KeyEvent.KEYCODE_SEMICOLON, 0xBA)
        m(KeyEvent.KEYCODE_APOSTROPHE, 0xDE)
        m(KeyEvent.KEYCODE_COMMA, 0xBC)
        m(KeyEvent.KEYCODE_PERIOD, 0xBE)
        m(KeyEvent.KEYCODE_SLASH, 0xBF)
    }

    /** Returns the packed code, or -1 if the key is not forwarded. */
    fun lookup(keyCode: Int): Int = map[keyCode] ?: -1

    fun vk(packed: Int) = packed and 0xFF
    fun extended(packed: Int) = packed and EXTENDED != 0
}
