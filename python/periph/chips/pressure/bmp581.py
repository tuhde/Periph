import time


_REG_CHIP_ID       = 0x01
_REG_REV_ID        = 0x02
_REG_CHIP_STATUS   = 0x11
_REG_DRIVE_CONFIG  = 0x13
_REG_INT_CONFIG    = 0x14
_REG_INT_SOURCE    = 0x15
_REG_FIFO_CONFIG   = 0x16
_REG_FIFO_COUNT    = 0x17
_REG_FIFO_SEL      = 0x18
_REG_TEMP_XLSB     = 0x1D
_REG_TEMP_LSB      = 0x1E
_REG_TEMP_MSB      = 0x1F
_REG_PRESS_XLSB    = 0x20
_REG_PRESS_LSB     = 0x21
_REG_PRESS_MSB     = 0x22
_REG_INT_STATUS    = 0x27
_REG_STATUS        = 0x28
_REG_FIFO_DATA     = 0x29
_REG_NVM_ADDR      = 0x2B
_REG_NVM_DATA_LSB  = 0x2C
_REG_NVM_DATA_MSB  = 0x2D
_REG_DSP_CONFIG    = 0x30
_REG_DSP_IIR       = 0x31
_REG_OOR_THR_P_LSB = 0x32
_REG_OOR_THR_P_MSB = 0x33
_REG_OOR_RANGE     = 0x34
_REG_OOR_CONFIG    = 0x35
_REG_OSR_CONFIG    = 0x36
_REG_ODR_CONFIG    = 0x37
_REG_OSR_EFF       = 0x38
_REG_CMD           = 0x7E

_CHIP_ID_EXPECTED  = 0x50
_SOFT_RESET_CMD    = 0xB6
_NVM_PROG_EN       = 0x40
_NVM_READ_SEQ      = 0x5D
_NVM_READ_TRIG     = 0xA5
_NVM_WRITE_SEQ     = 0x5D
_NVM_WRITE_TRIG    = 0xA0

_STATUS_NVM_RDY    = 0x02
_STATUS_NVM_ERR    = 0x04
_INT_STATUS_POR    = 0x10
_INT_STATUS_DRDY   = 0x01


def _u24(data):
    raw = (data[2] << 16) | (data[1] << 8) | data[0]
    if raw & 0x800000:
        raw -= 0x1000000
    return raw


