"""AD5252 — Dual 256-position I²C digital potentiometer with EEMEM (Analog Devices).

Two independent 3-terminal potentiometers (A, W, B) usable as voltage
dividers or 2-terminal rheostats. End-to-end resistance RAB is ordered as 1,
10, 50 or 100 kΩ. Each wiper (RDAC) can be stored in nonvolatile memory
(EEMEM) and is restored at power-on; until a value is stored the wipers power
up at midscale (128). The chip also has 12 bytes of user EEMEM, quick
commands (±1 step, ±6 dB, store, restore, reset) and a factory-stored RAB
tolerance per channel. The I²C address is 0x2C–0x2F (AD1, AD0 pins). The same
driver file is used on MicroPython, CircuitPython, and Linux hosts.

The chip has no register map: the instruction byte works as a one-byte
register pointer, so the driver uses a RegisterConnection with
``reg_bytes`` = 1. Quick commands are single-byte writes. A write to EEMEM
(store, user EEMEM write, reset) starts a ≈ 26 ms internal cycle during which
the chip does not acknowledge; the driver polls with the harmless NOP quick
command until it answers.

Args:
    connection: RegisterConnection (I²C or SMBus) pointing at the device
        (0x2C–0x2F).
"""

import time

I2C_ADDRESS = 0x2C

# Instruction bytes (RDAC1 = channel 0, RDAC3 = channel 1).
_RDAC = (0x01, 0x03)
_EEMEM_FLAG = 0x20
_TOL_INT = (0x3A, 0x3E)

# Quick-command bytes: base | A1:A0 (0x01 channel 0, 0x03 channel 1).
_Q_NOP = 0x80
_Q_RESTORE = 0x88
_Q_STORE = 0x90
_Q_HALVE = 0x98
_Q_DEC_STEP = 0xA8
_Q_INC_6DB = 0xC0
_Q_INC_STEP = 0xD0
_Q_DEC_6DB_ALL = 0xA0
_Q_DEC_STEP_ALL = 0xB0
_Q_RESET = 0xB8
_Q_INC_6DB_ALL = 0xC8
_Q_INC_STEP_ALL = 0xD8

_POLL_S = 0.002
_POLL_TIMEOUT_S = 0.1


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


