"""Shared implementation for the ADS1113 / ADS1114 / ADS1115 16-bit ΔΣ ADCs.

The three chips share one register map and one Config-register layout; they
differ only in which Config fields are honoured in hardware (ADS1113: fixed
±2.048 V, no comparator, no mux; ADS1114: PGA + comparator; ADS1115: PGA +
comparator + 4-input mux). This module holds the common machinery — the
whole-word Config builder, the single-shot sequence, continuous mode, and
three mixins that the per-chip modules compose into their public Minimal /
Full classes:

    _ADS111xBase     connection, cached Config state, single-shot conversion
    _RateMixin       data rate, continuous mode, status, reset
    _GainMixin       programmable gain amplifier (ADS1114 / ADS1115)
    _ComparatorMixin comparator, thresholds and the ALERT/RDY interrupt API
                     (ADS1114 / ADS1115)

Registers are 16-bit big-endian, selected through a write-only Address
Pointer. Every Config write is a whole word — never read-modify-write.
"""

try:
    import threading as _threading
    _LINUX = True
except ImportError:
    _LINUX = False

import time

from periph.connection.register import to_signed


I2C_ADDRESS = 0x48

SOURCE_CONVERSION_READY = 0x01
SOURCE_COMPARATOR       = 0x02

_REG_CONVERSION = 0x00
_REG_CONFIG     = 0x01
_REG_LO_THRESH  = 0x02
_REG_HI_THRESH  = 0x03

_CONFIG_RESET = 0x8583
_LO_RESET     = 0x8000
_HI_RESET     = 0x7FFF

_OS        = 0x8000
_MODE_SHOT = 0x0100

# Full-scale range in volts, indexed by PGA[2:0] (codes 6 and 7 alias ±0.256 V).
_FSR = (6.144, 4.096, 2.048, 1.024, 0.512, 0.256)

# Samples per second, indexed by DR[2:0].
_RATES = (8, 16, 32, 64, 128, 250, 475, 860)

_QUE_DISABLED = 3


class _ADS111xBase:
    """Connection, cached Config fields and the single-shot conversion."""

    I2C_ADDRESS = I2C_ADDRESS

    def __init__(self, connection):
        self._connection = connection
        self._mux = 0
        self._pga = 2
        self._dr = 4
        self._comp_mode = 0
        self._comp_pol = 0
        self._comp_lat = 0
        self._comp_que = _QUE_DISABLED
        self._continuous = False

    def _read_reg(self, reg):
        raw = self._connection.read_reg(reg, 2)
        return (raw[0] << 8) | raw[1]

    def _write_reg(self, reg, value):
        self._connection.write_reg(reg, bytes([(value >> 8) & 0xFF, value & 0xFF]))

    def _config_word(self, os_bit, mux=None):
        mux = self._mux if mux is None else mux
        return ((_OS if os_bit else 0) | (mux << 12) | (self._pga << 9)
                | (0 if self._continuous else _MODE_SHOT) | (self._dr << 5)
                | (self._comp_mode << 4) | (self._comp_pol << 3)
                | (self._comp_lat << 2) | self._comp_que)

    def _write_config(self, os_bit=False, mux=None):
        self._write_reg(_REG_CONFIG, self._config_word(os_bit, mux))

    def _convert(self, mux):
        """Single-shot conversion on `mux`; returns the signed 16-bit code."""
        self._continuous = False
        self._write_config(True, mux)
        wait = 1.0 / _RATES[self._dr]
        deadline = time.time() + 2 * wait + 0.05
        time.sleep(wait)
        while not (self._read_reg(_REG_CONFIG) & _OS):
            if time.time() > deadline:
                raise OSError('ADS111x conversion timeout')
            time.sleep(0.0005)
        return to_signed(self._read_reg(_REG_CONVERSION), 16)


class _RateMixin:
    """Data rate, continuous conversion, status and reset."""

    DR_8   = 0
    DR_16  = 1
    DR_32  = 2
    DR_64  = 3
    DR_128 = 4
    DR_250 = 5
    DR_475 = 6
    DR_860 = 7

    def set_data_rate(self, dr):
        """Set the data rate used by all following conversions.

        Args:
            dr: DR_8 … DR_860 (0–7).

        Raises:
            ValueError: If dr is outside 0–7.
        """
        if not 0 <= dr <= 7:
            raise ValueError('dr must be 0-7')
        self._dr = dr
        if self._continuous:
            self._write_config()

    def get_data_rate(self):
        """Return the configured DR code (0–7)."""
        return self._dr

    def data_rate_sps(self):
        """Return the configured data rate in samples per second."""
        return _RATES[self._dr]

    def _start_continuous(self, mux):
        self._mux = mux
        self._continuous = True
        self._write_config()

    def read_continuous_raw(self):
        """Read the most recent completed result without touching Config.

        Valid at least 1/DR after start_continuous().

        Returns:
            int: Signed 16-bit conversion code.
        """
        return to_signed(self._read_reg(_REG_CONVERSION), 16)

    def read_continuous(self):
        """Read the most recent continuous result in volts."""
        return self.read_continuous_raw() * self.lsb()

    def stop_continuous(self):
        """Return to single-shot / power-down mode (MODE ← 1)."""
        self._continuous = False
        self._write_config()

    def is_converting(self):
        """Return True while CONFIG.OS reads 0 (single-shot mode only)."""
        return not (self._read_reg(_REG_CONFIG) & _OS)

    def read_config(self):
        """Return the raw 16-bit CONFIG register."""
        return self._read_reg(_REG_CONFIG)

    def full_scale(self):
        """Return ±FSR of the current gain in volts."""
        return _FSR[self._pga]

    def lsb(self):
        """Return the size of one code in volts (full_scale() / 32768)."""
        return self.full_scale() / 32768


