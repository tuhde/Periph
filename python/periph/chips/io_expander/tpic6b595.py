try:
    import digitalio as _digitalio
    _CP = True
except ImportError:
    _CP = False


class Tpic6b595Minimal:
    """TPIC6B595 8-bit power SIPO shift register — minimal interface.

    Drives up to ``num_devices`` cascaded TPIC6B595s through a SiPo (serial-
    in/parallel-out) connection. Each device exposes 8 open-drain outputs
    (DRAIN0–DRAIN7); every write shifts the entire cascade MSB-first and pulses
    RCK to latch all outputs atomically. Outputs only sink current — they
    never source it; an external pull-up or load supply is required for the
    "off"/high state.

    The driver owns a ``num_devices``-byte shadow register so single-pin
    updates work without re-reading the bus. Every write (pin, port, fill)
    rebuilds and retransmits the entire reversed cascade — see
    ``specs/io_expander/tpic6b595.md`` for the wire-order reversal that
    cascading requires.

    Initialises every output to OFF at construction (shadow zero, latched
    once). If the SiPo connection has SRCLR wired, the constructor pulses
    it to clear the shift register before the all-zero latch.

    Args:
        connection: Configured SiPo connection (see ``periph.connection.sipo_*``).
        num_devices: Number of cascaded TPIC6B595s on the wire; default 1.
    """

    def __init__(self, connection, num_devices=1):
        self._connection = connection
        self._num_devices = num_devices
        self._shadow = bytearray(num_devices)
        try:
            self._connection.clear()
        except Exception:
            pass
        self._flush()

    def _flush(self):
        wire = bytes(reversed(self._shadow))
        self._connection.write(wire)

    def _set_pin(self, n, high):
        port = n // 8
        bit = n % 8
        if high:
            self._shadow[port] |= (1 << bit)
        else:
            self._shadow[port] &= ~(1 << bit) & 0xFF
        self._flush()

    def pin(self, n):
        """Return a Pin proxy object for global pin number ``n``.

        Args:
            n: Pin index, 0 (DRAIN0 of the device nearest the controller) to
                ``num_devices * 8 - 1``.

        Returns:
            _CPPin compatible with ``digitalio.DigitalInOut`` on CircuitPython,
            _Pin compatible with ``machine.Pin`` on MicroPython and Linux.
        """
        if _CP:
            return self._CPPin(self, n)
        return self._Pin(self, n)

    def write_port(self, port, mask):
        """Write all 8 outputs of cascaded device ``port`` from ``mask``.

        Updates the shadow register for the targeted port, rebuilds the
        reversed cascade, shifts it out, and pulses RCK to latch every
        cascaded device's outputs.

        Args:
            port: Cascaded device index (0 = nearest the controller).
            mask: 8-bit output mask. Bit 0 = DRAIN0, bit 7 = DRAIN7.
                  1 = ON (DMOS transistor conducting, sinks current through
                  an external load to GND); 0 = OFF (high-impedance).
        """
        self._shadow[port] = mask & 0xFF
        self._flush()

    def fill(self, value):
        """Set every pin on every cascaded device to ``value``.

        Sends immediately — the fast path for "all on"/"all off". A ``True``
        argument turns every DMOS output ON; ``False`` turns them all OFF
        (the safe initial state, also used at construction).

        Args:
            value: ``True`` to turn every output ON, ``False`` to turn them OFF.
        """
        b = 0xFF if value else 0x00
        for i in range(self._num_devices):
            self._shadow[i] = b
        self._flush()

    def off(self):
        """Turn every output off (equivalent to ``fill(False)``)."""
        self.fill(False)

    # ------------------------------------------------------------------
    # MicroPython / Linux Pin proxy (output-only)
    # ------------------------------------------------------------------

    class _Pin:
        """GPIO proxy for a single TPIC6B595 pin — ``machine.Pin``-compatible.

        Output-only — direction is fixed by the chip's hardware, so there is
        no ``init(mode)`` / ``mode`` / ``pull`` machinery; calls that imply
        input behavior are not implemented.

        Obtain via ``Tpic6b595Minimal.pin(n)``. Do not instantiate directly.

        Args:
            chip: Parent ``Tpic6b595Minimal`` instance.
            n: Pin index (0 to ``num_devices * 8 - 1``).
        """

        def __init__(self, chip, n):
            self._chip = chip
            self._n = n

        def value(self, x=None):
            """Read or write the pin.

            With no argument, returns the **shadow** bit for this pin
            (the driver has no bus read-back — the SiPo connection is
            write-only).

            With an argument, sets the pin's output state (1 = ON — DMOS
            transistor conducting, sinks current; 0 = OFF — high-impedance).

            Args:
                x: None to read; 0 or 1 to write.

            Returns:
                int: 0 or 1 when reading; ``None`` when writing.
            """
            if x is None:
                port = self._n // 8
                bit = self._n % 8
                return (self._chip._shadow[port] >> bit) & 1
            self._chip._set_pin(self._n, x)

        def on(self):
            """Turn the DMOS output ON (sink current through the external load)."""
            self._chip._set_pin(self._n, 1)

        def off(self):
            """Turn the DMOS output OFF (high-impedance)."""
            self._chip._set_pin(self._n, 0)

        def toggle(self):
            """Invert the current shadow bit for this pin."""
            port = self._n // 8
            bit = self._n % 8
            cur = (self._chip._shadow[port] >> bit) & 1
            self._chip._set_pin(self._n, 1 - cur)

        def set(self, high):
            """Satisfies the project's ``OutputPin`` contract.

            Args:
                high: ``True`` to turn ON, ``False`` to turn OFF.
            """
            self._chip._set_pin(self._n, 1 if high else 0)

    # ------------------------------------------------------------------
    # CircuitPython Pin proxy (output-only)
    # ------------------------------------------------------------------

    class _CPPin:
        """GPIO proxy for a single TPIC6B595 pin — ``digitalio.DigitalInOut``-compatible.

        Output-only subset of the ``digitalio.DigitalInOut`` interface. The
        ``value`` property reads the shadow bit (no bus read-back exists) and
        ``set(high)`` writes through. ``direction``/``switch_to_input`` are
        intentionally not implemented because there is no input mode.

        Obtain via ``Tpic6b595Minimal.pin(n)``. Do not instantiate directly.

        Args:
            chip: Parent ``Tpic6b595Minimal`` instance.
            n: Pin index (0 to ``num_devices * 8 - 1``).
        """

        def __init__(self, chip, n):
            self._chip = chip
            self._n = n

        @property
        def value(self):
            """bool: Current shadow bit (1 = ON, 0 = OFF)."""
            port = self._n // 8
            bit = self._n % 8
            return bool((self._chip._shadow[port] >> bit) & 1)

        @value.setter
        def value(self, v):
            self._chip._set_pin(self._n, int(bool(v)))

        def set(self, high):
            """Satisfies the project's ``OutputPin`` contract.

            Args:
                high: ``True`` to turn ON, ``False`` to turn OFF.
            """
            self._chip._set_pin(self._n, 1 if high else 0)

        def deinit(self):
            """Release the pin (no-op; shadow state is preserved)."""


