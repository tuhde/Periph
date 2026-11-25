"""AD5243 — Dual 256-position I²C digital potentiometer (Analog Devices).

Two independent 3-terminal potentiometers (A, W, B) in a 10-lead MSOP, usable
as voltage dividers or 2-terminal rheostats. End-to-end resistance RAB is
ordered as 2.5, 10, 50 or 100 kΩ; both wipers power up at midscale (0x80).
The I²C address is fixed at 0x2F (no address pins). Wiper values are
volatile — there is no EEPROM. The same driver file is used on MicroPython,
CircuitPython, and Linux hosts.

The chip has no register map: a write is ``[instruction, data]`` where the
instruction byte carries the channel select (A0, bit 7) and the shutdown flag
(SD, bit 6). The instruction byte is passed as the "register" of the
RegisterConnection.

Args:
    connection: RegisterConnection (I²C or SMBus) pointing at the device (0x2F).
"""

I2C_ADDRESS = 0x2F

# Instruction byte bits.
_INSTR_A0 = 0x80
_INSTR_SD = 0x40

# Wiper position after power-up / reset.
_MIDSCALE = 0x80


def _check_channel(channel):
    if channel != 0 and channel != 1:
        raise ValueError('channel must be 0 or 1')


def _clamp_code(code):
    code = int(code)
    if code < 0:
        return 0
    if code > 255:
        return 255
    return code


class AD5243Minimal:
    """AD5243 dual digital potentiometer — minimal interface.

    Sets and reads back the wiper position, or the wiper-to-B resistance, of
    either channel. Defaults: RAB = 10 kΩ, RW = 160 Ω; both channels assumed
    active (not in shutdown) at their power-up midscale.

    Args:
        connection: RegisterConnection (I²C or SMBus) pointing at the device.
        r_ab: End-to-end resistance RAB in ohms (default 10000.0).
        r_wiper: Wiper resistance RW in ohms (default 160.0).
    """

    def __init__(self, connection, r_ab=10000.0, r_wiper=160.0):
        """Initialize AD5243Minimal; performs no I²C traffic.

        Args:
            connection: RegisterConnection pointing at the device.
            r_ab: End-to-end resistance RAB in ohms.
            r_wiper: Wiper resistance RW in ohms.
        """
        self._connection = connection
        self._r_ab = float(r_ab)
        self._r_w = float(r_wiper)
        self._code = [_MIDSCALE, _MIDSCALE]
        self._sd = [False, False]

    def _instr(self, channel):
        _check_channel(channel)
        return (_INSTR_A0 if channel else 0) | (_INSTR_SD if self._sd[channel] else 0)

    def _write_code(self, channel, code):
        code = _clamp_code(code)
        self._connection.write_reg(self._instr(channel), code)
        self._code[channel] = code

    def _read_code(self, channel):
        code = self._connection.read_reg(self._instr(channel), 1)[0]
        self._code[channel] = code
        return code

    def _code_for_wb(self, ohms):
        return _clamp_code(int((ohms - 2.0 * self._r_w) / self._r_ab * 256.0 + 0.5))

    def set_wiper(self, channel, code):
        """Set the wiper position of a channel.

        Args:
            channel: 0 or 1.
            code: Wiper position 0–255 (clamped); 0 = at B, 255 = near A.

        Raises:
            ValueError: If channel is not 0 or 1.
        """
        self._write_code(channel, code)

    def get_wiper(self, channel):
        """Read back the wiper position of a channel.

        Sends the channel's instruction byte alone, then reads one byte.

        Args:
            channel: 0 or 1.

        Returns:
            int: Wiper position 0–255.
        """
        return self._read_code(channel)

    def set_resistance(self, channel, ohms):
        """Set the W–B rheostat resistance to the nearest reachable code.

        Args:
            channel: 0 or 1.
            ohms: Target W–B resistance in ohms (clamped to the reachable range).
        """
        self._write_code(channel, self._code_for_wb(ohms))

    def get_resistance(self, channel):
        """Read the W–B resistance for the channel's current wiper code.

        Args:
            channel: 0 or 1.

        Returns:
            float: Resistance in ohms, ``D / 256 * RAB + 2 * RW``.
        """
        return self._read_code(channel) / 256.0 * self._r_ab + 2.0 * self._r_w


