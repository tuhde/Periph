"""RRH62000 all-in-one air-quality module (Renesas).

The RRH62000 combines a laser-scattering particulate-matter sensor, an HS40xx
temperature/humidity sensor and a ZMOD4410 TVOC/IAQ sensor behind a fan. Over
I²C (address ``0x69``, ``SEL`` pin to GND) it behaves as a register-addressed
device: a one-byte command code is written as the register pointer, followed
by a read (or the data byte for writable commands). Reading ``0x00`` returns
the 37-byte measurement block protected by a CRC-8.

The module must not be accessed more often than once per second (``tRD``);
the driver rate-limits itself and sleeps between accesses. A new sample is
produced every 3 s.
"""

import time


def _ticks_ms():
    """Monotonic milliseconds."""
    if hasattr(time, 'ticks_ms'):
        return time.ticks_ms()
    return int(time.monotonic() * 1000) if hasattr(time, 'monotonic') else int(time.time() * 1000)


def _diff_ms(a, b):
    """Return a - b in milliseconds (wrap-safe on MicroPython)."""
    if hasattr(time, 'ticks_diff'):
        return time.ticks_diff(a, b)
    return a - b


def _add_ms(t, ms):
    """Return t + ms (wrap-safe on MicroPython)."""
    if hasattr(time, 'ticks_add'):
        return time.ticks_add(t, ms)
    return t + ms


def _delay_ms(ms):
    """Sleep for `ms` milliseconds."""
    if ms <= 0:
        return
    if hasattr(time, 'sleep_ms'):
        time.sleep_ms(int(ms))
    else:
        time.sleep(ms / 1000.0)


def crc8(data):
    """CRC-8 of the measurement block (polynomial 0x31, init 0xFF, no reflection, no final XOR).

    Args:
        data: Bytes to check (bytes 0–35 of the block).

    Returns:
        int: The CRC (``crc8(b'\\xBE\\xEF') == 0x92``).
    """
    crc = 0xFF
    for b in data:
        crc ^= b
        for _ in range(8):
            crc = ((crc << 1) ^ 0x31) & 0xFF if crc & 0x80 else (crc << 1) & 0xFF
    return crc


class RRH62000Error(OSError):
    """RRH62000 data error: CRC mismatch, invalid (start-up) sample or timeout."""


