import struct
import time


class BMP280Minimal:
    """BMP280 piezo-resistive pressure + temperature sensor — minimal interface.

    Provides calibrated temperature (°C) and pressure (hPa) readings with no
    configuration beyond the transport. Default configuration: forced mode,
    oversampling ×1 for both channels, IIR filter off.

    The constructor reads and stores all 12 calibration trimming coefficients.
    Each measurement call triggers a single forced-mode conversion and returns
    the result.

    Args:
        transport: Configured I²C transport pointing at the device.
        addr: 7-bit I²C device address (default 0x76, alternate 0x77).
    """

    _REG_ID         = 0xD0
    _REG_RESET      = 0xE0
    _REG_STATUS     = 0xF3
    _REG_CTRL_MEAS = 0xF4
    _REG_CONFIG     = 0xF5
    _REG_CAL_START = 0x88
    _REG_DATA      = 0xF7

    _CHIP_ID          = 0x58
    _RESET_CMD        = 0xB6
    _CTRL_MEAS_DEFAULT = 0x29

    def __init__(self, transport, addr=0x76):
        self._transport = transport
        self._addr = addr
        self._t_fine = None
        self._load_calibration()
        self._write_ctrl_meas(self._CTRL_MEAS_DEFAULT)
        self._write_config(0x00)

    def _write_reg(self, reg, value):
        self._transport.write(bytes([reg, value]))

    def _read_reg(self, reg, n=1):
        self._transport.write(bytes([reg]))
        return self._transport.read(n)

    def _write_ctrl_meas(self, value):
        self._write_reg(self._REG_CTRL_MEAS, value)

    def _write_config(self, value):
        self._write_reg(self._REG_CONFIG, value)

    def _load_calibration(self):
        raw = self._read_reg(self._REG_CAL_START, 24)
        self._dig_T1 = struct.unpack('<H', raw[0:2])[0]
        self._dig_T2 = struct.unpack('<h', raw[2:4])[0]
        self._dig_T3 = struct.unpack('<h', raw[4:6])[0]
        self._dig_P1 = struct.unpack('<H', raw[6:8])[0]
        self._dig_P2 = struct.unpack('<h', raw[8:10])[0]
        self._dig_P3 = struct.unpack('<h', raw[10:12])[0]
        self._dig_P4 = struct.unpack('<h', raw[12:14])[0]
        self._dig_P5 = struct.unpack('<h', raw[14:16])[0]
        self._dig_P6 = struct.unpack('<h', raw[16:18])[0]
        self._dig_P7 = struct.unpack('<h', raw[18:20])[0]
        self._dig_P8 = struct.unpack('<h', raw[20:22])[0]
        self._dig_P9 = struct.unpack('<h', raw[22:24])[0]

    def _trigger_read_burst(self):
        raw = self._read_reg(self._REG_DATA, 6)
        adc_P = (raw[0] << 12) | (raw[1] << 4) | (raw[2] >> 4)
        adc_T = (raw[3] << 12) | (raw[4] << 4) | (raw[5] >> 4)
        return adc_T, adc_P

    def _compensate_temp(self, adc_T):
        T1 = self._dig_T1
        T2 = self._dig_T2
        T3 = self._dig_T3
        var1 = (((adc_T >> 3) - (T1 << 1)) * T2) >> 11
        var2 = (((((adc_T >> 4) - T1) * ((adc_T >> 4) - T1)) >> 12) * T3) >> 14
        self._t_fine = var1 + var2
        return ((self._t_fine * 5 + 128) >> 8) / 100.0

    def _compensate_pressure(self, adc_P):
        tf = self._t_fine if self._t_fine is not None else 0
        P1, P2, P3, P4, P5, P6, P7, P8, P9 = (
            self._dig_P1, self._dig_P2, self._dig_P3, self._dig_P4,
            self._dig_P5, self._dig_P6, self._dig_P7, self._dig_P8, self._dig_P9,
        )
        var1 = tf - 128000
        var2 = var1 * var1 * P6
        var2 = var2 + ((var1 * P5) << 17)
        var2 = var2 + (P4 << 35)
        var1 = ((var1 * var1 * P3) >> 8) + ((var1 * P2) << 12)
        var1 = (((1 << 47) + var1) * P1) >> 33
        if var1 == 0:
            return 0.0
        p = 1048576 - adc_P
        p = (((p << 31) - var2) * 3125) // var1
        var1 = (P9 * (p >> 13) * (p >> 13)) >> 25
        var2 = (P8 * p) >> 19
        p = ((p + var1 + var2) >> 8) + (P7 << 4)
        return (p / 256.0) / 100.0

    def _force_measurement(self):
        self._write_ctrl_meas(0x25 | (1 << 5))
        time.sleep(0.0064)

    def temperature(self):
        """Read calibrated temperature.

        Triggers a forced-mode conversion and returns temperature in °C.
        Caches t_fine for use in subsequent pressure() calls.

        Returns:
            float: Temperature in degrees Celsius.
        """
        self._force_measurement()
        adc_T, _ = self._trigger_read_burst()
        return self._compensate_temp(adc_T)

    def pressure(self):
        """Read calibrated pressure.

        Triggers a forced-mode conversion and returns pressure in hPa.
        Re-reads the temperature ADC alongside pressure to refresh t_fine.

        Returns:
            float: Pressure in hPa.
        """
        self._force_measurement()
        adc_T, adc_P = self._trigger_read_burst()
        self._compensate_temp(adc_T)
        return self._compensate_pressure(adc_P)