class Tpic6b595Full(Tpic6b595Minimal):
    """TPIC6B595 full interface — extends :class:`Tpic6b595Minimal`.

    Adds the shift-register-clear and output-enable hardware features, plus
    a bulk multi-device write. Pin API surface stays exactly Minimal's —
    every TPIC6B595 pin is a fixed, capability-less output (no pull, no
    drive mode, no interrupt).

    Args:
        connection: Configured SiPo connection.
        num_devices: Number of cascaded TPIC6B595s on the wire; default 1.
    """

    def clear(self):
        """Pulse SRCLR to clear the shift register only.

        The storage register (and therefore the DRAIN outputs) keeps its
        last-latched value until the next RCK pulse. To actually blank the
        outputs, call ``off()`` or ``fill(False)`` afterwards.

        Raises:
            RuntimeError: If SRCLR was not wired on the SiPo connection.
        """
        self._connection.clear()

    def set_output_enable(self, enabled):
        """Drive G LOW (``enabled=True``) or HIGH (``enabled=False``).

        ``enabled=True`` lets the storage register drive the outputs.
        ``enabled=False`` forces every output off without disturbing the
        shadow register or the storage register's contents — the datasheet's
        documented use case for global PWM-style brightness dimming.

        Raises:
            RuntimeError: If G was not wired on the SiPo connection.
        """
        self._connection.set_output_enable(enabled)

    def write_all(self, values):
        """Write every cascaded device's byte in one call.

        Updates the whole shadow register and performs exactly one transmit +
        latch. ``values`` is length-truncated or zero-extended to
        ``num_devices`` as needed.

        Args:
            values: Sequence of ints; ``values[0]`` is the byte for cascaded
                device 0 (nearest the controller).
        """
        for i in range(self._num_devices):
            v = values[i] if i < len(values) else 0
            self._shadow[i] = v & 0xFF
        self._flush()
