import struct
import time
import math


class BME280Minimal:
    """BME280 combined humidity + pressure + temperature sensor — minimal interface.

    Provides calibrated temperature (°C), pressure (hPa), and humidity (%RH)
    with no configuration beyond the transport. I²C address is 0x76 (SDO=GND)
    or 0x77 (SDO=VDDIO). 0x77 collides with the BMP180/BMP280/BMP388.

    Sibling of the BMP280 driver: register-compatible for pressure and
    temperature, plus an integrated humidity front-end (its own calibration
    block, control register, output registers, and compensation formula).

    Default configuration (baked in at construction):
        - Power mode: forced (each call triggers a single-shot conversion)
        - osrs_t = ×1, osrs_p = ×1, osrs_h = ×1 (Ultra Low Power)
        - IIR filter off
        - spi3w_en = 0

    Args:
        transport: Configured I²C or SPI transport pointing at the device.
        bus_type: Bus type string, ``'i2c'`` (default) or ``'spi'``.
            SPI writes mask bit 7 of the register address.
    """

    _REG_CAL_START  = 0x88
    _REG_H1         = 0xA1
    _REG_ID         = 0xD0
    _REG_RESET      = 0xE0
    _REG_CAL_H2     = 0xE1
    _REG_STATUS     = 0xF3
    _REG_CTRL_HUM   = 0xF2
    _REG_CTRL_MEAS  = 0xF4
    _REG_CONFIG     = 0xF5
    _REG_DATA_START = 0xF7

    _CHIP_ID        = 0x60
    _RESET_CMD      = 0xB6

    _MEAS_TIME_MS   = 9

    def __init__(self, transport, bus_type='i2c'):
        self._transport = transport
        self._bus_type = bus_type
        self._mode = 0
        self._osrs_t = 1
        self._osrs_p = 1
        self._osrs_h = 1
        self._filter = 0
        self._t_sb = 0
        self._t_fine = 0
        self._read_calibration()
        self._write_reg(self._REG_CTRL_HUM, self._osrs_h)
        self._write_reg(self._REG_CTRL_MEAS, (self._osrs_t << 5) | (self._osrs_p << 2) | 0)
        self._write_reg(self._REG_CONFIG, 0)

    def _read_calibration(self):
        data = self._transport.write_read(bytes([self._REG_CAL_START]), 26)
        self._dig_T1 = struct.unpack('<H', data[0:2])[0]
        self._dig_T2 = struct.unpack('<h', data[2:4])[0]
        self._dig_T3 = struct.unpack('<h', data[4:6])[0]
        self._dig_P1 = struct.unpack('<H', data[6:8])[0]
        self._dig_P2 = struct.unpack('<h', data[8:10])[0]
        self._dig_P3 = struct.unpack('<h', data[10:12])[0]
        self._dig_P4 = struct.unpack('<h', data[12:14])[0]
        self._dig_P5 = struct.unpack('<h', data[14:16])[0]
        self._dig_P6 = struct.unpack('<h', data[16:18])[0]
        self._dig_P7 = struct.unpack('<h', data[18:20])[0]
        self._dig_P8 = struct.unpack('<h', data[20:22])[0]
        self._dig_P9 = struct.unpack('<h', data[22:24])[0]
        self._dig_H1 = data[25]

        h = self._transport.write_read(bytes([self._REG_CAL_H2]), 7)
        self._dig_H2 = struct.unpack('<h', h[0:2])[0]
        self._dig_H3 = h[2]
        h4_raw = (h[3] << 4) | (h[4] & 0x0F)
        h5_raw = (h[5] << 4) | (h[4] >> 4)
        if h4_raw & 0x800:
            h4_raw -= 0x1000
        if h5_raw & 0x800:
            h5_raw -= 0x1000
        self._dig_H4 = h4_raw
        self._dig_H5 = h5_raw
        self._dig_H6 = struct.unpack('<b', h[6:7])[0]

    def _write_reg(self, reg, value):
        if self._bus_type == 'spi':
            reg = reg & 0x7F
        self._transport.write(bytes([reg, value]))

    def _read_reg(self, reg, n):
        return self._transport.write_read(bytes([reg]), n)

    def _trigger_and_read(self):
        if self._mode != 3:
            self._write_reg(self._REG_CTRL_HUM, self._osrs_h)
            ctrl = (self._osrs_t << 5) | (self._osrs_p << 2) | 1
            self._write_reg(self._REG_CTRL_MEAS, ctrl)
            time.sleep(self._MEAS_TIME_MS / 1000.0)
        raw = self._read_reg(self._REG_DATA_START, 8)
        adc_P = (raw[0] << 12) | (raw[1] << 4) | (raw[2] >> 4)
        adc_T = (raw[3] << 12) | (raw[4] << 4) | (raw[5] >> 4)
        adc_H = (raw[6] << 8) | raw[7]
        return adc_P, adc_T, adc_H

    def _compensate_temp(self, adc_T):
        dig_T1 = self._dig_T1
        dig_T2 = self._dig_T2
        dig_T3 = self._dig_T3
        var1 = (((adc_T >> 3) - (dig_T1 << 1)) * dig_T2) >> 11
        var2 = (((((adc_T >> 4) - dig_T1) * ((adc_T >> 4) - dig_T1)) >> 12) * dig_T3) >> 14
        self._t_fine = var1 + var2
        return ((self._t_fine * 5 + 128) >> 8) / 100.0

    def _compensate_pressure(self, adc_P):
        t_fine = self._t_fine
        dig_P1 = self._dig_P1
        dig_P2 = self._dig_P2
        dig_P3 = self._dig_P3
        dig_P4 = self._dig_P4
        dig_P5 = self._dig_P5
        dig_P6 = self._dig_P6
        dig_P7 = self._dig_P7
        dig_P8 = self._dig_P8
        dig_P9 = self._dig_P9

        var1 = t_fine - 128000
        var2 = var1 * var1 * dig_P6
        var2 = var2 + ((var1 * dig_P5) << 17)
        var2 = var2 + (dig_P4 << 35)
        var1 = ((var1 * var1 * dig_P3) >> 8) + ((var1 * dig_P2) << 12)
        var1 = (((1 << 47) + var1) * dig_P1) >> 33
        if var1 == 0:
            return 0.0
        p = 1048576 - adc_P
        p = (((p << 31) - var2) * 3125) // var1
        var1 = (dig_P9 * (p >> 13) * (p >> 13)) >> 25
        var2 = (dig_P8 * p) >> 19
        p = ((p + var1 + var2) >> 8) + (dig_P7 << 4)
        return (p / 256.0) / 100.0

    def _compensate_humidity(self, adc_H):
        t_fine = self._t_fine
        dig_H1 = self._dig_H1
        dig_H2 = self._dig_H2
        dig_H3 = self._dig_H3
        dig_H4 = self._dig_H4
        dig_H5 = self._dig_H5
        dig_H6 = self._dig_H6

        v = t_fine - 76800
        v = (((((adc_H << 14) - (dig_H4 << 20) - (dig_H5 * v)) + 16384) >> 15) *
             (((((((v * dig_H6) >> 10) * (((v * dig_H3) >> 11) + 32768)) >> 10) + 2097152) *
               dig_H2 + 8192) >> 14))
        v = v - (((((v >> 15) * (v >> 15)) >> 7) * dig_H1) >> 4)
        if v < 0:
            v = 0
        if v > 419430400:
            v = 419430400
        return (v >> 12) / 1024.0

    def temperature(self):
        """Read calibrated temperature.

        Triggers a forced measurement if not in normal mode.
        Caches t_fine for subsequent pressure and humidity compensation.

        Returns:
            float: Temperature in degrees Celsius.
        """
        _, adc_T, _ = self._trigger_and_read()
        return self._compensate_temp(adc_T)

    def pressure(self):
        """Read calibrated pressure.

        Triggers a forced measurement if not in normal mode.
        Reads all three ADCs and refreshes t_fine.

        Returns:
            float: Pressure in hPa.
        """
        adc_P, adc_T, _ = self._trigger_and_read()
        self._compensate_temp(adc_T)
        return self._compensate_pressure(adc_P)

    def humidity(self):
        """Read calibrated humidity.

        Triggers a forced measurement if not in normal mode.
        Reads all three ADCs and refreshes t_fine.

        Returns:
            float: Relative humidity in %RH.
        """
        _, adc_T, adc_H = self._trigger_and_read()
        self._compensate_temp(adc_T)
        return self._compensate_humidity(adc_H)