class BMP581Minimal:
    """BMP581 MEMS barometric pressure + temperature sensor — minimal interface.

    Provides calibrated temperature and pressure in NORMAL mode at 1 Hz with
    no configuration beyond the connection. I²C address is 0x46 (SDO=GND)
    or 0x47 (SDO=VDDIO).

    Default configuration (baked in at construction):
        - Power mode: NORMAL
        - ODR: 1 Hz
        - press_en = 1, osr_p = x1, osr_t = x1
        - comp_pt_en = 0x3 (P+T compensation; chip default, not written)
        - IIR filter = bypass (chip default, not written)
        - FIFO disabled
        - INT_SOURCE = 0 (no interrupt sources active)

    Args:
        connection: Configured I²C or SPI connection pointing at the device.
        bus_type: Bus type string, ``'i2c'`` (default) or ``'spi'``.
    """

    def __init__(self, connection, bus_type='i2c'):
        self._connection = connection
        self._bus_type = bus_type
        self._init()

    def _write_reg(self, reg, value):
        if self._bus_type == 'spi':
            reg = reg & 0x7F
        self._connection.write(bytes([reg, value & 0xFF]))

    def _read_reg(self, reg, n):
        if self._bus_type == 'spi':
            self._connection.write(bytes([reg | 0x80]))
            return self._connection.read(n)
        return self._connection.write_read(bytes([reg]), n)

    def _init(self):
        if self._bus_type == 'spi':
            try:
                self._read_reg(_REG_CHIP_ID, 1)
            except Exception:
                pass
        cid = self._read_reg(_REG_CHIP_ID, 1)[0]
        if cid != _CHIP_ID_EXPECTED:
            raise OSError('BMP581 chip ID mismatch: expected 0x50, got 0x{:02X}'.format(cid))
        for _ in range(50):
            st = self._read_reg(_REG_STATUS, 1)[0]
            if (st & _STATUS_NVM_RDY) and not (st & _STATUS_NVM_ERR):
                break
            time.sleep(0.002)
        else:
            raise OSError('BMP581 STATUS.nvm_rdy never set')
        try:
            self._read_reg(_REG_INT_STATUS, 1)
        except Exception:
            pass
        try:
            self._write_reg(_REG_CMD, _SOFT_RESET_CMD)
        except Exception:
            pass
        time.sleep(0.002)
        for _ in range(50):
            st = self._read_reg(_REG_STATUS, 1)[0]
            if (st & _STATUS_NVM_RDY) and not (st & _STATUS_NVM_ERR):
                break
            time.sleep(0.002)
        try:
            self._read_reg(_REG_INT_STATUS, 1)
        except Exception:
            pass
        self._write_reg(_REG_OSR_CONFIG, 0x40)
        self._write_reg(_REG_ODR_CONFIG, 0x71)
        self._odr = 0x1C
        self._pwr_mode = 0x01
        self._osr_p = 0
        self._osr_t = 0
        self._press_en = True

    def _read_both(self):
        data = self._read_reg(_REG_TEMP_XLSB, 6)
        raw_t = _u24(data[0:3])
        raw_p = _u24(data[3:6])
        return raw_p / 64.0, raw_t / 65536.0

    def pressure(self):
        """Read calibrated pressure.

        Returns:
            float: Pressure in Pa.
        """
        if self._pwr_mode == 2:
            for _ in range(200):
                st = self._read_reg(_REG_INT_STATUS, 1)[0]
                if st & _INT_STATUS_DRDY:
                    break
                time.sleep(0.005)
        data = self._read_reg(_REG_PRESS_XLSB, 3)
        return _u24(data) / 64.0

    def temperature(self):
        """Read calibrated temperature.

        Returns:
            float: Temperature in degrees Celsius.
        """
        if self._pwr_mode == 2:
            for _ in range(200):
                st = self._read_reg(_REG_INT_STATUS, 1)[0]
                if st & _INT_STATUS_DRDY:
                    break
                time.sleep(0.005)
        data = self._read_reg(_REG_TEMP_XLSB, 3)
        return _u24(data) / 65536.0

    def both(self):
        """Read both pressure and temperature in a single burst transaction.

        Returns:
            tuple: (pressure_Pa, temperature_C).
        """
        if self._pwr_mode == 2:
            for _ in range(200):
                st = self._read_reg(_REG_INT_STATUS, 1)[0]
                if st & _INT_STATUS_DRDY:
                    break
                time.sleep(0.005)
        return self._read_both()


