import struct
import time


class BMP384Minimal:
    """BMP384 high-precision barometric pressure and temperature sensor — minimal interface.

    Provides calibrated temperature (°C) and pressure (hPa) readings with no
    configuration beyond the connection. I²C address is 0x76 (SDO=GND) or
    0x77 (SDO=VDD).

    Default configuration (baked in at construction):
        - Power mode: normal (continuous measurement at configured ODR)
        - osr_t = ×2, osr_p = ×16
        - IIR filter coefficient 3 (moderate noise rejection)
        - ODR = 25 Hz
        - Both pressure and temperature sensors enabled

    Args:
        connection: Configured I²C or SPI connection pointing at the device.
        bus_type: Bus type string, ``'i2c'`` (default) or ``'spi'``.
            SPI writes clear bit 7 of the register address.
    """

    _REG_CHIP_ID     = 0x00
    _REG_DATA_0      = 0x04
    _REG_PWR_CTRL    = 0x1B
    _REG_OSR         = 0x1C
    _REG_ODR         = 0x1D
    _REG_CONFIG      = 0x1F
    _REG_CMD         = 0x7E
    _REG_CAL_START   = 0x31
    _REG_CAL_END     = 0x45
    _REG_CAL_LEN     = 0x45 - 0x31 + 1  # 21 bytes

    _CHIP_ID         = 0x50
    _SOFT_RESET_CMD  = 0xB6
    _FIFO_FLUSH_CMD  = 0xB0

    _OSR_P_SHIFT     = 0
    _OSR_T_SHIFT     = 3
    _IIR_SHIFT       = 1

    _MODE_SLEEP      = 0x00
    _MODE_FORCED     = 0x01
    _MODE_NORMAL     = 0x03

    _PWR_PRESS_EN    = 0x01
    _PWR_TEMP_EN     = 0x02

    _MEAS_TIME_MS    = 40

    def __init__(self, connection, bus_type='i2c'):
        self._connection = connection
        self._bus_type = bus_type
        self._osr_p = 4    # ×16
        self._osr_t = 1    # ×2
        self._iir = 2      # coefficient 3
        self._odr = 0x03   # 25 Hz
        self._mode = self._MODE_NORMAL
        self._read_calibration()
        self._verify_chip_id()
        self._apply_config()

    def _verify_chip_id(self):
        raw = self._read_reg(self._REG_CHIP_ID, 1)
        if raw[0] != self._CHIP_ID:
            raise OSError(
                'BMP384 not found: expected CHIP_ID 0x%02X, got 0x%02X'
                % (self._CHIP_ID, raw[0])
            )

    def _read_calibration(self):
        data = self._read_reg(self._REG_CAL_START, self._REG_CAL_LEN)
        # NVM_PAR_T1 (u16 LE)
        self._par_t1 = struct.unpack('<H', data[0:2])[0]
        # NVM_PAR_T2 (u16 LE)
        self._par_t2 = struct.unpack('<H', data[2:4])[0]
        # NVM_PAR_T3 (s8)
        self._par_t3 = self._s8(data[4])
        # NVM_PAR_P1 (s16 LE)
        self._par_p1 = struct.unpack('<h', data[5:7])[0]
        # NVM_PAR_P2 (s16 LE)
        self._par_p2 = struct.unpack('<h', data[7:9])[0]
        # NVM_PAR_P3 (s8)
        self._par_p3 = self._s8(data[9])
        # NVM_PAR_P4 (s8)
        self._par_p4 = self._s8(data[10])
        # NVM_PAR_P5 (u16 LE)
        self._par_p5 = struct.unpack('<H', data[11:13])[0]
        # NVM_PAR_P6 (u16 LE)
        self._par_p6 = struct.unpack('<H', data[13:15])[0]
        # NVM_PAR_P7 (s8)
        self._par_p7 = self._s8(data[15])
        # NVM_PAR_P8 (s8)
        self._par_p8 = self._s8(data[16])
        # NVM_PAR_P9 (s16 LE)
        self._par_p9 = struct.unpack('<h', data[17:19])[0]
        # NVM_PAR_P10 (s8)
        self._par_p10 = self._s8(data[19])
        # NVM_PAR_P11 (s8)
        self._par_p11 = self._s8(data[20])

        # Convert raw NVM coefficients to floating-point PAR values per datasheet.
        # PAR_T1 = NVM_PAR_T1 / 2^-8  → multiply by 256
        # PAR_T2 = NVM_PAR_T2 / 2^30
        # PAR_T3 = NVM_PAR_T3 / 2^48
        # PAR_P1 = (NVM_PAR_P1 - 2^14) / 2^20
        # PAR_P2 = (NVM_PAR_P2 - 2^14) / 2^29
        # PAR_P3 = NVM_PAR_P3 / 2^32
        # PAR_P4 = NVM_PAR_P4 / 2^37
        # PAR_P5 = NVM_PAR_P5 / 2^-3  → multiply by 8
        # PAR_P6 = NVM_PAR_P6 / 2^6
        # PAR_P7 = NVM_PAR_P7 / 2^8
        # PAR_P8 = NVM_PAR_P8 / 2^15
        # PAR_P9 = NVM_PAR_P9 / 2^48
        # PAR_P10 = NVM_PAR_P10 / 2^48
        # PAR_P11 = NVM_PAR_P11 / 2^65
        self._par_t1 = self._par_t1 / (1 << -8)
        self._par_t2 = self._par_t2 / (1 << 30)
        self._par_t3 = self._par_t3 / (1 << 48)
        self._par_p1 = (self._par_p1 - (1 << 14)) / (1 << 20)
        self._par_p2 = (self._par_p2 - (1 << 14)) / (1 << 29)
        self._par_p3 = self._par_p3 / (1 << 32)
        self._par_p4 = self._par_p4 / (1 << 37)
        self._par_p5 = self._par_p5 / (1 << -3)
        self._par_p6 = self._par_p6 / (1 << 6)
        self._par_p7 = self._par_p7 / (1 << 8)
        self._par_p8 = self._par_p8 / (1 << 15)
        self._par_p9 = self._par_p9 / (1 << 48)
        self._par_p10 = self._par_p10 / (1 << 48)
        self._par_p11 = self._par_p11 / (1 << 65)

    def _apply_config(self):
        osr_reg = (self._osr_t << self._OSR_T_SHIFT) | (self._osr_p << self._OSR_P_SHIFT)
        config_reg = (self._iir << self._IIR_SHIFT)
        pwr_reg = (self._mode << 4) | self._PWR_TEMP_EN | self._PWR_PRESS_EN
        self._write_reg(self._REG_OSR, osr_reg)
        self._write_reg(self._REG_CONFIG, config_reg)
        self._write_reg(self._REG_ODR, self._odr)
        self._write_reg(self._REG_PWR_CTRL, pwr_reg)

    def _write_reg(self, reg, value):
        if self._bus_type == 'spi':
            reg = reg & 0x7F
        self._connection.write(bytes([reg, value]))

    def _read_reg(self, reg, n):
        return self._connection.write_read(bytes([reg]), n)

    def _read_burst(self):
        # DATA_0..DATA_5 (0x04..0x09) = pressure XLSB/LSB/MSB, then temperature XLSB/LSB/MSB
        raw = self._read_reg(self._REG_DATA_0, 6)
        uncomp_press = (raw[2] << 16) | (raw[1] << 8) | raw[0]
        uncomp_temp  = (raw[5] << 16) | (raw[4] << 8) | raw[3]
        return uncomp_press, uncomp_temp

    def _compensate_temperature(self, uncomp_temp):
        par_t1 = self._par_t1
        par_t2 = self._par_t2
        par_t3 = self._par_t3
        partial1 = float(uncomp_temp) - par_t1
        partial2 = partial1 * par_t2
        self._t_lin = partial2 + (partial1 * partial1) * par_t3
        return self._t_lin

    def _compensate_pressure(self, uncomp_press):
        t_lin = self._t_lin
        par_p1 = self._par_p1
        par_p2 = self._par_p2
        par_p3 = self._par_p3
        par_p4 = self._par_p4
        par_p5 = self._par_p5
        par_p6 = self._par_p6
        par_p7 = self._par_p7
        par_p8 = self._par_p8
        par_p9 = self._par_p9
        par_p10 = self._par_p10
        par_p11 = self._par_p11

        partial1 = par_p6 * t_lin
        partial2 = par_p7 * t_lin * t_lin
        partial3 = par_p8 * t_lin * t_lin * t_lin
        partial_out1 = par_p5 + partial1 + partial2 + partial3

        partial1 = par_p2 * t_lin
        partial2 = par_p3 * t_lin * t_lin
        partial3 = par_p4 * t_lin * t_lin * t_lin
        partial_out2 = float(uncomp_press) * (par_p1 + partial1 + partial2 + partial3)

        partial1 = float(uncomp_press) * float(uncomp_press)
        partial2 = par_p9 + par_p10 * t_lin
        partial3 = partial1 * partial2
        partial4 = partial3 + float(uncomp_press) ** 3 * par_p11

        comp_press = partial_out1 + partial_out2 + partial4
        return comp_press

    def temperature(self):
        """Read calibrated temperature.

        In normal mode the chip continuously produces new data; this call
        burst-reads DATA_0..DATA_5 and runs the compensation. In forced
        mode it first triggers a measurement.

        Returns:
            float: Temperature in degrees Celsius.
        """
        if self._mode == self._MODE_FORCED:
            pwr_reg = (self._MODE_FORCED << 4) | self._PWR_TEMP_EN | self._PWR_PRESS_EN
            self._write_reg(self._REG_PWR_CTRL, pwr_reg)
            time.sleep_ms(self._MEAS_TIME_MS)
        uncomp_press, uncomp_temp = self._read_burst()
        return self._compensate_temperature(uncomp_temp)

    def pressure(self):
        """Read calibrated pressure.

        Burst-reads DATA_0..DATA_5, runs temperature compensation first
        (to populate t_lin, which the pressure formula needs), then the
        pressure compensation. In forced mode it first triggers a measurement.

        Returns:
            float: Pressure in hectopascals (hPa).
        """
        if self._mode == self._MODE_FORCED:
            pwr_reg = (self._MODE_FORCED << 4) | self._PWR_TEMP_EN | self._PWR_PRESS_EN
            self._write_reg(self._REG_PWR_CTRL, pwr_reg)
            time.sleep_ms(self._MEAS_TIME_MS)
        uncomp_press, uncomp_temp = self._read_burst()
        self._compensate_temperature(uncomp_temp)
        return self._compensate_pressure(uncomp_press) / 100.0

    @staticmethod
    def _s8(b):
        return b - 256 if b >= 128 else b
