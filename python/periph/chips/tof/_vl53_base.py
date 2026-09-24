"""Shared base for ST's VL53 FlightSense Time-of-Flight ranging family.

Internal module — not a public driver. VL53L0X and VL53L1X have different
register maps (8-bit vs 16-bit register index), so this base holds no
register addresses and no ranging logic, only the plumbing both chips share:
big-endian register access with a 1- or 2-byte index, the bounded poll
helper, the XSHUT boot wait, the lock that serializes multi-register
sequences against the interrupt poller, interrupt delivery (int_pin edge or
Linux polling thread), the volatile re-addressing helper, and the family's
shared constants. See specs/tof/_vl53_base.md.
"""

try:
    import threading as _threading
    _LINUX = True
except ImportError:
    _LINUX = False

import time


I2C_ADDRESS = 0x29

TIMEOUT_MS = 500
BOOT_US = 1200

# Logical interrupt sources shared by the family (mutually exclusive).
SOURCE_LEVEL_LOW        = 1
SOURCE_LEVEL_HIGH       = 2
SOURCE_OUT_OF_WINDOW    = 3
SOURCE_NEW_SAMPLE_READY = 4
SOURCE_IN_WINDOW        = 5  # VL53L1X only


class _NoLock:
    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


def _ticks_ms():
    if hasattr(time, 'ticks_ms'):
        return time.ticks_ms()
    if hasattr(time, 'monotonic'):
        return int(time.monotonic() * 1000)
    return int(time.time() * 1000)


def _elapsed_ms(start):
    if hasattr(time, 'ticks_diff'):
        return time.ticks_diff(time.ticks_ms(), start)
    return _ticks_ms() - start


class _VL53Base:
    """Protected helpers shared by the VL53 chip drivers.

    Never instantiated directly. Subclasses implement
    _poll_interrupt_status() for the interrupt delivery helpers.

    Args:
        connection: Configured I2C connection. Its optional en_pin drives
            XSHUT (high = enabled), its optional int_pin receives GPIO1.
        index_bytes: Register index width on the wire, 1 or 2.
        chip_name: Chip name used in error messages.
    """

    def __init__(self, connection, index_bytes, chip_name):
        self._connection = connection
        self._index_bytes = index_bytes
        self._chip_name = chip_name
        # Serializes multi-register sequences against the interrupt polling
        # thread (a status read/clear must not interleave with them).
        self._lock = _threading.RLock() if _LINUX else _NoLock()
        self._callback = None
        self._int_pin_used = None
        self._poll_stop = False
        self._poll_thread = None

    # --- Register access --------------------------------------------------

    def _index(self, reg):
        if self._index_bytes == 2:
            return bytes([(reg >> 8) & 0xFF, reg & 0xFF])
        return bytes([reg & 0xFF])

    def _wr_block(self, reg, data):
        self._connection.write(self._index(reg) + bytes(data))

    def _rd_block(self, reg, n):
        return self._connection.write_read(self._index(reg), n)

    def _wr8(self, reg, value):
        self._wr_block(reg, (value & 0xFF,))

    def _rd8(self, reg):
        return self._rd_block(reg, 1)[0]

    def _wr16(self, reg, value):
        self._wr_block(reg, ((value >> 8) & 0xFF, value & 0xFF))

    def _rd16(self, reg):
        data = self._rd_block(reg, 2)
        return (data[0] << 8) | data[1]

    def _wr32(self, reg, value):
        self._wr_block(reg, ((value >> 24) & 0xFF, (value >> 16) & 0xFF,
                             (value >> 8) & 0xFF, value & 0xFF))

    def _rd32(self, reg):
        data = self._rd_block(reg, 4)
        return (data[0] << 24) | (data[1] << 16) | (data[2] << 8) | data[3]

    # --- Polling and boot -------------------------------------------------

    def _wait_until(self, predicate, what):
        start = _ticks_ms()
        while True:
            if predicate():
                return
            if _elapsed_ms(start) > TIMEOUT_MS:
                raise OSError('{} timeout waiting for {}'.format(self._chip_name, what))

    def _boot_wait(self):
        if getattr(self._connection, 'en_pin', None):
            self._connection.enable()
        time.sleep(BOOT_US / 1000000)

    # --- Re-addressing ----------------------------------------------------

    def _set_address_reg(self, reg, address):
        if address < 0x08 or address > 0x77:
            raise ValueError('address must be 0x08 to 0x77')
        self._wr8(reg, address & 0x7F)

    # --- Interrupt delivery -----------------------------------------------

    def _poll_interrupt_status(self):
        """Read and clear a pending interrupt; return its SOURCE_* value or 0."""
        raise NotImplementedError

    def _subscribe(self, callback, int_pin=None):
        self._callback = callback
        pin = int_pin if int_pin is not None else getattr(self._connection, 'int_pin', None)
        self._int_pin_used = pin
        if pin is not None:
            from periph.connection.input_pin import InputPin
            pin.on_edge(self._int_handler, InputPin.FALLING)
        elif _LINUX:
            self._poll_stop = False
            self._poll_thread = _threading.Thread(target=self._poll_loop, daemon=True)
            self._poll_thread.start()

    def _unsubscribe(self):
        if self._int_pin_used is not None:
            self._int_pin_used.off_edge(self._int_handler)
            self._int_pin_used = None
        elif _LINUX:
            self._poll_stop = True
        self._callback = None

    def _int_handler(self):
        status = self._poll_interrupt_status()
        if status and self._callback:
            self._callback(status)

    def _poll_loop(self):
        while not self._poll_stop:
            status = self._poll_interrupt_status()
            if status and self._callback:
                self._callback(status)
            time.sleep(0.005)