class AD5243Full(AD5243Minimal):
    """AD5243 full interface — extends Minimal with ratio and W–A helpers,
    increment/decrement, per-channel shutdown and reset.

    The shutdown state cannot be read back from the chip, so the driver tracks
    it (``False`` after construction and after ``reset``).

    Args:
        connection: RegisterConnection (I²C or SMBus) pointing at the device.
        r_ab: End-to-end resistance RAB in ohms (default 10000.0).
        r_wiper: Wiper resistance RW in ohms (default 160.0).
    """

    def set_both(self, code1, code2):
        """Write both wipers (two transactions).

        Args:
            code1: Channel 0 wiper position 0–255.
            code2: Channel 1 wiper position 0–255.
        """
        self._write_code(0, code1)
        self._write_code(1, code2)

    def set_ratio(self, channel, ratio):
        """Set the wiper by divider ratio V(W−B)/V(A−B).

        Args:
            channel: 0 or 1.
            ratio: 0.0–1.0; the code is ``round(ratio * 256)`` clamped to 255.
        """
        self._write_code(channel, int(ratio * 256.0 + 0.5))

    def get_ratio(self, channel):
        """Read the divider ratio of a channel.

        Args:
            channel: 0 or 1.

        Returns:
            float: ``code / 256``.
        """
        return self._read_code(channel) / 256.0

    def set_resistance_wa(self, channel, ohms):
        """Set the W–A resistance (rheostat mode, B open) to the nearest code.

        Args:
            channel: 0 or 1.
            ohms: Target W–A resistance in ohms (clamped to the reachable range).
        """
        steps = int((ohms - 2.0 * self._r_w) / self._r_ab * 256.0 + 0.5)
        self._write_code(channel, 256 - steps)

    def get_resistance_wa(self, channel):
        """Read the W–A resistance for the channel's current wiper code.

        Args:
            channel: 0 or 1.

        Returns:
            float: Resistance in ohms, ``(256 - D) / 256 * RAB + 2 * RW``.
        """
        return (256 - self._read_code(channel)) / 256.0 * self._r_ab + 2.0 * self._r_w

    def increment(self, channel, steps=1):
        """Move the wiper relative to its current position.

        Args:
            channel: 0 or 1.
            steps: Signed step count (negative to decrement).

        Returns:
            int: The new wiper code, clamped to 0–255.
        """
        code = _clamp_code(self._read_code(channel) + int(steps))
        self._write_code(channel, code)
        return code

    def shutdown(self, channel):
        """Shut a channel down: terminal A opens and the wiper shorts to B.

        The RDAC contents are kept; the tracked current code is rewritten
        together with SD = 1.

        Args:
            channel: 0 or 1.
        """
        _check_channel(channel)
        self._sd[channel] = True
        self._write_code(channel, self._code[channel])

    def resume(self, channel):
        """Leave shutdown (SD = 0), rewriting the tracked current code.

        Args:
            channel: 0 or 1.
        """
        _check_channel(channel)
        self._sd[channel] = False
        self._write_code(channel, self._code[channel])

    def is_shutdown(self, channel):
        """Return the driver-tracked shutdown state of a channel.

        Args:
            channel: 0 or 1.

        Returns:
            bool: True if the channel was shut down by this driver.
        """
        _check_channel(channel)
        return self._sd[channel]

    def reset(self):
        """Restore the power-up preset: both wipers 0x80, SD = 0.

        The chip has no reset pin or command, so this writes the preset.
        """
        self._sd = [False, False]
        self._write_code(0, _MIDSCALE)
        self._write_code(1, _MIDSCALE)

    def set_rab(self, r_ab, r_wiper=None):
        """Change the resistance parameters used by conversions (no I²C traffic).

        Args:
            r_ab: End-to-end resistance RAB in ohms.
            r_wiper: Wiper resistance RW in ohms; unchanged if None.
        """
        self._r_ab = float(r_ab)
        if r_wiper is not None:
            self._r_w = float(r_wiper)