class BMP581Full(BMP581Minimal):
    """BMP581 full interface — extends BMP581Minimal with configuration, FIFO,
    interrupts, OOR detection, and NVM access.

    Adds power-mode control, oversampling, IIR filter, FIFO configuration,
    interrupt configuration, out-of-range threshold configuration, and
    NVM read/write.

    Args:
        connection: Configured I²C or SPI connection pointing at the device.
        bus_type: Bus type string, ``'i2c'`` (default) or ``'spi'``.
    """

    OSR_1X   = 0
    OSR_2X   = 1
    OSR_4X   = 2
    OSR_8X   = 3
    OSR_16X  = 4
    OSR_32X  = 5
    OSR_64X  = 6
    OSR_128X = 7

    MODE_STANDBY    = 0
    MODE_NORMAL     = 1
    MODE_FORCED     = 2
    MODE_CONTINUOUS = 3

    IIR_BYPASS    = 0
    IIR_COEFF_1   = 1
    IIR_COEFF_3   = 2
    IIR_COEFF_7   = 3
    IIR_COEFF_15  = 4
    IIR_COEFF_31  = 5
    IIR_COEFF_63  = 6
    IIR_COEFF_127 = 7

    FIFO_DISABLED = 0
    FIFO_TEMP     = 1
    FIFO_PRESS    = 2
    FIFO_BOTH     = 3

    FIFO_STREAM     = 0
    FIFO_STOP_ON_FULL = 1

    INT_SOURCE_DRDY = 0x01
    INT_SOURCE_FIFO_FULL = 0x02
    INT_SOURCE_FIFO_THS = 0x04
    INT_SOURCE_OOR_P = 0x08

    def __init__(self, connection, bus_type='i2c'):
        super().__init__(connection, bus_type)

    def configure(self, odr=0x1C, osr_p=0, osr_t=0, press_en=True):
        """Write OSR_CONFIG and ODR_CONFIG atomically.

        Args:
            odr: ODR field value 0x00-0x1F (default 0x1C = 1 Hz).
            osr_p: Pressure oversampling 0-7 (default 0 = x1).
            osr_t: Temperature oversampling 0-7 (default 0 = x1).
            press_en: True to enable pressure measurements (default).
        """
        self._odr = odr
        self._osr_p = osr_p
        self._osr_t = osr_t
        self._press_en = press_en
        press_bit = 0x40 if press_en else 0
        self._write_reg(_REG_OSR_CONFIG, press_bit | ((osr_p & 0x7) << 3) | (osr_t & 0x7))
        odr_byte = ((odr & 0x1F) << 2) | (self._pwr_mode & 0x3)
        self._write_reg(_REG_ODR_CONFIG, odr_byte)

    def set_mode(self, mode):
        """Set the power mode (preserves current ODR setting).

        Args:
            mode: MODE_STANDBY (0), MODE_NORMAL (1), MODE_FORCED (2),
                MODE_CONTINUOUS (3).
        """
        self._pwr_mode = mode
        odr_byte = ((self._odr & 0x1F) << 2) | (mode & 0x3)
        self._write_reg(_REG_ODR_CONFIG, odr_byte)

    def forced(self):
        """Trigger a single FORCED measurement, wait for completion, return readings.

        Returns:
            tuple: (pressure_Pa, temperature_C).
        """
        prev_mode = self._pwr_mode
        if prev_mode != 2:
            self.set_mode(2)
        for _ in range(400):
            st = self._read_reg(_REG_INT_STATUS, 1)[0]
            if st & _INT_STATUS_DRDY:
                break
            time.sleep(0.005)
        p, t = self._read_both()
        return p, t

    def altitude(self, sea_level_pa=101325.0):
        """Compute altitude from the current pressure reading.

        Args:
            sea_level_pa: Sea-level reference pressure in Pa (default 101325).

        Returns:
            float: Altitude in metres.
        """
        p = self.pressure()
        if p <= 0:
            return 0.0
        return 44330.0 * (1.0 - (p / sea_level_pa) ** (1.0 / 5.255))

    def software_reset(self):
        """Issue a soft reset and re-initialise the chip with current config."""
        try:
            self._write_reg(_REG_CMD, _SOFT_RESET_CMD)
        except Exception:
            pass
        time.sleep(0.002)
        self._init()

    def chip_id(self):
        """Read CHIP_ID (0x01).

        Returns:
            int: 0x50 for a genuine BMP581.
        """
        return self._read_reg(_REG_CHIP_ID, 1)[0]

    def rev_id(self):
        """Read REV_ID (0x02).

        Returns:
            int: ASIC revision identifier.
        """
        return self._read_reg(_REG_REV_ID, 1)[0]

    def status(self):
        """Read STATUS (0x28).

        Returns:
            int: Raw status byte.
        """
        return self._read_reg(_REG_STATUS, 1)[0]

    def interrupt_status(self):
        """Read INT_STATUS (0x27) — clear-on-read.

        Returns:
            int: Raw interrupt status byte; reading clears all bits.
        """
        return self._read_reg(_REG_INT_STATUS, 1)[0]

    def data_ready(self):
        """Check whether a new data sample is available.

        Reads INT_STATUS (clear-on-read).

        Returns:
            bool: True if drdy_data_reg is set.
        """
        return bool(self.interrupt_status() & _INT_STATUS_DRDY)

    def configure_interrupt(self, mode=0, polarity=0, open_drain=False, enable=True):
        """Write INT_CONFIG: latching, polarity, drive mode, pin enable.

        Args:
            mode: 0 = pulsed, 1 = latched (default 0).
            polarity: 0 = active-low, 1 = active-high (default 0).
            open_drain: True for open-drain output (default False = push-pull).
            enable: True to enable the INT pin driver (default).
        """
        val = 0x08 if enable else 0
        if open_drain:
            val |= 0x04
        if polarity:
            val |= 0x02
        if mode:
            val |= 0x01
        self._write_reg(_REG_INT_CONFIG, val)

    def enable_drdy_interrupt(self, enable=True):
        """Enable/disable the data-ready interrupt source."""
        self._set_int_source(self.INT_SOURCE_DRDY, enable)

    def enable_fifo_interrupt(self, threshold=True, full=False):
        """Enable/disable FIFO threshold and FIFO-full interrupt sources."""
        cur = self._read_reg(_REG_INT_SOURCE, 1)[0]
        cur &= ~(self.INT_SOURCE_FIFO_FULL | self.INT_SOURCE_FIFO_THS)
        if threshold:
            cur |= self.INT_SOURCE_FIFO_THS
        if full:
            cur |= self.INT_SOURCE_FIFO_FULL
        self._write_reg(_REG_INT_SOURCE, cur)

    def enable_oor_interrupt(self, enable=True):
        """Enable/disable the pressure out-of-range interrupt source."""
        self._set_int_source(self.INT_SOURCE_OOR_P, enable)

    def _set_int_source(self, source, enable):
        cur = self._read_reg(_REG_INT_SOURCE, 1)[0]
        if enable:
            cur |= source
        else:
            cur &= ~source
        self._write_reg(_REG_INT_SOURCE, cur)

    def set_iir_filter(self, coeff_p, coeff_t):
        """Set IIR filter coefficients for pressure and temperature.

        Also sets shdw_sel_iir_p/t in DSP_CONFIG so the data registers hold
        post-IIR values.

        Args:
            coeff_p: Pressure filter coefficient 0-7 (0 = bypass).
            coeff_t: Temperature filter coefficient 0-7 (0 = bypass).
        """
        dsp = self._read_reg(_REG_DSP_CONFIG, 1)[0]
        dsp |= 0x20
        dsp |= 0x08
        dsp &= ~0x10
        dsp &= ~0x04
        self._write_reg(_REG_DSP_CONFIG, dsp)
        iir_val = ((coeff_p & 0x7) << 3) | (coeff_t & 0x7)
        self._write_reg(_REG_DSP_IIR, iir_val)

    def configure_fifo(self, frame_sel, mode=0, threshold=0):
        """Configure FIFO source, mode, and threshold.

        Must be called in STANDBY mode.

        Args:
            frame_sel: FIFO_DISABLED (0), FIFO_TEMP (1), FIFO_PRESS (2),
                FIFO_BOTH (3).
            mode: FIFO_STREAM (0) or FIFO_STOP_ON_FULL (1).
            threshold: 0-31 frames (0 = disabled).
        """
        prev_mode = self._pwr_mode
        if prev_mode != 0:
            self.set_mode(0)
        self._write_reg(_REG_FIFO_SEL, ((frame_sel & 0x3) << 0))
        fifo_cfg = ((mode & 0x1) << 5) | (threshold & 0x1F)
        self._write_reg(_REG_FIFO_CONFIG, fifo_cfg)
        if prev_mode != 0:
            self.set_mode(prev_mode)

    def fifo_count(self):
        """Read the number of frames currently in the FIFO.

        Returns:
            int: Number of frames (0-32).
        """
        return self._read_reg(_REG_FIFO_COUNT, 1)[0] & 0x3F

    def read_fifo(self):
        """Drain all frames from the FIFO and decode them.

        The frame layout matches the FIFO_SEL.fifo_frame_sel setting:
            - 0 (DISABLED): always empty list
            - 1 (TEMP):      [t0, t1, ...]
            - 2 (PRESS):     [p0, p1, ...]
            - 3 (BOTH):      [(p0,t0), (p1,t1), ...]

        Returns:
            list: Per-frame readings.
        """
        sel = self._read_reg(_REG_FIFO_SEL, 1)[0] & 0x3
        n = self.fifo_count()
        if n == 0 or sel == 0:
            return []
        bytes_per_frame = 3
        if sel == 3:
            bytes_per_frame = 6
        elif sel == 1:
            bytes_per_frame = 3
        elif sel == 2:
            bytes_per_frame = 3
        buf = self._read_reg(_REG_FIFO_DATA, n * bytes_per_frame)
        result = []
        for i in range(n):
            offset = i * bytes_per_frame
            if sel == 3:
                raw_t = _u24(buf[offset:offset + 3])
                raw_p = _u24(buf[offset + 3:offset + 6])
                result.append((raw_p / 64.0, raw_t / 65536.0))
            elif sel == 2:
                result.append(_u24(buf[offset:offset + 3]) / 64.0)
            else:
                result.append(_u24(buf[offset:offset + 3]) / 65536.0)
        return result

    def effective_osr(self):
        """Read OSR_EFF.

        Returns:
            tuple: (osr_p_eff, osr_t_eff) as 0-7 values.
        """
        val = self._read_reg(_REG_OSR_EFF, 1)[0]
        return ((val >> 3) & 0x7, val & 0x7)

    def odr_is_valid(self):
        """Check whether the currently configured ODR/OSR combination is valid.

        Returns:
            bool: True if odr_is_valid bit is set.
        """
        return bool(self._read_reg(_REG_OSR_EFF, 1)[0] & 0x80)

    def set_oor_threshold(self, threshold_pa, range_pa, count_limit=0):
        """Configure the out-of-range pressure detector.

        Args:
            threshold_pa: Pressure threshold in Pa.
            range_pa: Symmetric +/- window around the threshold in Pa.
            count_limit: 0-3 successive over-threshold events required to fire.
        """
        oor_thr_17bit = int(threshold_pa * 64.0) >> 7
        oor_thr_p_16 = (oor_thr_17bit >> 16) & 0x01
        oor_thr_p_msb = (oor_thr_17bit >> 8) & 0xFF
        oor_thr_p_lsb = oor_thr_17bit & 0xFF
        self._write_reg(_REG_OOR_THR_P_LSB, oor_thr_p_lsb)
        self._write_reg(_REG_OOR_THR_P_MSB, oor_thr_p_msb)
        oor_range_8bit = int(range_pa * 64.0) >> 7
        self._write_reg(_REG_OOR_RANGE, oor_range_8bit & 0xFF)
        oor_cfg = ((count_limit & 0x3) << 6) | (oor_thr_p_16 & 0x01)
        self._write_reg(_REG_OOR_CONFIG, oor_cfg)

    def nvm_read(self, row):
        """Read one user NVM row (3 rows: 0x20, 0x21, 0x22).

        Args:
            row: NVM row address 0x20-0x22.

        Returns:
            int: 16-bit value stored in the row.
        """
        prev_mode = self._pwr_mode
        if prev_mode != 0:
            self.set_mode(0)
        try:
            self._write_reg(_REG_NVM_ADDR, _NVM_READ_SEQ)
            self._write_reg(_REG_CMD, _NVM_READ_TRIG)
            time.sleep(0.002)
            self._write_reg(_REG_NVM_ADDR, _NVM_PROG_EN | (row & 0x3F))
            self._write_reg(_REG_CMD, _NVM_READ_TRIG)
            time.sleep(0.002)
            data = self._read_reg(_REG_NVM_DATA_LSB, 2)
            return (data[1] << 8) | data[0]
        finally:
            if prev_mode != 0:
                self.set_mode(prev_mode)

    def nvm_write(self, row, value):
        """Write one user NVM row.

        Args:
            row: NVM row address 0x20-0x22.
            value: 16-bit value to store.

        Note:
            Limited to 10,000 total write cycles across all rows.
        """
        prev_mode = self._pwr_mode
        if prev_mode != 0:
            self.set_mode(0)
        try:
            self._write_reg(_REG_NVM_ADDR, _NVM_PROG_EN | (row & 0x3F))
            self._write_reg(_REG_NVM_DATA_LSB, value & 0xFF)
            self._write_reg(_REG_NVM_DATA_MSB, (value >> 8) & 0xFF)
            self._write_reg(_REG_NVM_ADDR, _NVM_WRITE_SEQ)
            self._write_reg(_REG_CMD, _NVM_WRITE_TRIG)
            time.sleep(0.005)
        finally:
            if prev_mode != 0:
                self.set_mode(prev_mode)