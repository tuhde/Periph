import time


class BH1750Minimal:
    """BH1750FVI ambient light sensor — minimal interface.

    Each read() starts one one-time H-resolution measurement (opcode 0x20, 1 lx
    steps), waits the datasheet maximum of 180 ms, reads the 16-bit result and
    returns the illuminance in lux (raw / 1.2). The chip powers down by itself
    after the measurement.

    The device is command driven (no register pointer), so a plain I²C
    connection is used. The constructor does not touch the bus; init() powers
    the chip on.

    Args:
        connection: Configured I²C connection pointing at the device (address 0x23 or 0x5C).
    """

    MODE_H = 0
    MODE_H2 = 1
    MODE_L = 2

    _OP_POWER_DOWN = 0x00
    _OP_POWER_ON = 0x01
    _OP_RESET = 0x07
    _OP_CONTINUOUS = (0x10, 0x11, 0x13)
    _OP_ONE_TIME = (0x20, 0x21, 0x23)
    _OP_MT_HIGH = 0x40
    _OP_MT_LOW = 0x60
    _DEFAULT_MTREG = 69
    # maximum measurement time in seconds at MTreg = 69, indexed by mode
    _T_MAX = (0.180, 0.180, 0.024)

    def __init__(self, connection):
        self._connection = connection
        self._mtreg = self._DEFAULT_MTREG
        self._mode = self.MODE_H

    def init(self):
        """Power the chip on (opcode 0x01)."""
        self._connection.write(bytes([self._OP_POWER_ON]))

    def _wait_time(self, mode):
        return self._T_MAX[mode] * self._mtreg / self._DEFAULT_MTREG

    def _to_lux(self, raw, mode):
        lux = raw / 1.2 * (self._DEFAULT_MTREG / self._mtreg)
        return lux / 2.0 if mode == self.MODE_H2 else lux

    def _read_word(self):
        data = bytes(self._connection.read(2))
        return (data[0] << 8) | data[1]

    def _measure_raw(self, mode):
        self._connection.write(bytes([self._OP_ONE_TIME[mode]]))
        time.sleep(self._wait_time(mode))
        return self._read_word()

    def read(self):
        """Measure the illuminance once.

        Returns:
            float: Illuminance in lux (one-time H-resolution measurement).
        """
        return self._to_lux(self._measure_raw(self._mode), self._mode)


class BH1750Full(BH1750Minimal):
    """BH1750FVI full interface — all measurement modes, continuous mode, sensitivity, power control.

    Extends BH1750Minimal with raw codes, one-time measurements in each mode,
    continuous measurement, power-down/power-on, reset, the measurement-time
    register (MTreg) and sensitivity adjustment. read() uses the mode of the
    last measure_once() or start_continuous() call (H-resolution by default).

    Args:
        connection: Configured I²C connection pointing at the device (address 0x23 or 0x5C).
    """

    def __init__(self, connection):
        super().__init__(connection)

    def read_raw(self):
        """Run one one-time measurement in the current mode and return the raw count.

        Returns:
            int: 16-bit unsigned raw count.
        """
        return self._measure_raw(self._mode)

    def measure_once(self, mode=BH1750Minimal.MODE_H):
        """Run one one-time measurement.

        Args:
            mode: MODE_H (1 lx), MODE_H2 (0.5 lx) or MODE_L (4 lx).

        Returns:
            float: Illuminance in lux.

        Raises:
            ValueError: If mode is not a valid mode constant.
        """
        self._check_mode(mode)
        self._mode = mode
        return self._to_lux(self._measure_raw(mode), mode)

    def start_continuous(self, mode=BH1750Minimal.MODE_H):
        """Start continuous measurements; the first result is ready after measurement_time(mode).

        Args:
            mode: MODE_H (1 lx), MODE_H2 (0.5 lx) or MODE_L (4 lx).

        Raises:
            ValueError: If mode is not a valid mode constant.
        """
        self._check_mode(mode)
        self._mode = mode
        self._connection.write(bytes([self._OP_CONTINUOUS[mode]]))

    def read_continuous(self):
        """Read the latest continuous-mode result without waiting.

        Returns:
            float: Illuminance in lux.
        """
        return self._to_lux(self._read_word(), self._mode)

    def power_down(self):
        """Enter power-down (opcode 0x00)."""
        self._connection.write(bytes([self._OP_POWER_DOWN]))

    def power_on(self):
        """Leave power-down and wait for a measurement command (opcode 0x01)."""
        self._connection.write(bytes([self._OP_POWER_ON]))

    def reset(self):
        """Clear the illuminance data register (opcode 0x07; powers on first, as reset is ignored in power-down)."""
        self._connection.write(bytes([self._OP_POWER_ON]))
        self._connection.write(bytes([self._OP_RESET]))

    def set_measurement_time(self, mtreg):
        """Write the measurement-time register (two opcodes, high bits then low bits).

        Args:
            mtreg: Register value, 31-254 (default 69).

        Raises:
            ValueError: If mtreg is outside 31-254.
        """
        if not 31 <= mtreg <= 254:
            raise ValueError('BH1750 MTreg must be 31-254')
        self._connection.write(bytes([self._OP_MT_HIGH | (mtreg >> 5)]))
        self._connection.write(bytes([self._OP_MT_LOW | (mtreg & 0x1F)]))
        self._mtreg = mtreg

    def get_measurement_time_register(self):
        """Return the last written MTreg (the chip has no read-back; 69 after init).

        Returns:
            int: MTreg value.
        """
        return self._mtreg

    def set_sensitivity(self, factor):
        """Set the sensitivity relative to the default (MTreg = round(69 * factor)).

        Args:
            factor: Sensitivity factor, 0.45-3.68.

        Raises:
            ValueError: If the resulting MTreg is outside 31-254.
        """
        self.set_measurement_time(int(round(self._DEFAULT_MTREG * factor)))

    def measurement_time(self, mode=BH1750Minimal.MODE_H):
        """Maximum measurement time for a mode at the current MTreg.

        Args:
            mode: MODE_H, MODE_H2 or MODE_L.

        Returns:
            float: Seconds.
        """
        self._check_mode(mode)
        return self._wait_time(mode)

    def lux_per_count(self, mode=BH1750Minimal.MODE_H):
        """Illuminance represented by one raw count at the current MTreg.

        Args:
            mode: MODE_H, MODE_H2 or MODE_L.

        Returns:
            float: Lux per count.
        """
        self._check_mode(mode)
        return self._to_lux(1, mode)

    def _check_mode(self, mode):
        if mode not in (self.MODE_H, self.MODE_H2, self.MODE_L):
            raise ValueError('BH1750 mode must be MODE_H, MODE_H2 or MODE_L')
