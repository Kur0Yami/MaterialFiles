/*
 * Run-script support (fork patch).
 * How a script is executed: as this app, as root (su), or as the Shizuku shell user.
 */

package me.zhanghai.android.files.filelist

enum class ScriptRunMethod { APP, ROOT, SHIZUKU }
