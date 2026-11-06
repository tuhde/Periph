"""ADS1014 — 12-bit I²C ADC with PGA and programmable comparator (Texas Instruments).

Single-input (AIN0 − AIN1) ΔΣ ADC with internal reference and oscillator, a
programmable-gain amplifier (±6.144 V … ±0.256 V full scale), data rates of
128–3300 SPS, single-shot or continuous conversion, and a programmable digital
comparator with an ALERT/RDY output. The chip has no input multiplexer and no
ID register. Four selectable addresses (0x48–0x4B) via the ADDR strap. The same
driver file is used on MicroPython, CircuitPython, and Linux hosts.

Registers are 16-bit, big-endian, addressed through a persistent Address
Pointer. Config writes are always whole-word (never read-modify-write).

Args:
    connection: RegisterConnection (I²C or SMBus) pointing at the device
        (0x48–0x4B, per the board's ADDR strapping).
"""

import time

from periph.connection.register import to_signed

try:
    import threading as _threading
    _LINUX = True
except ImportError:
    _LINUX = False


I2C_ADDRESS = 0x48

# Programmable-gain amplifier settings (PGA[2:0]).
PGA_6_144 = 0
PGA_4_096 = 1
PGA_2_048 = 2
PGA_1_024 = 3
PGA_0_512 = 4
PGA_0_256 = 5

# Data-rate codes (DR[2:0]).
DR_128 = 0
DR_250 = 1
DR_490 = 2
DR_920 = 3
DR_1600 = 4
DR_2400 = 5
DR_3300 = 6

# Comparator mode, polarity and queue settings.
COMP_TRADITIONAL = 0
COMP_WINDOW = 1
ACTIVE_LOW = 0
ACTIVE_HIGH = 1
QUEUE_1 = 0
QUEUE_2 = 1
QUEUE_4 = 2

# Interrupt sources — one at a time, both share the ALERT/RDY pin.
SOURCE_CONVERSION_READY = 0x01
SOURCE_COMPARATOR = 0x02

_FULL_SCALE = (6.144, 4.096, 2.048, 1.024, 0.512, 0.256)
_SPS = (128, 250, 490, 920, 1600, 2400, 3300)

_REG_CONVERSION = 0x00
_REG_CONFIG = 0x01
_REG_LO_THRESH = 0x02
_REG_HI_THRESH = 0x03

_CONFIG_RESET = 0x8583
_LO_RESET = 0x8000
_HI_RESET = 0x7FFF

_OS = 0x8000
_MODE_SINGLE = 0x0100
_QUEUE_DISABLE = 3


class ADS1014Minimal:
    """ADS1014 12-bit ADC — minimal interface.

    Reads AIN0 − AIN1 with a single-shot conversion. Defaults: ±2.048 V range
    (1 mV/LSB), 1600 SPS, comparator disabled. The full Config word is written
    for every conversion, so the chip is never left converting. No I²C traffic
    at construction.

    Args:
        connection: RegisterConnection (I²C or SMBus) pointing at the device.
    """

    def __init__(self, connection):
        self._connection = connection
        self._pga = PGA_2_048
        self._dr = DR_1600
        self._continuous = False
        self._comp_mode = COMP_TRADITIONAL
        self._comp_pol = ACTIVE_LOW
        self._comp_lat = False
        self._queue = QUEUE_1
        self._source = 0

    def _read_reg(self, reg):
        data = self._connection.read_reg(reg, 2)
        return (data[0] << 8) | data[1]

    def _write_reg(self, reg, value):
        self._connection.write_reg(reg, bytes([(value >> 8) & 0xFF, value & 0xFF]))

    def _comp_que(self):
        if self._source == SOURCE_CONVERSION_READY:
            return QUEUE_1
        if self._source == SOURCE_COMPARATOR:
            return self._queue
        return _QUEUE_DISABLE

    def _config_word(self, os_bit, continuous):
        word = _OS if os_bit else 0
        word |= self._pga << 9
        if not continuous:
            word |= _MODE_SINGLE
        word |= self._dr << 5
        word |= self._comp_mode << 4
        word |= self._comp_pol << 3
        word |= (1 if self._comp_lat else 0) << 2
        word |= self._comp_que()
        return word

    def _read_conversion(self):
        return to_signed(self._read_reg(_REG_CONVERSION), 16) >> 4

    def read_raw(self):
        """Run one single-shot conversion of AIN0 − AIN1.

        Writes the whole Config word with OS = 1, polls OS until the chip is
        idle again, then reads CONVERSION.

        Returns:
            int: 12-bit two's-complement code, −2048…2047.
        """
        self._write_reg(_REG_CONFIG, self._config_word(True, False))
        self._continuous = False
        for _ in range(100):
            if self._read_reg(_REG_CONFIG) & _OS:
                break
            time.sleep(0.0005)
        return self._read_conversion()

    def read_voltage(self):
        """Run one single-shot conversion and convert it to volts.

        Returns:
            float: Voltage AIN0 − AIN1 in volts (raw × full-scale / 2048).
        """
        return self.read_raw() * _FULL_SCALE[self._pga] / 2048.0


