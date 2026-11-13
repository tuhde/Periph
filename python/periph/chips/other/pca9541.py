"""PCA9541 — 2-to-1 I²C-bus master selector (NXP).

Two upstream masters (for example a primary and a back-up) sit on separate I²C
buses and share one downstream bus of slave devices; each master can connect
its bus to the downstream channel through the PCA9541's pass gates, or
disconnect both. There is no arbitration — whichever master writes its CONTROL
register last before a STOP wins. The chip also offers a built-in bus recovery
(nine clocks, a NACK and a STOP on the downstream channel before the switch), a
bus-traffic sensor, interrupt logic (``INT0`` / ``INT1`` per master, ``INT_IN``
collecting a downstream interrupt) and a functional interrupt test. The
driver is used by **one** of the two masters; the same software runs on both.

The I²C address is 0x70 + A (A3..A0 address pins); prefer 0x70–0x77. Each master
sees its **own** copy of the IE, CONTROL and ISTAT registers behind the same
addresses. The driver uses ``RegisterConnection`` (1-byte command code:
0x00 IE, 0x01 CONTROL, 0x02 ISTAT, +0x10 for auto-increment). The CONTROL
byte to write depends on the read-only complement bits of the master's own
register, so every change reads CONTROL first and applies the datasheet's
Table 12 rule. The same driver file is used on MicroPython, CircuitPython, and
Linux hosts.

Args:
    connection: RegisterConnection (I²C) pointing at the device (0x70–0x77).
"""

import time

try:
    import threading as _threading
    _LINUX = True
except ImportError:
    _LINUX = False

I2C_ADDRESS = 0x70

_REG_IE = 0x00
_REG_CONTROL = 0x01
_REG_ISTAT = 0x02
_AI = 0x10

# CONTROL bits.
_C_MYBUS = 0x01
_C_NMYBUS = 0x02
_C_BUSON = 0x04
_C_NBUSON = 0x08
_C_BUSINIT = 0x10
_C_TESTON = 0x40
_C_NTESTON = 0x80

SOURCE_INT_IN = 0x01
SOURCE_BUS_INIT = 0x02
SOURCE_BUS_NOT_IDLE = 0x04
SOURCE_BUS_LOST = 0x08
SOURCE_TEST = 0x40
SOURCE_OTHER_TEST = 0x80

_MASKABLE = 0x0F
_POLL_INTERVAL_S = 0.1


def _has_control(c):
    """MYBUS equals NMYBUS (XNOR): the reading master has control."""
    return ((c & _C_MYBUS) != 0) == ((c & _C_NMYBUS) != 0)


def _connected(c):
    """BUSON differs from NBUSON (XOR): the downstream bus is connected to some master."""
    return ((c & _C_BUSON) != 0) != ((c & _C_NBUSON) != 0)


def _nbuson(c):
    return 1 if c & _C_NBUSON else 0


def _nmybus(c):
    return 1 if c & _C_NMYBUS else 0


