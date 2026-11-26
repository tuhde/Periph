import time


def _now():
    """Monotonic time in seconds (millisecond resolution on MicroPython)."""
    if hasattr(time, 'ticks_ms'):
        return time.ticks_ms() / 1000.0
    return time.time()


class CCS811Minimal:
    """CCS811 I²C indoor air quality gas sensor (ScioSense / ams) — minimal interface.

    One call returns the equivalent CO₂ (eCO₂) and total VOC (TVOC) values of the
    chip's internal algorithm.

    Default configuration (written by :meth:`init`):
        - application firmware started (``APP_START``) if the chip is in boot mode
        - MEAS_MODE = 0x10: drive mode 1 (constant power, one sample per second),
          interrupts off
        - no environment data (the chip assumes 50 %RH and 25 °C)

    The chip stretches the I²C clock; the bus must support it. Tie ``nWAKE`` low
    (or drive it low around every transaction). New sensors need a 48 h burn-in,
    and values settle about 20 min after each power-up.

    I²C address 0x5A (ADDR low, default) or 0x5B (ADDR high).

    Args:
        connection: RegisterConnection (I²C) pointing at the device.
    """

    _REG_STATUS          = 0x00
    _REG_MEAS_MODE       = 0x01
    _REG_ALG_RESULT_DATA = 0x02
    _REG_RAW_DATA        = 0x03
    _REG_ENV_DATA        = 0x05
    _REG_NTC             = 0x06
    _REG_THRESHOLDS      = 0x10
    _REG_BASELINE        = 0x11
    _REG_HW_ID           = 0x20
    _REG_HW_VERSION      = 0x21
    _REG_FW_BOOT_VERSION = 0x23
    _REG_FW_APP_VERSION  = 0x24
    _REG_ERROR_ID        = 0xE0
    _REG_APP_START       = 0xF4
    _REG_SW_RESET        = 0xFF

    _HW_ID = 0x81

    STATUS_FW_MODE    = 0x80
    STATUS_APP_VALID  = 0x10
    STATUS_DATA_READY = 0x08
    STATUS_ERROR      = 0x01

    _MODE_1 = 0x10
    # Sample interval in seconds per drive mode (0 = idle)
    _INTERVAL = (0, 1, 10, 60, 0.25)

    def __init__(self, connection):
        self._connection = connection
        self._meas_mode = self._MODE_1

    def init(self):
        """Verify the chip, start the application firmware and select drive mode 1.

        Raises:
            OSError: If HW_ID is not 0x81, no valid application is loaded, or the
                chip does not enter application mode.
        """
        hw_id = self._connection.read_reg(self._REG_HW_ID, 1)[0]
        if hw_id != self._HW_ID:
            raise OSError('Unexpected HW_ID 0x{:02X}, expected 0x{:02X}'.format(hw_id, self._HW_ID))
        self._start_app()
        self._meas_mode = self._MODE_1
        self._connection.write_reg(self._REG_MEAS_MODE, self._meas_mode)

    def _start_app(self):
        status = self._connection.read_reg(self._REG_STATUS, 1)[0]
        if not status & self.STATUS_APP_VALID:
            raise OSError('CCS811 has no valid application firmware')
        if status & self.STATUS_FW_MODE:
            return
        self._connection.write_reg(self._REG_APP_START, b'')
        time.sleep(0.002)
        status = self._connection.read_reg(self._REG_STATUS, 1)[0]
        if not status & self.STATUS_FW_MODE:
            raise OSError('CCS811 did not enter application mode')

    def _timeout(self):
        mode = (self._meas_mode >> 4) & 0x07
        interval = self._INTERVAL[mode] if mode < len(self._INTERVAL) else 0
        return max(1.5, interval * 1.5)

    def _wait_data_ready(self):
        deadline = _now() + self._timeout()
        while not self._connection.read_reg(self._REG_STATUS, 1)[0] & self.STATUS_DATA_READY:
            if _now() > deadline:
                raise OSError('CCS811 data ready timeout')
            time.sleep(0.02)

    def _read_result(self):
        b = self._connection.read_reg(self._REG_ALG_RESULT_DATA, 5)
        if b[4] & self.STATUS_ERROR:
            error_id = self._connection.read_reg(self._REG_ERROR_ID, 1)[0]
            raise OSError('CCS811 error, ERROR_ID 0x{:02X}'.format(error_id))
        return {
            'eco2_ppm': (b[0] << 8) | b[1],
            'tvoc_ppb': (b[2] << 8) | b[3],
        }

    def read(self):
        """Wait for the next sample and return it.

        Returns:
            dict: ``{eco2_ppm, tvoc_ppb}`` — equivalent CO₂ in ppm (400–8192) and
            total VOC in ppb (0–1187).

        Raises:
            OSError: If no sample arrives in time or the chip reports an error
                (the message carries ERROR_ID).
        """
        self._wait_data_ready()
        return self._read_result()