class AD5252Minimal:
    """AD5252 dual digital potentiometer — minimal interface.

    Sets and reads back the wiper position, or the wiper-to-B resistance, of
    either channel. Defaults: RAB = 10 kΩ, RW = 75 Ω; the wipers are at their
    power-on value (EEMEM or midscale 128).

    Args:
        connection: RegisterConnection (I²C or SMBus) pointing at the device.
        r_ab: End-to-end resistance RAB in ohms (default 10000.0).
        r_wiper: Wiper resistance RW in ohms (default 75.0).
    """

    def __init__(self, connection, r_ab=10000.0, r_wiper=75.0):
        """Initialize AD5252Minimal; performs no I²C traffic.

        Args:
            connection: RegisterConnection pointing at the device.
            r_ab: End-to-end resistance RAB in ohms.
            r_wiper: Wiper resistance RW in ohms.
        """
        self._connection = connection
        self._r_ab = float(r_ab)
        self._r_w = float(r_wiper)

    def _write_code(self, channel, code):
        _check_channel(channel)
        self._connection.write_reg(_RDAC[channel], _clamp_code(code))

    def _read_code(self, channel):
        _check_channel(channel)
        return self._connection.read_reg(_RDAC[channel], 1)[0]

    def _code_for_wb(self, ohms):
        return _clamp_code(int((ohms - self._r_w) / self._r_ab * 256.0 + 0.5))

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
            float: Resistance in ohms, ``D / 256 * RAB + RW``.
        """
        return self._read_code(channel) / 256.0 * self._r_ab + self._r_w


class AD5252Full(AD5252Minimal):
    """AD5252 full interface — extends Minimal with ratio and W–A helpers,
    quick commands (±1 step, ±6 dB), EEMEM store/restore/reset, user EEMEM and
    the factory RAB tolerance.

    Args:
        connection: RegisterConnection (I²C or SMBus) pointing at the device.
        r_ab: End-to-end resistance RAB in ohms (default 10000.0).
        r_wiper: Wiper resistance RW in ohms (default 75.0).
    """

    def _quick(self, base, channel):
        if channel is None:
            raise ValueError('channel required')
        _check_channel(channel)
        self._connection.write(bytes([base | _RDAC[channel]]))

    def _quick_byte(self, byte):
        self._connection.write(bytes([byte]))

    def _wait_ready(self):
        deadline = time.monotonic() + _POLL_TIMEOUT_S
        while True:
            try:
                self._connection.write(bytes([_Q_NOP]))
                return
            except OSError:
                if time.monotonic() >= deadline:
                    raise OSError('AD5252 busy: EEMEM write did not finish')
                time.sleep(_POLL_S)

    def _each(self, channel, base, base_all):
        if channel is None:
            self._quick_byte(base_all)
        else:
            self._quick(base, channel)

    def set_both(self, code0, code1):
        """Write both wipers in one transaction (consecutive write).

        Args:
            code0: Channel 0 wiper position 0–255.
            code1: Channel 1 wiper position 0–255.
        """
        self._connection.write_reg(_RDAC[0], bytes([_clamp_code(code0), _clamp_code(code1)]))

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
        steps = int((ohms - self._r_w) / self._r_ab * 256.0 + 0.5)
        self._write_code(channel, 256 - steps)

    def get_resistance_wa(self, channel):
        """Read the W–A resistance for the channel's current wiper code.

        Args:
            channel: 0 or 1.

        Returns:
            float: Resistance in ohms, ``(256 - D) / 256 * RAB + RW``.
        """
        return (256 - self._read_code(channel)) / 256.0 * self._r_ab + self._r_w

    def increment(self, channel=None):
        """Increment the wiper by one step (quick command).

        Args:
            channel: 0 or 1, or None for both channels.
        """
        self._each(channel, _Q_INC_STEP, _Q_INC_STEP_ALL)

    def decrement(self, channel=None):
        """Decrement the wiper by one step (quick command).

        Args:
            channel: 0 or 1, or None for both channels.
        """
        self._each(channel, _Q_DEC_STEP, _Q_DEC_STEP_ALL)

    def double(self, channel=None):
        """Increase the wiper by 6 dB — the code doubles (quick command).

        Args:
            channel: 0 or 1, or None for both channels.
        """
        self._each(channel, _Q_INC_6DB, _Q_INC_6DB_ALL)

    def halve(self, channel=None):
        """Decrease the wiper by 6 dB — the code halves (quick command).

        Args:
            channel: 0 or 1, or None for both channels.
        """
        self._each(channel, _Q_HALVE, _Q_DEC_6DB_ALL)

    def store(self, channel=None):
        """Store the wiper in EEMEM (restored at power-on), then wait ≈ 26 ms.

        Args:
            channel: 0 or 1, or None for both channels.

        Raises:
            OSError: If the chip does not acknowledge again within 100 ms.
        """
        if channel is None:
            self._quick(_Q_STORE, 0)
            self._wait_ready()
            self._quick(_Q_STORE, 1)
        else:
            self._quick(_Q_STORE, channel)
        self._wait_ready()

    def restore(self, channel=None):
        """Restore the wiper from EEMEM, then send a NOP to return to idle.

        Args:
            channel: 0 or 1, or None for both channels.
        """
        if channel is None:
            self._quick(_Q_RESTORE, 0)
            self._quick(_Q_RESTORE, 1)
        else:
            self._quick(_Q_RESTORE, channel)
        self.nop()

    def reset(self):
        """Quick-command reset: restore both wipers from EEMEM and wait.

        Raises:
            OSError: If the chip does not acknowledge again within 100 ms.
        """
        self._quick_byte(_Q_RESET)
        self._wait_ready()

    def read_eemem(self, index):
        """Read a user EEMEM byte.

        Args:
            index: 4–15.

        Returns:
            int: The stored byte.

        Raises:
            ValueError: If index is outside 4–15.
        """
        if index < 4 or index > 15:
            raise ValueError('index must be 4..15')
        return self._connection.read_reg(_EEMEM_FLAG | index, 1)[0]

    def write_eemem(self, index, value):
        """Write a user EEMEM byte and wait ≈ 26 ms for the write cycle.

        Args:
            index: 4–15.
            value: Byte 0–255.

        Raises:
            ValueError: If index is outside 4–15.
            OSError: If the chip does not acknowledge again within 100 ms.
        """
        if index < 4 or index > 15:
            raise ValueError('index must be 4..15')
        self._connection.write_reg(_EEMEM_FLAG | index, int(value) & 0xFF)
        self._wait_ready()

    def read_tolerance(self, channel):
        """Read the factory-stored RAB tolerance of a channel.

        Args:
            channel: 0 or 1.

        Returns:
            float: Tolerance in percent (sign-magnitude integer byte plus
            decimal byte in units of 1/256 %).
        """
        _check_channel(channel)
        raw = self._connection.read_reg(_TOL_INT[channel], 2)
        value = (raw[0] & 0x7F) + raw[1] / 256.0
        return -value if raw[0] & 0x80 else value

    def rab_actual(self, channel):
        """Return the actual RAB of a channel from its stored tolerance.

        Args:
            channel: 0 or 1.

        Returns:
            float: ``r_ab * (1 + tolerance / 100)`` in ohms.
        """
        return self._r_ab * (1.0 + self.read_tolerance(channel) / 100.0)

    def nop(self):
        """Send the NOP quick command (0x80), returning the chip to idle."""
        self._quick_byte(_Q_NOP)

    def set_rab(self, r_ab, r_wiper=None):
        """Change the resistance parameters used by conversions (no I²C traffic).

        Args:
            r_ab: End-to-end resistance RAB in ohms.
            r_wiper: Wiper resistance RW in ohms; unchanged if None.
        """
        self._r_ab = float(r_ab)
        if r_wiper is not None:
            self._r_w = float(r_wiper)