class PCA9541Minimal:
    """PCA9541 bus selector — minimal interface.

    Lets this master take, give up and check ownership of the downstream bus.
    ``init`` writes nothing: the connection state and the interrupt masks stay
    as power-up (or the other master) left them.

    Args:
        connection: RegisterConnection (I²C) pointing at the device.
    """

    def __init__(self, connection):
        """Initialize PCA9541Minimal and run the initialization sequence.

        Reads CONTROL as a bus check (raises if the chip does not acknowledge)
        and reads ISTAT once to clear stale BUSLOST / BUSOK / BUSINIT flags.

        Args:
            connection: RegisterConnection (I²C) pointing at the device.
        """
        self._connection = connection
        self._read_reg(_REG_CONTROL)
        self._read_reg(_REG_ISTAT)

    def _read_reg(self, reg):
        return self._connection.read_reg(reg, 1)[0]

    def _write_reg(self, reg, value):
        self._connection.write_reg(reg, bytes([value & 0xFF]))

    def has_control(self):
        """Report whether this master's channel is connected to the downstream bus.

        Returns:
            bool: True if MYBUS equals NMYBUS and the bus is connected
            (BUSON differs from NBUSON).
        """
        c = self._read_reg(_REG_CONTROL)
        return _has_control(c) and _connected(c)

    def _acquire_write(self, c, bus_init):
        w = ((1 - _nbuson(c)) << 2) | _nmybus(c)
        if bus_init:
            w |= _C_BUSINIT
        self._write_reg(_REG_CONTROL, w)

    def acquire(self, bus_init=False, timeout_ms=20):
        """Take the downstream bus.

        Reads CONTROL and writes MYBUS / BUSON per the datasheet rule; the write
        ends with a STOP, which makes the switch. With ``bus_init`` the chip
        first sends nine clocks, a NACK and a STOP on the downstream bus and
        flags BUSINIT in ISTAT.

        Args:
            bus_init: True to request bus initialization / recovery.
            timeout_ms: Maximum time to wait for the switch, in milliseconds.

        Returns:
            bool: True if this master has control afterwards, False if not
            within ``timeout_ms``.
        """
        c = self._read_reg(_REG_CONTROL)
        self._acquire_write(c, bus_init)
        attempts = int(timeout_ms) + 1
        for _ in range(attempts):
            if bus_init:
                if not self._read_reg(_REG_ISTAT) & 0x02:
                    time.sleep(0.001)
                    continue
            if self.has_control():
                return True
            time.sleep(0.001)
        return False

    def release(self):
        """Give the downstream bus to the other master.

        Writes MYBUS = NOT NMYBUS (the bus stays connected); the other master
        then owns the bus.
        """
        c = self._read_reg(_REG_CONTROL)
        self._write_reg(_REG_CONTROL, ((1 - _nbuson(c)) << 2) | (1 - _nmybus(c)))