class BMP280Full(BMP280Minimal):
    """BMP280 full interface — extends BMP280Minimal with configuration and altitude helpers.

    Adds power-mode control, oversampling settings, IIR filter, standby time,
    status read, altitude / sea-level helpers, chip_id, and reset.

    Args:
        transport: Configured I²C transport pointing at the device.
        addr: 7-bit I²C device address (default 0x76).
        osrs_t: Temperature oversampling 0–5 (default 1 = ×1).
        osrs_p: Pressure oversampling 0–5 (default 1 = ×1).
        mode: Power mode 0/1/3 (default 1 = forced).
        filter: IIR filter coefficient 0–4 (default 0 = off).
        t_sb: Standby time in normal mode 0–7 (default 0 = 0.5 ms).
    """

    OSRS_SKIP = 0
    OSRS_X1   = 1
    OSRS_X2   = 2
    OSRS_X4   = 3
    OSRS_X8   = 4
    OSRS_X16  = 5

    MODE_SLEEP   = 0
    MODE_FORCED   = 1
    MODE_NORMAL   = 3

    FILTER_OFF = 0
    FILTER_2   = 1
    FILTER_4   = 2
    FILTER_8   = 3
    FILTER_16  = 4

    T_SB_0_5_MS   = 0
    T_SB_62_5_MS  = 1
    T_SB_125_MS   = 2
    T_SB_250_MS   = 3
    T_SB_500_MS  = 4
    T_SB_1000_MS = 5
    T_SB_2000_MS = 6
    T_SB_4000_MS = 7

    STATUS_MEASURING = 0x08
    STATUS_IM_UPDATE = 0x01

    def __init__(self, transport, addr=0x76, osrs_t=1, osrs_p=1, mode=1, filter=0, t_sb=0):
        self._transport = transport
        self._addr = addr
        self._osrs_t = osrs_t
        self._osrs_p = osrs_p
        self._mode = mode
        self._filter = filter
        self._t_sb = t_sb
        self._t_fine = None
        self._load_calibration()
        self._write_ctrl_meas(self._ctrl_meas_value())
        self._write_config(self._config_value())

    def _ctrl_meas_value(self):
        return (self._osrs_t << 5) | (self._osrs_p << 2) | self._mode

    def _config_value(self):
        return (self._t_sb << 5) | (self._filter << 2)

    def configure(self, osrs_t=None, osrs_p=None, mode=None, filter=None, t_sb=None):
        """Update chip configuration.

        Writes both ctrl_meas and config registers with new or current values.

        Args:
            osrs_t: Temperature oversampling (0–5).
            osrs_p: Pressure oversampling (0–5).
            mode: Power mode (0=sleep, 1=forced, 3=normal).
            filter: IIR filter coefficient (0=off, 1, 2, 3, 4=×16).
            t_sb: Standby time in normal mode (0–7).
        """
        if osrs_t is not None:
            self._osrs_t = osrs_t
        if osrs_p is not None:
            self._osrs_p = osrs_p
        if mode is not None:
            self._mode = mode
        if filter is not None:
            self._filter = filter
        if t_sb is not None:
            self._t_sb = t_sb
        self._write_ctrl_meas(self._ctrl_meas_value())
        self._write_config(self._config_value())

    def set_oversampling(self, osrs_t, osrs_p):
        """Update oversampling settings.

        Args:
            osrs_t: Temperature oversampling (0–5).
            osrs_p: Pressure oversampling (0–5).
        """
        self._osrs_t = osrs_t
        self._osrs_p = osrs_p
        self._write_ctrl_meas(self._ctrl_meas_value())

    def set_mode(self, mode):
        """Update power mode.

        Args:
            mode: 0=sleep, 1=forced, 3=normal.
        """
        self._mode = mode
        self._write_ctrl_meas(self._ctrl_meas_value())

    def set_filter(self, coeff):
        """Update IIR filter coefficient.

        Args:
            coeff: 0=off, 1=×2, 2=×4, 3=×8, 4=×16.
        """
        self._filter = coeff
        self._write_config(self._config_value())

    def set_standby(self, t_sb):
        """Update standby time (only relevant in normal mode).

        Args:
            t_sb: 0=0.5ms, 1=62.5ms, 2=125ms, 3=250ms, 4=500ms, 5=1s, 6=2s, 7=4s.
        """
        self._t_sb = t_sb
        self._write_config(self._config_value())

    def status(self):
        """Read status register.

        Returns:
            int: Status byte; check STATUS_MEASURING and STATUS_IM_UPDATE bits.
        """
        return self._read_reg(self._REG_STATUS, 1)[0]

    def altitude(self, sea_level_hpa=1013.25):
        """Compute altitude above sea level from current pressure.

        Args:
            sea_level_hpa: Reference sea-level pressure in hPa (default 1013.25).

        Returns:
            float: Altitude in metres.
        """
        p = self.pressure()
        return 44330 * (1 - (p / sea_level_hpa) ** (1 / 5.255))

    def sea_level_pressure(self, altitude_m):
        """Compute sea-level pressure corresponding to current pressure at a known altitude.

        Args:
            altitude_m: Altitude in metres.

        Returns:
            float: Sea-level pressure in hPa.
        """
        p = self.pressure()
        return p / (1 - altitude_m / 44330) ** 5.255

    def chip_id(self):
        """Read chip ID register.

        Returns:
            int: Chip ID; expect 0x58 for BMP280.
        """
        return self._read_reg(self._REG_ID, 1)[0]

    def reset(self):
        """Perform soft reset and re-read calibration coefficients.

        Re-applies the current ctrl_meas and config settings.
        """
        self._write_reg(self._REG_RESET, self._RESET_CMD)
        time.sleep(0.002)
        self._load_calibration()
        self._write_ctrl_meas(self._ctrl_meas_value())
        self._write_config(self._config_value())