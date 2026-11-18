try:
    import threading as _threading
    _LINUX = True
except ImportError:
    _LINUX = False

import time


class MAX17048Minimal:
    """MAX17048 / MAX17049 lithium fuel gauge (ModelGauge) — minimal interface.

    Reports the cell voltage and the state of charge. The constructor makes
    no bus access; the chip runs with its power-on defaults (hibernate
    thresholds 0x8030, RCOMP 0x97, empty alert at 4 %, voltage alert window
    disabled, on-chip default battery model). The first SOC result is valid
    about 1 s after power-up.

    Args:
        connection: RegisterConnection (I²C, address 0x36).
        cells: 1 for the MAX17048 (default), 2 for the MAX17049; scales the voltage.
    """

    ADDRESS = 0x36

    _REG_VCELL = 0x02
    _REG_SOC = 0x04
    _REG_MODE = 0x06
    _REG_VERSION = 0x08
    _REG_HIBRT = 0x0A
    _REG_CONFIG = 0x0C
    _REG_VALRT = 0x14
    _REG_CRATE = 0x16
    _REG_VRESET = 0x18
    _REG_STATUS = 0x1A
    _REG_CMD = 0xFE

    def __init__(self, connection, cells=1):
        self._connection = connection
        self._cells = 2 if cells == 2 else 1

    def _read_word(self, reg):
        raw = self._connection.read_reg(reg, 2)
        return (raw[0] << 8) | raw[1]

    def _write_word(self, reg, value):
        self._connection.write_reg(reg, bytes([(value >> 8) & 0xFF, value & 0xFF]))

    def voltage(self):
        """Read the cell voltage.

        Returns:
            float: Voltage in volts ((VCELL >> 4) × 1.25 mV × cells).
        """
        return (self._read_word(self._REG_VCELL) >> 4) * 1.25e-3 * self._cells

    def percent(self):
        """Read the state of charge.

        Returns:
            float: State of charge in %, clamped to 0.0–100.0 (SOC / 256).
        """
        return max(0.0, min(100.0, self._read_word(self._REG_SOC) / 256.0))


