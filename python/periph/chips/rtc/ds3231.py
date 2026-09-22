"""DS3231 — Extremely accurate I2C-integrated RTC/TCXO/crystal (Analog Devices).

Battery-backed calendar clock (seconds..year) plus a free on-chip
temperature reading, with sensible defaults: 24-hour time format,
oscillator continuously enabled on VBAT. Fixed I2C address 0x68. The same
driver file is used on MicroPython, CircuitPython, and Linux hosts.

Weekday convention: this driver defines 1=Monday..7=Sunday (ISO 8601) — the
chip's DAY register is a free-running 1-7 counter with no hardware-enforced
meaning.

Args:
    connection: Configured I2C connection pointing at the device (fixed
        address 0x68).
"""

try:
    import threading as _threading
    _LINUX = True
except ImportError:
    _LINUX = False


I2C_ADDRESS = 0x68


def _delay_ms(ms):
    import time
    if hasattr(time, 'sleep_ms'):
        time.sleep_ms(ms)
    else:
        time.sleep(ms / 1000.0)


def _bcd_to_int(bcd):
    return ((bcd >> 4) & 0x0F) * 10 + (bcd & 0x0F)


def _int_to_bcd(value):
    return ((value // 10) << 4) | (value % 10)


def _decode_hour(raw):
    if raw & 0x40:
        pm = bool(raw & 0x20)
        hour12 = ((raw >> 4) & 0x01) * 10 + (raw & 0x0F)
        if pm and hour12 != 12:
            return hour12 + 12
        if not pm and hour12 == 12:
            return 0
        return hour12
    return _bcd_to_int(raw & 0x3F)


class DS3231Minimal:
    """DS3231 extremely accurate I2C RTC/TCXO/crystal — minimal interface.

    Args:
        connection: Configured I2C connection pointing at the device.
    """

    _REG_SECONDS        = 0x00
    _REG_MINUTES        = 0x01
    _REG_HOURS          = 0x02
    _REG_DAY            = 0x03
    _REG_DATE           = 0x04
    _REG_MONTH_CENTURY  = 0x05
    _REG_YEAR           = 0x06
    _REG_ALARM1_SECONDS = 0x07
    _REG_ALARM1_MINUTES = 0x08
    _REG_ALARM1_HOURS   = 0x09
    _REG_ALARM1_DAYDATE = 0x0A
    _REG_ALARM2_MINUTES = 0x0B
    _REG_ALARM2_HOURS   = 0x0C
    _REG_ALARM2_DAYDATE = 0x0D
    _REG_CONTROL        = 0x0E
    _REG_STATUS         = 0x0F
    _REG_AGING_OFFSET   = 0x10
    _REG_TEMP_MSB       = 0x11
    _REG_TEMP_LSB       = 0x12

    # CONTROL (0x0E) bits.
    _CTRL_EOSC  = 0x80
    _CTRL_BBSQW = 0x40
    _CTRL_CONV  = 0x20
    _CTRL_RS2   = 0x10
    _CTRL_RS1   = 0x08
    _CTRL_INTCN = 0x04
    _CTRL_A2IE  = 0x02
    _CTRL_A1IE  = 0x01

    # CONTROL_STATUS (0x0F) bits.
    _STAT_OSF     = 0x80
    _STAT_EN32KHZ = 0x08
    _STAT_BSY     = 0x04
    _STAT_A2F     = 0x02
    _STAT_A1F     = 0x01

    def __init__(self, connection):
        self._connection = connection
        self._read_reg(self._REG_CONTROL)  # presence check; no writes needed

    def _write_reg(self, reg, value):
        self._connection.write(bytes([reg, value & 0xFF]))

    def _read_reg(self, reg):
        return self._connection.write_read(bytes([reg]), 1)[0]

    def _write_regs(self, start_reg, data):
        self._connection.write(bytes([start_reg]) + bytes(data))

    def _read_regs(self, start_reg, length):
        return self._connection.write_read(bytes([start_reg]), length)

    def get_datetime(self):
        """Read the current calendar clock value.

        Returns:
            tuple: (year, month, day, weekday, hour, minute, second), all
            int. year is 2000-2099; weekday is ISO 8601 (1=Monday..7=Sunday).
        """
        buf = self._read_regs(self._REG_SECONDS, 7)
        second  = _bcd_to_int(buf[0] & 0x7F)
        minute  = _bcd_to_int(buf[1] & 0x7F)
        hour    = _decode_hour(buf[2])
        weekday = _bcd_to_int(buf[3] & 0x07)
        day     = _bcd_to_int(buf[4] & 0x3F)
        month   = _bcd_to_int(buf[5] & 0x1F)
        year    = 2000 + _bcd_to_int(buf[6])
        return (year, month, day, weekday, hour, minute, second)

    def set_datetime(self, year, month, day, weekday, hour, minute, second):
        """Write the calendar clock.

        Forces 24-hour mode and clears the Oscillator Stop Flag (the time is
        now known-good).

        Args:
            year: Full year, 2000-2099.
            month: 1-12.
            day: Day of month, 1-31.
            weekday: ISO 8601 day of week, 1=Monday..7=Sunday.
            hour: 0-23.
            minute: 0-59.
            second: 0-59.
        """
        buf = bytes([
            _int_to_bcd(second),
            _int_to_bcd(minute),
            _int_to_bcd(hour) & 0x3F,  # 24-hour mode, bit 6 = 0
            _int_to_bcd(weekday),
            _int_to_bcd(day),
            _int_to_bcd(month) & 0x1F,
            _int_to_bcd(year - 2000),
        ])
        self._write_regs(self._REG_SECONDS, buf)

        # The time is now known-good: clear OSF. Writing back the rest of
        # the status byte as read is safe (see the spec's Implementation
        # Notes: writing 1 to A1F/A2F/OSF is a documented no-op).
        status = self._read_reg(self._REG_STATUS)
        self._write_reg(self._REG_STATUS, status & ~self._STAT_OSF & 0xFF)

    def read_temperature(self):
        """Read the last completed temperature conversion.

        No forced conversion — the chip converts autonomously every 64 s and
        on power-up, so this may be up to 64 s stale.

        Returns:
            float: Temperature in degrees Celsius.
        """
        buf = self._read_regs(self._REG_TEMP_MSB, 2)
        msb = buf[0] - 256 if buf[0] & 0x80 else buf[0]
        frac = (buf[1] >> 6) & 0x03
        return msb + frac * 0.25


# Alarm 1 match modes (datasheet Table 2).
ALARM1_EVERY_SECOND = 0
ALARM1_MATCH_SECONDS = 1
ALARM1_MATCH_MINUTES_SECONDS = 2
ALARM1_MATCH_HOURS_MINUTES_SECONDS = 3
ALARM1_MATCH_DATE_HOURS_MINUTES_SECONDS = 4
ALARM1_MATCH_DAY_HOURS_MINUTES_SECONDS = 5

# Alarm 2 match modes (datasheet Table 2).
ALARM2_EVERY_MINUTE = 0
ALARM2_MATCH_MINUTES = 1
ALARM2_MATCH_HOURS_MINUTES = 2
ALARM2_MATCH_DATE_HOURS_MINUTES = 3
ALARM2_MATCH_DAY_HOURS_MINUTES = 4

# Interrupt source bits.
SOURCE_ALARM1 = 0x01
SOURCE_ALARM2 = 0x02

_ALARM1_MASKS = {
    ALARM1_EVERY_SECOND:                    (1, 1, 1, 1, 0),
    ALARM1_MATCH_SECONDS:                   (0, 1, 1, 1, 0),
    ALARM1_MATCH_MINUTES_SECONDS:           (0, 0, 1, 1, 0),
    ALARM1_MATCH_HOURS_MINUTES_SECONDS:     (0, 0, 0, 1, 0),
    ALARM1_MATCH_DATE_HOURS_MINUTES_SECONDS: (0, 0, 0, 0, 0),
    ALARM1_MATCH_DAY_HOURS_MINUTES_SECONDS: (0, 0, 0, 0, 1),
}  # matchMode -> (a1m1, a1m2, a1m3, a1m4, dydt)

_ALARM2_MASKS = {
    ALARM2_EVERY_MINUTE:            (1, 1, 1, 0),
    ALARM2_MATCH_MINUTES:            (0, 1, 1, 0),
    ALARM2_MATCH_HOURS_MINUTES:      (0, 0, 1, 0),
    ALARM2_MATCH_DATE_HOURS_MINUTES: (0, 0, 0, 0),
    ALARM2_MATCH_DAY_HOURS_MINUTES:  (0, 0, 0, 1),
}  # matchMode -> (a2m2, a2m3, a2m4, dydt)


def _alarm1_match_mode(a1m1, a1m2, a1m3, a1m4, dydt):
    if a1m4:
        if a1m3:
            if a1m2:
                return ALARM1_EVERY_SECOND if a1m1 else ALARM1_MATCH_SECONDS
            return ALARM1_MATCH_MINUTES_SECONDS
        return ALARM1_MATCH_HOURS_MINUTES_SECONDS
    return ALARM1_MATCH_DAY_HOURS_MINUTES_SECONDS if dydt else ALARM1_MATCH_DATE_HOURS_MINUTES_SECONDS


def _alarm2_match_mode(a2m2, a2m3, a2m4, dydt):
    if a2m4:
        if a2m3:
            return ALARM2_EVERY_MINUTE if a2m2 else ALARM2_MATCH_MINUTES
        return ALARM2_MATCH_HOURS_MINUTES
    return ALARM2_MATCH_DAY_HOURS_MINUTES if dydt else ALARM2_MATCH_DATE_HOURS_MINUTES


class DS3231Full(DS3231Minimal):
    """DS3231 full interface — extends Minimal with both alarms,
    square-wave/32kHz outputs, oscillator/aging control, forced temperature
    conversion, and the Level-2 selectable-source interrupt API.

    INT/SQW is one physical pin multiplexed by the CONTROL register's INTCN
    bit — enable_interrupt()/on_interrupt() and enable_square_wave() are
    mutually exclusive; whichever call happens last wins. poll_interrupt()
    still reports alarm matches correctly regardless of INTCN, since
    A1F/A2F latch independently of the pin's mode.

    Args:
        connection: Configured I2C connection pointing at the device.
    """

    def __init__(self, connection):
        super().__init__(connection)
        self._callback = None
        self._int_pin_used = None
        self._poll_stop = False
        self._poll_thread = None

    def get_alarm1(self):
        """Decode the Alarm 1 registers (0x07-0x0A).

        Returns:
            dict: {second, minute, hour, day_or_date, is_day_of_week, match_mode}
        """
        buf = self._read_regs(self._REG_ALARM1_SECONDS, 4)
        a1m1 = (buf[0] >> 7) & 1
        a1m2 = (buf[1] >> 7) & 1
        a1m3 = (buf[2] >> 7) & 1
        a1m4 = (buf[3] >> 7) & 1
        dydt = (buf[3] >> 6) & 1
        return {
            'second': _bcd_to_int(buf[0] & 0x7F),
            'minute': _bcd_to_int(buf[1] & 0x7F),
            'hour': _decode_hour(buf[2] & 0x7F),
            'day_or_date': _bcd_to_int(buf[3] & 0x3F),
            'is_day_of_week': bool(dydt),
            'match_mode': _alarm1_match_mode(a1m1, a1m2, a1m3, a1m4, dydt),
        }

    def set_alarm1(self, second, minute, hour, day_or_date, is_day_of_week, match_mode):
        """Write the Alarm 1 registers (0x07-0x0A).

        Args:
            second: 0-59.
            minute: 0-59.
            hour: 0-23.
            day_or_date: day-of-week (1-7) or day-of-month (1-31), per is_day_of_week.
            is_day_of_week: Ignored for the mask-only modes.
            match_mode: One of the ALARM1_* constants.
        """
        a1m1, a1m2, a1m3, a1m4, dydt = _ALARM1_MASKS[match_mode]
        if match_mode == ALARM1_MATCH_DAY_HOURS_MINUTES_SECONDS:
            is_day_of_week = True
        elif match_mode == ALARM1_MATCH_DATE_HOURS_MINUTES_SECONDS:
            is_day_of_week = False
        buf = bytes([
            (a1m1 << 7) | _int_to_bcd(second),
            (a1m2 << 7) | _int_to_bcd(minute),
            (a1m3 << 7) | (_int_to_bcd(hour) & 0x3F),
            (a1m4 << 7) | ((1 if is_day_of_week else 0) << 6) | (_int_to_bcd(day_or_date) & 0x3F),
        ])
        self._write_regs(self._REG_ALARM1_SECONDS, buf)

    def get_alarm2(self):
        """Decode the Alarm 2 registers (0x0B-0x0D).

        Returns:
            dict: {minute, hour, day_or_date, is_day_of_week, match_mode}
        """
        buf = self._read_regs(self._REG_ALARM2_MINUTES, 3)
        a2m2 = (buf[0] >> 7) & 1
        a2m3 = (buf[1] >> 7) & 1
        a2m4 = (buf[2] >> 7) & 1
        dydt = (buf[2] >> 6) & 1
        return {
            'minute': _bcd_to_int(buf[0] & 0x7F),
            'hour': _decode_hour(buf[1] & 0x7F),
            'day_or_date': _bcd_to_int(buf[2] & 0x3F),
            'is_day_of_week': bool(dydt),
            'match_mode': _alarm2_match_mode(a2m2, a2m3, a2m4, dydt),
        }

    def set_alarm2(self, minute, hour, day_or_date, is_day_of_week, match_mode):
        """Write the Alarm 2 registers (0x0B-0x0D).

        Args:
            minute: 0-59.
            hour: 0-23.
            day_or_date: day-of-week (1-7) or day-of-month (1-31), per is_day_of_week.
            is_day_of_week: Ignored for the mask-only modes.
            match_mode: One of the ALARM2_* constants.
        """
        a2m2, a2m3, a2m4, dydt = _ALARM2_MASKS[match_mode]
        if match_mode == ALARM2_MATCH_DAY_HOURS_MINUTES:
            is_day_of_week = True
        elif match_mode == ALARM2_MATCH_DATE_HOURS_MINUTES:
            is_day_of_week = False
        buf = bytes([
            (a2m2 << 7) | _int_to_bcd(minute),
            (a2m3 << 7) | (_int_to_bcd(hour) & 0x3F),
            (a2m4 << 7) | ((1 if is_day_of_week else 0) << 6) | (_int_to_bcd(day_or_date) & 0x3F),
        ])
        self._write_regs(self._REG_ALARM2_MINUTES, buf)

    def enable_square_wave(self, rate_hz=8192, battery_backed=False):
        """Drive INT/SQW as a square wave. Sets INTCN=0 (mutually exclusive
        with alarm interrupts).

        Args:
            rate_hz: One of 1, 1024, 4096, 8192 (default; nearest match used otherwise).
            battery_backed: Keep the square wave driven while running on VBAT.
        """
        if rate_hz >= 8192:
            rs = self._CTRL_RS2 | self._CTRL_RS1
        elif rate_hz >= 4096:
            rs = self._CTRL_RS2
        elif rate_hz >= 1024:
            rs = self._CTRL_RS1
        else:
            rs = 0
        ctrl = self._read_reg(self._REG_CONTROL)
        ctrl &= ~(self._CTRL_INTCN | self._CTRL_RS2 | self._CTRL_RS1 | self._CTRL_BBSQW) & 0xFF
        ctrl |= rs
        if battery_backed:
            ctrl |= self._CTRL_BBSQW
        self._write_reg(self._REG_CONTROL, ctrl)

    def disable_square_wave(self):
        """Return INT/SQW to interrupt mode (sets INTCN=1)."""
        ctrl = self._read_reg(self._REG_CONTROL)
        self._write_reg(self._REG_CONTROL, ctrl | self._CTRL_INTCN)

    def is_32khz_enabled(self):
        """Read the EN32kHz bit.

        Returns:
            bool: Whether the separate 32kHz output pin is driven.
        """
        return bool(self._read_reg(self._REG_STATUS) & self._STAT_EN32KHZ)

    def enable_32khz_output(self):
        """Enable the separate 32kHz output pin."""
        status = self._read_reg(self._REG_STATUS)
        self._write_reg(self._REG_STATUS, status | self._STAT_EN32KHZ)

    def disable_32khz_output(self):
        """Disable the separate 32kHz output pin."""
        status = self._read_reg(self._REG_STATUS)
        self._write_reg(self._REG_STATUS, status & ~self._STAT_EN32KHZ & 0xFF)

    def oscillator_stopped(self):
        """Read the Oscillator Stop Flag.

        Returns:
            bool: True means timekeeping data may be invalid since the last check.
        """
        return bool(self._read_reg(self._REG_STATUS) & self._STAT_OSF)

    def clear_oscillator_stopped(self):
        """Clear the Oscillator Stop Flag, preserving EN32kHz."""
        status = self._read_reg(self._REG_STATUS)
        self._write_reg(self._REG_STATUS, status & ~self._STAT_OSF & 0xFF)

    def enable_battery_oscillator(self):
        """Keep the oscillator running while on VBAT (EOSC=0, power-on default)."""
        ctrl = self._read_reg(self._REG_CONTROL)
        self._write_reg(self._REG_CONTROL, ctrl & ~self._CTRL_EOSC & 0xFF)

    def disable_battery_oscillator(self):
        """Stop the oscillator when switched to VBAT, saving battery current (EOSC=1)."""
        ctrl = self._read_reg(self._REG_CONTROL)
        self._write_reg(self._REG_CONTROL, ctrl | self._CTRL_EOSC)

    def force_temperature_conversion(self):
        """Force an immediate temperature conversion and block until it
        completes (polls BSY, max ~200 ms)."""
        ctrl = self._read_reg(self._REG_CONTROL)
        self._write_reg(self._REG_CONTROL, ctrl | self._CTRL_CONV)
        for _ in range(40):
            if not (self._read_reg(self._REG_STATUS) & self._STAT_BSY):
                return
            _delay_ms(5)

    def get_aging_offset(self):
        """Read the raw two's-complement oscillator trim code.

        Returns:
            int: Signed 8-bit trim value. Not a physically-scaled unit — see
            the spec's Implementation Notes.
        """
        raw = self._read_reg(self._REG_AGING_OFFSET)
        return raw - 256 if raw & 0x80 else raw

    def set_aging_offset(self, offset):
        """Write the raw two's-complement oscillator trim code.

        Args:
            offset: Signed 8-bit trim value, -128 to 127.
        """
        self._write_reg(self._REG_AGING_OFFSET, offset & 0xFF)

    def on_interrupt(self, callback, int_pin=None):
        """Subscribe to alarm interrupts. Sets INTCN=1 so INT/SQW carries
        alarm interrupts instead of the square wave.

        Delivery: if int_pin is given, it is wired directly. Otherwise falls
        back to connection.int_pin, or a 5 ms polling thread on Linux if
        neither is available.

        Args:
            callback: Callable(status: int) — called with the pre-clear
                status byte; mask with SOURCE_ALARM1/SOURCE_ALARM2.
            int_pin: Optional InputPin for this call, overriding connection.int_pin.
        """
        self._callback = callback
        pin = int_pin if int_pin is not None else getattr(self._connection, 'int_pin', None)
        self._int_pin_used = pin
        if pin is not None:
            from periph.connection.input_pin import InputPin
            pin.on_edge(self._int_handler, InputPin.FALLING)
        elif _LINUX:
            self._poll_stop = False
            self._poll_thread = _threading.Thread(target=self._poll_loop, daemon=True)
            self._poll_thread.start()

    def off_interrupt(self):
        """Unsubscribe and stop delivery."""
        if self._int_pin_used is not None:
            from periph.connection.input_pin import InputPin
            self._int_pin_used.off_edge(self._int_handler)
            self._int_pin_used = None
        elif _LINUX:
            self._poll_stop = True
        self._callback = None

    def _int_handler(self):
        status = self.poll_interrupt()
        if status and self._callback:
            self._callback(status)

    def _poll_loop(self):
        import time
        while not self._poll_stop:
            self._int_handler()
            time.sleep(0.005)

    def poll_interrupt(self):
        """Read CONTROL_STATUS, clear A1F/A2F (leaving OSF/EN32kHz/BSY
        untouched), and return the pre-clear byte.

        Returns:
            int: Pre-clear status byte — mask with SOURCE_ALARM1/SOURCE_ALARM2
            to test each source.
        """
        status = self._read_reg(self._REG_STATUS)
        sources = status & (self._STAT_A1F | self._STAT_A2F)
        self._write_reg(self._REG_STATUS, status & ~(self._STAT_A1F | self._STAT_A2F) & 0xFF)
        return sources

    def enable_interrupt(self, source):
        """Enable one or both alarm interrupt sources and set INTCN=1.

        Args:
            source: Bitwise OR of SOURCE_ALARM1/SOURCE_ALARM2.
        """
        ctrl = self._read_reg(self._REG_CONTROL)
        if source & SOURCE_ALARM1:
            ctrl |= self._CTRL_A1IE
        if source & SOURCE_ALARM2:
            ctrl |= self._CTRL_A2IE
        ctrl |= self._CTRL_INTCN
        self._write_reg(self._REG_CONTROL, ctrl)

    def disable_interrupt(self, source):
        """Disable one or both alarm interrupt sources.

        Args:
            source: Bitwise OR of SOURCE_ALARM1/SOURCE_ALARM2.
        """
        ctrl = self._read_reg(self._REG_CONTROL)
        if source & SOURCE_ALARM1:
            ctrl &= ~self._CTRL_A1IE & 0xFF
        if source & SOURCE_ALARM2:
            ctrl &= ~self._CTRL_A2IE & 0xFF
        self._write_reg(self._REG_CONTROL, ctrl)