class _GainMixin:
    """Programmable gain amplifier (ADS1114 / ADS1115)."""

    PGA_6_144 = 0
    PGA_4_096 = 1
    PGA_2_048 = 2
    PGA_1_024 = 3
    PGA_0_512 = 4
    PGA_0_256 = 5

    def set_gain(self, pga):
        """Set the full-scale range used by all following reads.

        Thresholds are not rescaled — rewrite them after a gain change.

        Args:
            pga: PGA_6_144 … PGA_0_256 (0–5).

        Raises:
            ValueError: If pga is outside 0–5 (6 and 7 alias ±0.256 V).
        """
        if not 0 <= pga <= 5:
            raise ValueError('pga must be 0-5')
        self._pga = pga
        if self._continuous:
            self._write_config()

    def get_gain(self):
        """Return the configured PGA code (0–5)."""
        return self._pga


class _ComparatorMixin:
    """Comparator, thresholds and ALERT/RDY interrupt API (ADS1114 / ADS1115)."""

    COMP_TRADITIONAL = 0
    COMP_WINDOW      = 1
    ACTIVE_LOW       = 0
    ACTIVE_HIGH      = 1
    QUEUE_1          = 0
    QUEUE_2          = 1
    QUEUE_4          = 2

    SOURCE_CONVERSION_READY = SOURCE_CONVERSION_READY
    SOURCE_COMPARATOR       = SOURCE_COMPARATOR

    def _init_comparator(self):
        self._lo_raw = -32768
        self._hi_raw = 32767
        self._source = 0
        self._queue = 0
        self._callback = None
        self._int_pin_used = None
        self._poll_stop = False
        self._poll_thread = None
        self._last_status = 0

    def configure_comparator(self, mode=COMP_TRADITIONAL, polarity=ACTIVE_LOW,
                             latch=False, queue=QUEUE_1):
        """Configure the comparator and route it to ALERT/RDY.

        Args:
            mode: COMP_TRADITIONAL or COMP_WINDOW.
            polarity: ACTIVE_LOW or ACTIVE_HIGH.
            latch: True to latch until clear_alert().
            queue: QUEUE_1, QUEUE_2 or QUEUE_4 consecutive out-of-range samples.

        Raises:
            ValueError: If an argument is out of range, or the thresholds last
                set through the driver have high <= low.
        """
        if mode not in (0, 1) or polarity not in (0, 1) or queue not in (0, 1, 2):
            raise ValueError('invalid comparator argument')
        if self._hi_raw <= self._lo_raw:
            raise ValueError('high threshold must exceed low threshold')
        self._comp_mode = mode
        self._comp_pol = polarity
        self._comp_lat = 1 if latch else 0
        self._queue = queue
        self._comp_que = queue
        self._source = SOURCE_COMPARATOR
        self._write_config()

    def disable_comparator(self):
        """Set COMP_QUE to 11; ALERT/RDY becomes high-impedance."""
        self._comp_que = _QUE_DISABLED
        self._source = 0
        self._write_config()

    def set_low_threshold_raw(self, raw):
        """Write LO_THRESH (signed 16-bit code, same scale as CONVERSION)."""
        self._lo_raw = raw
        self._write_reg(_REG_LO_THRESH, raw & 0xFFFF)

    def set_high_threshold_raw(self, raw):
        """Write HI_THRESH (signed 16-bit code, same scale as CONVERSION)."""
        self._hi_raw = raw
        self._write_reg(_REG_HI_THRESH, raw & 0xFFFF)

    def set_thresholds(self, low, high):
        """Write both thresholds from volts at the current gain.

        Args:
            low: Lower threshold in volts.
            high: Upper threshold in volts.

        Raises:
            ValueError: If high <= low.
        """
        if high <= low:
            raise ValueError('high must exceed low')
        self.set_low_threshold_raw(self._volts_to_code(low))
        self.set_high_threshold_raw(self._volts_to_code(high))

    def _volts_to_code(self, volts):
        steps = volts / self.lsb()
        code = int(steps + 0.5) if steps >= 0 else -int(-steps + 0.5)
        return max(-32768, min(32767, code))

    def get_thresholds_raw(self):
        """Return (low, high) read back from the chip, sign-extended."""
        return (to_signed(self._read_reg(_REG_LO_THRESH), 16),
                to_signed(self._read_reg(_REG_HI_THRESH), 16))

    def clear_alert(self):
        """Read CONVERSION to release a latched comparator output."""
        self._read_reg(_REG_CONVERSION)

    # --- Interrupt API ----------------------------------------------------

    def enable_interrupt(self, source):
        """Route one source to ALERT/RDY (the other is disabled).

        SOURCE_CONVERSION_READY programs HI_THRESH=0x8000, LO_THRESH=0x0000
        and COMP_QUE=00. SOURCE_COMPARATOR enables the comparator with the
        last configure_comparator() settings.

        Raises:
            ValueError: If source is unknown, or comparator thresholds have
                high <= low.
        """
        if source == SOURCE_CONVERSION_READY:
            self._write_reg(_REG_HI_THRESH, 0x8000)
            self._write_reg(_REG_LO_THRESH, 0x0000)
            self._comp_que = 0
            self._source = SOURCE_CONVERSION_READY
            self._write_config()
        elif source == SOURCE_COMPARATOR:
            self.set_low_threshold_raw(self._lo_raw)
            self.set_high_threshold_raw(self._hi_raw)
            self.configure_comparator(self._comp_mode, self._comp_pol,
                                      bool(self._comp_lat), self._queue)
        else:
            raise ValueError('unknown interrupt source')

    def disable_interrupt(self, source):
        """Release ALERT/RDY (COMP_QUE ← 11)."""
        self.disable_comparator()

    def poll_interrupt(self):
        """Return the enabled source constant if ALERT/RDY is asserted, else 0.

        Samples the connection's int_pin when present. Without a pin,
        SOURCE_CONVERSION_READY falls back to CONFIG.OS (single-shot only)
        and SOURCE_COMPARATOR returns 0. A latched comparator alert is
        cleared by reading CONVERSION.
        """
        if self._source == 0:
            return 0
        pin = getattr(self._connection, 'int_pin', None)
        if pin is not None:
            asserted = pin.is_high() == bool(self._comp_pol)
        elif self._source == SOURCE_CONVERSION_READY:
            asserted = bool(self._read_reg(_REG_CONFIG) & _OS)
        else:
            asserted = False
        if not asserted:
            return 0
        if self._source == SOURCE_COMPARATOR and self._comp_lat:
            self.clear_alert()
        return self._source

    def on_interrupt(self, callback, int_pin=None):
        """Subscribe to ALERT/RDY events.

        Uses int_pin, else connection.int_pin (edge matching COMP_POL), else a
        5 ms polling thread on Linux.

        Args:
            callback: Callable(status: int) — the poll_interrupt() value.
            int_pin: Optional InputPin overriding connection.int_pin.
        """
        self._callback = callback
        pin = int_pin if int_pin is not None else getattr(self._connection, 'int_pin', None)
        self._int_pin_used = pin
        if pin is not None:
            from periph.connection.input_pin import InputPin
            pin.on_edge(self._int_handler,
                        InputPin.RISING if self._comp_pol else InputPin.FALLING)
        elif _LINUX:
            self._last_status = self.poll_interrupt()
            self._poll_stop = False
            self._poll_thread = _threading.Thread(target=self._poll_loop, daemon=True)
            self._poll_thread.start()

    def off_interrupt(self):
        """Unsubscribe and stop delivery."""
        if self._int_pin_used is not None:
            self._int_pin_used.off_edge(self._int_handler)
            self._int_pin_used = None
        elif _LINUX:
            self._poll_stop = True
        self._callback = None

    def _int_handler(self):
        status = self.poll_interrupt()
        if self._callback:
            self._callback(status)

    def _poll_loop(self):
        while not self._poll_stop:
            status = self.poll_interrupt()
            if status != self._last_status:
                self._last_status = status
                if self._callback:
                    self._callback(status)
            time.sleep(0.005)


def _reset_registers(chip, with_thresholds):
    """Write the reset values back and clear the cached settings."""
    chip._write_reg(_REG_CONFIG, _CONFIG_RESET)
    if with_thresholds:
        chip._write_reg(_REG_LO_THRESH, _LO_RESET)
        chip._write_reg(_REG_HI_THRESH, _HI_RESET)
    _ADS111xBase.__init__(chip, chip._connection)
    if with_thresholds:
        chip._init_comparator()
