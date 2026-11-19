import struct
import time


class AS7262Minimal:
    """AS7262 6-channel visible spectral sensor — minimal interface.

    The AS7262 (ams OSRAM) measures violet (450 nm), blue (500 nm), green
    (550 nm), yellow (570 nm), orange (600 nm) and red (650 nm). Every sensor
    register is a *virtual register* reached through three hardware registers
    (STATUS 0x00, WRITE 0x01, READ 0x02); this class implements the handshake.

    The constructor soft-resets the chip and configures the Minimal defaults:
    gain 16x, integration time 0x3B (165.2 ms), one-shot bank mode 3, both LED
    outputs off, INT pin disabled. read() runs one one-shot conversion.

    Args:
        connection: Register-capable I²C connection pointing at the device
            (address 0x49).

    Raises:
        OSError: If the chip does not answer on the bus.
        RuntimeError: If the chip does not boot or reset within the time-out.
    """

    ADDR = 0x49

    _HW_STATUS = 0x00
    _HW_WRITE = 0x01
    _HW_READ = 0x02
    _TX_VALID = 0x02
    _RX_VALID = 0x01

    _REG_HW_VERSION = 0x00
    _REG_FW_VERSION = 0x02
    _REG_CONTROL = 0x04
    _REG_INT_T = 0x05
    _REG_TEMP = 0x06
    _REG_LED = 0x07
    _REG_RAW = 0x08
    _REG_CAL = 0x14

    _CTRL_RST = 0x80
    _CTRL_INT = 0x40
    _CTRL_DATA_RDY = 0x02

    _BANK_ONE_SHOT = 3
    _INT_T_DEFAULT = 0x3B
    _GAIN_DEFAULT = 2
    _STEP_MS = 2.8
    _TIMEOUT_S = 1.0

    CHANNELS = ('violet', 'blue', 'green', 'yellow', 'orange', 'red')

    def __init__(self, connection):
        self._conn = connection
        self._wait_boot()
        self._reset()
        self.write_virtual(self._REG_INT_T, self._INT_T_DEFAULT)
        self.write_virtual(self._REG_LED, 0x00)
        self.write_virtual(self._REG_CONTROL, self._GAIN_DEFAULT << 4)

    # ---- virtual-register layer -------------------------------------------------

    def _status(self):
        return self._conn.read_reg(self._HW_STATUS, 1)[0]

    def _wait_status(self, mask, want, timeout_s=2.0):
        deadline = time.time() + timeout_s
        while True:
            if (self._status() & mask) == want:
                return
            if time.time() >= deadline:
                raise RuntimeError('AS7262 virtual-register handshake time-out')

    def read_virtual(self, reg):
        """Read one virtual register through the STATUS/WRITE/READ handshake.

        Args:
            reg: Virtual register address (0x00-0x2B).

        Returns:
            int: The register byte.

        Raises:
            RuntimeError: If the handshake times out.
        """
        self._wait_status(self._TX_VALID, 0)
        if self._status() & self._RX_VALID:
            self._conn.read_reg(self._HW_READ, 1)  # drain a stale byte
        self._conn.write_reg(self._HW_WRITE, reg & 0x7F)
        self._wait_status(self._RX_VALID, self._RX_VALID)
        return self._conn.read_reg(self._HW_READ, 1)[0]

    def write_virtual(self, reg, value):
        """Write one virtual register through the STATUS/WRITE handshake.

        Args:
            reg: Virtual register address (0x04, 0x05 or 0x07 are writable).
            value: Byte to write (0-255).

        Raises:
            RuntimeError: If the handshake times out.
        """
        self._wait_status(self._TX_VALID, 0)
        self._conn.write_reg(self._HW_WRITE, (reg & 0x7F) | 0x80)
        self._wait_status(self._TX_VALID, 0)
        self._conn.write_reg(self._HW_WRITE, value & 0xFF)

    def _wait_boot(self):
        deadline = time.time() + 2.0
        while True:
            try:
                if self.read_virtual(self._REG_HW_VERSION) == 0x40:
                    return
            except (OSError, RuntimeError):
                pass
            if time.time() >= deadline:
                raise RuntimeError('AS7262 not found (HW_VERSION != 0x40)')
            time.sleep(0.01)

    def _reset(self):
        self.write_virtual(self._REG_CONTROL, self._CTRL_RST)
        deadline = time.time() + self._TIMEOUT_S
        while True:
            time.sleep(0.01)
            try:
                if not self.read_virtual(self._REG_CONTROL) & self._CTRL_RST:
                    return
            except (OSError, RuntimeError):
                pass
            if time.time() >= deadline:
                raise RuntimeError('AS7262 reset time-out')

    # ---- conversions -----------------------------------------------------------------

    def _int_time_s(self):
        return self.read_virtual(self._REG_INT_T) * self._STEP_MS / 1000.0

    def _start_one_shot(self):
        ctrl = self.read_virtual(self._REG_CONTROL)
        self.write_virtual(self._REG_CONTROL, (ctrl & 0x70) | (self._BANK_ONE_SHOT << 2))

    def _wait_data(self):
        timeout_s = 2 * self._int_time_s() + self._TIMEOUT_S
        deadline = time.time() + timeout_s
        while True:
            if self.read_virtual(self._REG_CONTROL) & self._CTRL_DATA_RDY:
                return
            if time.time() >= deadline:
                raise RuntimeError('AS7262 conversion time-out')
            time.sleep(0.005)

    def _read_calibrated(self):
        data = bytes(self.read_virtual(self._REG_CAL + i) for i in range(24))
        values = struct.unpack('>6f', data)
        return dict(zip(self.CHANNELS, values))

    def read(self):
        """Run one one-shot conversion and return the six calibrated channels.

        Returns:
            dict: {'violet', 'blue', 'green', 'yellow', 'orange', 'red'} —
                calibrated values as floats (unit not published by the vendor).

        Raises:
            RuntimeError: If the conversion does not finish in time.
        """
        self._start_one_shot()
        self._wait_data()
        return self._read_calibrated()

    def temperature(self):
        """Read the device temperature.

        Returns:
            float: Temperature in degrees Celsius.
        """
        return float(self.read_virtual(self._REG_TEMP))