class CCS811Full(CCS811Minimal):
    """CCS811 full interface — extends CCS811Minimal with drive modes, raw sensor
    data, environment compensation, the NTC input, thresholds, baseline
    save/restore, versions, soft reset and the nINT interrupt.

    ``nINT`` is active low and open-drain (external pull-up required); it is
    released when ALG_RESULT_DATA is read.

    Args:
        connection: RegisterConnection (I²C) pointing at the device.
    """

    SOURCE_DATA_READY = 0x01
    SOURCE_THRESHOLD  = 0x02

    _INT_DATARDY = 0x08
    _INT_THRESH  = 0x04
    _RESET_KEY = b'\x11\xE5\x72\x8A'

    def __init__(self, connection):
        super().__init__(connection)
        self._int_callback = None
        self._int_handler = None
        self._int_src_pin = None
        self._poll_stop = None

    @classmethod
    def _int_bits(cls, source):
        bits = 0
        if source & cls.SOURCE_DATA_READY:
            bits |= cls._INT_DATARDY
        if source & cls.SOURCE_THRESHOLD:
            bits |= cls._INT_DATARDY | cls._INT_THRESH
        return bits

    def _write_meas_mode(self):
        self._connection.write_reg(self._REG_MEAS_MODE, self._meas_mode)

    def _read16(self, reg):
        b = self._connection.read_reg(reg, 2)
        return (b[0] << 8) | b[1]

    # -- measurement -----------------------------------------------------

    def read_eco2(self):
        """Wait for the next sample and return the equivalent CO₂.

        Returns:
            int: eCO₂ in ppm (400–8192).
        """
        return self.read()['eco2_ppm']

    def read_tvoc(self):
        """Wait for the next sample and return the total VOC.

        Returns:
            int: TVOC in ppb (0–1187).
        """
        return self.read()['tvoc_ppb']

    def read_raw(self):
        """Read the raw sensor current and ADC voltage (RAW_DATA).

        Returns:
            tuple: ``(current_ua, voltage_v)`` — sense current 0–63 µA and the
            voltage across the sensor in volts (1023 = 1.65 V).
        """
        raw = self._read16(self._REG_RAW_DATA)
        return raw >> 10, (raw & 0x3FF) * 1.65 / 1023

    def data_ready(self):
        """Return whether a new sample is waiting in ALG_RESULT_DATA.

        Returns:
            bool: STATUS.DATA_READY.
        """
        return bool(self.status() & self.STATUS_DATA_READY)

    # -- configuration ---------------------------------------------------

    def set_mode(self, mode):
        """Select the drive mode, keeping the interrupt bits.

        Mode 0 is idle, 1 samples every second, 2 every 10 s, 3 every 60 s and
        4 every 250 ms (raw data only, ALG_RESULT_DATA is not updated). When
        moving to a slower mode, the datasheet recommends idling (mode 0) first;
        the first sample at the new rate may be stale.

        Args:
            mode: Drive mode 0–4.

        Raises:
            ValueError: If mode is not 0–4.
        """
        if not 0 <= mode <= 4:
            raise ValueError('mode must be 0..4')
        self._meas_mode = (self._meas_mode & 0x0C) | (mode << 4)
        self._write_meas_mode()

    def set_environment(self, humidity_rh, temperature_c):
        """Write ambient humidity and temperature for the algorithm's compensation.

        Args:
            humidity_rh: Relative humidity in % (0–100).
            temperature_c: Temperature in °C (-25 to 102).

        Raises:
            ValueError: If a value is out of range.
        """
        if not 0 <= humidity_rh <= 100:
            raise ValueError('humidity_rh must be 0..100')
        if not -25 <= temperature_c <= 102:
            raise ValueError('temperature_c must be -25..102')
        h = int(round(humidity_rh * 512))
        t = int(round((temperature_c + 25) * 512))
        self._connection.write_reg(self._REG_ENV_DATA, bytes((h >> 8, h & 0xFF, t >> 8, t & 0xFF)))

    def read_ntc(self):
        """Read the voltages of the optional NTC circuit.

        Returns:
            tuple: ``(v_ref_mv, v_ntc_mv)`` — voltage across R_REF and R_NTC in mV.
        """
        b = self._connection.read_reg(self._REG_NTC, 4)
        return (b[0] << 8) | b[1], (b[2] << 8) | b[3]

    def ntc_resistance(self, r_ref_ohm):
        """Compute the NTC thermistor resistance from the NTC register.

        Args:
            r_ref_ohm: Value of the reference resistor in ohms.

        Returns:
            float: NTC resistance in ohms (convert with the thermistor's curve).

        Raises:
            OSError: If the reference voltage reads 0 (no NTC circuit).
        """
        v_ref, v_ntc = self.read_ntc()
        if v_ref == 0:
            raise OSError('CCS811 NTC reference voltage is 0')
        return v_ntc * r_ref_ohm / v_ref

    def set_thresholds(self, low_medium_ppm=1500, medium_high_ppm=2500, hysteresis_ppm=50):
        """Set the eCO₂ boundaries used by the threshold interrupt.

        Args:
            low_medium_ppm: Low-to-medium boundary in ppm.
            medium_high_ppm: Medium-to-high boundary in ppm.
            hysteresis_ppm: Hysteresis in ppm (0–255).

        Raises:
            ValueError: If a value is out of range or low ≥ high.
        """
        if not 0 <= low_medium_ppm < medium_high_ppm <= 0xFFFF:
            raise ValueError('thresholds must satisfy 0 <= low_medium < medium_high <= 65535')
        if not 0 <= hysteresis_ppm <= 0xFF:
            raise ValueError('hysteresis_ppm must be 0..255')
        self._connection.write_reg(self._REG_THRESHOLDS, bytes((
            low_medium_ppm >> 8, low_medium_ppm & 0xFF,
            medium_high_ppm >> 8, medium_high_ppm & 0xFF,
            hysteresis_ppm)))

    def get_baseline(self):
        """Read the encoded algorithm baseline (save it to non-volatile storage).

        Returns:
            int: 16-bit baseline value.
        """
        return self._read16(self._REG_BASELINE)

    def set_baseline(self, baseline):
        """Restore a saved baseline.

        Only write it after the sensor has run for about 20 minutes.

        Args:
            baseline: 16-bit value from :meth:`get_baseline`.

        Raises:
            ValueError: If baseline is not 0–65535.
        """
        if not 0 <= baseline <= 0xFFFF:
            raise ValueError('baseline must be 0..65535')
        self._connection.write_reg(self._REG_BASELINE, bytes((baseline >> 8, baseline & 0xFF)))

    # -- status and identity ---------------------------------------------

    def status(self):
        """Read the STATUS register.

        Returns:
            int: STATUS byte (see the STATUS_* constants).
        """
        return self._connection.read_reg(self._REG_STATUS, 1)[0]

    def error(self):
        """Read ERROR_ID (bit 0 write-invalid … bit 5 heater supply).

        Returns:
            int: ERROR_ID byte.
        """
        return self._connection.read_reg(self._REG_ERROR_ID, 1)[0]

    def hardware_version(self):
        """Read the hardware ID and version.

        Returns:
            tuple: ``(hw_id, hw_version)`` — 0x81 and 0x1X.
        """
        return (self._connection.read_reg(self._REG_HW_ID, 1)[0],
                self._connection.read_reg(self._REG_HW_VERSION, 1)[0])

    def _version(self, reg):
        b = self._connection.read_reg(reg, 2)
        return b[0] >> 4, b[0] & 0x0F, b[1]

    def firmware_version(self):
        """Read the bootloader and application firmware versions.

        Returns:
            dict: ``{boot: (major, minor, trivial), app: (major, minor, trivial)}``.
        """
        return {
            'boot': self._version(self._REG_FW_BOOT_VERSION),
            'app': self._version(self._REG_FW_APP_VERSION),
        }

    def soft_reset(self):
        """Reset the chip (SW_RESET), then start the application and mode 1 again.

        Drive mode, interrupt bits, thresholds and environment data return to
        their defaults.
        """
        try:
            self._connection.write_reg(self._REG_SW_RESET, self._RESET_KEY)
        except OSError:
            pass  # the chip may not acknowledge the last byte while resetting
        time.sleep(0.02)
        self.init()

    # -- interrupt -------------------------------------------------------

    def enable_interrupt(self, source):
        """Enable nINT for a source, keeping the drive mode.

        Args:
            source: SOURCE_DATA_READY and/or SOURCE_THRESHOLD (which also sets
                INT_DATARDY, as the chip requires).
        """
        self._meas_mode |= self._int_bits(source)
        self._write_meas_mode()

    def disable_interrupt(self, source):
        """Disable nINT for a source, keeping the drive mode.

        Args:
            source: SOURCE_DATA_READY and/or SOURCE_THRESHOLD.
        """
        self._meas_mode &= ~self._int_bits(source) & 0xFF
        self._write_meas_mode()

    def poll_interrupt(self):
        """Read STATUS. Does not release nINT (reading ALG_RESULT_DATA does).

        Returns:
            int: STATUS byte; STATUS_DATA_READY marks a new sample.
        """
        return self.status()

    def _dispatch_int(self):
        callback = self._int_callback
        if callback is None:
            return
        status = self.poll_interrupt()
        if status & self.STATUS_DATA_READY:
            callback(status)

    def on_interrupt(self, callback, int_pin=None):
        """Subscribe to the interrupt.

        Uses ``int_pin`` (or the connection's ``int_pin``) on its falling edge when
        present; otherwise a 20 ms polling thread on STATUS.DATA_READY. The
        callback should read the sample (:meth:`read`) to release nINT.

        Args:
            callback: Function called with the STATUS byte.
            int_pin: Optional InputPin wired to nINT.
        """
        self._int_callback = callback
        ipin = int_pin if int_pin is not None else getattr(self._connection, 'int_pin', None)
        if ipin is not None and hasattr(ipin, 'on_edge'):
            self._int_handler = lambda p: self._dispatch_int()
            ipin.on_edge(self._int_handler, getattr(ipin, 'FALLING', 2))
            self._int_src_pin = ipin
        else:
            self._int_src_pin = None
            self._start_polling()

    def _start_polling(self):
        try:
            import _thread
        except ImportError:
            return
        stop = [False]
        self._poll_stop = stop

        def _loop():
            while not stop[0]:
                self._dispatch_int()
                time.sleep(0.02)

        _thread.start_new_thread(_loop, ())

    def off_interrupt(self):
        """Unsubscribe from the interrupt."""
        self._int_callback = None
        ipin = self._int_src_pin
        handler = self._int_handler
        self._int_handler = None
        self._int_src_pin = None
        if ipin is not None and handler is not None and hasattr(ipin, 'off_edge'):
            ipin.off_edge(handler)
        if self._poll_stop is not None:
            self._poll_stop[0] = True
            self._poll_stop = None
