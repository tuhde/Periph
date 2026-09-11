import struct
import time


class LPS28DFWMinimal:
    """LPS28DFW dual full-scale digital barometer — minimal interface.

    Reads absolute pressure and temperature from the CCLGA-7L water-resistant
    sensor. I²C address is 0x5C (SA0=GND) or 0x5D (SA0=VDD). Pressure and
    temperature are returned in SI units (hPa and °C).

    Default configuration (baked in at construction):
        - FS_MODE = 0 (Mode 1, 0–1260 hPa, 4096 LSB/hPa)
        - AVG = 010b (16 samples)
        - ODR = 0100b (25 Hz)
        - BDU = 1, EN_LPFP = 1, LFPF_CFG = 0 (ODR/4 bandwidth)

    Args:
        connection: Configured I²C connection pointing at the device.
    """

    _REG_INTERRUPT_CFG = 0x0B
    _REG_WHO_AM_I      = 0x0F
    _REG_CTRL_REG1     = 0x10
    _REG_CTRL_REG2     = 0x11
    _REG_CTRL_REG3     = 0x12
    _REG_STATUS        = 0x27
    _REG_PRESS_OUT_XL  = 0x28
    _REG_PRESS_OUT_L   = 0x29
    _REG_PRESS_OUT_H   = 0x2A
    _REG_TEMP_OUT_L    = 0x2B
    _REG_TEMP_OUT_H    = 0x2C

    _CHIP_ID        = 0xB4
    _SENSITIVITY_LSB_PER_HPA_MODE1 = 4096.0
    _SENSITIVITY_LSB_PER_HPA_MODE2 = 2048.0
    _BOOT_WAIT_MS   = 2

    def __init__(self, connection):
        self._connection = connection
        self._fs_mode = 0
        self._odr = 0x04
        self._avg = 0x02
        self._lpf_en = 1
        self._lpf_cfg = 0
        self._bdu = 1
        self._init()

    def _init(self):
        who = self._read_reg(self._REG_WHO_AM_I, 1)[0]
        if who != self._CHIP_ID:
            raise OSError('LPS28DFW WHO_AM_I mismatch: expected 0x{:02X}, got 0x{:02X}'.format(self._CHIP_ID, who))
        time.sleep(self._BOOT_WAIT_MS / 1000.0)
        ctrl2 = (self._fs_mode << 6) | (self._lpf_cfg << 5) | (self._lpf_en << 4) | (self._bdu << 3)
        self._write_reg(self._REG_CTRL_REG2, ctrl2)
        ctrl1 = (self._odr << 3) | (self._avg & 0x07)
        self._write_reg(self._REG_CTRL_REG1, ctrl1)

    def _write_reg(self, reg, value):
        self._connection.write(bytes([reg, value]))

    def _read_reg(self, reg, n):
        return self._connection.write_read(bytes([reg]), n)

    def _read_pressure_raw(self):
        raw = self._read_reg(self._REG_PRESS_OUT_XL, 3)
        value = (raw[2] << 16) | (raw[1] << 8) | raw[0]
        if value & 0x800000:
            value -= 0x1000000
        return value

    def _read_temperature_raw(self):
        raw = self._read_reg(self._REG_TEMP_OUT_L, 2)
        value = (raw[1] << 8) | raw[0]
        if value & 0x8000:
            value -= 0x10000
        return value

    def read_pressure(self):
        """Read absolute pressure.

        Returns:
            float: Pressure in hPa. Sensitivity is 4096 LSB/hPa in Mode 1
            (default) and 2048 LSB/hPa in Mode 2 (260–4060 hPa range).
        """
        raw = self._read_pressure_raw()
        if self._fs_mode == 0:
            return raw / self._SENSITIVITY_LSB_PER_HPA_MODE1
        return raw / self._SENSITIVITY_LSB_PER_HPA_MODE2

    def read_temperature(self):
        """Read sensor temperature.

        Returns:
            float: Temperature in degrees Celsius (0.01 °C LSB).
        """
        raw = self._read_temperature_raw()
        return raw / 100.0