class AS7262Full(AS7262Minimal):
    """AS7262 full interface — raw counts, gain, integration time, bank modes, LEDs, interrupt.

    Extends AS7262Minimal.

    Args:
        connection: Register-capable I²C connection pointing at the device
            (address 0x49).
    """

    _GAINS = (1.0, 3.7, 16.0, 64.0)
    _DRV_CURRENTS = (12.5, 25.0, 50.0, 100.0)
    _IND_CURRENTS = (1.0, 2.0, 4.0, 8.0)

    def __init__(self, connection):
        super().__init__(connection)
        self._int_callback = None
        self._int_handler = None
        self._int_pin = None
        self._poll_stop = None

    # ---- conversions -----------------------------------------------------------------

    def _read_raw_regs(self):
        data = [self.read_virtual(self._REG_RAW + i) for i in range(12)]
        values = [(data[2 * i] << 8) | data[2 * i + 1] for i in range(6)]
        return dict(zip(self.CHANNELS, values))

    def read_raw(self):
        """Run one one-shot conversion and return the raw 16-bit counts.

        Returns:
            dict: {'violet', ..., 'red'} — raw counts (0-65535).
        """
        self._start_one_shot()
        self._wait_data()
        return self._read_raw_regs()

    def measure(self):
        """Start a one-shot conversion without waiting for it (BANK = 3)."""
        self._start_one_shot()

    def data_ready(self):
        """Return True if a conversion result is waiting (CONTROL_SETUP.DATA_RDY)."""
        return bool(self.read_virtual(self._REG_CONTROL) & self._CTRL_DATA_RDY)

    def read_calibrated(self):
        """Read the last calibrated results without starting a conversion.

        Returns:
            dict: {'violet', ..., 'red'} floats.
        """
        return self._read_calibrated()

    def read_raw_last(self):
        """Read the last raw counts without starting a conversion.

        Returns:
            dict: {'violet', ..., 'red'} ints.
        """
        return self._read_raw_regs()

    def read_channel(self, index, calibrated=True):
        """Read one channel of the last conversion.

        Args:
            index: Channel 0-5 (V, B, G, Y, O, R).
            calibrated: True for the calibrated float, False for the raw count.

        Returns:
            float or int: Calibrated value or raw count.

        Raises:
            ValueError: If index is outside 0-5.
        """
        if not 0 <= index <= 5:
            raise ValueError('channel index must be 0-5')
        if calibrated:
            base = self._REG_CAL + 4 * index
            data = bytes(self.read_virtual(base + i) for i in range(4))
            return struct.unpack('>f', data)[0]
        base = self._REG_RAW + 2 * index
        return (self.read_virtual(base) << 8) | self.read_virtual(base + 1)

    # ---- configuration ---------------------------------------------------------------

    def _update_control(self, mask, bits):
        ctrl = self.read_virtual(self._REG_CONTROL)
        self.write_virtual(self._REG_CONTROL, (ctrl & 0x7C & ~mask) | bits)

    def set_gain(self, gain):
        """Set the analog gain.

        Args:
            gain: 0-3 (index), or the gain 1, 3.7, 16 or 64 (1 means 1x).

        Raises:
            ValueError: If the gain is not valid.
        """
        if gain in self._GAINS:
            index = self._GAINS.index(gain)
        elif gain in (0, 2, 3):
            index = int(gain)
        else:
            raise ValueError('gain must be 0-3 or one of 1, 3.7, 16, 64')
        self._update_control(0x30, index << 4)

    def get_gain(self):
        """Return the analog gain as a multiplier (1, 3.7, 16 or 64)."""
        return self._GAINS[(self.read_virtual(self._REG_CONTROL) >> 4) & 0x03]

    def set_integration_time(self, ms):
        """Set the integration time per bank.

        Args:
            ms: Integration time in milliseconds, 2.8-714 (rounded to a multiple of 2.8 ms).

        Raises:
            ValueError: If ms is outside 2.8-714.
        """
        n = int(round(ms / self._STEP_MS))
        if n < 1 or n > 255:
            raise ValueError('integration time must be 2.8-714 ms')
        self.write_virtual(self._REG_INT_T, n)

    def get_integration_time(self):
        """Return the integration time per bank in milliseconds."""
        return self.read_virtual(self._REG_INT_T) * self._STEP_MS

    def set_mode(self, mode):
        """Set the bank mode.

        Args:
            mode: 0 (V,B,G,Y continuous), 1 (G,Y,O,R continuous), 2 (all six
                continuous) or 3 (all six, one-shot).

        Raises:
            ValueError: If mode is outside 0-3.
        """
        if not 0 <= mode <= 3:
            raise ValueError('mode must be 0-3')
        self._update_control(0x0C, mode << 2)

    def get_mode(self):
        """Return the bank mode (0-3)."""
        return (self.read_virtual(self._REG_CONTROL) >> 2) & 0x03

    def set_continuous(self, mode=2):
        """Switch to continuous conversions.

        Args:
            mode: Bank mode 0, 1 or 2.

        Raises:
            ValueError: If mode is not 0, 1 or 2.
        """
        if mode not in (0, 1, 2):
            raise ValueError('continuous mode must be 0, 1 or 2')
        self.set_mode(mode)

    def set_interrupt(self, enabled):
        """Enable or disable the INT pin output (CONTROL_SETUP.INT)."""
        self._update_control(self._CTRL_INT, self._CTRL_INT if enabled else 0)

    @staticmethod
    def _nearest(options, value):
        if value > options[-1]:
            raise ValueError('current above maximum %s mA' % options[-1])
        best = 0
        for i, option in enumerate(options):
            if abs(option - value) < abs(options[best] - value):
                best = i
        return best

    def set_led(self, on, current_ma=12.5):
        """Control the driver LED output.

        Args:
            on: True to enable the output.
            current_ma: Current limit; nearest of 12.5, 25, 50, 100 mA.

        Raises:
            ValueError: If current_ma is above 100.
        """
        index = self._nearest(self._DRV_CURRENTS, current_ma)
        led = self.read_virtual(self._REG_LED) & 0x07
        self.write_virtual(self._REG_LED, led | (index << 4) | (0x08 if on else 0))

    def set_indicator_led(self, on, current_ma=1):
        """Control the indicator LED output.

        Args:
            on: True to enable the output.
            current_ma: Current limit; nearest of 1, 2, 4, 8 mA.

        Raises:
            ValueError: If current_ma is above 8.
        """
        index = self._nearest(self._IND_CURRENTS, current_ma)
        led = self.read_virtual(self._REG_LED) & 0x38
        self.write_virtual(self._REG_LED, led | (index << 1) | (0x01 if on else 0))

    def soft_reset(self):
        """Soft-reset the chip (all registers return to their defaults)."""
        self._reset()

    def hardware_version(self):
        """Return (device_type, hardware_version) — expect (0x40, 0x3E)."""
        return (self.read_virtual(self._REG_HW_VERSION),
                self.read_virtual(self._REG_HW_VERSION + 1))

    def firmware_version(self):
        """Return the firmware version as (major, minor, sub)."""
        r2 = self.read_virtual(self._REG_FW_VERSION)
        r3 = self.read_virtual(self._REG_FW_VERSION + 1)
        return (r3 >> 4, ((r3 & 0x0F) << 2) | (r2 >> 6), r2 & 0x3F)

    def device_type(self):
        """Return the device type byte (virtual register 0x00, expect 0x40)."""
        return self.read_virtual(self._REG_HW_VERSION)

    # ---- interrupt -------------------------------------------------------------------

    def poll_interrupt(self):
        """Read CONTROL_SETUP (releases INT) and return DATA_RDY.

        Returns:
            int: 1 if data is ready, else 0.
        """
        return 1 if self.read_virtual(self._REG_CONTROL) & self._CTRL_DATA_RDY else 0

    def on_interrupt(self, callback, int_pin=None):
        """Enable the INT output and call ``callback(status)`` on data ready.

        Subscribes to the falling edge of the INT pin (``int_pin`` or
        ``connection.int_pin``); otherwise polls every integration time.

        Args:
            callback: Function called with 1 when data is ready.
            int_pin: Optional InputPin; defaults to ``connection.int_pin``.
        """
        self._int_callback = callback
        self.set_interrupt(True)
        pin = int_pin if int_pin is not None else getattr(self._conn, 'int_pin', None)
        if pin is not None and hasattr(pin, 'on_edge'):
            self._int_pin = pin
            self._int_handler = lambda p: self._dispatch_int()
            pin.on_edge(self._int_handler, 2)
        else:
            try:
                import _thread
            except ImportError:
                return
            stop = [False]
            self._poll_stop = stop
            period_s = max(self.get_integration_time() / 1000.0, 0.01)

            def _loop():
                while not stop[0]:
                    self._dispatch_int()
                    time.sleep(period_s)
            _thread.start_new_thread(_loop, ())

    def off_interrupt(self):
        """Unsubscribe from the INT pin (or stop the polling task) and disable the INT output."""
        self._int_callback = None
        if self._int_pin is not None and self._int_handler is not None and hasattr(self._int_pin, 'off_edge'):
            self._int_pin.off_edge(self._int_handler)
        self._int_pin = None
        self._int_handler = None
        if self._poll_stop is not None:
            self._poll_stop[0] = True
            self._poll_stop = None
        self.set_interrupt(False)

    def _dispatch_int(self):
        cb = self._int_callback
        if cb is None:
            return
        try:
            status = self.poll_interrupt()
            if status:
                cb(status)
        except Exception:
            pass
