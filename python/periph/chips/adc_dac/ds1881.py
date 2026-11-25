"""DS1881 — dual nonvolatile audio log-taper digital potentiometer (Maxim).

Two 45 kΩ potentiometers whose wiper positions are steps in an attenuation
table. Option 1 gives 64 positions (1 dB per step, 0 to −62 dB, position 63
= mute); Option 2 (DS1808 compatible) gives 34 positions (12 × 1 dB, 12 × 2 dB,
8 × 3 dB, position 33 = mute). The I²C address is 0x28 (A2 = A1 = A0 = 0)
to 0x2F. The same driver file is used on MicroPython, CircuitPython, and
Linux hosts.

The chip has no register map: a write is one or more *command bytes*
(``00pppppp`` wiper 0, ``01pppppp`` wiper 1, ``10xxxVZC`` configuration) and a
read returns wiper 0, wiper 1 and the configuration register round-robin.
The driver therefore uses the plain ``Connection`` ``write`` / ``read``
calls, not register access. Wipers and configuration live in EEPROM when
V/NV = 0 (50 000 writes); the drivers default to volatile operation. The CE
pin is active low and is left to the ``Connection`` ``en_pin``
(``en_active_high=False``).

Args:
    connection: Connection (I²C) pointing at the device (0x28–0x2F).
"""

import time

I2C_ADDRESS = 0x28

# Command byte prefixes.
_CMD_WIPER0 = 0x00
_CMD_WIPER1 = 0x40
_CMD_CONFIG = 0x80

# Configuration bits.
_CFG_VOLATILE = 0x04  # V/NV: 1 = volatile
_CFG_ZC = 0x02
_CFG_OPTION2 = 0x01
_CFG_MASK = 0x07

# Minimal default: volatile, zero-crossing on, Option 1.
_CFG_DEFAULT = _CFG_VOLATILE | _CFG_ZC

_MUTE_DB = 80.0
_POWER_UP_S = 0.001
_POLL_S = 0.001


def _check_channel(channel):
    if channel != 0 and channel != 1:
        raise ValueError('channel must be 0 or 1')
    return channel


def _mute_position(option):
    return 63 if option == 1 else 33


def _position_to_db(position, option):
    if position >= _mute_position(option):
        return _MUTE_DB
    if option == 1 or position <= 12:
        return float(position)
    if position <= 24:
        return float(12 + 2 * (position - 12))
    return float(36 + 3 * (position - 24))


def _db_to_position(db, option):
    """Nearest non-mute position for ``db`` (clamped to the table)."""
    if db <= 0:
        return 0
    if option == 1:
        return min(int(db + 0.5), 62)
    best = 0
    best_err = db
    for p in range(1, 33):
        err = abs(_position_to_db(p, 2) - db)
        if err < best_err:
            best = p
            best_err = err
    return best


