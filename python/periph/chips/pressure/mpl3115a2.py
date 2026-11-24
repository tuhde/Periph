import time

from periph.connection.register import to_signed


def _now():
    """Monotonic time in seconds (millisecond resolution on MicroPython)."""
    if hasattr(time, 'ticks_ms'):
        return time.ticks_ms() / 1000.0
    return time.time()


class MPL3115A2Minimal:
    """MPL3115A2 I²C barometric pressure / altitude / temperature sensor — minimal interface.

    One-shot barometer readings with no configuration beyond the connection.
    I²C address is fixed at 0x60.

    Default configuration (written by :meth:`init`):
        - barometer mode (ALT = 0)
        - oversample ratio 128 (OS = 7, about 512 ms per sample)
        - data-ready event flags enabled (PT_DATA_CFG = 0x07)
        - STANDBY between readings (one-shot acquisition)
        - no interrupts, FIFO off, offsets 0

    Args:
        connection: RegisterConnection (I²C) pointing at the device.
    """

    _REG_STATUS      = 0x00
    _REG_OUT_P_MSB   = 0x01
    _REG_DR_STATUS   = 0x06
    _REG_WHO_AM_I    = 0x0C
    _REG_F_STATUS    = 0x0D
    _REG_F_DATA      = 0x0E
    _REG_F_SETUP     = 0x0F
    _REG_SYSMOD      = 0x11
    _REG_INT_SOURCE  = 0x12
    _REG_PT_DATA_CFG = 0x13
    _REG_BAR_IN_MSB  = 0x14
    _REG_P_TGT_MSB   = 0x16
    _REG_T_TGT       = 0x18
    _REG_P_WND_MSB   = 0x19
    _REG_T_WND       = 0x1B
    _REG_P_MIN       = 0x1C
    _REG_T_MIN       = 0x1F
    _REG_P_MAX       = 0x21
    _REG_T_MAX       = 0x24
    _REG_CTRL_REG1   = 0x26
    _REG_CTRL_REG2   = 0x27
    _REG_CTRL_REG3   = 0x28
    _REG_CTRL_REG4   = 0x29
    _REG_CTRL_REG5   = 0x2A
    _REG_OFF_P       = 0x2B
    _REG_OFF_T       = 0x2C
    _REG_OFF_H       = 0x2D

    _CHIP_ID         = 0xC4

    _CTRL1_ALT       = 0x80
    _CTRL1_RST       = 0x04
    _CTRL1_OST       = 0x02
    _CTRL1_SBYB      = 0x01
    _DR_PTDR         = 0x08
    _PT_DATA_CFG_ALL = 0x07

    # Minimum time between samples (s) for OS = 0..7
    _OSR_TIME = (0.006, 0.010, 0.018, 0.034, 0.066, 0.130, 0.258, 0.512)

    def __init__(self, connection):
        self._connection = connection
        self._os = 7
        self._alt = False

    def _write_reg(self, reg, value):
        self._connection.write_reg(reg, value)

    def _read_reg(self, reg, n=1):
        return self._connection.read_reg(reg, n)

    def init(self):
        """Reset the chip and apply the default configuration.

        Software reset (RST), then PT_DATA_CFG = 0x07 (data-ready flags — without
        them the PTDR flag never sets), then barometer mode with OSR 128 in
        STANDBY.

        Raises:
            OSError: If the WHO_AM_I register is not 0xC4.
        """
        chip_id = self._read_reg(self._REG_WHO_AM_I)[0]
        if chip_id != self._CHIP_ID:
            raise OSError('Unexpected chip ID 0x{:02X}, expected 0x{:02X}'.format(
                chip_id, self._CHIP_ID))
        self._soft_reset()
        self._os = 7
        self._alt = False
        self._apply_config()

    def _soft_reset(self):
        try:
            self._write_reg(self._REG_CTRL_REG1, self._CTRL1_RST)
        except OSError:
            pass  # the chip may not acknowledge while rebooting
        for _ in range(100):
            time.sleep(0.002)
            try:
                if not (self._read_reg(self._REG_CTRL_REG1)[0] & self._CTRL1_RST):
                    return
            except OSError:
                pass
        raise OSError('MPL3115A2 reset timeout')

    def _apply_config(self):
        self._write_reg(self._REG_PT_DATA_CFG, self._PT_DATA_CFG_ALL)
        self._write_reg(self._REG_CTRL_REG1,
                        (self._CTRL1_ALT if self._alt else 0) | (self._os << 3))

    def _one_shot(self):
        """Trigger one conversion in STANDBY and return the 5 data bytes."""
        timeout = self._OSR_TIME[self._os] * 2 + 0.05
        ctrl1 = (self._CTRL1_ALT if self._alt else 0) | (self._os << 3)
        self._write_reg(self._REG_CTRL_REG1, ctrl1)  # OST must be 0 before it is set
        self._write_reg(self._REG_CTRL_REG1, ctrl1 | self._CTRL1_OST)
        deadline = _now() + timeout
        while True:
            time.sleep(0.003)
            if self._read_reg(self._REG_DR_STATUS)[0] & self._DR_PTDR:
                break
            if _now() > deadline:
                raise OSError('MPL3115A2 data not ready')
        return self._read_reg(self._REG_OUT_P_MSB, 5)

    @staticmethod
    def _pressure_from(raw):
        return ((raw[0] << 12) | (raw[1] << 4) | (raw[2] >> 4)) / 4.0

    @staticmethod
    def _altitude_from(raw):
        return to_signed((raw[0] << 8) | raw[1], 16) + (raw[2] >> 4) / 16.0

    @staticmethod
    def _temperature_from(raw):
        return to_signed(raw[0], 8) + (raw[1] >> 4) / 16.0

    def read_pressure(self):
        """Run one barometer conversion and return the pressure.

        Returns:
            float: Absolute pressure in pascals.
        """
        return self.read()['pressure_pa']

    def read_temperature(self):
        """Run one conversion and return the temperature.

        Returns:
            float: Temperature in degrees Celsius.
        """
        return self.read()['temperature_c']

    def read(self):
        """Run one conversion and return pressure and temperature together.

        Returns:
            dict: ``{pressure_pa, temperature_c}`` — pascals and degrees Celsius.
        """
        raw = self._one_shot()
        return {
            'pressure_pa': self._pressure_from(raw[0:3]),
            'temperature_c': self._temperature_from(raw[3:5]),
        }