class ADS1014Full(ADS1014Minimal):
    """ADS1014 full interface — extends Minimal with PGA gain, data rate,
    continuous conversion, the programmable comparator and the Level-2
    ALERT/RDY interrupt API.

    Args:
        connection: RegisterConnection (I²C or SMBus) pointing at the device.
    """

    PGA_6_144 = PGA_6_144
    PGA_4_096 = PGA_4_096
    PGA_2_048 = PGA_2_048
    PGA_1_024 = PGA_1_024
    PGA_0_512 = PGA_0_512
    PGA_0_256 = PGA_0_256
    DR_128 = DR_128
    DR_250 = DR_250
    DR_490 = DR_490
    DR_920 = DR_920
    DR_1600 = DR_1600
    DR_2400 = DR_2400
    DR_3300 = DR_3300
    COMP_TRADITIONAL = COMP_TRADITIONAL
    COMP_WINDOW = COMP_WINDOW
    ACTIVE_LOW = ACTIVE_LOW
    ACTIVE_HIGH = ACTIVE_HIGH
    QUEUE_1 = QUEUE_1
    QUEUE_2 = QUEUE_2
    QUEUE_4 = QUEUE_4
    SOURCE_CONVERSION_READY = SOURCE_CONVERSION_READY
    SOURCE_COMPARATOR = SOURCE_COMPARATOR

    def __init__(self, connection):
        super().__init__(connection)
        self._lo_raw = -2048
        self._hi_raw = 2047
        self._callback = None
        self._int_pin_used = None
        self._poll_stop = False
        self._poll_thread = None
        self._last_status = 0

    def _write_config(self):
        self._write_reg(_REG_CONFIG, self._config_word(False, self._continuous))

    # --- Gain and data rate ----------------------------------------------

    def set_gain(self, pga):
        """Select the full-scale range used by all following reads.

        Thresholds must be rewritten after a gain change.

        Args:
            pga: PGA_6_144 (0) … PGA_0_256 (5).

        Raises:
            ValueError: If pga is outside 0–5 (6 and 7 alias ±0.256 V).
        """
        if pga < 0 or pga > PGA_0_256:
            raise ValueError('pga must be 0-5')
        self._pga = pga
        if self._continuous:
            self._write_config()

    def get_gain(self):
        """Return the configured PGA code.

        Returns:
            int: PGA code 0–5.
        """
        return self._pga

    def full_scale(self):
        """Return the full-scale range of the current gain.

        Returns:
            float: ±FSR in volts (6.144, 4.096, 2.048, 1.024, 0.512 or 0.256).
        """
        return _FULL_SCALE[self._pga]

    def lsb(self):
        """Return the size of one conversion code.

        Returns:
            float: full_scale() / 2048 in volts (3 mV … 0.125 mV).
        """
        return _FULL_SCALE[self._pga] / 2048.0

    def set_data_rate(self, dr):
        """Select the conversion data rate.

        Args:
            dr: DR_128 (0) … DR_3300 (6).

        Raises:
            ValueError: If dr is outside 0–6 (7 aliases 3300 SPS).
        """
        if dr < 0 or dr > DR_3300:
            raise ValueError('dr must be 0-6')
        self._dr = dr
        if self._continuous:
            self._write_config()

    def get_data_rate(self):
        """Return the configured data-rate code.

        Returns:
            int: DR code 0–6.
        """
        return self._dr

    def data_rate_sps(self):
        """Return the current data rate in samples per second.

        Returns:
            int: 128, 250, 490, 920, 1600, 2400 or 3300.
        """
        return _SPS[self._dr]

    # --- Continuous conversion -------------------------------------------

    def start_continuous(self):
        """Start continuous conversion (MODE = 0) at the current data rate."""
        self._continuous = True
        self._write_config()

    def read_continuous_raw(self):
        """Read the most recent completed result without touching Config.

        Valid at least 1/DR after start_continuous().

        Returns:
            int: 12-bit two's-complement code, −2048…2047.
        """
        return self._read_conversion()

    def read_continuous(self):
        """Read the most recent completed result in volts.

        Returns:
            float: read_continuous_raw() × lsb() in volts.
        """
        return self._read_conversion() * self.lsb()

    def stop_continuous(self):
        """Stop continuous conversion (MODE = 1, single-shot / power-down)."""
        self._continuous = False
        self._write_config()

    def is_converting(self):
        """Report whether a single-shot conversion is running (OS = 0).

        Only meaningful in single-shot mode; in continuous mode OS reads 1.

        Returns:
            bool: True while converting.
        """
        return not (self._read_reg(_REG_CONFIG) & _OS)

    def read_config(self):
        """Read the raw CONFIG register.

        Returns:
            int: 16-bit CONFIG value.
        """
        return self._read_reg(_REG_CONFIG)

    def reset(self):
        """Write the reset values back and clear the cached settings.

        Writes CONFIG = 0x8583, LO_THRESH = 0x8000, HI_THRESH = 0x7FFF. This is
        not an I²C general-call reset.
        """
        self._write_reg(_REG_CONFIG, _CONFIG_RESET)
        self._write_reg(_REG_LO_THRESH, _LO_RESET)
        self._write_reg(_REG_HI_THRESH, _HI_RESET)
        self._pga = PGA_2_048
        self._dr = DR_1600
        self._continuous = False
        self._comp_mode = COMP_TRADITIONAL
        self._comp_pol = ACTIVE_LOW
        self._comp_lat = False
        self._queue = QUEUE_1
        self._source = 0
        self._lo_raw = -2048
        self._hi_raw = 2047

    # --- Comparator -------------------------------------------------------

    def configure_comparator(self, mode=COMP_TRADITIONAL, polarity=ACTIVE_LOW,
                             latch=False, queue=QUEUE_1):
        """Configure the comparator and enable it as the ALERT/RDY source.

        Args:
            mode: COMP_TRADITIONAL (0) or COMP_WINDOW (1).
            polarity: ACTIVE_LOW (0) or ACTIVE_HIGH (1).
            latch: Latch the alert until CONVERSION is read.
            queue: QUEUE_1 (0), QUEUE_2 (1) or QUEUE_4 (2) consecutive
                out-of-range conversions before asserting.

        Raises:
            ValueError: If an argument is out of range.
        """
        if mode not in (COMP_TRADITIONAL, COMP_WINDOW):
            raise ValueError('mode must be COMP_TRADITIONAL or COMP_WINDOW')
        if polarity not in (ACTIVE_LOW, ACTIVE_HIGH):
            raise ValueError('polarity must be ACTIVE_LOW or ACTIVE_HIGH')
        if queue not in (QUEUE_1, QUEUE_2, QUEUE_4):
            raise ValueError('queue must be QUEUE_1, QUEUE_2 or QUEUE_4')
        self._comp_mode = mode
        self._comp_pol = polarity
        self._comp_lat = bool(latch)
        self._queue = queue
        self._source = SOURCE_COMPARATOR
        self._write_config()

    def disable_comparator(self):
        """Disable the comparator (COMP_QUE = 11); ALERT/RDY goes high-impedance."""
        self._source = 0
        self._write_config()

    def set_low_threshold_raw(self, raw):
        """Write the comparator low threshold.

        Args:
            raw: 12-bit two's-complement code, −2048…2047.

        Raises:
            ValueError: If raw is out of range.
        """
        self._write_threshold(_REG_LO_THRESH, raw)
        self._lo_raw = raw

    def set_high_threshold_raw(self, raw):
        """Write the comparator high threshold.

        Args:
            raw: 12-bit two's-complement code, −2048…2047.

        Raises:
            ValueError: If raw is out of range.
        """
        self._write_threshold(_REG_HI_THRESH, raw)
        self._hi_raw = raw

    def _write_threshold(self, reg, raw):
        if raw < -2048 or raw > 2047:
            raise ValueError('raw must be -2048..2047')
        self._write_reg(reg, (raw & 0x0FFF) << 4)

    def set_thresholds(self, low, high):
        """Write both comparator thresholds from volts.

        Converts with the current lsb() and clamps to the code range.

        Args:
            low: Low threshold in volts.
            high: High threshold in volts.

        Raises:
            ValueError: If high is not greater than low.
        """
        if high <= low:
            raise ValueError('high must be greater than low')
        step = self.lsb()
        self.set_low_threshold_raw(self._to_code(low, step))
        self.set_high_threshold_raw(self._to_code(high, step))

    @staticmethod
    def _to_code(volts, step):
        code = int(round(volts / step))
        if code < -2048:
            return -2048
        if code > 2047:
            return 2047
        return code

    def get_thresholds_raw(self):
        """Read both thresholds back.

        Returns:
            tuple: (low, high) as sign-extended 12-bit codes.
        """
        lo = to_signed(self._read_reg(_REG_LO_THRESH), 16) >> 4
        hi = to_signed(self._read_reg(_REG_HI_THRESH), 16) >> 4
        return (lo, hi)

    def clear_alert(self):
        """Release a latched comparator output by reading CONVERSION."""
        self._read_reg(_REG_CONVERSION)

    # --- Interrupt API ----------------------------------------------------

    def enable_interrupt(self, source):
        """Route one source to ALERT/RDY; the other is disabled.

        SOURCE_CONVERSION_READY writes HI_THRESH = 0x8000 and LO_THRESH =
        0x0000 and sets COMP_QUE = 00. SOURCE_COMPARATOR restores the
        thresholds last set through the driver and the configured queue.

        Args:
            source: SOURCE_CONVERSION_READY or SOURCE_COMPARATOR.

        Raises:
            ValueError: If source is unknown, or for SOURCE_COMPARATOR when the
                thresholds set through the driver have high ≤ low.
        """
        if source == SOURCE_CONVERSION_READY:
            self._write_reg(_REG_HI_THRESH, 0x8000)
            self._write_reg(_REG_LO_THRESH, 0x0000)
        elif source == SOURCE_COMPARATOR:
            if self._hi_raw <= self._lo_raw:
                raise ValueError('set thresholds (high > low) before enabling the comparator')
            self._write_reg(_REG_LO_THRESH, (self._lo_raw & 0x0FFF) << 4)
            self._write_reg(_REG_HI_THRESH, (self._hi_raw & 0x0FFF) << 4)
        else:
            raise ValueError('unknown interrupt source')
        self._source = source
        self._write_config()

    def disable_interrupt(self, source):
        """Release ALERT/RDY (COMP_QUE = 11).

        Args:
            source: SOURCE_CONVERSION_READY or SOURCE_COMPARATOR (the pin is
                released regardless — the two sources share it).
        """
        self._source = 0
        self._write_config()

    def poll_interrupt(self):
        """Report whether the enabled source is asserted.

        The chip has no interrupt-status register. With an INT pin on the
        connection, samples the ALERT/RDY level against COMP_POL and, for a
        latched comparator alert, reads CONVERSION to clear it. Without a pin,
        SOURCE_CONVERSION_READY falls back to CONFIG.OS (single-shot only) and
        SOURCE_COMPARATOR returns 0.

        Returns:
            int: The enabled SOURCE_* constant if asserted, else 0.
        """
        if self._source == 0:
            return 0
        pin = self._int_pin_used or getattr(self._connection, 'int_pin', None)
        if pin is not None:
            asserted = pin.is_high() == (self._comp_pol == ACTIVE_HIGH)
            if not asserted:
                return 0
            if self._source == SOURCE_COMPARATOR and self._comp_lat:
                self.clear_alert()
            return self._source
        if self._source == SOURCE_CONVERSION_READY and not self._continuous:
            if self._read_reg(_REG_CONFIG) & _OS:
                return SOURCE_CONVERSION_READY
        return 0

    def on_interrupt(self, callback, int_pin=None):
        """Subscribe to ALERT/RDY events.

        With a pin (int_pin, else connection.int_pin) the callback runs on
        every asserting edge (falling for ACTIVE_LOW, rising for ACTIVE_HIGH).
        Otherwise, on Linux, a 5 ms polling thread calls it whenever
        poll_interrupt() changes. Call enable_interrupt() first.

        Args:
            callback: Callable(status: int) — the poll_interrupt() value.
            int_pin: Optional InputPin for this call, overriding connection.int_pin.
        """
        self._callback = callback
        pin = int_pin if int_pin is not None else getattr(self._connection, 'int_pin', None)
        self._int_pin_used = pin
        if pin is not None:
            from periph.connection.input_pin import InputPin
            pin.on_edge(self._int_handler,
                        InputPin.RISING if self._comp_pol == ACTIVE_HIGH else InputPin.FALLING)
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
        if self._callback and status:
            self._callback(status)

    def _poll_loop(self):
        while not self._poll_stop:
            status = self.poll_interrupt()
            if status != self._last_status:
                self._last_status = status
                if self._callback:
                    self._callback(status)
            time.sleep(0.005)