class DS1881Minimal:
    """DS1881 dual audio potentiometer — minimal interface.

    Sets and reads back the attenuation of the two channels in dB. Defaults:
    Option 1 (1 dB steps, 0 to −62 dB plus mute), volatile wipers (no EEPROM
    wear; both channels are at mute after power-up), zero-crossing detection
    on. Call ``init()`` once after power-up to apply them.

    Args:
        connection: Connection (I²C) pointing at the device (0x28–0x2F).
    """

    def __init__(self, connection):
        """Initialize DS1881Minimal; performs no I²C traffic (see ``init``).

        Args:
            connection: Connection (I²C) pointing at the device.
        """
        self._connection = connection
        self._config = _CFG_DEFAULT
        self._option = 1

    def init(self):
        """Run the initialization sequence.

        Waits the 1 ms power-up time, reads the three bytes and rewrites the
        configuration (volatile, zero-crossing on, Option 1) only if it
        differs, waiting for the EEPROM write that this triggers. The wipers
        are not touched.
        """
        time.sleep(_POWER_UP_S)
        raw = self._connection.read(3)
        if (raw[2] & _CFG_MASK) != _CFG_DEFAULT:
            self._connection.write(bytes([_CMD_CONFIG | _CFG_DEFAULT]))
            self._wait_ready(0.05)
        self._config = _CFG_DEFAULT
        self._option = 1

    def _wait_ready(self, timeout_s):
        attempts = int(timeout_s / _POLL_S) + 1
        for _ in range(attempts):
            try:
                self._connection.read(1)
                return
            except OSError:
                time.sleep(_POLL_S)
        raise OSError('DS1881 busy: EEPROM write did not finish')

    def _write_commands(self, data):
        self._connection.write(bytes(data))
        if not (self._config & _CFG_VOLATILE):
            self._wait_ready(0.05)

    def _write_position(self, channel, position):
        _check_channel(channel)
        position = int(position)
        mute = _mute_position(self._option)
        if position < 0:
            position = 0
        elif position > mute:
            position = mute
        self._write_commands([(channel << 6) | position])

    def _read_all(self):
        raw = self._connection.read(3)
        return raw[0] & 0x3F, raw[1] & 0x3F, raw[2]

    def _db_to_pos(self, db):
        return _db_to_position(db, self._option)

    def set_attenuation(self, channel, db):
        """Set a channel's attenuation to the nearest position.

        Args:
            channel: 0 or 1.
            db: Attenuation in dB (positive = quieter); rounded to the nearest
                step and clamped to 0…62 dB (Option 1).
        """
        _check_channel(channel)
        self._write_position(channel, self._db_to_pos(db))

    def get_attenuation(self, channel):
        """Read a channel's attenuation.

        Args:
            channel: 0 or 1.

        Returns:
            float: Attenuation in dB (``80.0`` for mute).
        """
        return _position_to_db(self.get_position(channel), self._option)

    def mute(self, channel):
        """Mute a channel (position 63 in Option 1).

        Args:
            channel: 0 or 1.
        """
        self._write_position(channel, _mute_position(self._option))

    def set_position(self, channel, position):
        """Write a raw wiper position.

        Args:
            channel: 0 or 1.
            position: 0–63 (63 = mute); clamped.
        """
        self._write_position(channel, position)

    def get_position(self, channel):
        """Read a wiper position with the three-byte round-robin read.

        Args:
            channel: 0 or 1.

        Returns:
            int: Position (low 6 bits of the read-back byte).
        """
        _check_channel(channel)
        return self._read_all()[channel]


