try:
    import threading as _threading
    _LINUX = True
except ImportError:
    _LINUX = False

import time


class LM25066Minimal:
    """LM25066 hot-swap controller / PMBus power monitor - minimal interface.

    Reads the monitored rail: input voltage, output voltage, input current,
    input power and MOSFET temperature. The constructor performs no bus access
    and never writes the chip (OPERATION, GATE_MASK and DEVICE_SETUP are left
    alone). Telemetry is PMBus DIRECT; the current and power coefficients
    depend on the sense resistor and the CL pin setting.

    Args:
        connection: RegisterConnection (I2C/SMBus, 1-byte command, address
            0x10-0x17, 0x40-0x47 or 0x50-0x5A).
        r_sense_mohm: Sense resistor in milliohms (default 1.0).
        current_limit_high: False = CL pin at GND (25 mV), True = CL pin at
            VDD (46 mV).
    """

    _CMD_OPERATION = 0x01
    _CMD_CLEAR_FAULTS = 0x03
    _CMD_CAPABILITY = 0x19
    _CMD_VOUT_UV_WARN = 0x43
    _CMD_OT_FAULT = 0x4F
    _CMD_OT_WARN = 0x51
    _CMD_VIN_OV_WARN = 0x57
    _CMD_VIN_UV_WARN = 0x58
    _CMD_STATUS_BYTE = 0x78
    _CMD_STATUS_WORD = 0x79
    _CMD_STATUS_VOUT = 0x7A
    _CMD_STATUS_INPUT = 0x7C
    _CMD_STATUS_TEMP = 0x7D
    _CMD_STATUS_CML = 0x7E
    _CMD_STATUS_MFR = 0x80
    _CMD_READ_VIN = 0x88
    _CMD_READ_VOUT = 0x8B
    _CMD_READ_TEMP = 0x8D
    _CMD_MFR_ID = 0x99
    _CMD_MFR_MODEL = 0x9A
    _CMD_MFR_REVISION = 0x9B
    _CMD_READ_VAUX = 0xD0
    _CMD_READ_IIN = 0xD1
    _CMD_READ_PIN = 0xD2
    _CMD_IIN_WARN = 0xD3
    _CMD_PIN_WARN = 0xD4
    _CMD_READ_PIN_PEAK = 0xD5
    _CMD_CLEAR_PIN_PEAK = 0xD6
    _CMD_GATE_MASK = 0xD7
    _CMD_ALERT_MASK = 0xD8
    _CMD_DEVICE_SETUP = 0xD9
    _CMD_BLOCK_READ = 0xDA
    _CMD_SAMPLES_FOR_AVG = 0xDB
    _CMD_READ_AVG_VIN = 0xDC
    _CMD_READ_AVG_VOUT = 0xDD
    _CMD_READ_AVG_IIN = 0xDE
    _CMD_READ_AVG_PIN = 0xDF
    _CMD_BLACK_BOX = 0xE0
    _CMD_DIAGNOSTIC = 0xE1
    _CMD_AVG_BLOCK_READ = 0xE2

    # (m, b, R) per quantity; current/power slopes are per milliohm of RS.
    _VOLT = (22070.0, -1800.0, -2)
    _VAUX = (3546.0, -3.0, 0)
    _TEMP = (16000.0, 0.0, -3)

    def __init__(self, connection, r_sense_mohm=1.0, current_limit_high=False):
        self._connection = connection
        self._rs = r_sense_mohm
        self._set_cl(current_limit_high)

    def _set_cl(self, high):
        self._cl_high = bool(high)
        rs = self._rs
        if self._cl_high:
            self._amp = (6854.0 * rs, -3100.0, -2)
            self._watt = (369.0 * rs, -1900.0, -2)
        else:
            self._amp = (13661.0 * rs, -5200.0, -2)
            self._watt = (736.0 * rs, -3300.0, -2)

    @staticmethod
    def _from_y(y, coef):
        m, b, r = coef
        return (y * (10.0 ** (-r)) - b) / m

    @staticmethod
    def _to_y(x, coef):
        m, b, r = coef
        y = int(round((m * x + b) * (10.0 ** r)))
        return max(0, min(0x0FFE, y))

    def _read_word(self, cmd):
        raw = self._connection.read_reg(cmd, 2)
        return raw[0] | (raw[1] << 8)

    def _write_word(self, cmd, value):
        self._connection.write_reg(cmd, bytes([value & 0xFF, (value >> 8) & 0xFF]))

    def _read_byte(self, cmd):
        return self._connection.read_reg(cmd, 1)[0]

    def _read_y(self, cmd):
        return self._read_word(cmd) & 0x0FFF

    @staticmethod
    def _signed16(word):
        return word - 0x10000 if word & 0x8000 else word

    def _temp_c(self, word):
        return self._signed16(word) / 16.0

    def voltage_in(self):
        """Read the input voltage (READ_VIN).

        Returns:
            float: VIN in volts.
        """
        return self._from_y(self._read_y(self._CMD_READ_VIN), self._VOLT)

    def voltage_out(self):
        """Read the output voltage (READ_VOUT).

        Returns:
            float: VOUT in volts.
        """
        return self._from_y(self._read_y(self._CMD_READ_VOUT), self._VOLT)

    def current(self):
        """Read the instantaneous input current (MFR_READ_IIN).

        Returns:
            float: Input current in amperes.
        """
        return self._from_y(self._read_y(self._CMD_READ_IIN), self._amp)

    def power(self):
        """Read the instantaneous input power (MFR_READ_PIN).

        Returns:
            float: Input power in watts.
        """
        return self._from_y(self._read_y(self._CMD_READ_PIN), self._watt)

    def temperature(self):
        """Read the external MOSFET temperature (READ_TEMPERATURE_1).

        Returns:
            float: Temperature in degrees Celsius.
        """
        return self._temp_c(self._read_word(self._CMD_READ_TEMP))