class RRH62000Minimal:
    """RRH62000 air-quality module — minimal interface.

    One call returns PM1/PM2.5/PM10 (KCl reference), temperature, humidity,
    TVOC, estimated CO₂ and the UBA IAQ index.

    Default configuration (module defaults, not rewritten):
        - 10 moving averages
        - fan speed 86 %
        - automatic fan cleaning every 7 days for 10 s

    Args:
        connection: ``RegisterConnection`` (1-byte register addresses) bound to
            address 0x69.
        access_interval_ms: Minimum spacing between two I²C accesses. The
            module requires more than 1000 ms; lower it only for mocks.
    """

    CMD_READ = 0x00
    CMD_DATA = 0x40
    CMD_SLEEP = 0x50
    CMD_CLEAN = 0x51
    CMD_RESET = 0x52
    CMD_MAVE = 0x53
    CMD_TINTC_H = 0x5A
    CMD_TINTC_L = 0x5B
    CMD_TCLEAN = 0x5C
    CMD_SPEEDFAN = 0x63
    CMD_RMOX = 0x71
    CMD_UID = 0x72
    CMD_ARGVER = 0x73
    CMD_CSTATUS = 0x74
    CMD_FWVER = 0x75

    BLOCK_LENGTH = 37

    STATUS_HIGH_CONCENTRATION = 0x01
    STATUS_DUST_ACCUMULATION = 0x02
    STATUS_FAN_SPEED = 0x04
    STATUS_FAN = 0x08

    _STARTUP_MS = 1000       # tST: I²C available after power-up / reset / wake-up
    _VALID_MS = 3300         # first valid sample after power-up / reset / wake-up
    _DATA_TIMEOUT_MS = 4000  # read(): wait for DATA

    def __init__(self, connection, access_interval_ms=1000):
        self._connection = connection
        self._interval = access_interval_ms
        self._last_access = 0
        self._valid_at = 0
        self._mark_startup()  # the module may just have been powered: tST counts from construction

    # --- access control --------------------------------------------------------

    def _mark_startup(self):
        """Restart the start-up timers (power-up, reset, wake-up).

        The waits scale with the access interval so a mock (interval 0) does not sleep.
        """
        now = _ticks_ms()
        scale = self._interval / 1000.0
        self._last_access = _add_ms(now, int(self._STARTUP_MS * scale) - self._interval)
        self._valid_at = _add_ms(now, int(self._VALID_MS * scale))

    def _wait_access(self):
        wait = self._interval - _diff_ms(_ticks_ms(), self._last_access)
        _delay_ms(wait)

    def _read(self, cmd, length):
        self._wait_access()
        try:
            return bytes(self._connection.read_reg(cmd, length))
        finally:
            self._last_access = _ticks_ms()

    def _write(self, cmd, value):
        self._wait_access()
        try:
            self._connection.write_reg(cmd, bytes([value & 0xFF]))
        finally:
            self._last_access = _ticks_ms()

    # --- Minimal API -------------------------------------------------------------

    def init(self):
        """Wait for the start-up time (1 s from construction) and check presence.

        Reads the firmware version (``0x75``).

        Raises:
            OSError: If the module does not acknowledge.
        """
        self._read(self.CMD_FWVER, 2)

    def read(self):
        """Wait for a new sample and return the main air-quality values.

        Polls ``DATA`` once per second (up to 4 s), then reads and checks the
        37-byte block.

        Returns:
            dict: ``{pm1_ug_m3, pm2_5_ug_m3, pm10_ug_m3, temperature_c,
            humidity_rh, tvoc, eco2_ppm, iaq}`` — PM in µg/m³ (KCl reference),
            °C, %RH, TVOC in µg/m³, eCO₂ in ppm, IAQ index 1.00–5.00.

        Raises:
            RRH62000Error: On a timeout, a CRC mismatch or a start-up (all 0xFF) sample.
        """
        m = self._read_block(True)
        return {
            'pm1_ug_m3': m['pm1_ug_m3'],
            'pm2_5_ug_m3': m['pm2_5_ug_m3'],
            'pm10_ug_m3': m['pm10_ug_m3'],
            'temperature_c': m['temperature_c'],
            'humidity_rh': m['humidity_rh'],
            'tvoc': m['tvoc'],
            'eco2_ppm': m['eco2_ppm'],
            'iaq': m['iaq'],
        }

    # --- block --------------------------------------------------------------------

    def _wait_data(self):
        start = _ticks_ms()
        while True:
            if self._read(self.CMD_DATA, 1)[0] == 0x01:
                return
            if _diff_ms(_ticks_ms(), start) >= self._DATA_TIMEOUT_MS:
                raise RRH62000Error('RRH62000 no new data within 4 s')

    def _read_block(self, wait):
        _delay_ms(_diff_ms(self._valid_at, _ticks_ms()))
        if wait:
            self._wait_data()
        return self.decode(self._read(self.CMD_READ, self.BLOCK_LENGTH))

    @classmethod
    def decode(cls, block):
        """Decode a 37-byte measurement block.

        Args:
            block: The raw block read from ``0x00``.

        Returns:
            dict: All values (see :meth:`RRH62000Full.read_all`).

        Raises:
            RRH62000Error: On a wrong length, an all-0xFF block or a CRC mismatch.
        """
        block = bytes(block)
        if len(block) != cls.BLOCK_LENGTH:
            raise RRH62000Error('RRH62000 block must be 37 bytes')
        if all(b == 0xFF for b in block[:36]):
            raise RRH62000Error('RRH62000 sample invalid (start-up)')
        if crc8(block[:36]) != block[36]:
            raise RRH62000Error('RRH62000 CRC error')

        def word(i):
            return (block[i] << 8) | block[i + 1]

        status = block[0] | (block[1] << 8)
        t = word(24)
        if t & 0x8000:
            t -= 0x10000
        return {
            'status': status,
            'high_concentration': bool(status & cls.STATUS_HIGH_CONCENTRATION),
            'dust_accumulation': bool(status & cls.STATUS_DUST_ACCUMULATION),
            'fan_speed': bool(status & cls.STATUS_FAN_SPEED),
            'fan': bool(status & cls.STATUS_FAN),
            'nc_0_3': word(2) / 10,
            'nc_0_5': word(4) / 10,
            'nc_1': word(6) / 10,
            'nc_2_5': word(8) / 10,
            'nc_4': word(10) / 10,
            'pm1_ug_m3': word(12) / 10,
            'pm2_5_ug_m3': word(14) / 10,
            'pm10_ug_m3': word(16) / 10,
            'pm1_smoke_ug_m3': word(18) / 10,
            'pm2_5_smoke_ug_m3': word(20) / 10,
            'pm10_smoke_ug_m3': word(22) / 10,
            'temperature_c': t / 100,
            'humidity_rh': word(26) / 100,
            'tvoc': word(28) * 10,
            'eco2_ppm': word(30),
            'iaq': word(32) / 100,
        }


