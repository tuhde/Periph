try:
    import threading as _threading
    _LINUX = True
except ImportError:
    _LINUX = False

import time


def crc8_atm(data):
    """CRC-8 (ATM): polynomial 0x07, init 0x00, MSB first, no reflection, no final XOR.

    Args:
        data: Iterable of ints (bytes) to checksum.

    Returns:
        int: CRC byte.
    """
    crc = 0
    for b in data:
        crc ^= b
        for _ in range(8):
            crc = ((crc << 1) ^ 0x07) & 0xFF if crc & 0x80 else (crc << 1) & 0xFF
    return crc


class LC709203FMinimal:
    """LC709203F 1-cell Li-ion fuel gauge — minimal interface.

    Reports cell voltage, state of charge and temperature. The constructor
    runs the datasheet starting flow (operational mode, APA, battery profile,
    I²C temperature mode at 25 °C). Every transfer carries a CRC-8 that the
    driver appends on writes and verifies on reads (one retry on error).

    Args:
        connection: RegisterConnection (I²C, address 0x0B).
        capacity_mah: Design capacity of the battery in mAh (clamped to 100-3000
            for the APA interpolation).
        profile: Battery profile written to register 0x12 (0 or 1; 1 = 3.7 V / 4.2 V
            on -01/-03 parts).
    """

    ADDRESS = 0x0B

    _REG_BEFORE_RSOC = 0x04
    _REG_THERMISTOR_B = 0x06
    _REG_INITIAL_RSOC = 0x07
    _REG_TEMPERATURE = 0x08
    _REG_VOLTAGE = 0x09
    _REG_DIRECTION = 0x0A
    _REG_APA = 0x0B
    _REG_APT = 0x0C
    _REG_RSOC = 0x0D
    _REG_ITE = 0x0F
    _REG_IC_VERSION = 0x11
    _REG_PROFILE = 0x12
    _REG_ALARM_RSOC = 0x13
    _REG_ALARM_VOLTAGE = 0x14
    _REG_POWER_MODE = 0x15
    _REG_STATUS = 0x16
    _REG_PROFILE_ID = 0x1A

    _APA_TABLE = ((100, 0x08), (200, 0x0B), (500, 0x10), (1000, 0x19), (2000, 0x2D), (3000, 0x36))

    def __init__(self, connection, capacity_mah=1000, profile=1):
        self._connection = connection
        self._wake_and_operational()
        self._write_word(self._REG_APA, self._apa_for(capacity_mah))
        self._write_word(self._REG_PROFILE, profile & 1)
        time.sleep(0.1)  # TINIT: re-initialization like a battery insertion
        self._wake_and_operational()
        self._write_word(self._REG_STATUS, 0x0000)
        self.set_temperature(25.0)

    @classmethod
    def _apa_for(cls, capacity_mah):
        table = cls._APA_TABLE
        if capacity_mah <= table[0][0]:
            return table[0][1]
        if capacity_mah >= table[-1][0]:
            return table[-1][1]
        for i in range(1, len(table)):
            c0, a0 = table[i - 1]
            c1, a1 = table[i]
            if capacity_mah <= c1:
                return int(round(a0 + (a1 - a0) * (capacity_mah - c0) / (c1 - c0)))
        return table[-1][1]

    def _wake_and_operational(self):
        # Written twice: the first transfer may be lost while the chip wakes up.
        for _ in range(2):
            try:
                self._write_word(self._REG_POWER_MODE, 0x0001, retry=False)
            except OSError:
                pass
        time.sleep(0.001)

    def _read_word(self, cmd):
        last = None
        for _ in range(2):
            try:
                raw = self._connection.read_reg(cmd, 3)
                low, high, crc = raw[0], raw[1], raw[2]
                if crc8_atm((0x16, cmd, 0x17, low, high)) == crc:
                    return low | (high << 8)
                last = ValueError('LC709203F CRC mismatch on command 0x%02X' % cmd)
            except OSError as e:
                last = e
        raise last

    def _write_word(self, cmd, value, retry=True):
        low = value & 0xFF
        high = (value >> 8) & 0xFF
        data = bytes([low, high, crc8_atm((0x16, cmd, low, high))])
        try:
            self._connection.write_reg(cmd, data)
        except OSError:
            if not retry:
                raise
            self._connection.write_reg(cmd, data)

    def voltage(self):
        """Read the cell voltage.

        Returns:
            float: Cell voltage in volts (raw × 1 mV).
        """
        return self._read_word(self._REG_VOLTAGE) / 1000.0

    def percent(self):
        """Read the state of charge from the ITE register.

        Returns:
            float: State of charge in % (0.0-100.0, 0.1 % resolution).
        """
        return self._read_word(self._REG_ITE) / 10.0

    def temperature(self):
        """Read the cell temperature register.

        Returns:
            float: Cell temperature in °C.
        """
        return self._read_word(self._REG_TEMPERATURE) / 10.0 - 273.15

    def set_temperature(self, celsius):
        """Supply the cell temperature (I²C temperature mode).

        Update whenever the temperature changes by more than 1 °C.

        Args:
            celsius: Cell temperature in °C, clamped to -20...+60.
        """
        celsius = max(-20.0, min(60.0, celsius))
        self._write_word(self._REG_TEMPERATURE, int(round((celsius + 273.15) * 10)))


