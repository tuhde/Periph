"""PCF8523 — Low-power I2C real-time clock and calendar (NXP).

Battery-backed calendar clock (seconds..year) with sensible defaults: 24-hour
time format, battery switch-over in standard mode with battery-low detection
enabled (PM[2:0]=000, written at init). Fixed I2C address 0x68. The same
driver file is used on MicroPython, CircuitPython, and Linux hosts.

Weekday convention: this driver adopts the datasheet's own suggested
assignment, 0=Sunday..6=Saturday — the chip's WEEKDAYS register is a
free-running 0-6 counter with no hardware-enforced meaning.

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


def _bcd_to_int(bcd):
    return ((bcd >> 4) & 0x0F) * 10 + (bcd & 0x0F)


def _int_to_bcd(value):
    return ((value // 10) << 4) | (value % 10)


class PCF8523Minimal:
    """PCF8523 low-power I2C real-time clock — minimal interface.

    Args:
        connection: Configured I2C connection pointing at the device.
    """

    _REG_CONTROL_1       = 0x00
    _REG_CONTROL_2       = 0x01
    _REG_CONTROL_3       = 0x02
    _REG_SECONDS         = 0x03
    _REG_MINUTES         = 0x04
    _REG_HOURS           = 0x05
    _REG_DAYS            = 0x06
    _REG_WEEKDAYS        = 0x07
    _REG_MONTHS          = 0x08
    _REG_YEARS           = 0x09
    _REG_MINUTE_ALARM    = 0x0A
    _REG_HOUR_ALARM      = 0x0B
    _REG_DAY_ALARM       = 0x0C
    _REG_WEEKDAY_ALARM   = 0x0D
    _REG_OFFSET          = 0x0E
    _REG_TMR_CLKOUT_CTRL = 0x0F
    _REG_TMR_A_FREQ_CTRL = 0x10
    _REG_TMR_A_REG       = 0x11
    _REG_TMR_B_FREQ_CTRL = 0x12
    _REG_TMR_B_REG       = 0x13

    # CONTROL_1 (0x00) bits.
    _C1_CAP_SEL = 0x80
    _C1_T       = 0x40
    _C1_STOP    = 0x20
    _C1_SR      = 0x10
    _C1_12_24   = 0x08
    _C1_SIE     = 0x04
    _C1_AIE     = 0x02
    _C1_CIE     = 0x01

    # CONTROL_2 (0x01) bits.
    _C2_WTAF  = 0x80
    _C2_CTAF  = 0x40
    _C2_CTBF  = 0x20
    _C2_SF    = 0x10
    _C2_AF    = 0x08
    _C2_WTAIE = 0x04
    _C2_CTAIE = 0x02
    _C2_CTBIE = 0x01
    _C2_CLEARABLE = 0x78  # CTAF | CTBF | SF | AF (write 0 clears, 1 keeps)
    _C2_ENABLES   = 0x07

    # CONTROL_3 (0x02) bits.
    _C3_PM_MASK = 0xE0
    _C3_BSF     = 0x08
    _C3_BLF     = 0x04
    _C3_BSIE    = 0x02
    _C3_BLIE    = 0x01

    _SECONDS_OS = 0x80

    def __init__(self, connection):
        self._connection = connection
        self._read_reg(self._REG_CONTROL_1)  # presence check (no identity register)
        # Battery switch-over standard mode, battery-low detection enabled
        # (PM=000); BSF/BSIE/BLIE left at their POR-default 0.
        self._write_reg(self._REG_CONTROL_3, 0x00)

    def _write_reg(self, reg, value):
        self._connection.write(bytes([reg, value & 0xFF]))

    def _read_reg(self, reg):
        return self._connection.write_read(bytes([reg]), 1)[0]

    def _write_regs(self, start_reg, data):
        self._connection.write(bytes([start_reg]) + bytes(data))

    def _read_regs(self, start_reg, length):
        return self._connection.write_read(bytes([start_reg]), length)

    def _read_control_1(self):
        # T must always be written 0 and SR always reads 0; mask both so a
        # read-modify-write never triggers a reset.
        return self._read_reg(self._REG_CONTROL_1) & ~(self._C1_T | self._C1_SR) & 0xFF

    def get_datetime(self):
        """Read the current calendar clock value.

        Returns:
            tuple: (year, month, day, weekday, hour, minute, second), all
            int. year is 2000-2099; hour is 0-23; weekday is 0=Sunday..6=Saturday.
        """
        buf = self._read_regs(self._REG_SECONDS, 7)
        second  = _bcd_to_int(buf[0] & 0x7F)
        minute  = _bcd_to_int(buf[1] & 0x7F)
        hour    = _bcd_to_int(buf[2] & 0x3F)
        day     = _bcd_to_int(buf[3] & 0x3F)
        weekday = buf[4] & 0x07
        month   = _bcd_to_int(buf[5] & 0x1F)
        year    = 2000 + _bcd_to_int(buf[6])
        return (year, month, day, weekday, hour, minute, second)

    def set_datetime(self, year, month, day, weekday, hour, minute, second):
        """Write the calendar clock using the STOP-bit precision start.

        Freezes the divider chain (STOP=1), writes all seven time/date
        registers in one transaction, then releases STOP — the first 1 Hz
        tick follows ~0.5 s later. Forces 24-hour mode and clears the OS
        flag (the time is now known-good).

        Args:
            year: Full year, 2000-2099.
            month: 1-12.
            day: Day of month, 1-31.
            weekday: 0=Sunday..6=Saturday.
            hour: 0-23.
            minute: 0-59.
            second: 0-59.
        """
        ctrl1 = self._read_control_1() & ~self._C1_12_24 & 0xFF
        self._write_reg(self._REG_CONTROL_1, ctrl1 | self._C1_STOP)
        buf = bytes([
            _int_to_bcd(second) & 0x7F,  # OS = 0
            _int_to_bcd(minute),
            _int_to_bcd(hour) & 0x3F,
            _int_to_bcd(day),
            weekday & 0x07,
            _int_to_bcd(month),
            _int_to_bcd(year - 2000),
        ])
        self._write_regs(self._REG_SECONDS, buf)
        self._write_reg(self._REG_CONTROL_1, ctrl1 & ~self._C1_STOP & 0xFF)


# Interrupt source bits (combined CONTROL_2/CONTROL_3 status mask).
SOURCE_SECOND         = 0x01
SOURCE_TIMER_A        = 0x02
SOURCE_TIMER_B        = 0x04
SOURCE_ALARM          = 0x08
SOURCE_BATTERY_SWITCH = 0x10
SOURCE_BATTERY_LOW    = 0x20

_SOURCE_CLOCKS = {
    '4096hz':   0x00,
    '64hz':     0x01,
    '1hz':      0x02,
    '1_60hz':   0x03,
    '1_3600hz': 0x07,
}

# TBW[2:0] -> low-pulse width in ms (datasheet Table 36; not uniformly spaced).
_TBW_WIDTHS_MS = (46.875, 62.5, 78.125, 93.75, 125.0, 156.25, 187.5, 218.75)

_CLKOUT_COF = {
    32768: 0x00,
    16384: 0x01,
    8192:  0x02,
    4096:  0x03,
    1024:  0x04,
    32:    0x05,
    1:     0x06,
}

_PM_MODES = {
    ('standard', True):  0x00,
    ('direct', True):    0x01,
    ('disabled', True):  0x02,
    ('standard', False): 0x04,
    ('direct', False):   0x05,
    ('disabled', False): 0x07,
}


class PCF8523Full(PCF8523Minimal):
    """PCF8523 full interface — extends Minimal with the alarm, Timer A
    (countdown or watchdog), Timer B, programmable CLKOUT, offset
    calibration, battery backup control/status, oscillator-stop detection,
    software reset, and the Level-3 interrupt API.

    INT1 is shared with CLKOUT: interrupts only reach INT1 once CLKOUT is
    disabled (disable_clock_output()); this driver never does that
    implicitly. Timer B additionally drives the dedicated INT2 pin.

    Args:
        connection: Configured I2C connection pointing at the device.
    """

    _TMR_TAM      = 0x80
    _TMR_TBM      = 0x40
    _TMR_COF_MASK = 0x38
    _TMR_TAC_MASK = 0x06
    _TMR_TAC_COUNTDOWN = 0x02
    _TMR_TAC_WATCHDOG  = 0x04
    _TMR_TBC      = 0x01

    def __init__(self, connection):
        super().__init__(connection)
        self._callback = None
        self._int_pin_used = None
        self._poll_stop = False
        self._poll_thread = None

    def _write_control_2(self, enables):
        # Re-supply the enable bits; write 1 to every clearable flag so none
        # is cleared by accident (AND semantics). WTAF is read-only.
        self._write_reg(self._REG_CONTROL_2, self._C2_CLEARABLE | (enables & self._C2_ENABLES))

    def _write_control_3(self, value):
        # Write 1 to BSF so it is left unchanged; BLF is read-only.
        self._write_reg(self._REG_CONTROL_3, (value & (self._C3_PM_MASK | self._C3_BSIE | self._C3_BLIE)) | self._C3_BSF)

    def _update_tmr_clkout(self, clear_mask, set_bits):
        reg = self._read_reg(self._REG_TMR_CLKOUT_CTRL)
        self._write_reg(self._REG_TMR_CLKOUT_CTRL, (reg & ~clear_mask & 0xFF) | set_bits)

    def get_alarm(self):
        """Decode the alarm registers (0x0A-0x0D).

        Returns:
            tuple: (minute, hour, day, weekday) — each int, or None if that
            field is disabled (AEN_x=1).
        """
        buf = self._read_regs(self._REG_MINUTE_ALARM, 4)
        minute  = None if buf[0] & 0x80 else _bcd_to_int(buf[0] & 0x7F)
        hour    = None if buf[1] & 0x80 else _bcd_to_int(buf[1] & 0x3F)
        day     = None if buf[2] & 0x80 else _bcd_to_int(buf[2] & 0x3F)
        weekday = None if buf[3] & 0x80 else buf[3] & 0x07
        return (minute, hour, day, weekday)

    def set_alarm(self, minute=None, hour=None, day=None, weekday=None):
        """Write the alarm registers (0x0A-0x0D).

        A field left None is disabled (AEN_x=1); a value enables it
        (AEN_x=0). The alarm fires when every enabled field matches; an alarm
        with all fields None never fires.

        Args:
            minute: 0-59, or None.
            hour: 0-23, or None.
            day: Day of month 1-31, or None.
            weekday: 0=Sunday..6=Saturday, or None.
        """
        buf = bytes([
            0x80 if minute is None else _int_to_bcd(minute) & 0x7F,
            0x80 if hour is None else _int_to_bcd(hour) & 0x3F,
            0x80 if day is None else _int_to_bcd(day) & 0x3F,
            0x80 if weekday is None else weekday & 0x07,
        ])
        self._write_regs(self._REG_MINUTE_ALARM, buf)

    def configure_timer_a(self, mode, value, source_clock, pulsed=False):
        """Configure and start Timer A.

        Args:
            mode: "countdown" or "watchdog".
            value: Countdown value, 0-255.
            source_clock: "4096hz", "64hz", "1hz", "1_60hz" or "1_3600hz".
            pulsed: Pulsed (True) or permanently-active (False) interrupt.
        """
        tac = self._TMR_TAC_WATCHDOG if mode == 'watchdog' else self._TMR_TAC_COUNTDOWN
        self._write_reg(self._REG_TMR_A_FREQ_CTRL, _SOURCE_CLOCKS[source_clock])
        self._write_reg(self._REG_TMR_A_REG, value)
        self._update_tmr_clkout(self._TMR_TAM | self._TMR_TAC_MASK,
                                (self._TMR_TAM if pulsed else 0) | tac)

    def disable_timer_a(self):
        """Stop Timer A (TAC=00)."""
        self._update_tmr_clkout(self._TMR_TAC_MASK, 0)

    def read_timer_a(self):
        """Read Timer A's live countdown value.

        Returns:
            int: Current counter value, 0-255 (not the originally loaded one).
        """
        return self._read_reg(self._REG_TMR_A_REG)

    def configure_timer_b(self, value, source_clock, pulse_width_ms=46.875, pulsed=False):
        """Configure and start Timer B (also drives INT2).

        Args:
            value: Countdown value, 0-255.
            source_clock: "4096hz", "64hz", "1hz", "1_60hz" or "1_3600hz".
            pulse_width_ms: Pulsed-mode low-pulse width in ms; the nearest of
                the eight hardware widths (46.875-218.75 ms) is used.
            pulsed: Pulsed (True) or permanently-active (False) interrupt.
        """
        tbw = 0
        for i, width in enumerate(_TBW_WIDTHS_MS):
            if abs(width - pulse_width_ms) < abs(_TBW_WIDTHS_MS[tbw] - pulse_width_ms):
                tbw = i
        self._write_reg(self._REG_TMR_B_FREQ_CTRL, (tbw << 4) | _SOURCE_CLOCKS[source_clock])
        self._write_reg(self._REG_TMR_B_REG, value)
        self._update_tmr_clkout(self._TMR_TBM | self._TMR_TBC,
                                (self._TMR_TBM if pulsed else 0) | self._TMR_TBC)

    def disable_timer_b(self):
        """Stop Timer B (TBC=0)."""
        self._update_tmr_clkout(self._TMR_TBC, 0)

    def read_timer_b(self):
        """Read Timer B's live countdown value.

        Returns:
            int: Current counter value, 0-255 (not the originally loaded one).
        """
        return self._read_reg(self._REG_TMR_B_REG)

    def set_clock_output(self, frequency_hz):
        """Drive CLKOUT on the shared INT1/CLKOUT pin.

        Args:
            frequency_hz: One of 32768, 16384, 8192, 4096, 1024, 32, 1 Hz.
        """
        self._update_tmr_clkout(self._TMR_COF_MASK, _CLKOUT_COF[frequency_hz] << 3)

    def disable_clock_output(self):
        """Disable CLKOUT (COF=111), freeing INT1 for interrupts."""
        self._update_tmr_clkout(self._TMR_COF_MASK, self._TMR_COF_MASK)

    def get_offset(self):
        """Read the clock-offset calibration register.

        Returns:
            tuple: (offset, mode) — offset int -64..+63 LSB (4.34 ppm per
            LSB every two hours, 4.069 ppm every minute); mode
            "every_two_hours" or "every_minute".
        """
        raw = self._read_reg(self._REG_OFFSET)
        offset = raw & 0x7F
        if offset & 0x40:
            offset -= 128
        return (offset, 'every_minute' if raw & 0x80 else 'every_two_hours')

    def set_offset(self, offset, mode='every_two_hours'):
        """Write the clock-offset calibration register.

        Args:
            offset: Two's-complement correction, -64 to 63 LSB.
            mode: "every_two_hours" (default, 4.34 ppm/LSB) or
                "every_minute" (4.069 ppm/LSB).
        """
        self._write_reg(self._REG_OFFSET,
                        (0x80 if mode == 'every_minute' else 0) | (offset & 0x7F))

    def configure_battery_backup(self, mode, low_detection=True):
        """Select the battery switch-over mode (PM[2:0]).

        Args:
            mode: "standard", "direct" or "disabled" (VDD only; tie VBAT to VDD).
            low_detection: Enable battery-low detection.
        """
        pm = _PM_MODES[(mode, bool(low_detection))]
        ctrl3 = self._read_reg(self._REG_CONTROL_3)
        self._write_control_3((ctrl3 & ~self._C3_PM_MASK & 0xFF) | (pm << 5))

    def is_battery_switched_over(self):
        """Read the battery switch-over flag (BSF).

        Returns:
            bool: True if a switch-over to VBAT occurred since it was last cleared.
        """
        return bool(self._read_reg(self._REG_CONTROL_3) & self._C3_BSF)

    def clear_battery_switchover(self):
        """Clear BSF only, leaving PM and the enable bits unchanged."""
        ctrl3 = self._read_reg(self._REG_CONTROL_3)
        self._write_reg(self._REG_CONTROL_3,
                        ctrl3 & (self._C3_PM_MASK | self._C3_BSIE | self._C3_BLIE))

    def is_battery_low(self):
        """Read the battery-low flag (BLF, read-only).

        Returns:
            bool: True if VBAT is below the detection threshold.
        """
        return bool(self._read_reg(self._REG_CONTROL_3) & self._C3_BLF)

    def oscillator_stopped(self):
        """Read the OS flag (bit 7 of SECONDS).

        Returns:
            bool: True means timekeeping may be invalid; cleared by set_datetime().
        """
        return bool(self._read_reg(self._REG_SECONDS) & self._SECONDS_OS)

    def software_reset(self):
        """Send the software-reset sequence (0x58 to CONTROL_1).

        Resets all control/configuration registers to POR defaults —
        including PM=111 (battery backup disabled) — but leaves the
        time/date/alarm/timer values unchanged.
        """
        self._write_reg(self._REG_CONTROL_1, 0x58)

    def on_interrupt(self, callback, int_pin=None):
        """Subscribe to interrupts on INT1 (or INT2 for Timer B only).

        Delivery: if int_pin is given, it is wired directly. Otherwise falls
        back to connection.int_pin, or a 5 ms polling thread on Linux if
        neither is available. Call disable_clock_output() first if INT1 is
        still carrying CLKOUT.

        Args:
            callback: Callable(status: int) — called with the pre-clear
                status mask; test it against the SOURCE_* constants.
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
        """Read CONTROL_2/CONTROL_3, clear the set CTAF/CTBF/SF/AF/BSF flags
        (WTAF/BLF are read-only; enable bits untouched), and return the
        pre-clear status.

        Returns:
            int: Combined status mask — test with the SOURCE_* constants.
        """
        buf = self._read_regs(self._REG_CONTROL_2, 2)
        ctrl2, ctrl3 = buf[0], buf[1]
        status = 0
        if ctrl2 & self._C2_SF:
            status |= SOURCE_SECOND
        if ctrl2 & (self._C2_CTAF | self._C2_WTAF):
            status |= SOURCE_TIMER_A
        if ctrl2 & self._C2_CTBF:
            status |= SOURCE_TIMER_B
        if ctrl2 & self._C2_AF:
            status |= SOURCE_ALARM
        if ctrl3 & self._C3_BSF:
            status |= SOURCE_BATTERY_SWITCH
        if ctrl3 & self._C3_BLF:
            status |= SOURCE_BATTERY_LOW
        # Write 0 only to the flags seen set, 1 to the rest, so a flag that
        # sets between the read and this write is not lost.
        if ctrl2 & self._C2_CLEARABLE:
            self._write_reg(self._REG_CONTROL_2,
                            (self._C2_CLEARABLE & ~ctrl2) | (ctrl2 & self._C2_ENABLES))
        if ctrl3 & self._C3_BSF:
            self._write_reg(self._REG_CONTROL_3,
                            ctrl3 & (self._C3_PM_MASK | self._C3_BSIE | self._C3_BLIE))
        return status

    def enable_interrupt(self, source):
        """Enable one or more interrupt sources.

        SOURCE_TIMER_A sets WTAIE or CTAIE depending on Timer A's configured
        mode — call configure_timer_a() first.

        Args:
            source: Bitwise OR of SOURCE_* constants.
        """
        self._set_interrupt_enables(source, True)

    def disable_interrupt(self, source):
        """Disable one or more interrupt sources.

        Args:
            source: Bitwise OR of SOURCE_* constants.
        """
        self._set_interrupt_enables(source, False)

    def _set_interrupt_enables(self, source, enable):
        if source & (SOURCE_SECOND | SOURCE_ALARM):
            bits = ((self._C1_SIE if source & SOURCE_SECOND else 0)
                    | (self._C1_AIE if source & SOURCE_ALARM else 0))
            ctrl1 = self._read_control_1()
            ctrl1 = (ctrl1 | bits) if enable else (ctrl1 & ~bits & 0xFF)
            self._write_reg(self._REG_CONTROL_1, ctrl1)
        if source & (SOURCE_TIMER_A | SOURCE_TIMER_B):
            bits = 0
            if source & SOURCE_TIMER_A:
                if enable:
                    tac = self._read_reg(self._REG_TMR_CLKOUT_CTRL) & self._TMR_TAC_MASK
                    bits |= self._C2_WTAIE if tac == self._TMR_TAC_WATCHDOG else self._C2_CTAIE
                else:
                    bits |= self._C2_WTAIE | self._C2_CTAIE
            if source & SOURCE_TIMER_B:
                bits |= self._C2_CTBIE
            enables = self._read_reg(self._REG_CONTROL_2) & self._C2_ENABLES
            enables = (enables | bits) if enable else (enables & ~bits)
            self._write_control_2(enables)
        if source & (SOURCE_BATTERY_SWITCH | SOURCE_BATTERY_LOW):
            bits = ((self._C3_BSIE if source & SOURCE_BATTERY_SWITCH else 0)
                    | (self._C3_BLIE if source & SOURCE_BATTERY_LOW else 0))
            ctrl3 = self._read_reg(self._REG_CONTROL_3)
            ctrl3 = (ctrl3 | bits) if enable else (ctrl3 & ~bits & 0xFF)
            self._write_control_3(ctrl3)