class RRH62000Full(RRH62000Minimal):
    """RRH62000 full interface — extends RRH62000Minimal with the complete
    measurement block, status flags, particle bins, sleep, fan cleaning,
    reset, settings (moving average, fan speed, cleaning interval and time),
    MOX resistance and identification.

    Args:
        connection: ``RegisterConnection`` (1-byte register addresses) bound to
            address 0x69.
        access_interval_ms: Minimum spacing between two I²C accesses (≥ 1000).
    """

    # --- measurement ------------------------------------------------------------

    def read_all(self):
        """Wait for a new sample and return the complete decoded block.

        Returns:
            dict: ``status`` (raw word), ``high_concentration``,
            ``dust_accumulation``, ``fan_speed``, ``fan`` (flags),
            ``nc_0_3``, ``nc_0_5``, ``nc_1``, ``nc_2_5``, ``nc_4`` (particles
            per cm³, 0.3/0.5/1/2.5/4–10 µm), ``pm1_ug_m3``, ``pm2_5_ug_m3``,
            ``pm10_ug_m3`` (KCl), ``pm1_smoke_ug_m3``, ``pm2_5_smoke_ug_m3``,
            ``pm10_smoke_ug_m3`` (cigarette smoke), ``temperature_c``,
            ``humidity_rh``, ``tvoc`` (µg/m³), ``eco2_ppm``, ``iaq``.

        Raises:
            RRH62000Error: On a timeout, a CRC mismatch or a start-up sample.
        """
        return self._read_block(True)

    def read_status(self):
        """Read the current block (without waiting for new data) and decode its status word.

        Returns:
            dict: ``{raw, high_concentration, dust_accumulation, fan_speed, fan}``.
        """
        m = self._read_block(False)
        return {
            'raw': m['status'],
            'high_concentration': m['high_concentration'],
            'dust_accumulation': m['dust_accumulation'],
            'fan_speed': m['fan_speed'],
            'fan': m['fan'],
        }

    def read_particles(self):
        """Read the current block (without waiting for new data) and return the particle values.

        Returns:
            dict: The six PM values (µg/m³, KCl and smoke) and the five
            number-concentration bins (cm⁻³).
        """
        m = self._read_block(False)
        keys = ('pm1_ug_m3', 'pm2_5_ug_m3', 'pm10_ug_m3', 'pm1_smoke_ug_m3',
                'pm2_5_smoke_ug_m3', 'pm10_smoke_ug_m3',
                'nc_0_3', 'nc_0_5', 'nc_1', 'nc_2_5', 'nc_4')
        return {k: m[k] for k in keys}

    def data_ready(self):
        """Check whether a new sample is available (``DATA``, ``0x40``).

        Returns:
            bool: True when new data has arrived.
        """
        return self._read(self.CMD_DATA, 1)[0] == 0x01

    # --- power, cleaning, reset ------------------------------------------------------

    def sleep(self):
        """Put the module to sleep (``0x50`` ← ``0x00``)."""
        self._write(self.CMD_SLEEP, 0x00)

    def wake(self):
        """Wake the module up (``0x50`` ← ``0x80``) and restart the start-up timers.

        The next access waits 1 s, the next measurement read 3.3 s.
        """
        self._write(self.CMD_SLEEP, 0x80)
        self._mark_startup()

    def start_cleaning(self):
        """Start the fan dust-cleaning (``0x51`` ← ``0x01``)."""
        self._write(self.CMD_CLEAN, 0x01)

    def cleaning_done(self):
        """Read the cleaning status (``0x74``).

        Returns:
            bool: True when the cleaning is completed.
        """
        return self._read(self.CMD_CSTATUS, 1)[0] == 0x01

    def soft_reset(self):
        """Reset the module (``0x52`` ← ``0x81``, same as power-on) and restart the start-up timers."""
        try:
            self._write(self.CMD_RESET, 0x81)
        except OSError:
            pass  # the module may not acknowledge while resetting
        self._mark_startup()

    # --- settings ------------------------------------------------------------------------

    def set_moving_average(self, count):
        """Set the number of moving averages (``0x53``).

        Args:
            count: 1–60 (default 10).

        Raises:
            ValueError: If count is out of range.
        """
        if not 1 <= count <= 60:
            raise ValueError('count must be 1..60')
        self._write(self.CMD_MAVE, count)

    def get_moving_average(self):
        """Read the number of moving averages.

        Returns:
            int: 1–60.
        """
        return self._read(self.CMD_MAVE, 1)[0]

    def set_fan_speed(self, percent):
        """Set the fan speed (``0x63``).

        Args:
            percent: 60–100 % (default 86).

        Raises:
            ValueError: If percent is out of range.
        """
        if not 60 <= percent <= 100:
            raise ValueError('percent must be 60..100')
        self._write(self.CMD_SPEEDFAN, percent)

    def get_fan_speed(self):
        """Read the fan speed.

        Returns:
            int: Percent (60–100).
        """
        return self._read(self.CMD_SPEEDFAN, 1)[0]

    def set_cleaning_interval(self, seconds):
        """Set the automatic cleaning interval (``0x5A``/``0x5B``, units of 30 s).

        Args:
            seconds: 0–1 814 400 s (21 days), a multiple of 30 (default 604 800 = 7 days).

        Raises:
            ValueError: If seconds is out of range or not a multiple of 30.
        """
        if not 0 <= seconds <= 1814400 or seconds % 30:
            raise ValueError('seconds must be 0..1814400 and a multiple of 30')
        units = seconds // 30
        self._write(self.CMD_TINTC_H, units >> 8)
        self._write(self.CMD_TINTC_L, units & 0xFF)

    def get_cleaning_interval(self):
        """Read the automatic cleaning interval.

        Returns:
            int: Seconds.
        """
        hi = self._read(self.CMD_TINTC_H, 1)[0]
        lo = self._read(self.CMD_TINTC_L, 1)[0]
        return ((hi << 8) | lo) * 30

    def set_cleaning_time(self, seconds):
        """Set the fan auto-cleaning time (``0x5C``).

        Args:
            seconds: 0–60 s (default 10).

        Raises:
            ValueError: If seconds is out of range.
        """
        if not 0 <= seconds <= 60:
            raise ValueError('seconds must be 0..60')
        self._write(self.CMD_TCLEAN, seconds)

    def get_cleaning_time(self):
        """Read the fan auto-cleaning time.

        Returns:
            int: Seconds (0–60).
        """
        return self._read(self.CMD_TCLEAN, 1)[0]

    # --- identification, diagnostics -------------------------------------------------------

    def read_mox_resistance(self):
        """Read the MOX sensor resistance (``0x71``, 4 bytes big-endian).

        Returns:
            int: Ohms.
        """
        d = self._read(self.CMD_RMOX, 4)
        return (d[0] << 24) | (d[1] << 16) | (d[2] << 8) | d[3]

    def unique_id(self):
        """Read the unique ID (``0x72``).

        Returns:
            bytes: 6 bytes.
        """
        return self._read(self.CMD_UID, 6)

    def algorithm_version(self):
        """Read the TVOC algorithm version (``0x73``).

        Returns:
            tuple: ``(major, minor, patch)``.
        """
        d = self._read(self.CMD_ARGVER, 3)
        return (d[0], d[1], d[2])

    def firmware_version(self):
        """Read the firmware version (``0x75``).

        Returns:
            tuple: ``(major, minor)``.
        """
        d = self._read(self.CMD_FWVER, 2)
        return (d[0], d[1])
