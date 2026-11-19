import time


class PCA9685Minimal:
    """PCA9685 16-channel, 12-bit PWM LED/servo controller — minimal interface.

    Dims LEDs / drives servos on 16 channels with one call each. Construction
    puts the chip to sleep, sets the PWM frequency to 1 kHz (PRE_SCALE = 5),
    then wakes it with auto-increment on and the ALLCALL address off and waits
    1 ms for the oscillator. Outputs are totem-pole, not inverted, and change on
    the I2C STOP. All channels keep their power-on state (off).

    Args:
        connection: RegisterConnection (I2C, address 0x40-0x6F or 0x71-0x77).
    """

    ADDRESS = 0x40
    CHANNELS = 16

    _REG_MODE1 = 0x00
    _REG_MODE2 = 0x01
    _REG_SUBADR1 = 0x02
    _REG_ALLCALLADR = 0x05
    _REG_LED0 = 0x06
    _REG_ALL_LED = 0xFA
    _REG_PRE_SCALE = 0xFE

    _MODE1_RESTART = 0x80
    _MODE1_EXTCLK = 0x40
    _MODE1_AI = 0x20
    _MODE1_SLEEP = 0x10
    _MODE1_ALLCALL = 0x01
    _FULL = 0x10

    def __init__(self, connection):
        self._connection = connection
        self._connection.write_reg(self._REG_MODE1, self._MODE1_AI | self._MODE1_SLEEP)
        self._connection.write_reg(self._REG_PRE_SCALE, 5)
        self._connection.write_reg(self._REG_MODE1, self._MODE1_AI)
        time.sleep(0.001)

    @staticmethod
    def _check_channel(channel):
        if not 0 <= channel < 16:
            raise ValueError('channel must be 0-15')

    @staticmethod
    def _duty_bytes(duty):
        if not 0.0 <= duty <= 1.0:
            raise ValueError('duty must be 0.0-1.0')
        off = int(duty * 4096 + 0.5)
        if off <= 0:
            return bytes([0, 0, 0, 0x10])
        if off >= 4096:
            return bytes([0, 0x10, 0, 0])
        return bytes([0, 0, off & 0xFF, off >> 8])

    def set_duty(self, channel, duty):
        """Set the duty cycle of one channel (ON = 0, OFF = round(duty * 4096)).

        0.0 writes full OFF and 1.0 writes full ON, so no glitch pulses occur.

        Args:
            channel: 0-15.
            duty: 0.0-1.0.

        Raises:
            ValueError: If an argument is out of range.
        """
        self._check_channel(channel)
        self._connection.write_reg(self._REG_LED0 + 4 * channel, self._duty_bytes(duty))