class LM25066Full(LM25066Minimal):
    """LM25066 full interface - averaging, limits, status, control, alerts.

    WARNING: operation_off() cuts the load rail and set_gate_mask() can stop
    hardware faults from switching the MOSFET off (which may destroy it).
    Neither is ever called by the constructor.

    Interrupt source constants are ALERT_MASK bit positions (1 = masked).

    Args:
        connection: RegisterConnection (I2C/SMBus).
        r_sense_mohm: Sense resistor in milliohms.
        current_limit_high: CL pin at VDD (46 mV) when True.
    """

    SOURCE_VOUT_UV_WARN = 0x8000
    SOURCE_IIN_WARN = 0x4000
    SOURCE_VIN_UV_WARN = 0x2000
    SOURCE_VIN_OV_WARN = 0x1000
    SOURCE_POWER_GOOD = 0x0800
    SOURCE_OT_WARN = 0x0400
    SOURCE_PIN_OP_WARN = 0x0100
    SOURCE_MOSFET_SHORTED = 0x0040
    SOURCE_VIN_UV_FAULT = 0x0020
    SOURCE_VIN_OV_FAULT = 0x0010
    SOURCE_IIN_FAULT = 0x0008
    SOURCE_OT_FAULT = 0x0004
    SOURCE_CML_FAULT = 0x0002
    SOURCE_CB_FAULT = 0x0001

    _GATE_MASK_VALID = 0x34

    def __init__(self, connection, r_sense_mohm=1.0, current_limit_high=False):
        self._callback = None
        self._int_source = None
        self._poll_stop = True
        self._poll_thread = None
        super().__init__(connection, r_sense_mohm, current_limit_high)

    def _block(self, cmd):
        raw = self._connection.read_reg(cmd, 13)
        if raw[0] != 12:
            raise ValueError('LM25066 block read count %d != 12' % raw[0])
        w = [raw[1 + 2 * i] | (raw[2 + 2 * i] << 8) for i in range(6)]
        return {
            'diagnostic': w[0],
            'current': self._from_y(w[1] & 0x0FFF, self._amp),
            'voltage_out': self._from_y(w[2] & 0x0FFF, self._VOLT),
            'voltage_in': self._from_y(w[3] & 0x0FFF, self._VOLT),
            'power': self._from_y(w[4] & 0x0FFF, self._watt),
            'temperature': self._temp_c(w[5]),
        }

    def _text(self, cmd, n):
        raw = self._connection.read_reg(cmd, n + 1)
        if raw[0] != n:
            raise ValueError('LM25066 block read count %d != %d' % (raw[0], n))
        return ''.join(chr(c) for c in raw[1:] if c != 0)

    def voltage_aux(self):
        """Read the auxiliary ADC input (READ_VAUX).

        Returns:
            float: Voltage at the VAUX pin in volts (0 ... 1.16 V).
        """
        return self._from_y(self._read_y(self._CMD_READ_VAUX), self._VAUX)

    def read_block(self):
        """Read the time-aligned BLOCK_READ telemetry.

        Returns:
            dict: diagnostic, current, voltage_out, voltage_in, power, temperature.
        """
        return self._block(self._CMD_BLOCK_READ)

    def read_average(self):
        """Read the averaged AVG_BLOCK_READ telemetry.

        Returns:
            dict: Same keys as read_block().
        """
        return self._block(self._CMD_AVG_BLOCK_READ)

    def black_box(self):
        """Read the snapshot latched at the first SMBA since clear_faults().

        Returns:
            dict: Same keys as read_block().
        """
        return self._block(self._CMD_BLACK_BOX)

    def voltage_in_avg(self):
        """Averaged VIN in volts."""
        return self._from_y(self._read_y(self._CMD_READ_AVG_VIN), self._VOLT)

    def voltage_out_avg(self):
        """Averaged VOUT in volts."""
        return self._from_y(self._read_y(self._CMD_READ_AVG_VOUT), self._VOLT)

    def current_avg(self):
        """Averaged input current in amperes."""
        return self._from_y(self._read_y(self._CMD_READ_AVG_IIN), self._amp)

    def power_avg(self):
        """Averaged input power in watts."""
        return self._from_y(self._read_y(self._CMD_READ_AVG_PIN), self._watt)

    def power_peak(self):
        """Peak input power since reset or clear_power_peak().

        Returns:
            float: Power in watts.
        """
        return self._from_y(self._read_y(self._CMD_READ_PIN_PEAK), self._watt)

    def clear_power_peak(self):
        """Reset the peak-power register (CLEAR_PIN_PEAK, send byte)."""
        self._connection.write_reg(self._CMD_CLEAR_PIN_PEAK, b'')

    def set_averaging(self, exponent):
        """Set the averaging window to 2**exponent samples.

        Args:
            exponent: AVGN, 0-12.

        Raises:
            ValueError: If exponent is outside 0-12.
        """
        if exponent < 0 or exponent > 12:
            raise ValueError('exponent must be 0-12')
        self._connection.write_reg(self._CMD_SAMPLES_FOR_AVG, bytes([exponent]))

    def get_averaging(self):
        """Read SAMPLES_FOR_AVG.

        Returns:
            int: AVGN exponent.
        """
        return self._read_byte(self._CMD_SAMPLES_FOR_AVG)

    def _set_limit(self, cmd, value, coef, disabled):
        y = disabled if value is None else self._to_y(value, coef)
        self._write_word(cmd, y)

    def _get_limit(self, cmd, coef, disabled, mask=0x0FFF):
        y = self._read_word(cmd) & mask
        if y == disabled:
            return None
        return self._from_y(y, coef)

    def set_vin_ov_warn(self, volts):
        """VIN over-voltage warning threshold in volts (None disables)."""
        self._set_limit(self._CMD_VIN_OV_WARN, volts, self._VOLT, 0x0FFF)

    def set_vin_uv_warn(self, volts):
        """VIN under-voltage warning threshold in volts (None disables)."""
        self._set_limit(self._CMD_VIN_UV_WARN, volts, self._VOLT, 0x0000)

    def set_vout_uv_warn(self, volts):
        """VOUT under-voltage warning threshold in volts (None disables)."""
        self._set_limit(self._CMD_VOUT_UV_WARN, volts, self._VOLT, 0x0000)

    def set_ot_warn(self, celsius):
        """Over-temperature warning threshold in Celsius (None disables)."""
        self._set_limit(self._CMD_OT_WARN, celsius, self._TEMP, 0x0FFF)

    def set_ot_fault(self, celsius):
        """Over-temperature fault threshold in Celsius (None disables)."""
        self._set_limit(self._CMD_OT_FAULT, celsius, self._TEMP, 0x0FFF)

    def set_iin_warn(self, amps):
        """Input over-current warning threshold in amperes (None disables)."""
        self._set_limit(self._CMD_IIN_WARN, amps, self._amp, 0x0FFF)

    def set_pin_warn(self, watts):
        """Input over-power warning threshold in watts (None disables)."""
        self._set_limit(self._CMD_PIN_WARN, watts, self._watt, 0x0FFF)

    def get_limits(self):
        """Read all warning/fault thresholds.

        Returns:
            dict: vin_ov_warn, vin_uv_warn, vout_uv_warn (V), ot_warn, ot_fault
            (Celsius), iin_warn (A), pin_warn (W); None where disabled.
        """
        return {
            'vin_ov_warn': self._get_limit(self._CMD_VIN_OV_WARN, self._VOLT, 0x0FFF),
            'vin_uv_warn': self._get_limit(self._CMD_VIN_UV_WARN, self._VOLT, 0x0000),
            'vout_uv_warn': self._get_limit(self._CMD_VOUT_UV_WARN, self._VOLT, 0x0000),
            'ot_warn': self._get_limit(self._CMD_OT_WARN, self._TEMP, 0x0FFF),
            'ot_fault': self._get_limit(self._CMD_OT_FAULT, self._TEMP, 0x0FFF),
            'iin_warn': self._get_limit(self._CMD_IIN_WARN, self._amp, 0x0FFF),
            'pin_warn': self._get_limit(self._CMD_PIN_WARN, self._watt, 0x0FFF),
        }

    def status_byte(self):
        """STATUS_BYTE."""
        return self._read_byte(self._CMD_STATUS_BYTE)

    def status_word(self):
        """STATUS_WORD."""
        return self._read_word(self._CMD_STATUS_WORD)

    def status_vout(self):
        """STATUS_VOUT."""
        return self._read_byte(self._CMD_STATUS_VOUT)

    def status_input(self):
        """STATUS_INPUT."""
        return self._read_byte(self._CMD_STATUS_INPUT)

    def status_temperature(self):
        """STATUS_TEMPERATURE."""
        return self._read_byte(self._CMD_STATUS_TEMP)

    def status_cml(self):
        """STATUS_CML."""
        return self._read_byte(self._CMD_STATUS_CML)

    def status_mfr(self):
        """STATUS_MFR_SPECIFIC."""
        return self._read_byte(self._CMD_STATUS_MFR)

    def diagnostic_word(self):
        """READ_DIAGNOSTIC_WORD (all faults and warnings)."""
        return self._read_word(self._CMD_DIAGNOSTIC)

    def clear_faults(self):
        """CLEAR_FAULTS (send byte). Does not switch the MOSFET on."""
        self._connection.write_reg(self._CMD_CLEAR_FAULTS, b'')

    def operation_on(self):
        """Switch the MOSFET on (OPERATION = 0x80). Switches the load."""
        self._connection.write_reg(self._CMD_OPERATION, bytes([0x80]))

    def operation_off(self):
        """Switch the MOSFET off (OPERATION = 0x00). CUTS THE LOAD RAIL."""
        self._connection.write_reg(self._CMD_OPERATION, bytes([0x00]))

    def is_on(self):
        """True if OPERATION reads 0x80 and STATUS_BYTE OFF bit is clear."""
        return self._read_byte(self._CMD_OPERATION) == 0x80 and \
            not (self._read_byte(self._CMD_STATUS_BYTE) & 0x40)

    def device_setup(self):
        """Read DEVICE_SETUP."""
        return self._read_byte(self._CMD_DEVICE_SETUP)

    def configure_device(self, retry_code=None, current_limit_high=None, cb_ratio_high=None):
        """Override the RETRY / CL / CB pin settings from software.

        Only the arguments given are overridden; the others stay under pin
        control. Updates the driver coefficients when current_limit_high is given.

        Args:
            retry_code: 0-7 (bits 7:5; 0 pin, 1 none, 2 = 1 retry, 3 = 2,
                4 = 4, 5 = 8, 6 = 16, 7 = unlimited), or None.
            current_limit_high: True = 46 mV, False = 25 mV, or None.
            cb_ratio_high: True = 3.6x, False = 1.8x, or None.

        Raises:
            ValueError: If retry_code is outside 0-7.
        """
        value = 0
        if retry_code is not None:
            if retry_code < 0 or retry_code > 7:
                raise ValueError('retry_code must be 0-7')
            value |= retry_code << 5
        if current_limit_high is not None:
            value |= 0x04
            if current_limit_high:
                value |= 0x10
        if cb_ratio_high is not None:
            value |= 0x02
            if cb_ratio_high:
                value |= 0x08
        self._connection.write_reg(self._CMD_DEVICE_SETUP, bytes([value]))
        if current_limit_high is not None:
            self._set_cl(current_limit_high)

    def set_gate_mask(self, mask):
        """Write GATE_MASK. DANGEROUS: can disable fault shut-off of the MOSFET.

        Args:
            mask: Bits 5, 4, 2 only.

        Raises:
            ValueError: If other bits are set.
        """
        if mask & ~self._GATE_MASK_VALID:
            raise ValueError('only GATE_MASK bits 5, 4, 2 may be set')
        self._connection.write_reg(self._CMD_GATE_MASK, bytes([mask]))

    def get_gate_mask(self):
        """Read GATE_MASK."""
        return self._read_byte(self._CMD_GATE_MASK)

    def capability(self):
        """Read CAPABILITY."""
        return self._read_byte(self._CMD_CAPABILITY)

    def manufacturer_id(self):
        """MFR_ID as a string."""
        return self._text(self._CMD_MFR_ID, 3)

    def model(self):
        """MFR_MODEL as a string."""
        return self._text(self._CMD_MFR_MODEL, 8)

    def revision(self):
        """MFR_REVISION as a string."""
        return self._text(self._CMD_MFR_REVISION, 2)

    def enable_interrupt(self, source):
        """Unmask alert source bit(s) in ALERT_MASK (read-modify-write)."""
        self._write_word(self._CMD_ALERT_MASK, self._read_word(self._CMD_ALERT_MASK) & ~source & 0xFFFF)

    def disable_interrupt(self, source):
        """Mask alert source bit(s) in ALERT_MASK (read-modify-write)."""
        self._write_word(self._CMD_ALERT_MASK, self._read_word(self._CMD_ALERT_MASK) | source)

    def on_interrupt(self, callback, int_pin=None):
        """Subscribe to SMBA (falling edge).

        ``callback(diagnostic_word)`` receives READ_DIAGNOSTIC_WORD. Delivery
        uses int_pin, else connection.int_pin, else a 1 s polling thread.

        Args:
            callback: Callable(diagnostic: int).
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
        """Read READ_DIAGNOSTIC_WORD (does not clear; call clear_faults()).

        Returns:
            int: Diagnostic word.
        """
        return self._read_word(self._CMD_DIAGNOSTIC)