class MPL3115A2Full(MPL3115A2Minimal):
    """MPL3115A2 full interface — extends MPL3115A2Minimal with altimeter mode,
    oversampling, continuous acquisition, offsets, sea-level pressure, targets
    and windows, min/max capture, the 32-sample FIFO and interrupts.

    Args:
        connection: RegisterConnection (I²C) pointing at the device.
    """

    SOURCE_DRDY = 0x80
    SOURCE_FIFO = 0x40
    SOURCE_PW   = 0x20
    SOURCE_TW   = 0x10
    SOURCE_PTH  = 0x08
    SOURCE_TTH  = 0x04
    SOURCE_PCHG = 0x02
    SOURCE_TCHG = 0x01

    FIFO_OFF      = 0
    FIFO_CIRCULAR = 1
    FIFO_STOP     = 2

    def __init__(self, connection):
        super().__init__(connection)
        self._int_callback = None
        self._int_handler = None
        self._int_pin_no = 1
        self._poll_stop = None
        self._fifo_mode = 0

    # -- helpers ---------------------------------------------------------

    def _ctrl1(self):
        return (self._CTRL1_ALT if self._alt else 0) | (self._os << 3)

    def _standby(self):
        """Drop to STANDBY; returns True if the chip had been ACTIVE."""
        c1 = self._read_reg(self._REG_CTRL_REG1)[0]
        was_active = bool(c1 & self._CTRL1_SBYB)
        if was_active:
            self._write_reg(self._REG_CTRL_REG1, c1 & ~self._CTRL1_SBYB & 0xFF)
        return was_active

    def _restore(self, was_active):
        if was_active:
            self._write_reg(self._REG_CTRL_REG1, self._ctrl1() | self._CTRL1_SBYB)

    def _wait_ptdr(self):
        deadline = _now() + self._OSR_TIME[self._os] * 2 + 1.0
        while not (self._read_reg(self._REG_DR_STATUS)[0] & self._DR_PTDR):
            if _now() > deadline:
                raise OSError('MPL3115A2 data not ready')
            time.sleep(0.003)

    # -- measurement -----------------------------------------------------

    def set_mode(self, altimeter):
        """Select barometer or altimeter mode (the chip is dropped to STANDBY and restored).

        Args:
            altimeter: True for altimeter (metres), False for barometer (pascals).
        """
        was = self._standby()
        self._alt = bool(altimeter)
        self._write_reg(self._REG_CTRL_REG1, self._ctrl1())
        self._restore(was)

    def read_altitude(self):
        """Switch to altimeter mode if needed and run one conversion.

        Returns:
            float: Altitude in metres relative to the sea-level pressure (BAR_IN).
        """
        if not self._alt:
            self.set_mode(True)
        raw = self._one_shot()
        return self._altitude_from(raw[0:3])

    def read_pressure(self):
        if self._alt:
            self.set_mode(False)
        return super().read_pressure()

    def read_temperature(self):
        return super().read_temperature()

    def read(self):
        if self._alt:
            self.set_mode(False)
        return super().read()

    def set_oversample(self, ratio):
        """Set the oversample ratio.

        Args:
            ratio: 1, 2, 4, 8, 16, 32, 64 or 128.

        Raises:
            ValueError: For any other value.
        """
        if ratio not in (1, 2, 4, 8, 16, 32, 64, 128):
            raise ValueError('ratio must be 1, 2, 4, ..., 128')
        was = self._standby()
        self._os = ratio.bit_length() - 1
        self._write_reg(self._REG_CTRL_REG1, self._ctrl1())
        self._restore(was)

    def get_oversample(self):
        """Return the current oversample ratio (1..128)."""
        return 1 << self._os

    def start_continuous(self, step_exp=0):
        """Start periodic acquisition (ACTIVE mode).

        Args:
            step_exp: Auto-acquisition step exponent 0-15 (2**n seconds).

        Raises:
            ValueError: If step_exp is outside 0-15.
        """
        if not 0 <= step_exp <= 15:
            raise ValueError('step_exp must be 0-15')
        was = self._standby()
        c2 = self._read_reg(self._REG_CTRL_REG2)[0]
        self._write_reg(self._REG_CTRL_REG2, (c2 & 0xF0) | step_exp)
        self._write_reg(self._REG_CTRL_REG1, self._ctrl1() | self._CTRL1_SBYB)

    def stop_continuous(self):
        """Stop periodic acquisition (back to STANDBY)."""
        self._write_reg(self._REG_CTRL_REG1, self._ctrl1())

    def data_ready(self):
        """Return True if new pressure/altitude or temperature data is ready (PTDR)."""
        return bool(self._read_reg(self._REG_DR_STATUS)[0] & self._DR_PTDR)

    def read_continuous(self):
        """Wait for PTDR and read the latest sample.

        Returns:
            dict: ``{value, temperature_c}`` — value is pascals (barometer) or
            metres (altimeter).
        """
        self._wait_ptdr()
        raw = self._read_reg(self._REG_OUT_P_MSB, 5)
        value = self._altitude_from(raw[0:3]) if self._alt else self._pressure_from(raw[0:3])
        return {'value': value, 'temperature_c': self._temperature_from(raw[3:5])}

    def read_delta(self):
        """Read the change since the previous sample.

        Returns:
            tuple: ``(delta_value, delta_temperature_c)`` — pascals or metres, and degrees Celsius.
        """
        raw = self._read_reg(0x07, 5)
        if self._alt:
            dv = self._altitude_from(raw[0:3])
        else:
            dv = self._pressure_from(raw[0:3])
            if dv >= 262144.0:
                dv -= 524288.0
        return dv, self._temperature_from(raw[3:5])

    # -- configuration ---------------------------------------------------

    def set_sea_level_pressure(self, pa):
        """Write BAR_IN (altitude reference).

        Args:
            pa: Sea-level pressure in pascals (2 Pa resolution, default 101326).
        """
        raw = int(round(pa / 2.0)) & 0xFFFF
        self._write_reg(self._REG_BAR_IN_MSB, bytes([raw >> 8, raw & 0xFF]))

    @staticmethod
    def _s8(value):
        return int(value) & 0xFF

    def set_offsets(self, pressure_pa=0.0, temperature_c=0.0, altitude_m=0):
        """Write the user offset trim registers.

        Args:
            pressure_pa: Pressure offset in pascals (-512..+508, 4 Pa steps).
            temperature_c: Temperature offset in °C (-8..+7.9375, 0.0625 steps).
            altitude_m: Altitude offset in metres (-128..+127).

        Raises:
            ValueError: If a value is outside its register range.
        """
        op = int(round(pressure_pa / 4.0))
        ot = int(round(temperature_c / 0.0625))
        oh = int(altitude_m)
        if not (-128 <= op <= 127 and -128 <= ot <= 127 and -128 <= oh <= 127):
            raise ValueError('offset out of range')
        self._write_reg(self._REG_OFF_P, op & 0xFF)
        self._write_reg(self._REG_OFF_T, ot & 0xFF)
        self._write_reg(self._REG_OFF_H, oh & 0xFF)

    def set_target(self, value, window, temperature_c=0, temperature_window=0):
        """Program the threshold/window targets.

        Args:
            value: Pressure target in pascals (2 Pa units) or altitude in metres (signed), per mode.
            window: Pressure window in pascals or altitude window in metres.
            temperature_c: Temperature target in °C (signed integer).
            temperature_window: Temperature window in °C (unsigned integer).
        """
        if self._alt:
            tgt = int(round(value)) & 0xFFFF
            wnd = int(round(window)) & 0xFFFF
        else:
            tgt = int(round(value / 2.0)) & 0xFFFF
            wnd = int(round(window / 2.0)) & 0xFFFF
        was = self._standby()
        self._write_reg(self._REG_P_TGT_MSB, bytes([tgt >> 8, tgt & 0xFF]))
        self._write_reg(self._REG_T_TGT, int(temperature_c) & 0xFF)
        self._write_reg(self._REG_P_WND_MSB, bytes([wnd >> 8, wnd & 0xFF]))
        self._write_reg(self._REG_T_WND, int(temperature_window) & 0xFF)
        self._restore(was)

    def load_current_as_target(self):
        """Use the current output as the threshold target (ALARM_SEL = 1, pulse LOAD_OUTPUT)."""
        c2 = self._read_reg(self._REG_CTRL_REG2)[0]
        self._write_reg(self._REG_CTRL_REG2, (c2 | 0x10) & ~0x20 & 0xFF)
        self._write_reg(self._REG_CTRL_REG2, c2 | 0x30)

    def read_min_max(self):
        """Read the captured extremes.

        Returns:
            dict: ``{p_min, p_max, t_min, t_max}`` — pressure in pascals (or altitude in
            metres in altimeter mode) and temperature in °C.
        """
        pm = self._read_reg(self._REG_P_MIN, 3)
        px = self._read_reg(self._REG_P_MAX, 3)
        tm = self._read_reg(self._REG_T_MIN, 2)
        tx = self._read_reg(self._REG_T_MAX, 2)
        conv = self._altitude_from if self._alt else self._pressure_from
        return {
            'p_min': conv(pm), 'p_max': conv(px),
            't_min': self._temperature_from(tm), 't_max': self._temperature_from(tx),
        }

    def clear_min_max(self):
        """Clear the min/max registers (write zero)."""
        self._write_reg(self._REG_P_MIN, bytes(3))
        self._write_reg(self._REG_T_MIN, bytes(2))
        self._write_reg(self._REG_P_MAX, bytes(3))
        self._write_reg(self._REG_T_MAX, bytes(2))

    # -- FIFO ------------------------------------------------------------

    def fifo_setup(self, mode, watermark=0):
        """Configure the FIFO.

        Args:
            mode: 0 off, 1 circular, 2 stop when full.
            watermark: Watermark sample count 0-63 (0 disables the flag).

        Raises:
            ValueError: For an invalid mode, or switching between 1 and 2 without disabling first.
        """
        if mode not in (0, 1, 2):
            raise ValueError('mode must be 0, 1 or 2')
        if self._fifo_mode in (1, 2) and mode in (1, 2) and mode != self._fifo_mode:
            raise ValueError('disable the FIFO before switching between circular and stop modes')
        was = self._standby()
        self._write_reg(self._REG_F_SETUP, (mode << 6) | (watermark & 0x3F))
        self._fifo_mode = mode
        self._restore(was)

    def fifo_status(self):
        """Return ``(overflow, watermark, count)`` from F_STATUS."""
        s = self._read_reg(self._REG_F_STATUS)[0]
        return bool(s & 0x80), bool(s & 0x40), s & 0x3F

    def fifo_read(self):
        """Drain the FIFO.

        Returns:
            list: ``(value, temperature_c)`` tuples (value in pascals or metres per mode).
        """
        count = self.fifo_status()[2]
        out = []
        for _ in range(count):
            raw = self._read_reg(self._REG_F_DATA, 5)
            value = self._altitude_from(raw[0:3]) if self._alt else self._pressure_from(raw[0:3])
            out.append((value, self._temperature_from(raw[3:5])))
        return out

    # -- misc ------------------------------------------------------------

    def reset(self):
        """Software reset, then restore the default configuration (as :meth:`init`)."""
        self._soft_reset()
        self._os = 7
        self._alt = False
        self._fifo_mode = 0
        self._apply_config()

    def who_am_i(self):
        """Return the WHO_AM_I register (0xC4)."""
        return self._read_reg(self._REG_WHO_AM_I)[0]

    def is_active(self):
        """Return True if SYSMOD reports ACTIVE mode."""
        return bool(self._read_reg(self._REG_SYSMOD)[0] & 0x01)

    # -- interrupts ------------------------------------------------------

    def set_interrupt_pins(self, polarity1=False, open_drain1=False,
                           polarity2=False, open_drain2=False):
        """Write CTRL_REG3.

        Args:
            polarity1: True for INT1 active high.
            open_drain1: True for INT1 open-drain.
            polarity2: True for INT2 active high.
            open_drain2: True for INT2 open-drain.
        """
        was = self._standby()
        self._write_reg(self._REG_CTRL_REG3,
                        (0x20 if polarity1 else 0) | (0x10 if open_drain1 else 0)
                        | (0x02 if polarity2 else 0) | (0x01 if open_drain2 else 0))
        self._restore(was)

    def enable_interrupt(self, source, pin=1):
        """Enable an interrupt source and route it to INT1 or INT2.

        Args:
            source: One of the SOURCE_* constants (or a bitwise OR of them).
            pin: 1 or 2.

        Raises:
            ValueError: If pin is not 1 or 2.
        """
        if pin not in (1, 2):
            raise ValueError('pin must be 1 or 2')
        was = self._standby()
        c4 = self._read_reg(self._REG_CTRL_REG4)[0]
        c5 = self._read_reg(self._REG_CTRL_REG5)[0]
        self._write_reg(self._REG_CTRL_REG4, c4 | source)
        self._write_reg(self._REG_CTRL_REG5, (c5 | source) if pin == 1 else (c5 & ~source & 0xFF))
        self._restore(was)

    def disable_interrupt(self, source):
        """Disable an interrupt source (clear it in CTRL_REG4)."""
        was = self._standby()
        c4 = self._read_reg(self._REG_CTRL_REG4)[0]
        self._write_reg(self._REG_CTRL_REG4, c4 & ~source & 0xFF)
        self._restore(was)

    def poll_interrupt(self):
        """Read INT_SOURCE.

        Returns:
            int: Bitmask of SOURCE_* flags.
        """
        return self._read_reg(self._REG_INT_SOURCE)[0]

    def _dispatch_int(self):
        callback = self._int_callback
        if callback is None:
            return
        try:
            status = self.poll_interrupt()
            c5 = self._read_reg(self._REG_CTRL_REG5)[0]
            routed = c5 if self._int_pin_no == 1 else (~c5 & 0xFF)
            status &= routed
            if status:
                callback(status)
        except Exception:
            pass

    def on_interrupt(self, callback, pin=1, int_pin=None):
        """Subscribe to INT1 or INT2.

        Uses ``int_pin`` (or the connection's ``int_pin``) if present, otherwise a
        100 ms polling thread on :meth:`poll_interrupt`. The callback receives the
        INT_SOURCE bitmask filtered to the sources routed to that pin.

        Args:
            callback: Function called with the status bitmask.
            pin: 1 or 2.
            int_pin: Optional InputPin the interrupt line is wired to.

        Raises:
            ValueError: If pin is not 1 or 2.
        """
        if pin not in (1, 2):
            raise ValueError('pin must be 1 or 2')
        self._int_callback = callback
        self._int_pin_no = pin
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
                time.sleep(0.1)

        _thread.start_new_thread(_loop, ())

    def off_interrupt(self):
        """Unsubscribe from the interrupt."""
        self._int_callback = None
        ipin = getattr(self, '_int_src_pin', None)
        handler = self._int_handler
        self._int_handler = None
        if ipin is not None and handler is not None and hasattr(ipin, 'off_edge'):
            ipin.off_edge(handler)
        if self._poll_stop is not None:
            self._poll_stop[0] = True
            self._poll_stop = None