class LC709203FFull(LC709203FMinimal):
    """LC709203F full interface — RSOC control, thermistor mode, alarms, profiles, power mode.

    Alarm bitmask constants (callback / poll_interrupt):
        ALARM_RSOC    — RSOC below the Alarm Low RSOC threshold
        ALARM_VOLTAGE — cell voltage below the Alarm Low Cell Voltage threshold

    The chip has no status register; poll_interrupt() evaluates RSOC and
    voltage against the cached thresholds.

    Args:
        connection: RegisterConnection (I²C, address 0x0B).
        capacity_mah: Design capacity in mAh.
        profile: Battery profile (0 or 1).
    """

    DIRECTION_AUTO = 0x0000
    DIRECTION_CHARGE = 0x0001
    DIRECTION_DISCHARGE = 0xFFFF

    ALARM_RSOC = 0x01
    ALARM_VOLTAGE = 0x02

    def __init__(self, connection, capacity_mah=1000, profile=1):
        self._callback = None
        self._int_source = None
        self._poll_stop = True
        self._poll_thread = None
        super().__init__(connection, capacity_mah, profile)
        self._alarm_rsoc = self._read_word(self._REG_ALARM_RSOC)
        self._alarm_voltage = self._read_word(self._REG_ALARM_VOLTAGE)

    def rsoc(self):
        """Read the RSOC register.

        Returns:
            int: State of charge in % (0-100).
        """
        return self._read_word(self._REG_RSOC)

    def set_rsoc(self, percent):
        """Force the RSOC register (use sleep mode to keep the value).

        Args:
            percent: State of charge in % (0-100).
        """
        self._write_word(self._REG_RSOC, max(0, min(100, int(percent))))

    def init_rsoc(self):
        """Initialize RSOC from the present voltage (0x07 ← 0xAA55); waits 2 ms."""
        self._write_word(self._REG_INITIAL_RSOC, 0xAA55)
        time.sleep(0.002)

    def before_rsoc(self):
        """Initialize RSOC from the maximum voltage since reset (0x04 ← 0xAA55); waits 2 ms."""
        self._write_word(self._REG_BEFORE_RSOC, 0xAA55)
        time.sleep(0.002)

    def use_thermistor(self, b_constant=3380):
        """Switch to thermistor temperature mode.

        Args:
            b_constant: B constant of the thermistor in K.
        """
        self._write_word(self._REG_THERMISTOR_B, b_constant)
        self._write_word(self._REG_STATUS, 0x0001)

    def use_i2c_temperature(self, celsius=25.0):
        """Switch to I²C temperature mode and write a temperature.

        Args:
            celsius: Cell temperature in °C.
        """
        self._write_word(self._REG_STATUS, 0x0000)
        self.set_temperature(celsius)

    def is_thermistor_mode(self):
        """Return True if the temperature source is the thermistor."""
        return self._read_word(self._REG_STATUS) == 0x0001

    def get_b_constant(self):
        """Read the thermistor B constant (K)."""
        return self._read_word(self._REG_THERMISTOR_B)

    def set_apa(self, value):
        """Write the Adjustment Pack Application register (0-255)."""
        self._write_word(self._REG_APA, value & 0xFF)

    def get_apa(self):
        """Read the Adjustment Pack Application register."""
        return self._read_word(self._REG_APA)

    def set_apt(self, value):
        """Write the Adjustment Pack Thermistor register (0-65535)."""
        self._write_word(self._REG_APT, value & 0xFFFF)

    def get_apt(self):
        """Read the Adjustment Pack Thermistor register."""
        return self._read_word(self._REG_APT)

    def set_current_direction(self, mode):
        """Set the current direction.

        Args:
            mode: DIRECTION_AUTO, DIRECTION_CHARGE or DIRECTION_DISCHARGE.

        Raises:
            ValueError: For any other value.
        """
        if mode not in (self.DIRECTION_AUTO, self.DIRECTION_CHARGE, self.DIRECTION_DISCHARGE):
            raise ValueError('invalid current direction')
        self._write_word(self._REG_DIRECTION, mode)

    def get_current_direction(self):
        """Read the current direction register."""
        return self._read_word(self._REG_DIRECTION)

    def set_profile(self, profile):
        """Select battery profile 0 or 1 (re-initializes the chip; waits 90 ms).

        Args:
            profile: 0 or 1.
        """
        self._write_word(self._REG_PROFILE, profile & 1)
        time.sleep(0.1)
        self._wake_and_operational()

    def get_profile(self):
        """Read the selected battery profile (0 or 1)."""
        return self._read_word(self._REG_PROFILE)

    def profile_id(self):
        """Read the Number of the Parameter register (e.g. 0x0301)."""
        return self._read_word(self._REG_PROFILE_ID)

    def ic_version(self):
        """Read the IC Version register."""
        return self._read_word(self._REG_IC_VERSION)

    def set_alarm_rsoc(self, percent):
        """Set the low-RSOC alarm threshold.

        Args:
            percent: 0 disables, 1-100 %.
        """
        percent = max(0, min(100, int(percent)))
        self._write_word(self._REG_ALARM_RSOC, percent)
        self._alarm_rsoc = percent

    def get_alarm_rsoc(self):
        """Read the low-RSOC alarm threshold in %."""
        return self._read_word(self._REG_ALARM_RSOC)

    def set_alarm_voltage(self, volts):
        """Set the low-voltage alarm threshold.

        Args:
            volts: 0 disables; otherwise volts (resolution 1 mV).
        """
        raw = max(0, min(0xFFFF, int(round(volts * 1000))))
        self._write_word(self._REG_ALARM_VOLTAGE, raw)
        self._alarm_voltage = raw

    def get_alarm_voltage(self):
        """Read the low-voltage alarm threshold in volts."""
        return self._read_word(self._REG_ALARM_VOLTAGE) / 1000.0

    def sleep(self):
        """Enter sleep mode (only I²C stays alive; charge counting stops)."""
        self._write_word(self._REG_POWER_MODE, 0x0002)

    def wake(self):
        """Wake from sleep: dummy transaction (SDA pulse), wait 400 µs, enter operational mode."""
        self._wake_and_operational()

    def is_sleeping(self):
        """Return True if the chip is in sleep mode (wakes the bus first)."""
        try:
            self._connection.write_reg(self._REG_POWER_MODE, b'')
        except OSError:
            pass
        time.sleep(0.001)
        return self._read_word(self._REG_POWER_MODE) == 0x0002

    def on_interrupt(self, callback, int_pin=None):
        """Subscribe to ALARMB (falling edge).

        ``callback(status)`` receives the ALARM_RSOC / ALARM_VOLTAGE bitmask.
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
        """Evaluate RSOC and voltage against the cached alarm thresholds.

        Returns:
            int: Bitmask of ALARM_RSOC / ALARM_VOLTAGE (0 = no alarm).
        """
        status = 0
        if self._alarm_rsoc and self.rsoc() < self._alarm_rsoc:
            status |= self.ALARM_RSOC
        if self._alarm_voltage and self._read_word(self._REG_VOLTAGE) < self._alarm_voltage:
            status |= self.ALARM_VOLTAGE
        return status
