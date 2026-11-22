package io.github.phillhf.iptv.platform

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.win32.StdCallLibrary
import java.awt.Window

/** Aufrufe, für die es in Java nichts Passendes gibt. */
object Win32 {
    private interface Dwm : StdCallLibrary {
        fun DwmSetWindowAttribute(hwnd: HWND, attribute: Int, value: Pointer, size: Int): Int
    }

    private fun handle(window: Window) = HWND(Native.getWindowPointer(window))

    fun darkTitleBar(window: Window) {
        runCatching {
            val on = Memory(4).apply { setInt(0, 1) }
            Native.load("dwmapi", Dwm::class.java).DwmSetWindowAttribute(handle(window), 20, on, 4)
        }
    }

    /** Was vor dem Vollbild galt. */
    class Before(val style: Int, val placement: WinUser.WINDOWPLACEMENT)

    /** Rahmenlos über den ganzen Bildschirm, auf dem das Fenster gerade liegt, auch über die Taskleiste. */
    fun enterFullscreen(window: Window): Before {
        val u = User32.INSTANCE
        val hwnd = handle(window)
        val placement = WinUser.WINDOWPLACEMENT()
        placement.length = placement.size()
        u.GetWindowPlacement(hwnd, placement)
        val style = u.GetWindowLong(hwnd, WinUser.GWL_STYLE)
        val monitor = u.MonitorFromWindow(hwnd, WinUser.MONITOR_DEFAULTTONEAREST)
        val info = WinUser.MONITORINFO()
        u.GetMonitorInfo(monitor, info)
        val r = info.rcMonitor
        u.SetWindowLong(hwnd, WinUser.GWL_STYLE, style and (WinUser.WS_CAPTION or WinUser.WS_THICKFRAME or WinUser.WS_MAXIMIZE).inv())
        u.SetWindowPos(hwnd, null, r.left, r.top, r.right - r.left, r.bottom - r.top, WinUser.SWP_NOZORDER or WinUser.SWP_FRAMECHANGED)
        return Before(style, placement)
    }

    fun leaveFullscreen(window: Window, before: Before) {
        val u = User32.INSTANCE
        val hwnd = handle(window)
        u.SetWindowLong(hwnd, WinUser.GWL_STYLE, before.style)
        u.SetWindowPos(
            hwnd, null, 0, 0, 0, 0,
            WinUser.SWP_NOMOVE or WinUser.SWP_NOSIZE or WinUser.SWP_NOZORDER or WinUser.SWP_FRAMECHANGED,
        )
        u.SetWindowPlacement(hwnd, before.placement)
    }

    fun showWindow(title: String) {
        User32.INSTANCE.FindWindow(null, title)?.let {
            User32.INSTANCE.ShowWindow(it, WinUser.SW_RESTORE)
            User32.INSTANCE.SetForegroundWindow(it)
        }
    }

    fun keepAwake(screen: Boolean) {
        val flags = WinBase.ES_SYSTEM_REQUIRED or if (screen) WinBase.ES_DISPLAY_REQUIRED else 0
        runCatching { Kernel32.INSTANCE.SetThreadExecutionState(flags) }
    }
}
