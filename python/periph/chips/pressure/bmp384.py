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


class BMP384Full(BMP384Minimal):
    """BMP384 full interface — extends BMP384Minimal with configuration, mode control, and FIFO access.

    Adds oversampling/IIR/ODR configuration, power-mode switching, data-ready
    polling, soft reset, and the 512-byte FIFO (with watermark, stop-on-full,
    and per-frame decoding).

    Args:
        connection: Configured I²C or SPI connection pointing at the device.
        bus_type: Bus type string, ``'i2c'`` (default) or ``'spi'``.
    """

    MODE_SLEEP  = 0x00
    MODE_FORCED = 0x01
    MODE_NORMAL = 0x03

    # FIFO frame header bytes (see spec §FIFO Frame Format).
    FIFO_HEADER_PRESS    = 0x84
    FIFO_HEADER_TEMP     = 0x90
    FIFO_HEADER_SENSORT  = 0xA0
    FIFO_HEADER_ERROR    = 0x44
    FIFO_HEADER_EMPTY    = 0x80

    def __init__(self, connection, bus_type='i2c'):
        super().__init__(connection, bus_type)

    def configure(self, osr_p, osr_t, iir_filter, odr_sel):
        """Write OSR, CONFIG, and ODR registers and verify ODR ≥ T_conv.

        Args:
            osr_p: Pressure oversampling (0–5, where 0=×1, 1=×2, … 5=×32).
            osr_t: Temperature oversampling (0–5).
            iir_filter: IIR filter coefficient index (0=bypass, 1=1, 2=3, … 7=127).
            odr_sel: Output data rate selector (0x00–0x11). See spec ODR table.

        Raises:
            ValueError: if ``odr_sel`` would set an ODR period shorter than
                T_conv at the chosen oversampling (which would set
                ``ERR_REG.conf_err`` and produce unreliable data).
        """
        self._osr_p = osr_p
        self._osr_t = osr_t
        self._iir = iir_filter
        self._odr = odr_sel
        t_conv_ms = self._compute_t_conv_ms(osr_p, osr_t)
        odr_period_ms = self._odr_period_ms(odr_sel)
        if odr_period_ms is not None and odr_period_ms < t_conv_ms:
            raise ValueError(
                'odr_sel 0x%02X (period %d ms) is shorter than T_conv %d ms at osr_p=%d osr_t=%d'
                % (odr_sel, odr_period_ms, t_conv_ms, osr_p, osr_t)
            )
        self._write_reg(self._REG_OSR, (osr_t << self._OSR_T_SHIFT) | (osr_p << self._OSR_P_SHIFT))
        self._write_reg(self._REG_CONFIG, (iir_filter << self._IIR_SHIFT))
        self._write_reg(self._REG_ODR, odr_sel)

    def read(self):
        """Read both pressure and temperature in a single burst.

        Returns:
            dict: ``{"pressure": float hPa, "temperature": float °C}``.
        """
        if self._mode == self._MODE_FORCED:
            self._trigger_forced()
        uncomp_press, uncomp_temp = self._read_burst()
        t = self._compensate_temperature(uncomp_temp)
        p = self._compensate_pressure(uncomp_press) / 100.0
        return {'pressure': p, 'temperature': t}

    def read_forced(self):
        """Trigger a forced-mode measurement, wait T_conv, then read both values.

        Returns:
            dict: ``{"pressure": float hPa, "temperature": float °C}``.
        """
        prev_mode = self._mode
        try:
            self.set_mode(self.MODE_FORCED)
            self._trigger_forced()
            time.sleep_ms(self._compute_t_conv_ms(self._osr_p, self._osr_t))
            uncomp_press, uncomp_temp = self._read_burst()
            t = self._compensate_temperature(uncomp_temp)
            p = self._compensate_pressure(uncomp_press) / 100.0
            return {'pressure': p, 'temperature': t}
        finally:
            self._mode = prev_mode
            self._apply_pwr()

    def set_mode(self, mode):
        """Set the power mode.

        Args:
            mode: ``MODE_SLEEP`` (0), ``MODE_FORCED`` (1), or ``MODE_NORMAL`` (3).
        """
        self._mode = mode
        self._apply_pwr()

    def is_data_ready(self):
        """Read the STATUS register and return ``drdy_press`` (bit 5).

        Returns:
            bool: True if a new pressure measurement is available.
        """
        raw = self._read_reg(0x03, 1)
        return bool(raw[0] & (1 << 5))

    def softreset(self):
        """Issue a soft reset, wait 2 ms, re-read calibration, re-apply config."""
        self._write_reg(self._REG_CMD, self._SOFT_RESET_CMD)
        time.sleep_ms(3)
        self._read_calibration()
        self._verify_chip_id()
        self._apply_config()

    def fifo_configure(self, press_en, temp_en, wtm, stop_on_full=False):
        """Configure the FIFO source, watermark, and stop-on-full behaviour.

        Args:
            press_en: Store pressure frames in FIFO.
            temp_en: Store temperature frames in FIFO.
            wtm: FIFO watermark in bytes (0–511). Triggers an interrupt when reached.
            stop_on_full: If True, stop writing at full (no overwrite); else overwrite oldest.
        """
        # FIFO_CONFIG_1: bit 4 fifo_mode, bit 3 stop_on_full, bit 1 temp_en, bit 0 press_en
        cfg1 = ((1 << 4)
                | ((1 if stop_on_full else 0) << 3)
                | ((1 if temp_en else 0) << 1)
                | (1 if press_en else 0))
        self._write_reg(0x17, cfg1)
        self._write_reg(0x15, wtm & 0xFF)
        self._write_reg(0x16, (wtm >> 8) & 0x01)

    def fifo_read(self):
        """Read and parse every available FIFO frame.

        Frames are parsed per the spec's FIFO Frame Format: 1-byte header
        followed by 3 payload bytes for sensor frames; 1-byte only for
        error and empty frames. Returns a list of decoded frames, oldest first.

        Returns:
            list: Each entry is ``{"type": str, "value": float | None}``.
                ``type`` is one of ``"pressure"``, ``"temperature"``,
                ``"sensortime"``, ``"error"``, ``"empty"``. ``value`` is
                in hPa for pressure, °C for temperature, or raw sensor-time
                ticks for sensortime. ``None`` for error and empty frames.
        """
        length_lo = self._read_reg(0x12, 1)[0]
        length_hi = self._read_reg(0x13, 1)[0]
        length = (length_hi << 8) | length_lo
        if length == 0:
            return []
        buf = self._read_reg(0x14, length)
        frames = []
        i = 0
        while i < len(buf):
            hdr = buf[i]
            if hdr == self.FIFO_HEADER_PRESS:
                uncomp = (buf[i + 3] << 16) | (buf[i + 2] << 8) | buf[i + 1]
                t_lin = getattr(self, '_t_lin', 0.0)
                value_pa = self._compensate_pressure_with_t_lin(uncomp, t_lin)
                frames.append({'type': 'pressure', 'value': value_pa / 100.0})
                i += 4
            elif hdr == self.FIFO_HEADER_TEMP:
                uncomp = (buf[i + 3] << 16) | (buf[i + 2] << 8) | buf[i + 1]
                t = self._compensate_temperature(uncomp)
                frames.append({'type': 'temperature', 'value': t})
                i += 4
            elif hdr == self.FIFO_HEADER_SENSORT:
                uncomp = (buf[i + 3] << 16) | (buf[i + 2] << 8) | buf[i + 1]
                frames.append({'type': 'sensortime', 'value': float(uncomp)})
                i += 4
            elif hdr in (self.FIFO_HEADER_ERROR, self.FIFO_HEADER_EMPTY):
                frames.append({'type': 'error' if hdr == self.FIFO_HEADER_ERROR else 'empty',
                               'value': None})
                i += 1
            else:
                frames.append({'type': 'unknown', 'value': None})
                i += 1
        return frames

    def fifo_flush(self):
        """Flush all FIFO contents (writes 0xB0 to CMD)."""
        self._write_reg(self._REG_CMD, self._FIFO_FLUSH_CMD)

    def altitude(self, sea_level_hpa=1013.25):
        """Compute altitude above sea level from the current pressure.

        Args:
            sea_level_hpa: Reference sea-level pressure in hPa (default 1013.25).

        Returns:
            float: Altitude in metres.
        """
        p = self.pressure()
        if p <= 0:
            return 0.0
        return 44330.0 * (1.0 - (p / sea_level_hpa) ** (1.0 / 5.255))

    def _trigger_forced(self):
        """Issue a single forced-mode measurement (does not wait)."""
        pwr_reg = (self._MODE_FORCED << 4) | self._PWR_TEMP_EN | self._PWR_PRESS_EN
        self._write_reg(self._REG_PWR_CTRL, pwr_reg)

    def _apply_pwr(self):
        """Write the current power-mode and sensor-enable bits to PWR_CTRL."""
        pwr_reg = (self._mode << 4) | self._PWR_TEMP_EN | self._PWR_PRESS_EN
        self._write_reg(self._REG_PWR_CTRL, pwr_reg)

    def _compensate_pressure_with_t_lin(self, uncomp_press, t_lin):
        """Pressure compensation using a caller-supplied t_lin (used by FIFO pressure frames)."""
        self._t_lin = t_lin
        return self._compensate_pressure(uncomp_press)

    @staticmethod
    def _compute_t_conv_ms(osr_p, osr_t):
        """Compute T_conv in milliseconds for the given oversampling (per spec §Timing Constraints)."""
        t_conv_us = 234
        t_conv_us += 392 + (1 << osr_p) * 2000
        t_conv_us += 313 + (1 << osr_t) * 2000
        return t_conv_us // 1000 + 1

    @staticmethod
    def _odr_period_ms(odr_sel):
        """Return the ODR period in milliseconds for the given odr_sel, or None for unknown rates."""
        odr_hz = {
            0x00: 200,    0x01: 100,    0x02: 50,     0x03: 25,
            0x04: 12.5,   0x05: 6.25,   0x06: 3.1,    0x07: 1.5,
            0x08: 0.78,   0x09: 0.39,   0x0A: 0.2,    0x0B: 0.1,
            0x0C: 0.05,   0x0D: 0.02,   0x0E: 0.01,   0x0F: 0.006,
            0x10: 0.003,  0x11: 25.0 / 16384.0,
        }
        if odr_sel not in odr_hz:
            return None
        return int(1000.0 / odr_hz[odr_sel]) + 1