class PCA9541Full(PCA9541Minimal):
    """PCA9541 full interface — extends Minimal with raw control access,
    connect / disconnect, bus recovery, interrupt masks, the INT functional
    test and interrupt delivery.

    Args:
        connection: RegisterConnection (I²C) pointing at the device.
    """

    SOURCE_INT_IN = SOURCE_INT_IN
    SOURCE_BUS_INIT = SOURCE_BUS_INIT
    SOURCE_BUS_NOT_IDLE = SOURCE_BUS_NOT_IDLE
    SOURCE_BUS_LOST = SOURCE_BUS_LOST
    SOURCE_TEST = SOURCE_TEST
    SOURCE_OTHER_TEST = SOURCE_OTHER_TEST

    def __init__(self, connection):
        PCA9541Minimal.__init__(self, connection)
        self._callback = None
        self._poll_thread = None
        self._poll_stop = True
        self._int_source = None

    def get_control(self):
        """Read the raw CONTROL byte.

        Returns:
            int: CONTROL register value.
        """
        return self._read_reg(_REG_CONTROL)

    def is_connected(self):
        """Report whether the downstream bus is connected to some master.

        Returns:
            bool: True if BUSON differs from NBUSON (not necessarily this master).
        """
        return _connected(self._read_reg(_REG_CONTROL))

    def status(self):
        """Decode the CONTROL register.

        Returns:
            dict: ``{has_control, connected, bus_init_requested, own_test,
            other_test}``; ``has_control`` is MYBUS == NMYBUS (control without
            regard to the connection).
        """
        c = self._read_reg(_REG_CONTROL)
        return {
            'has_control': _has_control(c),
            'connected': _connected(c),
            'bus_init_requested': bool(c & _C_BUSINIT),
            'own_test': bool(c & _C_TESTON),
            'other_test': bool(c & _C_NTESTON),
        }

    def connect(self):
        """Turn the connection on without changing ownership.

        Writes BUSON = NOT NBUSON; MYBUS keeps the value read.
        """
        c = self._read_reg(_REG_CONTROL)
        self._write_reg(_REG_CONTROL, ((1 - _nbuson(c)) << 2) | (c & _C_MYBUS))

    def disconnect(self):
        """Disconnect both masters from the downstream bus.

        Writes BUSON = NBUSON; the ownership bits are left. Used to isolate the
        downstream devices.
        """
        c = self._read_reg(_REG_CONTROL)
        self._write_reg(_REG_CONTROL, (_nbuson(c) << 2) | (c & _C_MYBUS))

    def recover_bus(self, timeout_ms=20):
        """Recover a hung downstream bus and take it.

        ``acquire(bus_init=True)``: the chip sends nine clocks, a NACK and a
        STOP on the downstream bus, then connects this master.

        Args:
            timeout_ms: Maximum time to wait for BUSINIT, in milliseconds.

        Returns:
            bool: True if this master has control afterwards.
        """
        return self.acquire(True, timeout_ms)

    def set_interrupt_mask(self, bus_lost=False, bus_not_idle=False, bus_init=False, int_in=False):
        """Write the IE register (True = masked, no interrupt).

        Called without arguments it re-enables every interrupt.

        Args:
            bus_lost: Mask the "other master took the bus" interrupt.
            bus_not_idle: Mask the "bus non-idle at the switch" interrupt.
            bus_init: Mask the "bus initialization done" interrupt.
            int_in: Mask the downstream INT_IN interrupt.
        """
        self._write_reg(_REG_IE, (8 if bus_lost else 0) | (4 if bus_not_idle else 0)
                        | (2 if bus_init else 0) | (1 if int_in else 0))

    def get_interrupt_mask(self):
        """Read the IE register.

        Returns:
            int: IE value (bit set = interrupt masked).
        """
        return self._read_reg(_REG_IE)

    def test_interrupt(self, own=False, other=False):
        """Drive the INT lines for the functional test.

        Sets TESTON (this master's INT low) and / or NTESTON (the other
        master's INT low); both False clears the test. The bus bits are
        written back unchanged, so ownership does not change.

        Args:
            own: True to drive this master's INT low.
            other: True to drive the other master's INT low.
        """
        c = self._read_reg(_REG_CONTROL)
        w = (c & (_C_BUSON | _C_MYBUS)) | (_C_TESTON if own else 0) | (_C_NTESTON if other else 0)
        self._write_reg(_REG_CONTROL, w)

    def read_all(self):
        """Read IE, CONTROL and ISTAT in one auto-increment transaction.

        Clears the self-clearing ISTAT flags.

        Returns:
            tuple: ``(ie, control, istat)``.
        """
        data = self._connection.read_reg(_AI | _REG_IE, 3)
        return data[0], data[1], data[2]

    def enable_interrupt(self, source):
        """Enable an interrupt source (clear its mask bit in IE).

        The test sources need no enable.

        Args:
            source: One of the ``SOURCE_*`` constants (0x01, 0x02, 0x04, 0x08).
        """
        bit = source & _MASKABLE
        if bit:
            self._write_reg(_REG_IE, self._read_reg(_REG_IE) & ~bit)

    def disable_interrupt(self, source):
        """Disable an interrupt source (set its mask bit in IE).

        Args:
            source: One of the ``SOURCE_*`` constants (0x01, 0x02, 0x04, 0x08).
        """
        bit = source & _MASKABLE
        if bit:
            self._write_reg(_REG_IE, self._read_reg(_REG_IE) | bit)

    def poll_interrupt(self):
        """Read ISTAT (clears BUSLOST / BUSOK / BUSINIT).

        Returns:
            int: ISTAT byte; bits as the ``SOURCE_*`` constants.
        """
        return self._read_reg(_REG_ISTAT)

    def on_interrupt(self, callback, int_pin=None):
        """Subscribe to this master's INT pin (falling edge).

        ``callback(status)`` receives the ISTAT byte read (which clears the
        self-clearing flags). Delivery uses int_pin, else connection.int_pin,
        else a 100 ms polling thread (Linux).

        Args:
            callback: Callable(status: int).
            int_pin: Optional InputPin overriding connection.int_pin.
        """
        self._callback = callback
        pin = int_pin if int_pin is not None else getattr(self._connection, 'int_pin', None)
        if pin is not None:
            from periph.connection.input_pin import InputPin
            pin.on_edge(self._int_handler, InputPin.FALLING)
            self._int_source = pin
        elif _LINUX:
            self._poll_stop = False
            self._poll_thread = _threading.Thread(target=self._poll_loop, daemon=True)
            self._poll_thread.start()

    def off_interrupt(self):
        """Unsubscribe and stop delivery."""
        self._poll_stop = True
        src = self._int_source
        if src is not None:
            src.off_edge(self._int_handler)
            self._int_source = None
        self._callback = None

    def _int_handler(self):
        status = self.poll_interrupt()
        if status and self._callback:
            self._callback(status)

    def _poll_loop(self):
        while not self._poll_stop:
            self._int_handler()
            time.sleep(_POLL_INTERVAL_S)
