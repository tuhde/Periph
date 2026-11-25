"""CAT5171 — 256-position I²C digital potentiometer (ON Semiconductor).

A single 3-terminal potentiometer (A, W, B) in an 8-lead SOT-23, usable as a
voltage divider or a 2-terminal rheostat. End-to-end resistance RAB is 50 kΩ
or 100 kΩ (ordering option); the wiper powers up at midscale (0x80). The I²C
address is 0x2C (AD0 = GND) or 0x2D (AD0 = VDD). The wiper register is
volatile — there is no EEPROM. The same driver file is used on MicroPython,
CircuitPython, and Linux hosts.

The chip has no register map. A write is ``[instruction, data]`` where the
instruction byte carries the midscale-reset flag (RS, bit 6) and the shutdown
flag (SD, bit 5); a read is a bare one-byte read returning the wiper
register. The driver therefore uses the plain ``Connection`` ``write`` /
``read`` calls, not register access.

Args:
    connection: Connection (I²C) pointing at the device (0x2C or 0x2D).
"""

I2C_ADDRESS = 0x2C
I2C_ADDRESS_AD0_HIGH = 0x2D

# Instruction byte bits.
_INSTR_RS = 0x40
_INSTR_SD = 0x20

# Wiper position after power-up / reset.
_MIDSCALE = 0x80


def _clamp_code(code):
    code = int(code)
    if code < 0:
        return 0
    if code > 255:
        return 255
    return code


class CAT5171Minimal:
    """CAT5171 digital potentiometer — minimal interface.

    Sets and reads back the wiper position, or the wiper-to-B resistance.
    Defaults: RAB = 100 kΩ, RW = 50 Ω; the wiper is assumed at its power-up
    midscale and the device not in shutdown.

    Args:
        connection: Connection (I²C) pointing at the device.
        r_ab: End-to-end resistance RAB in ohms (default 100000.0).
        r_wiper: Wiper resistance RW in ohms (default 50.0).
    """

    def __init__(self, connection, r_ab=100000.0, r_wiper=50.0):
        """Initialize CAT5171Minimal; performs no I²C traffic.

        Args:
            connection: Connection (I²C) pointing at the device.
            r_ab: End-to-end resistance RAB in ohms.
            r_wiper: Wiper resistance RW in ohms.
        """
        self._connection = connection
        self._r_ab = float(r_ab)
        self._r_w = float(r_wiper)
        self._code = _MIDSCALE
        self._sd = False

    def _write(self, instruction, code):
        self._connection.write(bytes([instruction, code]))

    def _write_code(self, code):
        code = _clamp_code(code)
        self._write(_INSTR_SD if self._sd else 0, code)
        self._code = code

    def _read_code(self):
        code = self._connection.read(1)[0]
        self._code = code
        return code

    def _code_for_wb(self, ohms):
        return _clamp_code(int((ohms - self._r_w) / self._r_ab * 256.0 + 0.5))

    def set_wiper(self, code):
        """Set the wiper position.

        Args:
            code: Wiper position 0–255 (clamped); 0 = at B, 255 = near A.
        """
        self._write_code(code)

    def get_wiper(self):
        """Read back the wiper position with a single read transaction.

        Returns:
            int: Wiper position 0–255.
        """
        return self._read_code()

    def set_resistance(self, ohms):
        """Set the W–B rheostat resistance to the nearest reachable code.

        Args:
            ohms: Target W–B resistance in ohms (clamped to the reachable range).
        """
        self._write_code(self._code_for_wb(ohms))

    def get_resistance(self):
        """Read the W–B resistance for the current wiper code.

        Returns:
            float: Resistance in ohms, ``D / 256 * RAB + RW``.
        """
        return self._read_code() / 256.0 * self._r_ab + self._r_w


class CAT5171Full(CAT5171Minimal):
    """CAT5171 full interface — extends Minimal with ratio and W–A helpers,
    increment/decrement, midscale, shutdown and reset.

    The shutdown state cannot be read back from the chip, so the driver tracks
    it (``False`` after construction and after ``reset``).

    Args:
        connection: Connection (I²C) pointing at the device.
        r_ab: End-to-end resistance RAB in ohms (default 100000.0).
        r_wiper: Wiper resistance RW in ohms (default 50.0).
    """

    def set_ratio(self, ratio):
        """Set the wiper by divider ratio V(W−B)/V(A−B).

        Args:
            ratio: 0.0–1.0; the code is ``round(ratio * 256)`` clamped to 255.
        """
        self._write_code(int(ratio * 256.0 + 0.5))

    def get_ratio(self):
        """Read the divider ratio.

        Returns:
            float: ``code / 256``.
        """
        return self._read_code() / 256.0

    def set_resistance_wa(self, ohms):
        """Set the W–A resistance (rheostat mode, B open) to the nearest code.

        Args:
            ohms: Target W–A resistance in ohms (clamped to the reachable range).
        """
        steps = int((ohms - self._r_w) / self._r_ab * 256.0 + 0.5)
        self._write_code(256 - steps)

    def get_resistance_wa(self):
        """Read the W–A resistance for the current wiper code.

        Returns:
            float: Resistance in ohms, ``(256 - D) / 256 * RAB + RW``.
        """
        return (256 - self._read_code()) / 256.0 * self._r_ab + self._r_w

    def increment(self, steps=1):
        """Move the wiper relative to its current position.

        Args:
            steps: Signed step count (negative to decrement).

        Returns:
            int: The new wiper code, clamped to 0–255.
        """
        code = _clamp_code(self._read_code() + int(steps))
        self._write_code(code)
        return code

    def midscale(self):
        """Move the wiper to midscale (0x80) with the RS instruction bit.

        SD is kept if the device is shut down.
        """
        self._write(_INSTR_RS | (_INSTR_SD if self._sd else 0), _MIDSCALE)
        self._code = _MIDSCALE

    def shutdown(self):
        """Shut down: terminal A opens and the wiper shorts to B.

        The wiper register is kept; the tracked current code is rewritten
        together with SD = 1.
        """
        self._sd = True
        self._write(_INSTR_SD, self._code)

    def resume(self):
        """Leave shutdown (SD = 0), rewriting the tracked current code."""
        self._sd = False
        self._write(0, self._code)

    def is_shutdown(self):
        """Return the driver-tracked shutdown state.

        Returns:
            bool: True if the device was shut down by this driver.
        """
        return self._sd

    def reset(self):
        """Midscale reset (RS = 1, SD = 0): wiper to 0x80, shutdown cleared.

        The chip has no reset pin, so this uses the RS instruction bit.
        """
        self._sd = False
        self._write(_INSTR_RS, _MIDSCALE)
        self._code = _MIDSCALE

    def set_rab(self, r_ab, r_wiper=None):
        """Change the resistance parameters used by conversions (no I²C traffic).

        Args:
            r_ab: End-to-end resistance RAB in ohms.
            r_wiper: Wiper resistance RW in ohms; unchanged if None.
        """
        self._r_ab = float(r_ab)
        if r_wiper is not None:
            self._r_w = float(r_wiper)