class DS1881Full(DS1881Minimal):
    """DS1881 full interface — extends Minimal with stereo writes, Option 2,
    zero-crossing and EEPROM control, and table helpers.

    The driver caches the configuration (volatile, zero-crossing, option);
    ``init()`` and ``get_config()`` refresh it from the chip.

    Args:
        connection: Connection (I²C) pointing at the device (0x28–0x2F).
    """

    def _db_to_pos(self, db):
        if db >= 70:
            return _mute_position(self._option)
        return _db_to_position(db, self._option)

    def set_both(self, position0, position1):
        """Write both wipers in one transaction (no STOP between them).

        Args:
            position0: Position for channel 0 (clamped).
            position1: Position for channel 1 (clamped).
        """
        mute = _mute_position(self._option)
        data = []
        for channel, position in ((0, position0), (1, position1)):
            position = max(0, min(int(position), mute))
            data.append((channel << 6) | position)
        self._write_commands(data)

    def set_attenuation_both(self, db0, db1=None):
        """Set both channels' attenuation (stereo volume).

        Args:
            db0: Attenuation of channel 0 in dB.
            db1: Attenuation of channel 1 in dB; defaults to ``db0``.
        """
        if db1 is None:
            db1 = db0
        self.set_both(self._db_to_pos(db0), self._db_to_pos(db1))

    def configure(self, option=1, zero_crossing=True, nonvolatile=False):
        """Write the configuration byte if it differs from the cached value.

        The configuration is stored in EEPROM, so a write waits for
        acknowledge polling. The wipers are not rewritten — positions keep
        their numeric value when the option changes.

        Args:
            option: 1 (63 positions + mute, 1 dB steps) or 2 (33 positions +
                mute, DS1808 compatible).
            zero_crossing: Enable zero-crossing detection.
            nonvolatile: Store wiper writes in EEPROM and restore them at
                power-up (EEPROM is rated for 50 000 writes).
        """
        if option != 1 and option != 2:
            raise ValueError('option must be 1 or 2')
        config = 0
        if not nonvolatile:
            config |= _CFG_VOLATILE
        if zero_crossing:
            config |= _CFG_ZC
        if option == 2:
            config |= _CFG_OPTION2
        if config != self._config:
            self._connection.write(bytes([_CMD_CONFIG | config]))
            self._wait_ready(0.05)
        self._config = config
        self._option = option

    def get_config(self):
        """Read the configuration register (fresh read; refreshes the cache).

        Returns:
            dict: ``{'option', 'zero_crossing', 'nonvolatile', 'raw'}``.
        """
        raw = self._connection.read(3)[2]
        self._config = raw & _CFG_MASK
        self._option = 2 if raw & _CFG_OPTION2 else 1
        return {
            'option': self._option,
            'zero_crossing': bool(raw & _CFG_ZC),
            'nonvolatile': not (raw & _CFG_VOLATILE),
            'raw': raw,
        }

    def set_option(self, option):
        """Switch between Option 1 and 2 (positions are not converted).

        Args:
            option: 1 or 2.
        """
        self.configure(option, bool(self._config & _CFG_ZC), not (self._config & _CFG_VOLATILE))

    def set_zero_crossing(self, enabled):
        """Enable or disable zero-crossing detection.

        Args:
            enabled: True to enable.
        """
        self.configure(self._option, enabled, not (self._config & _CFG_VOLATILE))

    def set_nonvolatile(self, enabled):
        """Select where wiper writes go (V/NV bit).

        Args:
            enabled: True → subsequent wiper writes are stored in EEPROM and
                restored at power-up (50 000 writes endurance).
        """
        self.configure(self._option, bool(self._config & _CFG_ZC), enabled)

    def store(self):
        """Persist the current wiper positions to EEPROM.

        Reads both positions, switches to nonvolatile mode if needed, rewrites
        both wiper bytes in one transaction, waits for the EEPROM write by
        acknowledge polling and restores volatile mode if it was active.
        The EEPROM is rated for only 50 000 writes.
        """
        position0, position1, _ = self._read_all()
        was_nv = not (self._config & _CFG_VOLATILE)
        zc = bool(self._config & _CFG_ZC)
        option = self._option
        if not was_nv:
            self.configure(option, zc, True)
        self._write_commands([_CMD_WIPER0 | position0, _CMD_WIPER1 | position1])
        if not was_nv:
            self.configure(option, zc, False)

    def wait_ready(self, timeout_s=0.05):
        """Acknowledge-poll until the device answers (it NACKs while an EEPROM
        write is in progress).

        Args:
            timeout_s: Maximum time to wait in seconds.

        Raises:
            OSError: If the device is still busy after ``timeout_s``.
        """
        self._wait_ready(timeout_s)

    def read_all(self):
        """Read wiper 0, wiper 1 and the configuration byte in one transaction.

        Returns:
            tuple: ``(position0, position1, config_byte)``.
        """
        return self._read_all()

    def position_to_db(self, position):
        """Attenuation of a position in the current option.

        Args:
            position: Wiper position.

        Returns:
            float: dB (``80.0`` for mute and above).
        """
        return _position_to_db(int(position), self._option)

    def db_to_position(self, db):
        """Nearest position for an attenuation in the current option.

        Args:
            db: Attenuation in dB; ≥ 70 dB maps to mute, negative to 0.

        Returns:
            int: Wiper position.
        """
        return self._db_to_pos(db)

    def max_position(self):
        """Last non-mute position of the current option.

        Returns:
            int: 62 (Option 1) or 32 (Option 2).
        """
        return _mute_position(self._option) - 1

    def reset(self):
        """Volatile, zero-crossing on, Option 1 and both wipers to mute.

        Sent as one transaction (configuration byte, then both wiper bytes);
        waits for the configuration EEPROM write.
        """
        self._connection.write(bytes([_CMD_CONFIG | _CFG_DEFAULT, _CMD_WIPER0 | 63, _CMD_WIPER1 | 63]))
        self._config = _CFG_DEFAULT
        self._option = 1
        self._wait_ready(0.05)
