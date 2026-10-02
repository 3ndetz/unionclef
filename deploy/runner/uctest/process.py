"""Subprocess API for unattended bench commands, without Windows console popups.

Output, errors, timeouts and process ownership are stdlib subprocess semantics.
Use interactive=True for an intentionally interactive child; explicit console or
visible STARTUPINFO requests also take precedence. POSIX launches are unchanged.
This module does not patch subprocess globally or hide graphical applications.
"""
import inspect
import os
import subprocess as _subprocess

_POPEN_PARAMETERS = tuple(inspect.signature(_subprocess.Popen).parameters)


def _quiet_args(args, kwargs, interactive):
    if os.name != "nt" or interactive:
        return args, kwargs

    def option(name, default):
        index = _POPEN_PARAMETERS.index(name)
        return args[index] if len(args) > index else kwargs.get(name, default)

    flags = option("creationflags", 0)
    startup = option("startupinfo", None)
    if flags & (_subprocess.CREATE_NEW_CONSOLE | _subprocess.DETACHED_PROCESS):
        return args, kwargs
    if (startup is not None
            and startup.dwFlags & _subprocess.STARTF_USESHOWWINDOW
            and startup.wShowWindow != _subprocess.SW_HIDE):
        return args, kwargs

    index = _POPEN_PARAMETERS.index("creationflags")
    if len(args) > index:
        args = (*args[:index], flags | _subprocess.CREATE_NO_WINDOW, *args[index + 1:])
    else:
        kwargs["creationflags"] = flags | _subprocess.CREATE_NO_WINDOW
    return args, kwargs


class Popen(_subprocess.Popen):
    """A stdlib Popen with quiet Windows console defaults for bench children."""

    def __init__(self, *args, interactive=False, **kwargs):
        args, kwargs = _quiet_args(args, kwargs, interactive)
        super().__init__(*args, **kwargs)


def run(*args, interactive=False, **kwargs):
    args, kwargs = _quiet_args(args, kwargs, interactive)
    return _subprocess.run(*args, **kwargs)


def call(*args, interactive=False, **kwargs):
    args, kwargs = _quiet_args(args, kwargs, interactive)
    return _subprocess.call(*args, **kwargs)


def check_call(*args, interactive=False, **kwargs):
    args, kwargs = _quiet_args(args, kwargs, interactive)
    return _subprocess.check_call(*args, **kwargs)


def check_output(*args, interactive=False, **kwargs):
    args, kwargs = _quiet_args(args, kwargs, interactive)
    return _subprocess.check_output(*args, **kwargs)


def __getattr__(name):
    """Keep stdlib constants, result types and exceptions available to callers."""
    return getattr(_subprocess, name)


__all__ = _subprocess.__all__
