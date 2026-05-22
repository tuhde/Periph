"""BMP280 piezo-resistive digital pressure sensor — minimal interface.

Provides calibrated temperature (°C) and pressure (hPa) readings with no
configuration beyond the transport. The chip has two I²C addresses:
0x76 (SDO=GND) or 0x77 (SDO=VDDIO). Only one address is active per chip.

Calibration NVM (12 coefficients, little-endian) is read once at construction
and reused for all subsequent compensated readings. The 64-bit integer
compensation algorithm from the datasheet is used for full precision.

Baked-in defaults (Minimal):
    - Power mode: forced (each temperature() / pressure() call triggers a
      single-shot conversion)
    - osrs_t = ×1, osrs_p = ×1 (Ultra Low Power)
    - IIR filter off
"""

import struct
import time


class BMP280Minimal:
    """BMP280 piezo-resistive pressure + temperature sensor — minimal interface.

    Provides calibrated temperature (°C) and pressure (hPa) readings with no
    configuration beyond the transport. Default address is 0x76 (SDO=GND).

    Baked-in defaults:
        - Power mode: forced (each temperature() / pressure() call triggers a
          single-shot conversion)
        - osrs_t = ×1, osrs_p = ×1 (Ultra Low Power)
        - IIR filter off

    Args:
        transport: Configured I²C transport pointing at the device.
        addr: 7-bit I²C address (default 0x76, alternative 0x77).
    """

    _REG_CAL     = 0x88
    _REG_ID      = 0xD0
    _REG_RESET   = 0xE0
    _REG_STATUS  = 0xF3
    _REG_CTRL    = 0xF4
    _REG_CONFIG  = 0xF5
    _REG_DATA    = 0xF7

    _CHIP_ID     = 0x58
    _RESET_CMD   = 0xB6

    # Power modes
    MODE_SLEEP  = 0
    MODE_FORCED = 1
    MODE_NORMAL = 3

    _MAX_CONV_TIME = 0.0064  # 6.4 ms at ×1/×1

    def __init__(self, transport, addr=0x76):
        self._transport = transport
        self._addr = addr
        self._t_fine = None
        self._mode = self.MODE_SLEEP

        self._read_calibration()
        self._write_reg(self._REG_CTRL, 0x00)
        self._write_reg(self._REG_CONFIG, 0x00)

    def _write_reg(self, reg, value):
        self._transport.write(bytes([reg, value & 0xFF]))

    def _read_reg(self, reg):
        return self._transport.write_read(bytes([reg]), 1)[0]

    def _read_calibration(self):
        data = self._transport.write_read(bytes([self._REG_CAL]), 24)
        self._dig = struct.unpack('<HHhHHHHHHHHH', data)

    def _trigger_forced(self):
        self._write_reg(self._REG_CTRL, 0x25)
        time.sleep(self._MAX_CONV_TIME)

    def _read_adc(self):
        data = self._transport.write_read(bytes([self._REG_DATA]), 6)
        adc_p = ((data[0] << 12) | (data[1] << 4) | (data[2] >> 4)) & 0x0FFFFF
        adc_t = ((data[3] << 12) | (data[4] << 4) | (data[5] >> 4)) & 0x0FFFFF
        return adc_t, adc_p

    def _compensate_temp(self, adc_t):
        dig = self._dig
        var1 = (((adc_t >> 3) - (dig[0] << 1)) * dig[1]) >> 11
        var2 = (((((adc_t >> 4) - dig[0]) * ((adc_t >> 4) - dig[0])) >> 12) * dig[2]) >> 14
        self._t_fine = var1 + var2
        return ((self._t_fine * 5 + 128) >> 8) / 100.0

    def _compensate_pressure(self, adc_p):
        dig = self._dig
        var1 = self._t_fine - 128000
        var2 = var1 * var1 * dig[8]
        var2 = var2 + ((var1 * dig[7]) << 17)
        var2 = var2 + (dig[6] << 35)
        var1 = ((var1 * var1 * dig[5]) >> 8) + ((var1 * dig[4]) << 12)
        var1 = (((1 << 47) + var1) * dig[3]) >> 33
        if var1 == 0:
            return 0.0
        p = 1048576 - adc_p
        p = (((p << 31) - var2) * 3125) // var1
        var1 = (dig[11] * (p >> 13) * (p >> 13)) >> 25
        var2 = (dig[10] * p) >> 19
        p = ((p + var1 + var2) >> 8) + (dig[9] << 4)
        return (p / 256.0) / 100.0

    def temperature(self):
        """Read calibrated temperature.

        Returns:
            float: Temperature in degrees Celsius.
        """
        self._trigger_forced()
        adc_t, adc_p = self._read_adc()
        return self._compensate_temp(adc_t)

    def pressure(self):
        """Read calibrated pressure.

        Reads temperature first (refreshes t_fine), then reads pressure
        using the cached t_fine. Self-contained — may be called without
        a prior temperature() call.

        Returns:
            float: Pressure in hPa.
        """
        self._trigger_forced()
        adc_t, adc_p = self._read_adc()
        self._compensate_temp(adc_t)
        return self._compensate_pressure(adc_p)