class PCA9685Full(PCA9685Minimal):
    """PCA9685 full interface — phase-shifted PWM, frequency, servo pulses, modes, addresses.

    Args:
        connection: RegisterConnection (I2C, address 0x40-0x6F or 0x71-0x77).
    """

    OFF_LOW = 0
    OFF_HIGH_OR_HIGHZ = 1
    OFF_HIGHZ = 2

    def _mode1(self):
        return self._connection.read_reg(self._REG_MODE1, 1)[0]

    @staticmethod
    def _check_count(value, name):
        if not 0 <= value <= 4095:
            raise ValueError(name + ' must be 0-4095')

    def set_pwm(self, channel, on, off):
        """Write the ON and OFF counts of one channel in one transaction (phase-shifted PWM).

        Args:
            channel: 0-15.
            on: 0-4095 counter value at which the output goes high.
            off: 0-4095 counter value at which the output goes low (must differ from on).

        Raises:
            ValueError: If an argument is out of range or on == off.
        """
        self._check_channel(channel)
        self._check_count(on, 'on')
        self._check_count(off, 'off')
        if on == off:
            raise ValueError('on must differ from off')
        self._connection.write_reg(self._REG_LED0 + 4 * channel,
                                   bytes([on & 0xFF, on >> 8, off & 0xFF, off >> 8]))

    def get_pwm(self, channel):
        """Read the ON and OFF counts of one channel (without the full-ON/OFF flags).

        Args:
            channel: 0-15.

        Returns:
            tuple: (on, off), each 0-4095.
        """
        self._check_channel(channel)
        d = self._connection.read_reg(self._REG_LED0 + 4 * channel, 4)
        return ((d[1] & 0x0F) << 8) | d[0], ((d[3] & 0x0F) << 8) | d[2]

    def set_full_on(self, channel):
        """Force a channel permanently high (full-ON bit set, full-OFF bit cleared).

        Args:
            channel: 0-15.
        """
        self._check_channel(channel)
        self._connection.write_reg(self._REG_LED0 + 4 * channel, bytes([0, 0x10, 0, 0]))

    def set_full_off(self, channel):
        """Force a channel permanently low (full-OFF bit set; it wins over full ON).

        Args:
            channel: 0-15.
        """
        self._check_channel(channel)
        self._connection.write_reg(self._REG_LED0 + 4 * channel, bytes([0, 0, 0, 0x10]))

    def set_all_duty(self, duty):
        """Set the same duty cycle on all 16 channels (ALL_LED registers).

        Args:
            duty: 0.0-1.0.

        Raises:
            ValueError: If duty is out of range.
        """
        self._connection.write_reg(self._REG_ALL_LED, self._duty_bytes(duty))

    def set_all_pwm(self, on, off):
        """Set the same ON and OFF counts on all 16 channels.

        Args:
            on: 0-4095.
            off: 0-4095 (must differ from on).

        Raises:
            ValueError: If an argument is out of range or on == off.
        """
        self._check_count(on, 'on')
        self._check_count(off, 'off')
        if on == off:
            raise ValueError('on must differ from off')
        self._connection.write_reg(self._REG_ALL_LED, bytes([on & 0xFF, on >> 8, off & 0xFF, off >> 8]))

    def set_frequency(self, hz, osc_hz=25000000.0):
        """Set the PWM frequency (PRE_SCALE = round(osc_hz / (4096 * hz)) - 1, clamped 3-255).

        Puts the chip to sleep, writes PRE_SCALE, wakes it, waits 500 us and
        restarts the channels if they were running before.

        Args:
            hz: Wanted PWM frequency in Hz (24-1526 at 25 MHz).
            osc_hz: Oscillator frequency in Hz (25 MHz internal, or the EXTCLK frequency).

        Raises:
            ValueError: If hz or osc_hz is not positive.
        """
        if hz <= 0 or osc_hz <= 0:
            raise ValueError('hz and osc_hz must be positive')
        prescale = int(osc_hz / (4096.0 * hz) + 0.5) - 1
        prescale = max(3, min(255, prescale))
        old = self._mode1()
        awake = old & ~(self._MODE1_RESTART | self._MODE1_SLEEP) & 0xFF
        self._connection.write_reg(self._REG_MODE1, (old & 0x7F) | self._MODE1_SLEEP)
        self._connection.write_reg(self._REG_PRE_SCALE, prescale)
        self._connection.write_reg(self._REG_MODE1, awake)
        time.sleep(0.0005)
        if old & self._MODE1_RESTART:
            self._connection.write_reg(self._REG_MODE1, awake | self._MODE1_RESTART)

    def get_frequency(self, osc_hz=25000000.0):
        """Read PRE_SCALE and return the PWM frequency.

        Args:
            osc_hz: Oscillator frequency in Hz.

        Returns:
            float: osc_hz / (4096 * (PRE_SCALE + 1)) in Hz.
        """
        return osc_hz / (4096.0 * (self._connection.read_reg(self._REG_PRE_SCALE, 1)[0] + 1))

    def set_pulse_width(self, channel, seconds):
        """Set a pulse width in seconds (servo/ESC mode): OFF = round(seconds * frequency * 4096).

        Uses the frequency currently programmed in PRE_SCALE (25 MHz oscillator).

        Args:
            channel: 0-15.
            seconds: Pulse width, 0 up to one PWM period.

        Raises:
            ValueError: If channel is out of range or seconds is negative.
        """
        self._check_channel(channel)
        if seconds < 0:
            raise ValueError('seconds must be >= 0')
        self.set_duty(channel, seconds * self.get_frequency())

    def sleep(self):
        """Enter low-power mode (MODE1.SLEEP = 1, oscillator off; outputs stop)."""
        self._connection.write_reg(self._REG_MODE1, (self._mode1() & 0x7F) | self._MODE1_SLEEP)

    def wake(self):
        """Leave low-power mode, wait 500 us and restart channels that were running."""
        old = self._mode1()
        self._connection.write_reg(self._REG_MODE1, old & ~(self._MODE1_RESTART | self._MODE1_SLEEP) & 0xFF)
        time.sleep(0.0005)
        if old & self._MODE1_RESTART:
            self._connection.write_reg(self._REG_MODE1,
                                       (old & ~self._MODE1_SLEEP & 0xFF) | self._MODE1_RESTART)

    def restart(self):
        """Run the datasheet restart sequence (read MODE1, clear SLEEP, wait 500 us, write RESTART)."""
        old = self._mode1()
        self._connection.write_reg(self._REG_MODE1, old & ~(self._MODE1_RESTART | self._MODE1_SLEEP) & 0xFF)
        time.sleep(0.0005)
        self._connection.write_reg(self._REG_MODE1,
                                   (old & ~self._MODE1_SLEEP & 0xFF) | self._MODE1_RESTART)

    def set_output_mode(self, open_drain=False, invert=False, off_state=0):
        """Configure MODE2: output driver, inversion and the output state while OE = 1.

        Args:
            open_drain: True for open-drain (OUTDRV = 0), False for totem-pole.
            invert: True to invert the output logic (INVRT).
            off_state: OFF_LOW (LEDn = 0), OFF_HIGH_OR_HIGHZ (LEDn = 1 / high-Z) or OFF_HIGHZ.

        Raises:
            ValueError: If off_state is not 0, 1 or 2.
        """
        if off_state not in (0, 1, 2):
            raise ValueError('off_state must be OFF_LOW, OFF_HIGH_OR_HIGHZ or OFF_HIGHZ')
        mode2 = (0x10 if invert else 0) | (0 if open_drain else 0x04) | (2 if off_state == 2 else off_state)
        old = self._connection.read_reg(self._REG_MODE2, 1)[0]
        self._connection.write_reg(self._REG_MODE2, (old & 0x08) | mode2)

    def set_update_on_ack(self, enabled):
        """Select when outputs change: on ACK (True, MODE2.OCH = 1) or on STOP (False, default).

        Args:
            enabled: True to update on ACK.
        """
        old = self._connection.read_reg(self._REG_MODE2, 1)[0]
        self._connection.write_reg(self._REG_MODE2, (old | 0x08) if enabled else (old & ~0x08 & 0xFF))

    def use_external_clock(self):
        """Switch to the EXTCLK pin as clock (sticky until power cycle / software reset).

        Afterwards pass the external clock frequency as osc_hz to set_frequency() / get_frequency().
        """
        old = self._mode1()
        sleeping = (old & 0x7F) | self._MODE1_SLEEP
        self._connection.write_reg(self._REG_MODE1, sleeping)
        self._connection.write_reg(self._REG_MODE1, sleeping | self._MODE1_EXTCLK)
        self._connection.write_reg(self._REG_MODE1, (sleeping | self._MODE1_EXTCLK) & ~self._MODE1_SLEEP & 0xFF)
        time.sleep(0.0005)

    def set_allcall(self, enabled, address=0x70):
        """Enable or disable the LED All Call address and set its value.

        Args:
            enabled: True to respond to the All Call address.
            address: 7-bit address (default 0x70).

        Raises:
            ValueError: If address is not 7 bit.
        """
        if not 0 <= address <= 0x7F:
            raise ValueError('address must be 0-127')
        self._connection.write_reg(self._REG_ALLCALLADR, address << 1)
        old = self._mode1()
        self._connection.write_reg(self._REG_MODE1, (old & 0x7E) | (1 if enabled else 0))

    def set_subaddress(self, index, address, enabled=True):
        """Program and enable/disable one of the three LED Sub Call addresses.

        Args:
            index: 1-3.
            address: 7-bit address.
            enabled: True to respond to the sub address.

        Raises:
            ValueError: If index or address is out of range.
        """
        if not 1 <= index <= 3:
            raise ValueError('index must be 1-3')
        if not 0 <= address <= 0x7F:
            raise ValueError('address must be 0-127')
        self._connection.write_reg(self._REG_SUBADR1 + index - 1, address << 1)
        mask = 1 << (4 - index)
        old = self._mode1() & 0x7F
        self._connection.write_reg(self._REG_MODE1, (old | mask) if enabled else (old & ~mask & 0xFF))

    def reset(self):
        """Return the chip registers to their power-on values by writing the documented defaults.

        Writes MODE1 = 0x11 (asleep, ALLCALL on), MODE2 = 0x04, PRE_SCALE = 0x1E and switches
        all outputs off. Does not issue the general-call software reset (see software_reset()).
        """
        c = self._connection
        c.write_reg(self._REG_MODE1, 0x10)
        c.write_reg(self._REG_PRE_SCALE, 0x1E)
        c.write_reg(self._REG_MODE2, 0x04)
        c.write_reg(self._REG_ALL_LED, bytes([0, 0, 0, 0x10]))
        c.write_reg(self._REG_SUBADR1, bytes([0xE2, 0xE4, 0xE8, 0xE0]))
        c.write_reg(self._REG_MODE1, 0x11)

    @staticmethod
    def software_reset(general_call):
        """Send the Software Reset call (0x06) over a connection bound to the general call address 0x00.

        Resets every PCA9685 on the bus. Wait at least 500 us before using the chips again.

        Args:
            general_call: Connection bound to I2C address 0x00.
        """
        general_call.write(bytes([0x06]))

    def read_mode(self):
        """Read the raw mode registers.

        Returns:
            tuple: (mode1, mode2).
        """
        d = self._connection.read_reg(self._REG_MODE1, 2)
        return d[0], d[1]