class LPS28DFWFull(LPS28DFWMinimal):
    """LPS28DFW full interface — extends LPS28DFWMinimal with full configuration.

    Adds full-scale selection, ODR / averaging control, IIR low-pass filter,
    block-data-update, one-shot trigger, one-point calibration offset, FIFO
    configuration / drain / level, and pressure-threshold interrupt setup.

    Args:
        connection: Configured I²C connection pointing at the device.
    """

    ODR_POWER_DOWN = 0x00
    ODR_1_HZ       = 0x01
    ODR_4_HZ       = 0x02
    ODR_10_HZ      = 0x03
    ODR_25_HZ      = 0x04
    ODR_50_HZ      = 0x05
    ODR_75_HZ      = 0x06
    ODR_100_HZ     = 0x07
    ODR_200_HZ     = 0x08

    AVG_4   = 0x00
    AVG_8   = 0x01
    AVG_16  = 0x02
    AVG_32  = 0x03
    AVG_64  = 0x04
    AVG_128 = 0x05
    AVG_512 = 0x07

    FS_MODE_1 = 0
    FS_MODE_2 = 1

    LFPF_ODR_OVER_4 = 0
    LFPF_ODR_OVER_9 = 1

    FIFO_BYPASS       = 0
    FIFO_FIFO         = 1
    FIFO_CONTINUOUS   = 2
    FIFO_BYPASS_TO_FIFO       = 4
    FIFO_BYPASS_TO_CONTINUOUS = 5
    FIFO_CONTINUOUS_TO_FIFO   = 6

    STATUS_P_DA = 0x01
    STATUS_T_DA = 0x02
    STATUS_P_OR = 0x10
    STATUS_T_OR = 0x20

    INT_SRC_BOOT = 0x80
    INT_SRC_IA   = 0x04
    INT_SRC_PL   = 0x02
    INT_SRC_PH   = 0x01

    def __init__(self, connection):
        super().__init__(connection)

    def configure(self, odr=0x04, avg=0x02, fs_mode=0, lpf_en=True, lpf_cfg=0):
        """Set output data rate, averaging, full-scale mode, and IIR filter.

        Args:
            odr: Output data rate code (0=power-down, 1–7=1–100 Hz, 8=200 Hz).
            avg: Averaging code (0–5 for 4/8/16/32/64/128 samples, 7=512).
            fs_mode: 0=Mode 1 (0–1260 hPa), 1=Mode 2 (0–4060 hPa).
            lpf_en: True to enable the IIR low-pass filter on pressure output.
            lpf_cfg: 0=ODR/4 bandwidth, 1=ODR/9 bandwidth.
        """
        self._odr = odr
        self._avg = avg
        self._fs_mode = fs_mode
        self._lpf_en = 1 if lpf_en else 0
        self._lpf_cfg = lpf_cfg
        ctrl2 = (self._fs_mode << 6) | (self._lpf_cfg << 5) | (self._lpf_en << 4) | (self._bdu << 3)
        self._write_reg(self._REG_CTRL_REG2, ctrl2)
        ctrl1 = (self._odr << 3) | (self._avg & 0x07)
        self._write_reg(self._REG_CTRL_REG1, ctrl1)

    def read(self):
        """Burst-read pressure and temperature.

        Returns:
            dict: ``{"pressure": float hPa, "temperature": float °C}``.
        """
        raw = self._read_reg(self._REG_PRESS_OUT_XL, 5)
        p = (raw[2] << 16) | (raw[1] << 8) | raw[0]
        if p & 0x800000:
            p -= 0x1000000
        t = (raw[4] << 8) | raw[3]
        if t & 0x8000:
            t -= 0x10000
        if self._fs_mode == 0:
            pressure = p / self._SENSITIVITY_LSB_PER_HPA_MODE1
        else:
            pressure = p / self._SENSITIVITY_LSB_PER_HPA_MODE2
        return {"pressure": pressure, "temperature": t / 100.0}

    def read_oneshot(self):
        """Trigger a one-shot measurement (with ODR=0000) and read the result.

        Returns:
            dict: ``{"pressure": float hPa, "temperature": float °C}``.

        Raises:
            OSError: If P_DA does not assert within ~1 s.
        """
        ctrl1 = self._read_reg(self._REG_CTRL_REG1, 1)[0]
        saved_odr = ctrl1 >> 3
        self._write_reg(self._REG_CTRL_REG1, (0 << 3) | (self._avg & 0x07))
        ctrl2 = self._read_reg(self._REG_CTRL_REG2, 1)[0]
        self._write_reg(self._REG_CTRL_REG2, ctrl2 | 0x01)
        deadline = time.ticks_ms() + 1000 if hasattr(time, 'ticks_ms') else None
        while True:
            status = self._read_reg(self._REG_STATUS, 1)[0]
            if status & self.STATUS_P_DA:
                break
            if deadline is not None and time.ticks_diff(deadline, time.ticks_ms()) <= 0:
                self._write_reg(self._REG_CTRL_REG1, (saved_odr << 3) | (self._avg & 0x07))
                raise OSError('LPS28DFW one-shot timeout (STATUS=0x{:02X})'.format(status))
            time.sleep(0.005)
        result = self.read()
        self._write_reg(self._REG_CTRL_REG1, (saved_odr << 3) | (self._avg & 0x07))
        return result

    def is_data_ready(self):
        """Return True if STATUS.P_DA is set (new pressure sample available).

        Returns:
            bool: True if STATUS bit 0 is asserted.
        """
        return bool(self._read_reg(self._REG_STATUS, 1)[0] & self.STATUS_P_DA)

    def set_offset(self, offset_hpa):
        """Program the one-point calibration offset (RPDS).

        Args:
            offset_hpa: Offset in hPa to subtract from subsequent pressure
                readings (signed). Sensitivity is 4096 LSB/hPa (Mode 1) or
                2048 LSB/hPa (Mode 2).
        """
        if self._fs_mode == 0:
            raw = int(offset_hpa * self._SENSITIVITY_LSB_PER_HPA_MODE1)
        else:
            raw = int(offset_hpa * self._SENSITIVITY_LSB_PER_HPA_MODE2)
        if raw < 0:
            raw += 0x10000
        self._write_reg(0x1A, raw & 0xFF)
        self._write_reg(0x1B, (raw >> 8) & 0xFF)

    def softreset(self):
        """Issue a software reset and wait for the chip to reboot (~2 ms)."""
        self._write_reg(self._REG_CTRL_REG2, 0x02)
        time.sleep(self._BOOT_WAIT_MS / 1000.0)

    def fifo_configure(self, mode=0, wtm=0, stop_on_wtm=False):
        """Configure FIFO mode, watermark level, and stop-on-watermark.

        Args:
            mode: FIFO mode (0=bypass, 1=FIFO, 2=continuous; +4/+5/+6 for
                triggered variants Bypass-to-FIFO / Bypass-to-continuous /
                Continuous-to-FIFO).
            wtm: Watermark level, 0–127 samples.
            stop_on_wtm: True to limit FIFO depth to the watermark.
        """
        if mode == self.FIFO_BYPASS:
            self._write_reg(0x14, 0x00)
        trig = 1 if mode >= 4 else 0
        f_mode = mode & 0x03
        ctrl = (trig << 2) | (int(bool(stop_on_wtm)) << 3) | f_mode
        self._write_reg(0x14, ctrl)
        self._write_reg(0x15, wtm & 0x7F)

    def fifo_read(self, count):
        """Drain up to `count` pressure samples from the FIFO.

        Args:
            count: Number of samples to read.

        Returns:
            list[float]: Pressure values in hPa, most-recent last.
        """
        if count < 0:
            raise ValueError('count must be >= 0')
        if count > 128:
            count = 128
        if count == 0:
            return []
        raw = self._read_reg(0x78, count * 3)
        out = []
        sens = self._SENSITIVITY_LSB_PER_HPA_MODE1 if self._fs_mode == 0 else self._SENSITIVITY_LSB_PER_HPA_MODE2
        for i in range(count):
            base = i * 3
            v = (raw[base + 2] << 16) | (raw[base + 1] << 8) | raw[base]
            if v & 0x800000:
                v -= 0x1000000
            out.append(v / sens)
        return out

    def fifo_level(self):
        """Return the number of unread samples in the FIFO.

        Returns:
            int: 0 (empty) through 128 (full).
        """
        return self._read_reg(0x25, 1)[0]

    def set_threshold(self, threshold_hpa, high=True, low=True):
        """Program the pressure threshold and enable interrupt sources.

        Args:
            threshold_hpa: Pressure threshold in hPa (15-bit unsigned).
                Sensitivity is 16 LSB/hPa (Mode 1) or 8 LSB/hPa (Mode 2).
            high: True to assert PH when pressure exceeds the threshold.
            low: True to assert PL when pressure falls below the threshold.
        """
        if self._fs_mode == 0:
            raw = int(threshold_hpa * 16)
        else:
            raw = int(threshold_hpa * 8)
        if raw < 0:
            raw = 0
        if raw > 0x7FFF:
            raw = 0x7FFF
        self._write_reg(0x0C, raw & 0xFF)
        self._write_reg(0x0D, (raw >> 8) & 0x7F)
        cfg = self._read_reg(self._REG_INTERRUPT_CFG, 1)[0]
        cfg &= ~0x03
        if high:
            cfg |= 0x01
        if low:
            cfg |= 0x02
        self._write_reg(self._REG_INTERRUPT_CFG, cfg)

    def chip_id(self):
        """Read the WHO_AM_I register.

        Returns:
            int: Chip ID; expect 0xB4 for LPS28DFW.
        """
        return self._read_reg(self._REG_WHO_AM_I, 1)[0]