class BMP280Full(BMP280Minimal):
    """BMP280 full interface — extends BMP280Minimal with configuration helpers.

    Adds power-mode control, oversampling, IIR filter, standby time,
    altitude helpers, chip_id(), and reset().

    Oversampling constants:
        OSRS_SKIP = 0   — temperature/pressure skipped
        OSRS_X1   = 1   — ×1 oversampling
        OSRS_X2   = 2   — ×2 oversampling
        OSRS_X4   = 3   — ×4 oversampling
        OSRS_X8   = 4   — ×8 oversampling
        OSRS_X16  = 5   — ×16 oversampling

    Mode constants:
        MODE_SLEEP  = 0
        MODE_FORCED = 1
        MODE_NORMAL = 3

    Filter constants:
        FILTER_OFF = 0
        FILTER_2   = 1
        FILTER_4   = 2
        FILTER_8   = 3
        FILTER_16  = 4

    Standby time constants (only relevant in Normal mode):
        T_SB_0_5_MS  = 0
        T_SB_62_5_MS = 1
        T_SB_125_MS  = 2
        T_SB_250_MS  = 3
        T_SB_500_MS  = 4
        T_SB_1000_MS = 5
        T_SB_2000_MS = 6
        T_SB_4000_MS = 7

    Args:
        transport: Configured I²C transport pointing at the device.
        addr: 7-bit I²C address (default 0x76).
        osrs_t: Temperature oversampling (default 1 = ×1).
        osrs_p: Pressure oversampling (default 1 = ×1).
        mode: Power mode (default 1 = forced).
        filter: IIR filter coefficient (default 0 = off).
        t_sb: Standby time (default 0 = 0.5 ms).
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

    T_SB_0_5_MS  = 0
    T_SB_62_5_MS = 1
    T_SB_125_MS  = 2
    T_SB_250_MS  = 3
    T_SB_500_MS  = 4
    T_SB_1000_MS = 5
    T_SB_2000_MS = 6
    T_SB_4000_MS = 7

    STATUS_MEASURING = 0x08
    STATUS_IM_UPDATE = 0x01

    def __init__(self, transport, addr=0x76,
                 osrs_t=1, osrs_p=1, mode=1, filter=0, t_sb=0):
        super().__init__(transport, addr)
        self._osrs_t = osrs_t
        self._osrs_p = osrs_p
        self._mode   = mode
        self._filter = filter
        self._t_sb   = t_sb
        self._apply_config()

    def _apply_config(self):
        osrs_t = (self._osrs_t & 7) << 5
        osrs_p = (self._osrs_p & 7) << 2
        self._write_reg(self._REG_CTRL, osrs_t | osrs_p | (self._mode & 3))
        self._write_reg(self._REG_CONFIG,
                        (self._t_sb & 7) << 5 | (self._filter & 7) << 2)

    def configure(self, osrs_t=None, osrs_p=None, mode=None, filter=None, t_sb=None):
        """Apply a new configuration atomically.

        Args:
            osrs_t: Temperature oversampling (0–5).
            osrs_p: Pressure oversampling (0–5).
            mode: Power mode (0=sleep, 1=forced, 3=normal).
            filter: IIR filter coefficient (0–4).
            t_sb: Standby time in normal mode (0–7).
        """
        if osrs_t is not None: self._osrs_t = osrs_t
        if osrs_p is not None: self._osrs_p = osrs_p
        if mode   is not None: self._mode   = mode
        if filter is not None: self._filter = filter
        if t_sb   is not None: self._t_sb   = t_sb
        self._apply_config()

    def set_oversampling(self, osrs_t=None, osrs_p=None):
        """Update temperature and/or pressure oversampling.

        Args:
            osrs_t: Temperature oversampling (0–5).
            osrs_p: Pressure oversampling (0–5).
        """
        if osrs_t is not None: self._osrs_t = osrs_t
        if osrs_p is not None: self._osrs_p = osrs_p
        self._apply_config()

    def set_mode(self, mode):
        """Update power mode.

        Args:
            mode: 0=sleep, 1=forced, 3=normal.
        """
        self._mode = mode
        self._apply_config()

    def set_filter(self, coeff):
        """Update IIR filter coefficient.

        Args:
            coeff: 0=off, 1=×2, 2=×4, 3=×8, 4=×16.
        """
        self._filter = coeff
        self._apply_config()

    def set_standby(self, t_sb):
        """Update standby time (only relevant in Normal mode).

        Args:
            t_sb: 0=0.5ms, 1=62.5ms, 2=125ms, 3=250ms, 4=500ms,
                  5=1000ms, 6=2000ms, 7=4000ms.
        """
        self._t_sb = t_sb
        self._apply_config()

    def status(self):
        """Read the status register.

        Returns:
            int: Status byte; bits STATUS_MEASURING (0x08) and
                STATUS_IM_UPDATE (0x01).
        """
        return self._read_reg(self._REG_STATUS)

    def chip_id(self):
        """Read the chip ID register.

        Returns:
            int: Chip ID; expect 0x58.
        """
        return self._read_reg(self._REG_ID)

    def reset(self):
        """Perform a soft reset and re-read calibration coefficients."""
        self._write_reg(self._REG_RESET, self._RESET_CMD)
        time.sleep(0.002)
        self._read_calibration()
        self._apply_config()

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
        """Compute sea-level pressure that corresponds to the current pressure.

        Args:
            altitude_m: Altitude in metres.

        Returns:
            float: Sea-level pressure in hPa.
        """
        p = self.pressure()
        return p / (1 - altitude_m / 44330) ** 5.255