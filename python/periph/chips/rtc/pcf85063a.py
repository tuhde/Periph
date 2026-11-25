"""PCF85063A — Low-power I2C real-time clock and calendar (NXP).

Calendar clock (seconds..year) with sensible defaults: 24-hour time format,
oscillator running, power-on CLKOUT/alarm/timer configuration left untouched.
Fixed I2C address 0x51. There is no battery switch-over; the chip is fed from
a single VDD supply. The same driver file is used on MicroPython,
CircuitPython, and Linux hosts.

Weekday convention: this driver adopts the datasheet's own suggested
assignment, 0=Sunday..6=Saturday — the chip's Weekdays register is a
free-running 0-6 counter with no hardware-enforced meaning.

Args:
    connection: RegisterConnection (I²C or SMBus) pointing at the device (fixed
        address 0x51).
"""

try:
    import threading as _threading
    _LINUX = True
except ImportError:
    _LINUX = False


I2C_ADDRESS = 0x51


def _bcd_to_int(bcd):
    return ((bcd >> 4) & 0x0F) * 10 + (bcd & 0x0F)


def _int_to_bcd(value):
    return ((value // 10) << 4) | (value % 10)


class PCF85063AMinimal:
    """PCF85063A low-power I2C real-time clock — minimal interface.

    Reads the connection once as a bus check (the chip has no identity
    register) and forces 24-hour mode with the clock running. No time
    register is written and the chip is not reset.

    Args:
        connection: RegisterConnection (I²C or SMBus) pointing at the device.
    """

    _REG_CONTROL_1     = 0x00
    _REG_CONTROL_2     = 0x01
    _REG_OFFSET        = 0x02
    _REG_RAM           = 0x03
    _REG_SECONDS       = 0x04
    _REG_MINUTES       = 0x05
    _REG_HOURS         = 0x06
    _REG_DAYS          = 0x07
    _REG_WEEKDAYS      = 0x08
    _REG_MONTHS        = 0x09
    _REG_YEARS         = 0x0A
    _REG_SECOND_ALARM  = 0x0B
    _REG_MINUTE_ALARM  = 0x0C
    _REG_HOUR_ALARM    = 0x0D
    _REG_DAY_ALARM     = 0x0E
    _REG_WEEKDAY_ALARM = 0x0F
    _REG_TIMER_VALUE   = 0x10
    _REG_TIMER_MODE    = 0x11

    # CONTROL_1 (0x00) bits.
    _C1_EXT_TEST = 0x80
    _C1_STOP     = 0x20
    _C1_SR       = 0x10
    _C1_CIE      = 0x04
    _C1_12_24    = 0x02
    _C1_CAP_SEL  = 0x01

    # CONTROL_2 (0x01) bits.
    _C2_AIE   = 0x80
    _C2_AF    = 0x40
    _C2_MI    = 0x20
    _C2_HMI   = 0x10
    _C2_TF    = 0x08
    _C2_COF   = 0x07
    _C2_FLAGS = 0x48  # AF | TF (write 0 clears, 1 keeps)

    # TIMER_MODE (0x11) bits.
    _TM_TCF_MASK = 0x18
    _TM_TE       = 0x04
    _TM_TIE      = 0x02
    _TM_TI_TP    = 0x01

    _SECONDS_OS = 0x80

    def __init__(self, connection):
        self._connection = connection
        self._read_reg(self._REG_CONTROL_1)  # presence check (no identity register)
        ctrl1 = self._read_control_1()
        self._write_reg(self._REG_CONTROL_1, ctrl1 & ~(self._C1_STOP | self._C1_12_24) & 0xFF)

    def _write_reg(self, reg, value):
        self._connection.write_reg(reg, value & 0xFF)

    def _read_reg(self, reg):
        return self._connection.read_reg(reg, 1)[0]

    def _write_regs(self, start_reg, data):
        self._connection.write_reg(start_reg, bytes(data))

    def _read_regs(self, start_reg, length):
        return self._connection.read_reg(start_reg, length)

    def _read_control_1(self):
        # SR always reads 0; mask it so a read-modify-write never resets.
        return self._read_reg(self._REG_CONTROL_1) & ~self._C1_SR & 0xFF

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

        Raises:
            ValueError: If year is outside 2000-2099.
        """
        if not 2000 <= year <= 2099:
            raise ValueError("year must be 2000-2099")
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


# Interrupt source bits.
SOURCE_ALARM      = 0x01
SOURCE_TIMER      = 0x02
SOURCE_MINUTE     = 0x04
SOURCE_HALF_MINUTE = 0x08
SOURCE_CORRECTION = 0x10

_SOURCE_CLOCKS = {
    '4096hz': 0x00,
    '64hz':   0x01,
    '1hz':    0x02,
    '1_60hz': 0x03,
}

_CLKOUT_COF = {
    32768: 0x00,
    16384: 0x01,
    8192:  0x02,
    4096:  0x03,
    2048:  0x04,
    1024:  0x05,
    1:     0x06,
}


class PCF85063AFull(PCF85063AMinimal):
    """PCF85063A full interface — extends Minimal with the five-field alarm,
    the countdown timer, minute / half-minute interrupts, programmable
    CLKOUT, offset calibration, oscillator-load selection, oscillator-stop
    detection, clock stop/start, the free RAM byte, software reset and the
    interrupt API.

    The INT pin is open-drain and active-low; CLKOUT is a separate pin.

    Args:
        connection: RegisterConnection (I²C or SMBus) pointing at the device.
    """

    def __init__(self, connection):
        super().__init__(connection)
        self._callback = None
        self._int_pin_used = None
        self._poll_stop = False
        self._poll_thread = None

    def _update_control_1(self, clear_mask, set_bits):
        ctrl1 = self._read_control_1()
        self._write_reg(self._REG_CONTROL_1, (ctrl1 & ~clear_mask & 0xFF) | set_bits)

    def _update_control_2(self, clear_mask, set_bits):
        # AF and TF are written 1 (unchanged) unless explicitly cleared
        # through clear_mask (AND semantics: write 0 clears, 1 keeps).
        reg = self._read_reg(self._REG_CONTROL_2)
        value = (reg & ~clear_mask & 0xFF) | set_bits
        self._write_reg(self._REG_CONTROL_2, value | (self._C2_FLAGS & ~clear_mask & 0xFF))

    def _update_timer_mode(self, clear_mask, set_bits):
        reg = self._read_reg(self._REG_TIMER_MODE)
        self._write_reg(self._REG_TIMER_MODE, ((reg & ~clear_mask) | set_bits) & 0x1F)

    def oscillator_stopped(self):
        """Read the OS flag (bit 7 of Seconds).

        Returns:
            bool: True means timekeeping may be invalid; cleared by set_datetime()
            or clear_oscillator_stop().
        """
        return bool(self._read_reg(self._REG_SECONDS) & self._SECONDS_OS)

    def clear_oscillator_stop(self):
        """Clear OS, keeping the current seconds value.

        If oscillator_stopped() is still True afterwards the oscillator is
        not running (supply or crystal problem).
        """
        sec = self._read_reg(self._REG_SECONDS)
        self._write_reg(self._REG_SECONDS, sec & 0x7F)

    def stop_clock(self):
        """Freeze the clock (STOP=1); the divider chain is held in reset."""
        self._update_control_1(0, self._C1_STOP)

    def start_clock(self):
        """Release the clock (STOP=0)."""
        self._update_control_1(self._C1_STOP, 0)

    def get_alarm(self):
        """Decode the alarm registers (0x0B-0x0F).

        Returns:
            tuple: (second, minute, hour, day, weekday) — each int, or None
            if that field is disabled (AEN_x=1).
        """
        buf = self._read_regs(self._REG_SECOND_ALARM, 5)
        second  = None if buf[0] & 0x80 else _bcd_to_int(buf[0] & 0x7F)
        minute  = None if buf[1] & 0x80 else _bcd_to_int(buf[1] & 0x7F)
        hour    = None if buf[2] & 0x80 else _bcd_to_int(buf[2] & 0x3F)
        day     = None if buf[3] & 0x80 else _bcd_to_int(buf[3] & 0x3F)
        weekday = None if buf[4] & 0x80 else buf[4] & 0x07
        return (second, minute, hour, day, weekday)

    def set_alarm(self, second=None, minute=None, hour=None, day=None, weekday=None):
        """Write the alarm registers (0x0B-0x0F) and clear AF.

        A field left None is disabled (AEN_x=1); a value enables it
        (AEN_x=0). The alarm fires when every enabled field matches; an alarm
        with all fields None never fires.

        Args:
            second: 0-59, or None.
            minute: 0-59, or None.
            hour: 0-23, or None.
            day: Day of month 1-31, or None.
            weekday: 0=Sunday..6=Saturday, or None.
        """
        buf = bytes([
            0x80 if second is None else _int_to_bcd(second) & 0x7F,
            0x80 if minute is None else _int_to_bcd(minute) & 0x7F,
            0x80 if hour is None else _int_to_bcd(hour) & 0x3F,
            0x80 if day is None else _int_to_bcd(day) & 0x3F,
            0x80 if weekday is None else weekday & 0x07,
        ])
        self._write_regs(self._REG_SECOND_ALARM, buf)
        self._update_control_2(self._C2_AF, 0)

    def configure_timer(self, value, source_clock, pulsed=False):
        """Configure and start the countdown timer.

        Disables the timer first so Timer_value can be changed safely, then
        loads the value, source clock and mode and sets TE. Does not set TIE
        — call enable_interrupt(SOURCE_TIMER) to route it to INT.

        Args:
            value: Countdown value, 1-255.
            source_clock: "4096hz", "64hz", "1hz" or "1_60hz".
            pulsed: Pulsed (True) or level (False, INT follows TF) interrupt.
        """
        self._update_timer_mode(self._TM_TE, 0)
        self._write_reg(self._REG_TIMER_VALUE, value)
        self._update_timer_mode(self._TM_TCF_MASK | self._TM_TI_TP,
                                (_SOURCE_CLOCKS[source_clock] << 3)
                                | (self._TM_TI_TP if pulsed else 0)
                                | self._TM_TE)

    def disable_timer(self):
        """Stop the timer (TE=0, TIE=0) and select the 1/60 Hz clock for power saving."""
        self._update_timer_mode(self._TM_TE | self._TM_TIE | self._TM_TCF_MASK,
                                self._TM_TCF_MASK)

    def read_timer(self):
        """Read the live countdown value.

        Reads until two consecutive reads agree, since the value may change
        during a read while the timer runs.

        Returns:
            int: Current counter value, 0-255 (not the originally loaded one).
        """
        prev = self._read_reg(self._REG_TIMER_VALUE)
        while True:
            cur = self._read_reg(self._REG_TIMER_VALUE)
            if cur == prev:
                return cur
            prev = cur

    def set_minute_interrupt(self, mode):
        """Select the minute / half-minute interrupt (requires offset mode normal).

        Args:
            mode: "off", "minute" or "half_minute".
        """
        bits = {'off': 0, 'minute': self._C2_MI, 'half_minute': self._C2_HMI}[mode]
        self._update_control_2(self._C2_MI | self._C2_HMI, bits)

    def set_clock_output(self, frequency_hz):
        """Drive CLKOUT at the given frequency.

        Args:
            frequency_hz: One of 32768, 16384, 8192, 4096, 2048, 1024, 1 Hz.
        """
        self._update_control_2(self._C2_COF, _CLKOUT_COF[frequency_hz])

    def disable_clock_output(self):
        """Disable CLKOUT (COF=111, pin low) to save power."""
        self._update_control_2(self._C2_COF, self._C2_COF)

    def get_offset(self):
        """Read the clock-offset calibration register.

        Returns:
            tuple: (offset, mode) — offset int -64..+63 LSB (4.34 ppm per LSB
            in "normal" mode, 4.069 ppm in "course" mode); mode "normal" or "course".
        """
        raw = self._read_reg(self._REG_OFFSET)
        offset = raw & 0x7F
        if offset & 0x40:
            offset -= 128
        return (offset, 'course' if raw & 0x80 else 'normal')

    def set_offset(self, offset, mode='normal'):
        """Write the clock-offset calibration register.

        Args:
            offset: Two's-complement correction, -64 to 63 LSB.
            mode: "normal" (correction every two hours, 4.34 ppm/LSB) or
                "course" (every four minutes, 4.069 ppm/LSB, higher power).
        """
        self._write_reg(self._REG_OFFSET,
                        (0x80 if mode == 'course' else 0) | (offset & 0x7F))

    def set_oscillator_load(self, picofarads):
        """Select the oscillator load capacitance (CAP_SEL).

        Args:
            picofarads: 7 or 12.5 — match the crystal's specified load.
        """
        if picofarads not in (7, 12.5):
            raise ValueError("picofarads must be 7 or 12.5")
        self._update_control_1(self._C1_CAP_SEL, self._C1_CAP_SEL if picofarads == 12.5 else 0)

    def read_ram(self):
        """Read the free general-purpose RAM byte.

        Returns:
            int: 0-255.
        """
        return self._read_reg(self._REG_RAM)

    def write_ram(self, value):
        """Write the free general-purpose RAM byte.

        Args:
            value: 0-255.
        """
        self._write_reg(self._REG_RAM, value)

    def software_reset(self):
        """Send the software-reset sequence (0x58 to Control_1).

        Resets ALL registers to their power-on values, including the time
        (2000-01-01 00:00:00, OS=1). Run it before set_datetime(), never as
        a routine.
        """
        self._write_reg(self._REG_CONTROL_1, 0x58)

    def on_interrupt(self, callback, int_pin=None):
        """Subscribe to interrupts on the INT pin (falling edge).

        Delivery: if int_pin is given, it is wired directly. Otherwise falls
        back to connection.int_pin, or a 5 ms polling thread on Linux if
        neither is available.

        Args:
            callback: Callable(status: int) — called with the pre-clear
                status mask; test it against SOURCE_ALARM / SOURCE_TIMER.
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
        """Read Control_2, clear the set AF/TF flags (enable bits and COF
        preserved), and return the pre-clear status.

        Returns:
            int: Status mask — SOURCE_ALARM and/or SOURCE_TIMER (the timer,
            minute and half-minute sources all report as SOURCE_TIMER).
        """
        ctrl2 = self._read_reg(self._REG_CONTROL_2)
        status = 0
        if ctrl2 & self._C2_AF:
            status |= SOURCE_ALARM
        if ctrl2 & self._C2_TF:
            status |= SOURCE_TIMER
        if status:
            # Write 0 only to the flags seen set, 1 to the others, so a flag
            # that sets between the read and this write is not lost.
            keep = self._C2_FLAGS & ~ctrl2 & 0xFF
            self._write_reg(self._REG_CONTROL_2, (ctrl2 & ~self._C2_FLAGS & 0xFF) | keep)
        return status

    def enable_interrupt(self, source):
        """Enable one or more interrupt sources.

        SOURCE_TIMER sets TIE (call configure_timer() first); SOURCE_MINUTE /
        SOURCE_HALF_MINUTE set MI / HMI; SOURCE_CORRECTION sets CIE.

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
        bits2 = ((self._C2_AIE if source & SOURCE_ALARM else 0)
                 | (self._C2_MI if source & SOURCE_MINUTE else 0)
                 | (self._C2_HMI if source & SOURCE_HALF_MINUTE else 0))
        if bits2:
            self._update_control_2(0 if enable else bits2, bits2 if enable else 0)
        if source & SOURCE_TIMER:
            self._update_timer_mode(0 if enable else self._TM_TIE,
                                    self._TM_TIE if enable else 0)
        if source & SOURCE_CORRECTION:
            self._update_control_1(0 if enable else self._C1_CIE,
                                   self._C1_CIE if enable else 0)