class BME280Full(BME280Minimal):
    """BME280 full interface — extends BME280Minimal with configuration and altitude helpers.

    Adds power-mode control, oversampling for all three channels, IIR filter,
    standby time, altitude / sea-level pressure conversion, dew point, and
    chip ID / soft reset.

    Args:
        transport: Configured I²C or SPI transport pointing at the device.
        bus_type: Bus type string, ``'i2c'`` (default) or ``'spi'``.
    """

    OSRS_SKIP = 0
    OSRS_X1   = 1
    OSRS_X2   = 2
    OSRS_X4   = 3
    OSRS_X8   = 4
    OSRS_X16  = 5

    MODE_SLEEP  = 0
    MODE_FORCED = 1
    MODE_NORMAL = 3

    FILTER_OFF = 0
    FILTER_2   = 1
    FILTER_4   = 2
    FILTER_8   = 3
    FILTER_16  = 4

    T_SB_0_5_MS    = 0
    T_SB_62_5_MS   = 1
    T_SB_125_MS    = 2
    T_SB_250_MS    = 3
    T_SB_500_MS    = 4
    T_SB_1000_MS   = 5
    T_SB_10_MS     = 6
    T_SB_20_MS     = 7

    STATUS_MEASURING = 0x08
    STATUS_IM_UPDATE = 0x01

    def __init__(self, transport, bus_type='i2c'):
        super().__init__(transport, bus_type)

    def configure(self, osrs_t=1, osrs_p=1, osrs_h=1, mode=0, filter=0, t_sb=0):
        """Write ctrl_hum, ctrl_meas, and config registers in the correct order.

        ctrl_hum must be written before ctrl_meas for humidity oversampling
        to latch.

        Args:
            osrs_t: Temperature oversampling (0–5).
            osrs_p: Pressure oversampling (0–5).
            osrs_h: Humidity oversampling (0–5).
            mode: Power mode (0=sleep, 1=forced, 3=normal).
            filter: IIR filter coefficient (0–4).
            t_sb: Standby time in normal mode (0–7; codes 6/7 mean 10 ms / 20 ms,
                not 2000 ms / 4000 ms as on the BMP280).
        """
        self._osrs_t = osrs_t
        self._osrs_p = osrs_p
        self._osrs_h = osrs_h
        self._mode = mode
        self._filter = filter
        self._t_sb = t_sb
        self._write_reg(self._REG_CTRL_HUM, osrs_h)
        self._write_reg(self._REG_CONFIG, (t_sb << 5) | (filter << 2))
        self._write_reg(self._REG_CTRL_MEAS, (osrs_t << 5) | (osrs_p << 2) | mode)

    def set_oversampling(self, osrs_t, osrs_p, osrs_h):
        """Update temperature, pressure, and humidity oversampling.

        Writes ctrl_hum then ctrl_meas so the humidity setting latches.

        Args:
            osrs_t: Temperature oversampling (0–5).
            osrs_p: Pressure oversampling (0–5).
            osrs_h: Humidity oversampling (0–5).
        """
        self._osrs_t = osrs_t
        self._osrs_p = osrs_p
        self._osrs_h = osrs_h
        self._write_reg(self._REG_CTRL_HUM, osrs_h)
        self._write_reg(self._REG_CTRL_MEAS, (osrs_t << 5) | (osrs_p << 2) | self._mode)

    def set_mode(self, mode):
        """Update power mode.

        Args:
            mode: Power mode (0=sleep, 1=forced, 3=normal).
        """
        self._mode = mode
        self._write_reg(self._REG_CTRL_MEAS, (self._osrs_t << 5) | (self._osrs_p << 2) | mode)

    def set_filter(self, coeff):
        """Update IIR filter coefficient.

        Args:
            coeff: Filter coefficient (0=off, 1=2, 2=4, 3=8, 4=16).
        """
        self._filter = coeff
        self._write_reg(self._REG_CONFIG, (self._t_sb << 5) | (coeff << 2))

    def set_standby(self, t_sb):
        """Update standby time for normal mode.

        Args:
            t_sb: Standby time value (0–7). On the BME280 codes 6 and 7 mean
                10 ms and 20 ms respectively (not 2000 ms / 4000 ms).
        """
        self._t_sb = t_sb
        self._write_reg(self._REG_CONFIG, (t_sb << 5) | (self._filter << 2))

    def status(self):
        """Read the status register.

        Returns:
            int: Status byte; bit 3 = measuring, bit 0 = im_update.
        """
        data = self._read_reg(self._REG_STATUS, 1)
        return data[0]

    def altitude(self, sea_level_hpa=1013.25):
        """Compute altitude above sea level from the current pressure.

        Args:
            sea_level_hpa: Reference sea-level pressure in hPa (default 1013.25).

        Returns:
            float: Altitude in metres.
        """
        p = self.pressure()
        return 44330 * (1 - (p / sea_level_hpa) ** (1 / 5.255))

    def sea_level_pressure(self, altitude_m):
        """Compute sea-level pressure from current pressure and known altitude.

        Args:
            altitude_m: Altitude in metres.

        Returns:
            float: Sea-level pressure in hPa.
        """
        p = self.pressure()
        return p / (1 - altitude_m / 44330) ** 5.255

    def dew_point(self):
        """Compute dew point from current temperature and humidity.

        Uses the Magnus-Tetens approximation (accurate to ±0.4 °C in the
        0–60 °C / 1–100 %RH range).

        Returns:
            float: Dew point in degrees Celsius.
        """
        t = self.temperature()
        h = self.humidity()
        if h <= 0:
            return float('-inf')
        a = 17.27
        b = 237.7
        alpha = (a * t) / (b + t) + math.log(h / 100.0)
        return (b * alpha) / (a - alpha)

    def chip_id(self):
        """Read the chip ID register.

        Returns:
            int: Chip ID; expect 0x60.
        """
        data = self._read_reg(self._REG_ID, 1)
        return data[0]

    def reset(self):
        """Perform a soft reset, re-read calibration, and re-apply configuration."""
        self._write_reg(self._REG_RESET, self._RESET_CMD)
        time.sleep(0.002)
        self._read_calibration()
        self._write_reg(self._REG_CTRL_HUM, self._osrs_h)
        self._write_reg(self._REG_CONFIG, (self._t_sb << 5) | (self._filter << 2))
        self._write_reg(self._REG_CTRL_MEAS, (self._osrs_t << 5) | (self._osrs_p << 2) | self._mode)