class MAX17048Full(MAX17048Minimal):
    """MAX17048 / MAX17049 full interface — alerts, RCOMP, hibernate, sleep, reset.

    Interrupt source constants (callback / poll_interrupt / enable_interrupt):
        SOURCE_RESET        — STATUS.RI, the chip has reset
        SOURCE_OVERVOLTAGE  — STATUS.VH, VCELL above VALRT.MAX
        SOURCE_UNDERVOLTAGE — STATUS.VL, VCELL below VALRT.MIN
        SOURCE_VRESET       — STATUS.VR, voltage reset alert (needs enable_voltage_reset_alert)
        SOURCE_LOW_SOC      — STATUS.HD, SOC crossed the empty-alert threshold
        SOURCE_SOC_CHANGE   — STATUS.SC, SOC changed by at least 1 % (needs the SOC change alert)

    Args:
        connection: RegisterConnection (I²C, address 0x36).
        cells: 1 for the MAX17048 (default), 2 for the MAX17049.
    """

    SOURCE_RESET = 0x0100
    SOURCE_OVERVOLTAGE = 0x0200
    SOURCE_UNDERVOLTAGE = 0x0400
    SOURCE_VRESET = 0x0800
    SOURCE_LOW_SOC = 0x1000
    SOURCE_SOC_CHANGE = 0x2000

    _SOURCES = 0x3F00
    _CONFIG_ALRT = 0x0020
    _CONFIG_ALSC = 0x0040
    _CONFIG_SLEEP = 0x0080
    _STATUS_ENVR = 0x4000

    def __init__(self, connection, cells=1):
        self._callback = None
        self._int_source = None
        self._poll_stop = True
        self._poll_thread = None
        self._sleep_enabled = False
        super().__init__(connection, cells)

    def _modify(self, reg, clear, setbits):
        self._write_word(reg, (self._read_word(reg) & ~clear & 0xFFFF) | setbits)

    def raw_voltage(self):
        """Read the raw VCELL register.

        Returns:
            int: 16-bit VCELL value (12-bit result in bits 15:4).
        """
        return self._read_word(self._REG_VCELL)

    def raw_soc(self):
        """Read the raw SOC register.

        Returns:
            int: 16-bit SOC value (high byte = 1 %, low byte = 1/256 %).
        """
        return self._read_word(self._REG_SOC)

    def charge_rate(self):
        """Read the approximate charge/discharge rate.

        Returns:
            float: Rate in %/h (signed CRATE × 0.208); positive while charging.
        """
        raw = self._read_word(self._REG_CRATE)
        if raw & 0x8000:
            raw -= 0x10000
        return raw * 0.208

    def version(self):
        """Read the VERSION register.

        Returns:
            int: Production version, 0x001x.
        """
        return self._read_word(self._REG_VERSION)

    def chip_id(self):
        """Read the factory-programmed ID.

        Returns:
            int: Low byte of the VRESET/ID register.
        """
        return self._read_word(self._REG_VRESET) & 0xFF

    def quick_start(self):
        """Restart the SOC estimate from the present voltage (MODE.QuickStart).

        Only use when the first SOC after power-up is clearly wrong.
        """
        self._write_word(self._REG_MODE, 0x4000 | (0x2000 if self._sleep_enabled else 0))

    def reset(self):
        """Power-on reset (CMD ← 0x5400); tolerates the missing ACK and waits 1 s."""
        try:
            self._write_word(self._REG_CMD, 0x5400)
        except OSError:
            pass
        time.sleep(1.0)

    def set_rcomp(self, value):
        """Write the RCOMP compensation value (CONFIG bits 15:8, read-modify-write).

        Args:
            value: RCOMP 0–255 (default 0x97).
        """
        self._modify(self._REG_CONFIG, 0xFF00, (int(value) & 0xFF) << 8)

    def get_rcomp(self):
        """Read the RCOMP value.

        Returns:
            int: RCOMP 0–255.
        """
        return self._read_word(self._REG_CONFIG) >> 8

    def compensate_temperature(self, celsius):
        """Set RCOMP from the datasheet temperature formula with the default coefficients.

        RCOMP = 0x97 + (T − 20) × −0.5 for T ≥ 20 °C, 0x97 + (T − 20) × −5.0 below.

        Args:
            celsius: Cell temperature in °C.
        """
        coefficient = -0.5 if celsius >= 20.0 else -5.0
        rcomp = int(round(0x97 + (celsius - 20.0) * coefficient))
        self.set_rcomp(max(0, min(255, rcomp)))

    def set_alert_soc(self, percent):
        """Set the empty-alert threshold (CONFIG.ATHD = 32 − percent).

        Args:
            percent: Threshold in % (1–32, default 4).
        """
        percent = max(1, min(32, int(percent)))
        self._modify(self._REG_CONFIG, 0x001F, 32 - percent)

    def get_alert_soc(self):
        """Read the empty-alert threshold.

        Returns:
            int: Threshold in % (1–32).
        """
        return 32 - (self._read_word(self._REG_CONFIG) & 0x1F)

    def set_soc_change_alert(self, enabled):
        """Enable or disable the alert on a SOC change of at least 1 % (CONFIG.ALSC).

        Args:
            enabled: True to enable.
        """
        self._modify(self._REG_CONFIG, self._CONFIG_ALSC, self._CONFIG_ALSC if enabled else 0)

    def set_voltage_alert(self, min_v, max_v):
        """Set the voltage alert window in 20 mV steps (VALRT).

        Args:
            min_v: Under-voltage threshold in V; 0 disables it.
            max_v: Over-voltage threshold in V; 5.1 disables it.

        Raises:
            ValueError: If min_v > max_v.
        """
        if min_v > max_v:
            raise ValueError('min_v must not exceed max_v')
        low = max(0, min(255, int(round(min_v / 0.020))))
        high = max(0, min(255, int(round(max_v / 0.020))))
        self._write_word(self._REG_VALRT, (low << 8) | high)

    def get_voltage_alert(self):
        """Read the voltage alert window.

        Returns:
            tuple: (min_v, max_v) in volts.
        """
        raw = self._read_word(self._REG_VALRT)
        return ((raw >> 8) * 0.020, (raw & 0xFF) * 0.020)

    def set_reset_voltage(self, volts):
        """Set the reset voltage (VRESET bits 15:9, 40 mV steps, read-modify-write).

        Args:
            volts: Reset voltage in V (0.0–5.08).
        """
        steps = max(0, min(127, int(round(volts / 0.040))))
        self._modify(self._REG_VRESET, 0xFE00, steps << 9)

    def get_reset_voltage(self):
        """Read the reset voltage.

        Returns:
            float: Reset voltage in volts.
        """
        return (self._read_word(self._REG_VRESET) >> 9) * 0.040

    def enable_voltage_reset_alert(self, enabled):
        """Let a voltage-reset event assert ALRT (STATUS.ENVR).

        Args:
            enabled: True to enable.
        """
        self._modify(self._REG_STATUS, self._STATUS_ENVR, self._STATUS_ENVR if enabled else 0)

    def set_comparator_disabled(self, disabled):
        """Disable the analog comparator in hibernate mode (VRESET/ID.DIS, saves about 0.5 µA).

        Args:
            disabled: True to disable the comparator.
        """
        self._modify(self._REG_VRESET, 0x0100, 0x0100 if disabled else 0)

    def set_hibernate_thresholds(self, hib_thr_pct_per_h, act_thr_v):
        """Program the hibernate thresholds (HIBRT).

        Args:
            hib_thr_pct_per_h: Hibernate when |CRATE| stays below this rate, in %/h (0.208 %/h steps).
            act_thr_v: Leave hibernate when |OCV − VCELL| exceeds this, in V (1.25 mV steps).
        """
        hib = max(0, min(255, int(round(hib_thr_pct_per_h / 0.208))))
        act = max(0, min(255, int(round(act_thr_v / 0.00125))))
        self._write_word(self._REG_HIBRT, (hib << 8) | act)

    def get_hibernate_thresholds(self):
        """Read the hibernate thresholds.

        Returns:
            tuple: (hib_thr_pct_per_h, act_thr_v).
        """
        raw = self._read_word(self._REG_HIBRT)
        return ((raw >> 8) * 0.208, (raw & 0xFF) * 0.00125)

    def disable_hibernate(self):
        """Never hibernate (HIBRT ← 0x0000)."""
        self._write_word(self._REG_HIBRT, 0x0000)

    def force_hibernate(self):
        """Always stay in hibernate (HIBRT ← 0xFFFF)."""
        self._write_word(self._REG_HIBRT, 0xFFFF)

    def is_hibernating(self):
        """Read MODE.HibStat (the register is write-only; an unreadable value counts as False).

        Returns:
            bool: True if the chip reports hibernate mode.
        """
        try:
            return bool(self._read_word(self._REG_MODE) & 0x1000)
        except OSError:
            return False

    def enable_sleep(self, enabled):
        """Allow or forbid sleep mode (MODE.EnSleep; MODE is written whole, never read-modify-write).

        Args:
            enabled: True to allow sleep.
        """
        self._sleep_enabled = bool(enabled)
        self._write_word(self._REG_MODE, 0x2000 if enabled else 0x0000)

    def sleep(self):
        """Enter sleep mode (CONFIG.SLEEP = 1); needs enable_sleep(True) first."""
        self._modify(self._REG_CONFIG, 0, self._CONFIG_SLEEP)

    def wake(self):
        """Leave sleep mode (CONFIG.SLEEP = 0)."""
        self._modify(self._REG_CONFIG, self._CONFIG_SLEEP, 0)

    def read_status(self):
        """Read the STATUS register.

        Returns:
            int: Raw 16-bit STATUS value.
        """
        return self._read_word(self._REG_STATUS)

    def clear_status(self, mask):
        """Clear the given STATUS bits and the CONFIG.ALRT flag (releases the ALRT pin).

        Args:
            mask: STATUS bits to clear, e.g. SOURCE_RESET | SOURCE_LOW_SOC.
        """
        self._modify(self._REG_STATUS, mask & 0xFFFF, 0)
        self._modify(self._REG_CONFIG, self._CONFIG_ALRT, 0)

    def read_config(self):
        """Read the raw CONFIG register.

        Returns:
            int: 16-bit CONFIG value.
        """
        return self._read_word(self._REG_CONFIG)

    def write_config(self, value):
        """Write the raw CONFIG register.

        Args:
            value: 16-bit CONFIG value.
        """
        self._write_word(self._REG_CONFIG, value & 0xFFFF)

    def enable_interrupt(self, source):
        """Enable an alert source that has a control bit.

        SOURCE_SOC_CHANGE sets CONFIG.ALSC, SOURCE_VRESET sets STATUS.ENVR. The other
        sources are enabled by their thresholds (ATHD, VALRT) and need no call.

        Args:
            source: One of the SOURCE_* constants.
        """
        if source == self.SOURCE_SOC_CHANGE:
            self.set_soc_change_alert(True)
        elif source == self.SOURCE_VRESET:
            self.enable_voltage_reset_alert(True)

    def disable_interrupt(self, source):
        """Disable an alert source.

        Clears the control bit (SOURCE_SOC_CHANGE, SOURCE_VRESET) or moves the voltage
        threshold to its disabled value (SOURCE_UNDERVOLTAGE: MIN = 0, SOURCE_OVERVOLTAGE: MAX = 0xFF).

        Args:
            source: One of the SOURCE_* constants.
        """
        if source == self.SOURCE_SOC_CHANGE:
            self.set_soc_change_alert(False)
        elif source == self.SOURCE_VRESET:
            self.enable_voltage_reset_alert(False)
        elif source == self.SOURCE_UNDERVOLTAGE:
            self._modify(self._REG_VALRT, 0xFF00, 0)
        elif source == self.SOURCE_OVERVOLTAGE:
            self._modify(self._REG_VALRT, 0x00FF, 0x00FF)

    def on_interrupt(self, callback, int_pin=None):
        """Subscribe to ALRT (falling edge).

        ``callback(status)`` receives the STATUS word masked to the SOURCE_* bits.
        Delivery uses int_pin, else connection.int_pin, else a 1 s polling thread.

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
            time.sleep(1.0)

    def poll_interrupt(self):
        """Read STATUS, clear the reported flags and CONFIG.ALRT (releasing ALRT).

        Returns:
            int: STATUS word masked to the SOURCE_* bits (0 = no alert).
        """
        status = self._read_word(self._REG_STATUS)
        flags = status & self._SOURCES
        if flags:
            self.clear_status(flags)
        return flags
