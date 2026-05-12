import struct
import time


class BMP180Minimal:
    """BMP180 piezo-resistive pressure + temperature sensor — minimal interface.

    Provides calibrated temperature (°C) and pressure (hPa) readings with no
    configuration beyond the transport. The BMP180 has a fixed I²C address (0x77)
    and no programmable address pin; only one BMP180 can exist on a single bus.

    Default configuration (baked in at construction):
        - OSS = 0 (Ultra Low Power, 4.5 ms conversion)

    Args:
        transport: Configured I²C transport pointing at the device.
    """

    _REG_ID         = 0xD0
    _REG_CAL_START  = 0xAA
    _REG_CAL_END    = 0xBF
    _REG_CTRL_MEAS  = 0xF4
    _REG_OUT_MSB    = 0xF6
    _REG_OUT_LSB    = 0xF7
    _REG_OUT_XLSB   = 0xF8
    _REG_SOFT_RESET = 0xE0

    _CMD_TEMP     = 0x2E
    _CMD_PRESSURE = (0x34, 0x74, 0xB4, 0xF4)

    _CHIP_ID       = 0x55
    _SOFT_RESET_CMD = 0xB6

    _CONVERSION_TIME = 0.0045

    def __init__(self, transport):
        self._transport = transport
        self._oss = 0
        self._read_calibration()

    def _read_calibration(self):
        data = self._transport.write_read(bytes([self._REG_CAL_START]), 22)
        ac1 = struct.unpack('>h', data[0:2])[0]
        ac2 = struct.unpack('>h', data[2:4])[0]
        ac3 = struct.unpack('>h', data[4:6])[0]
        ac4 = struct.unpack('>H', data[6:8])[0]
        ac5 = struct.unpack('>H', data[8:10])[0]
        ac6 = struct.unpack('>H', data[10:12])[0]
        b1  = struct.unpack('>h', data[12:14])[0]
        b2  = struct.unpack('>h', data[14:16])[0]
        mb  = struct.unpack('>h', data[16:18])[0]
        mc  = struct.unpack('>h', data[18:20])[0]
        md  = struct.unpack('>h', data[20:22])[0]

        coefficients = (ac1, ac2, ac3, ac4, ac5, ac6, b1, b2, mb, mc, md)
        if any(c == 0 or c == 0xFFFF for c in coefficients):
            raise ValueError('BMP180 calibration data invalid')

        self._ac1 = ac1
        self._ac2 = ac2
        self._ac3 = ac3
        self._ac4 = ac4
        self._ac5 = ac5
        self._ac6 = ac6
        self._b1  = b1
        self._b2  = b2
        self._mb  = mb
        self._mc  = mc
        self._md  = md

    def _write_reg(self, reg, value):
        self._transport.write(bytes([reg, value]))

    def _read_raw_temp(self):
        self._write_reg(self._REG_CTRL_MEAS, self._CMD_TEMP)
        time.sleep(self._CONVERSION_TIME)
        data = self._transport.write_read(bytes([self._REG_OUT_MSB]), 2)
        return (data[0] << 8) | data[1]

    def _read_raw_pressure(self):
        cmd = self._CMD_PRESSURE[self._oss]
        self._write_reg(self._REG_CTRL_MEAS, cmd)
        conv_time = (0.0045, 0.0075, 0.0135, 0.0255)[self._oss]
        time.sleep(conv_time)
        data = self._transport.write_read(bytes([self._REG_OUT_MSB]), 3)
        up = ((data[0] << 16) | (data[1] << 8) | data[2]) >> (8 - self._oss)
        return up

    def _compensate_temp(self, ut):
        ac1, ac2, ac3 = self._ac1, self._ac2, self._ac3
        ac4, ac5, ac6 = self._ac4, self._ac5, self._ac6
        b1, b2 = self._b1, self._b2
        mc, md = self._mc, self._md

        x1 = ((ut - ac6) * ac5) >> 15
        x2 = (mc << 11) // (x1 + md)
        b5 = x1 + x2
        self._b5 = b5
        return ((b5 + 8) >> 4) / 10.0

    def _compensate_pressure(self, up):
        oss = self._oss
        ac1, ac2, ac3 = self._ac1, self._ac2, self._ac3
        ac4 = self._ac4
        b1, b2 = self._b1, self._b2
        b5 = self._b5

        b6 = b5 - 4000
        x1 = (b2 * ((b6 * b6) >> 12)) >> 11
        x2 = (ac2 * b6) >> 11
        x3 = x1 + x2
        b3 = (((ac1 * 4 + x3) << oss) + 2) >> 2
        x1 = (ac3 * b6) >> 13
        x2 = (b1 * ((b6 * b6) >> 12)) >> 16
        x3 = ((x1 + x2) + 2) >> 2
        b4 = (ac4 * (x3 + 32768)) >> 15
        b7 = (up - b3) * (50000 >> oss)

        if b7 < 0x80000000:
            p = (b7 * 2) // b4
        else:
            p = (b7 // b4) * 2

        x1 = (p >> 8) * (p >> 8)
        x1 = (x1 * 3038) >> 16
        x2 = (-7357 * p) >> 16
        p = p + ((x1 + x2 + 3791) >> 4)

        return p / 100.0

    def temperature(self):
        """Read calibrated temperature.

        Returns:
            float: Temperature in degrees Celsius.
        """
        ut = self._read_raw_temp()
        return self._compensate_temp(ut)

    def pressure(self):
        """Read calibrated pressure.

        Reads temperature first to refresh B5, then reads pressure.
        Self-contained — may be called without a prior temperature() call.

        Returns:
            float: Pressure in hPa.
        """
        ut = self._read_raw_temp()
        self._compensate_temp(ut)
        up = self._read_raw_pressure()
        return self._compensate_pressure(up)


class BMP180Full(BMP180Minimal):
    """BMP180 full interface — extends BMP180Minimal with oversampling control and altitude helpers.

    Adds oversampling mode selection and altitude / sea-level pressure conversion
    convenience methods.

    OSS constants:
        OSS_ULP            — Ultra Low Power (oss=0, 4.5 ms)
        OSS_STANDARD       — Standard (oss=1, 7.5 ms)
        OSS_HIGH_RES       — High Resolution (oss=2, 13.5 ms)
        OSS_ULTRA_HIGH_RES — Ultra High Resolution (oss=3, 25.5 ms)

    Args:
        transport: Configured I²C transport pointing at the device.
        oss: Oversampling mode 0–3 (default 0 = ULP).
    """

    OSS_ULP            = 0
    OSS_STANDARD       = 1
    OSS_HIGH_RES       = 2
    OSS_ULTRA_HIGH_RES = 3

    def __init__(self, transport, oss=0):
        super().__init__(transport)
        self._oss = oss & 0x03

    def oversampling(self):
        """Read the current oversampling mode.

        Returns:
            int: Current OSS value (0–3).
        """
        return self._oss

    def set_oversampling(self, oss):
        """Change the oversampling mode for subsequent pressure() calls.

        Args:
            oss: Oversampling mode 0–3.
        """
        self._oss = oss & 0x03

    def altitude(self, sea_level_hpa=1013.25):
        """Compute altitude above sea level from the current pressure reading.

        Args:
            sea_level_hpa: Reference sea-level pressure in hPa (default 1013.25).

        Returns:
            float: Altitude in metres.
        """
        p = self.pressure()
        return 44330 * (1 - (p / sea_level_hpa) ** (1 / 5.255))

    def sea_level_pressure(self, altitude_m):
        """Compute sea-level pressure that corresponds to the current pressure at a known altitude.

        Args:
            altitude_m: Altitude in metres.

        Returns:
            float: Sea-level pressure in hPa.
        """
        p = self.pressure()
        return p / (1 - altitude_m / 44330) ** 5.255

    def chip_id(self):
        """Read the chip ID register.

        Returns:
            int: Chip ID; expect 0x55.
        """
        data = self._transport.write_read(bytes([self._REG_ID]), 1)
        return data[0]

    def reset(self):
        """Perform a soft reset and re-read calibration coefficients."""
        self._write_reg(self._REG_SOFT_RESET, self._SOFT_RESET_CMD)
        time.sleep(0.01)
        self._read_calibration()
