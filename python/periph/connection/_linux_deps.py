def linux_pip_hint(exc, module_name, pip_package, purpose):
    """Re-raise a missing Linux backend dependency with an actionable message.

    Only the exact expected missing module is rewritten; any other import
    failure (e.g. a real bug in the backend module) is re-raised unchanged
    so its traceback is not hidden.

    Args:
        exc: The caught ImportError/ModuleNotFoundError.
        module_name: The module actually imported (e.g. 'serial' for pyserial).
        pip_package: The pip package to install (e.g. 'pyserial').
        purpose: Short description of what needs it (e.g. 'I2C on Linux').
    """
    if isinstance(exc, ModuleNotFoundError) and exc.name == module_name:
        raise ModuleNotFoundError(
            f"{purpose} needs the '{pip_package}' package. Install it with: pip install {pip_package}"
        ) from None
    raise exc
