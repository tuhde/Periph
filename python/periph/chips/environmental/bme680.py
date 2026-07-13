import struct
import time
import math


_CONST_ARRAY1 = [
    2147483647, 2147483647, 2147483647, 2147483647, 2147483647,
    2126008810, 2147483647, 2130303777, 2147483647, 2147483647,
    2143188679, 2136746228, 2147483647, 2126008810, 2147483647,
    2147483647]

_CONST_ARRAY2 = [
    4096000000, 2048000000, 1024000000, 512000000, 255744255,
    127110228, 64000000, 32258064, 16016016, 8000000,
    4000000, 2000000, 1000000, 500000, 250000,
    125000]


class BME680Minimal:
    """BME680 4-in-1 environmental sensor: temperature, pressure, humidity, gas resistance.

    Provides calibrated readings with no configuration beyond the transport.
    I2C address is 0x76 (SDO=GND) or 0x77 (SDO=VDDIO).

    Default configuration (baked in at construction):
        - Power mode: forced (each call triggers a single-shot TPHG cycle)
        - osrs_t = x1, osrs_p = x1, osrs_h = x1
        - IIR filter off
        - Gas heater profile 0: 320 degC target, 150 ms duration, gas conversion enabled

    Args:
        transport: Configured I2C transport pointing at the device.
    """

    _REG_RES_HEAT_VAL   = 0x00
    _REG_RES_HEAT_RANGE = 0x02
    _REG_RANGE_SW_ERR   = 0x04
    _REG_MEAS_STATUS    = 0x1D
    _REG_PRESS_MSB      = 0x1F
    _REG_CTRL_GAS_0     = 0x70
    _REG_CTRL_GAS_1     = 0x71
    _REG_CTRL_HUM       = 0x72
    _REG_CTRL_MEAS      = 0x74
    _REG_CONFIG         = 0x75
    _REG_CAL_BLOCK1     = 0x8A
    _REG_ID             = 0xD0
    _REG_RESET          = 0xE0
    _REG_CAL_BLOCK2     = 0xE1

    _CHIP_ID            = 0x61
    _RESET_CMD          = 0xB6

    _MEAS_TIME_MS       = 200

    def __init__(self, transport):
        self._transport = transport
        self._osrs_t = 1
        self._osrs_p = 1
        self._osrs_h = 1
        self._filter = 0
        self._t_fine = 0
        self._ambient_temp = 25.0
        self._heat_temp = 320
        self._heat_dur = 150
        self._gas_enabled = True
        self._nb_conv = 0
        self._read_calibration()
        self._write_reg(self._REG_CTRL_HUM, self._osrs_h)
        self._write_reg(self._REG_CTRL_MEAS, (self._osrs_t << 5) | (self._osrs_p << 2) | 0)
        self._write_reg(self._REG_CONFIG, 0)
        self._setup_heater(0, self._heat_temp, self._heat_dur)
        self._write_reg(self._REG_CTRL_GAS_1, (1 << 4) | 0)

    def _read_calibration(self):
        b1 = self._transport.write_read(bytes([self._REG_CAL_BLOCK1]), 23)
        b2 = self._transport.write_read(bytes([self._REG_CAL_BLOCK2]), 14)
        s1 = self._transport.write_read(bytes([self._REG_RES_HEAT_VAL]), 1)
        s2 = self._transport.write_read(bytes([self._REG_RES_HEAT_RANGE]), 1)
        s3 = self._transport.write_read(bytes([self._REG_RANGE_SW_ERR]), 1)

        self._par_T2 = struct.unpack('<h', b1[0:2])[0]
        self._par_T3 = struct.unpack('<b', b1[2:3])[0]
        self._par_P1 = struct.unpack('<H', b1[4:6])[0]
        self._par_P2 = struct.unpack('<h', b1[6:8])[0]
        self._par_P3 = struct.unpack('<b', b1[8:9])[0]
        self._par_P4 = struct.unpack('<h', b1[10:12])[0]
        self._par_P5 = struct.unpack('<h', b1[12:14])[0]
        self._par_P7 = struct.unpack('<b', b1[14:15])[0]
        self._par_P6 = struct.unpack('<b', b1[15:16])[0]
        self._par_P8 = struct.unpack('<h', b1[18:20])[0]
        self._par_P9 = struct.unpack('<h', b1[20:22])[0]
        self._par_P10 = b1[22]

        self._par_H2 = (b2[0] << 4) | (b2[1] >> 4)
        self._par_H1 = (b2[2] << 4) | (b2[1] & 0x0F)
        self._par_H3 = struct.unpack('<b', b2[3:4])[0]
        self._par_H4 = struct.unpack('<b', b2[4:5])[0]
        self._par_H5 = struct.unpack('<b', b2[5:6])[0]
        self._par_H6 = b2[6]
        self._par_H7 = struct.unpack('<b', b2[7:8])[0]
        self._par_T1 = struct.unpack('<H', b2[8:10])[0]
        self._par_G2 = struct.unpack('<h', b2[10:12])[0]
        self._par_G1 = struct.unpack('<b', b2[12:13])[0]
        self._par_G3 = struct.unpack('<b', b2[13:14])[0]

        self._res_heat_val = struct.unpack('<b', s1[0:1])[0]
        self._res_heat_range = (s2[0] >> 4) & 0x03
        rse = (s3[0] >> 4) & 0x0F
        self._range_switching_error = rse if rse < 8 else rse - 16

    def _write_reg(self, reg, value):
        self._transport.write(bytes([reg, value]))

    def _read_reg(self, reg, n):
        return self._transport.write_read(bytes([reg]), n)

    def _calc_heater_resistance(self, target_temp, ambient_temp):
        par_g1 = self._par_G1
        par_g2 = self._par_G2
        par_g3 = self._par_G3
        rhr = self._res_heat_range
        rhv = self._res_heat_val

        var1 = ((ambient_temp * par_g3) // 10) << 8
        var2 = (par_g1 + 784) * (((((par_g2 + 154009) * target_temp * 5) // 100) + 3276800) // 10)
        var3 = var1 + (var2 >> 1)
        var4 = var3 // (rhr + 4)
        var5 = (131 * rhv) + 65536
        res_heat_x100 = ((var4 // var5) - 250) * 34
        res_heat_x = (res_heat_x100 + 50) // 100
        return res_heat_x & 0xFF

    def _calc_gas_wait(self, target_ms):
        if target_ms <= 0x3F:
            return target_ms
        elif target_ms <= 0x3F * 4:
            return (1 << 6) | (target_ms // 4)
        elif target_ms <= 0x3F * 16:
            return (2 << 6) | (target_ms // 16)
        else:
            return (3 << 6) | min(target_ms // 64, 0x3F)

    def _setup_heater(self, index, temp_c, dur_ms):
        res = self._calc_heater_resistance(temp_c, self._ambient_temp)
        gw = self._calc_gas_wait(dur_ms)
        self._write_reg(0x5A + index, res)
        self._write_reg(0x64 + index, gw)

    def _trigger_and_read(self):
        self._write_reg(self._REG_CTRL_HUM, self._osrs_h)
        ctrl = (self._osrs_t << 5) | (self._osrs_p << 2) | 1
        self._write_reg(self._REG_CTRL_MEAS, ctrl)
        time.sleep(self._MEAS_TIME_MS / 1000.0)
        raw = self._read_reg(self._REG_PRESS_MSB, 13)
        press_adc = (raw[0] << 12) | (raw[1] << 4) | (raw[2] >> 4)
        temp_adc = (raw[3] << 12) | (raw[4] << 4) | (raw[5] >> 4)
        hum_adc = (raw[6] << 8) | raw[7]
        gas_adc = (raw[11] << 2) | (raw[12] >> 6)
        gas_range = raw[12] & 0x0F
        gas_valid = (raw[12] >> 5) & 1
        heat_stab = (raw[12] >> 4) & 1
        return press_adc, temp_adc, hum_adc, gas_adc, gas_range, gas_valid, heat_stab

    def _compensate_temp(self, adc_T):
        par_T1 = self._par_T1
        par_T2 = self._par_T2
        par_T3 = self._par_T3
        var1 = (adc_T >> 3) - (par_T1 << 1)
        var2 = (var1 * par_T2) >> 11
        var3 = (((var1 >> 1) * (var1 >> 1)) >> 12) * (par_T3 << 4) >> 14
        self._t_fine = var2 + var3
        return ((self._t_fine * 5 + 128) >> 8) / 100.0

    def _compensate_pressure(self, adc_P):
        t_fine = self._t_fine
        par_P1 = self._par_P1
        par_P2 = self._par_P2
        par_P3 = self._par_P3
        par_P4 = self._par_P4
        par_P5 = self._par_P5
        par_P6 = self._par_P6
        par_P7 = self._par_P7
        par_P8 = self._par_P8
        par_P9 = self._par_P9
        par_P10 = self._par_P10

        var1 = (t_fine >> 1) - 64000
        var2 = ((((var1 >> 2) * (var1 >> 2)) >> 11) * par_P6) >> 2
        var2 = var2 + ((var1 * par_P5) << 1)
        var2 = (var2 >> 2) + (par_P4 << 16)
        var1 = (((((var1 >> 2) * (var1 >> 2)) >> 13) * (par_P3 << 5)) >> 3) + ((par_P2 * var1) >> 1)
        var1 = var1 >> 18
        var1 = ((32768 + var1) * par_P1) >> 15
        press_comp = 1048576 - adc_P
        press_comp = ((press_comp - (var2 >> 12)) * 3125)
        if press_comp >= (1 << 30):
            press_comp = (press_comp // var1) << 1
        else:
            press_comp = (press_comp << 1) // var1
        var1 = (par_P9 * (((press_comp >> 3) * (press_comp >> 3)) >> 13)) >> 12
        var2 = ((press_comp >> 2) * par_P8) >> 13
        var3 = ((press_comp >> 8) * (press_comp >> 8) * (press_comp >> 8) * par_P10) >> 17
        press_comp = press_comp + ((var1 + var2 + var3 + (par_P7 << 7)) >> 4)
        return press_comp / 100.0

    def _compensate_humidity(self, hum_adc):
        t_fine = self._t_fine
        par_H1 = self._par_H1
        par_H2 = self._par_H2
        par_H3 = self._par_H3
        par_H4 = self._par_H4
        par_H5 = self._par_H5
        par_H6 = self._par_H6
        par_H7 = self._par_H7

        temp_scaled = t_fine
        var1 = hum_adc - ((par_H1 << 4) + (((temp_scaled * par_H3) // 100) >> 1))
        var2 = (par_H2 * (((temp_scaled * par_H4) // 100) +
                          (((temp_scaled * ((temp_scaled * par_H5) // 100)) >> 6) // 100) +
                          (1 << 14))) >> 10
        var3 = var1 * var2
        var4 = ((par_H6 << 7) + ((temp_scaled * par_H7) // 100)) >> 4
        var5 = ((var3 >> 14) * (var3 >> 14)) >> 10
        var6 = (var4 * var5) >> 1
        hum_comp = (((var3 + var6) >> 10) * 1000) >> 12
        if hum_comp < 0:
            hum_comp = 0
        if hum_comp > 100000:
            hum_comp = 100000
        return hum_comp / 1000.0

    def _compensate_gas(self, gas_adc, gas_range):
        rse = self._range_switching_error
        var1 = ((1340 + 5 * rse) * _CONST_ARRAY1[gas_range]) >> 16
        var2 = ((gas_adc << 15) - (1 << 24)) + var1
        if var2 == 0:
            return float('nan')
        gas_res = ((_CONST_ARRAY2[gas_range] * var1) >> 9) + (var2 >> 1)
        gas_res = gas_res // var2
        return float(gas_res)

    def temperature(self):
        """Read calibrated temperature.

        Triggers a forced TPHG measurement cycle. Caches t_fine for
        subsequent pressure and humidity compensation. Updates the
        ambient temperature used for heater-resistance calculation.

        Returns:
            float: Temperature in degrees Celsius.
        """
        _, temp_adc, _, _, _, _, _ = self._trigger_and_read()
        t = self._compensate_temp(temp_adc)
        self._ambient_temp = t
        return t

    def pressure(self):
        """Read calibrated pressure.

        Triggers a forced TPHG measurement cycle. Refreshes t_fine.

        Returns:
            float: Pressure in hPa.
        """
        press_adc, temp_adc, _, _, _, _, _ = self._trigger_and_read()
        self._compensate_temp(temp_adc)
        return self._compensate_pressure(press_adc)

    def humidity(self):
        """Read calibrated humidity.

        Triggers a forced TPHG measurement cycle. Refreshes t_fine.

        Returns:
            float: Relative humidity in %RH.
        """
        _, temp_adc, hum_adc, _, _, _, _ = self._trigger_and_read()
        self._compensate_temp(temp_adc)
        return self._compensate_humidity(hum_adc)

    def gas_resistance(self):
        """Read gas sensor resistance.

        Triggers a forced TPHG measurement cycle. Returns NaN if the
        heater did not stabilize or the gas reading is invalid.

        Returns:
            float: Gas resistance in Ohms, or NaN on invalid reading.
        """
        _, temp_adc, _, gas_adc, gas_range, gas_valid, heat_stab = self._trigger_and_read()
        self._compensate_temp(temp_adc)
        if not gas_valid or not heat_stab:
            return float('nan')
        return self._compensate_gas(gas_adc, gas_range)


class BME680Full(BME680Minimal):
    """BME680 full interface — extends BME680Minimal with configuration, multi-profile heater, and status.

    Adds oversampling for all three TPH channels, IIR filter, multi-profile
    heater control, ambient-temperature override, read_all, and status queries.

    Args:
        transport: Configured I2C transport pointing at the device.
    """

    OSRS_SKIP = 0
    OSRS_X1   = 1
    OSRS_X2   = 2
    OSRS_X4   = 3
    OSRS_X8   = 4
    OSRS_X16  = 5

    MODE_SLEEP  = 0
    MODE_FORCED = 1

    FILTER_0   = 0
    FILTER_1   = 1
    FILTER_3   = 2
    FILTER_7   = 3
    FILTER_15  = 4
    FILTER_31  = 5
    FILTER_63  = 6
    FILTER_127 = 7

    STATUS_NEW_DATA     = 0x80
    STATUS_GAS_MEASURING = 0x40
    STATUS_MEASURING    = 0x20
    STATUS_GAS_VALID    = 0x20
    STATUS_HEATER_STABLE = 0x10

    def __init__(self, transport):
        super().__init__(transport)

    def configure(self, osrs_t=1, osrs_p=1, osrs_h=1, mode=0, filter=0):
        """Write ctrl_hum, ctrl_meas, and config registers in the correct order.

        Args:
            osrs_t: Temperature oversampling (0-5).
            osrs_p: Pressure oversampling (0-5).
            osrs_h: Humidity oversampling (0-5).
            mode: Power mode (0=sleep, 1=forced).
            filter: IIR filter coefficient (0-7).
        """
        self._osrs_t = osrs_t
        self._osrs_p = osrs_p
        self._osrs_h = osrs_h
        self._filter = filter
        self._write_reg(self._REG_CTRL_HUM, osrs_h)
        self._write_reg(self._REG_CONFIG, filter << 2)
        self._write_reg(self._REG_CTRL_MEAS, (osrs_t << 5) | (osrs_p << 2) | mode)

    def set_oversampling(self, osrs_t, osrs_p, osrs_h):
        """Update oversampling for all three TPH channels.

        Args:
            osrs_t: Temperature oversampling (0-5).
            osrs_p: Pressure oversampling (0-5).
            osrs_h: Humidity oversampling (0-5).
        """
        self._osrs_t = osrs_t
        self._osrs_p = osrs_p
        self._osrs_h = osrs_h
        self._write_reg(self._REG_CTRL_HUM, osrs_h)
        self._write_reg(self._REG_CTRL_MEAS, (osrs_t << 5) | (osrs_p << 2) | 0)

    def set_filter(self, coeff):
        """Update IIR filter coefficient.

        Args:
            coeff: Filter coefficient (0-7).
        """
        self._filter = coeff
        self._write_reg(self._REG_CONFIG, coeff << 2)

    def set_heater(self, temp_c, duration_ms):
        """Configure heater profile 0 and activate it.

        Args:
            temp_c: Target heater temperature in degrees Celsius.
            duration_ms: Heater on-time in milliseconds (1-4032).
        """
        self._heat_temp = temp_c
        self._heat_dur = duration_ms
        self._setup_heater(0, temp_c, duration_ms)
        self._write_reg(self._REG_CTRL_GAS_1, (1 << 4) | 0)

    def set_heater_profile(self, index, temp_c, duration_ms):
        """Configure one of the 10 heater profiles.

        Args:
            index: Profile index (0-9).
            temp_c: Target heater temperature in degrees Celsius.
            duration_ms: Heater on-time in milliseconds (1-4032).
        """
        self._setup_heater(index, temp_c, duration_ms)

    def select_heater_profile(self, index):
        """Select which heater profile to use in the next forced cycle.

        Args:
            index: Profile index (0-9).
        """
        self._nb_conv = index
        gas1 = (1 << 4) | index if self._gas_enabled else index
        self._write_reg(self._REG_CTRL_GAS_1, gas1)

    def set_gas_enabled(self, enabled):
        """Enable or disable gas conversion.

        Args:
            enabled: True to enable gas measurement.
        """
        self._gas_enabled = enabled
        gas1 = (1 << 4) | self._nb_conv if enabled else self._nb_conv
        self._write_reg(self._REG_CTRL_GAS_1, gas1)

    def set_heater_off(self, off):
        """Turn the heater off or on via ctrl_gas_0.

        Args:
            off: True to disable the heater.
        """
        self._write_reg(self._REG_CTRL_GAS_0, 0x08 if off else 0x00)

    def set_ambient_temperature(self, temp_c):
        """Override the ambient temperature used for heater-resistance calculation.

        Also re-applies the active heater profile with the new ambient value.

        Args:
            temp_c: Ambient temperature in degrees Celsius.
        """
        self._ambient_temp = temp_c
        self._setup_heater(self._nb_conv, self._heat_temp, self._heat_dur)

    def read_all(self):
        """Read all four sensor values from a single TPHG cycle.

        Returns:
            tuple: (temperature_C, pressure_hPa, humidity_RH, gas_resistance_Ohms).
                Gas resistance is NaN if the reading is invalid.
        """
        press_adc, temp_adc, hum_adc, gas_adc, gas_range, gas_valid, heat_stab = self._trigger_and_read()
        t = self._compensate_temp(temp_adc)
        self._ambient_temp = t
        p = self._compensate_pressure(press_adc)
        h = self._compensate_humidity(hum_adc)
        if gas_valid and heat_stab:
            g = self._compensate_gas(gas_adc, gas_range)
        else:
            g = float('nan')
        return (t, p, h, g)

    def gas_valid(self):
        """Check if the most recent gas reading is valid.

        Returns:
            bool: True if gas_valid_r was set.
        """
        raw = self._read_reg(0x2B, 1)
        return bool((raw[0] >> 5) & 1)

    def heater_stable(self):
        """Check if the heater reached its target temperature.

        Returns:
            bool: True if heat_stab_r was set.
        """
        raw = self._read_reg(0x2B, 1)
        return bool((raw[0] >> 4) & 1)

    def status(self):
        """Read the measurement status register.

        Returns:
            int: Status byte with flags STATUS_NEW_DATA, STATUS_MEASURING, STATUS_GAS_MEASURING.
        """
        raw = self._read_reg(self._REG_MEAS_STATUS, 1)
        return raw[0]

    def chip_id(self):
        """Read the chip ID register.

        Returns:
            int: Chip ID; expect 0x61.
        """
        raw = self._read_reg(self._REG_ID, 1)
        return raw[0]

    def reset(self):
        """Perform a soft reset, re-read calibration, and re-apply configuration."""
        self._write_reg(self._REG_RESET, self._RESET_CMD)
        time.sleep(0.002)
        self._read_calibration()
        self._write_reg(self._REG_CTRL_HUM, self._osrs_h)
        self._write_reg(self._REG_CONFIG, self._filter << 2)
        self._write_reg(self._REG_CTRL_MEAS, (self._osrs_t << 5) | (self._osrs_p << 2) | 0)
        self._setup_heater(self._nb_conv, self._heat_temp, self._heat_dur)
        gas1 = (1 << 4) | self._nb_conv if self._gas_enabled else self._nb_conv
        self._write_reg(self._REG_CTRL_GAS_1, gas1)
