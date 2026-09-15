import time


class LPS33HWMinimal:
    """LPS33HW water-resistant MEMS absolute pressure sensor — minimal interface.

    Provides calibrated pressure (Pa) and temperature (°C) readings with no
    configuration beyond the connection. I²C address is 0x5C (SA0=GND) or
    0x5D (SA0=VDD).

    Default configuration (baked in at construction):
        - ODR = 1 Hz (CTRL_REG1 = 0x12)
        - BDU = 1 (block data update — prevents reading pressure bytes from
          different samples)
        - EN_LPFP = 0 (LPF disabled)
        - IF_ADD_INC = 1 (auto-increment, kept from reset default)

    Args:
        connection: Configured I²C or SPI connection pointing at the device.
    """

    _REG_INTERRUPT_CFG = 0x0B
    _REG_THS_P_L       = 0x0C
    _REG_THS_P_H       = 0x0D
    _REG_WHO_AM_I      = 0x0F
    _REG_CTRL_REG1     = 0x10
    _REG_CTRL_REG2     = 0x11
    _REG_CTRL_REG3     = 0x12
    _REG_FIFO_CTRL     = 0x14
    _REG_REF_P_XL      = 0x15
    _REG_REF_P_L       = 0x16
    _REG_REF_P_H       = 0x17
    _REG_RPDS_L        = 0x18
    _REG_RPDS_H        = 0x19
    _REG_RES_CONF      = 0x1A
    _REG_INT_SOURCE    = 0x25
    _REG_FIFO_STATUS   = 0x26
    _REG_STATUS        = 0x27
    _REG_PRESS_XL      = 0x28
    _REG_PRESS_L       = 0x29
    _REG_PRESS_H       = 0x2A
    _REG_TEMP_L        = 0x2B
    _REG_TEMP_H        = 0x2C
    _REG_LPFP_RES      = 0x33

    _CHIP_ID           = 0xB1

    _STATUS_P_DA       = 0x01
    _STATUS_T_DA       = 0x02

    _CTRL_REG1_DEFAULT = 0x12
    _CTRL_REG2_RESET   = 0x04
    _CTRL_REG2_DEFAULT = 0x10

    def __init__(self, connection):
        self._connection = connection
        chip_id = self._read_reg(self._REG_WHO_AM_I, 1)[0]
        if chip_id != self._CHIP_ID:
            raise OSError(
                'Unexpected chip ID 0x{:02X}, expected 0x{:02X}'.format(
                    chip_id, self._CHIP_ID))
        self._write_reg(self._REG_CTRL_REG2, self._CTRL_REG2_RESET)
        time.sleep(0.001)
        self._write_reg(self._REG_CTRL_REG2, self._CTRL_REG2_DEFAULT)
        self._write_reg(self._REG_CTRL_REG1, self._CTRL_REG1_DEFAULT)

    def _write_reg(self, reg, value):
        self._connection.write(bytes([reg, value]))

    def _read_reg(self, reg, n):
        return self._connection.write_read(bytes([reg]), n)

    def _wait_status(self, mask):
        for _ in range(50):
            status = self._read_reg(self._REG_STATUS, 1)[0]
            if (status & mask) == mask:
                return
            time.sleep(0.005)
        raise OSError('LPS33HW data not ready (STATUS=0x{:02X})'.format(status))

    def pressure(self):
        """Read calibrated absolute pressure.

        Waits for STATUS.P_DA (data-available) before reading. With BDU=1
        the output registers only update after PRESS_OUT_H has been read,
        so a single burst read from PRESS_OUT_XL through TEMP_OUT_H is
        used to release the latch.

        Returns:
            float: Absolute pressure in pascals.
        """
        self._wait_status(self._STATUS_P_DA)
        raw = self._read_reg(self._REG_PRESS_XL, 5)
        raw_press = (raw[2] << 16) | (raw[1] << 8) | raw[0]
        if raw_press >= 0x800000:
            raw_press -= 0x1000000
        return raw_press * 100.0 / 4096.0

    def temperature(self):
        """Read calibrated temperature.

        Waits for STATUS.T_DA (data-available) before reading. Re-issues
        the burst read so the BDU latch releases cleanly.

        Returns:
            float: Temperature in degrees Celsius.
        """
        self._wait_status(self._STATUS_T_DA)
        raw = self._read_reg(self._REG_PRESS_XL, 5)
        raw_temp = (raw[4] << 8) | raw[3]
        if raw_temp >= 0x8000:
            raw_temp -= 0x10000
        return raw_temp / 100.0


class LPS33HWFull(LPS33HWMinimal):
    """LPS33HW full interface — extends LPS33HWMinimal with configuration,
    one-shot, FIFO, interrupt, AUTOZERO/AUTORIFP, reset, and reboot.

    Args:
        connection: Configured I²C or SPI connection pointing at the device.
    """

    ODR_POWER_DOWN = 0
    ODR_1_HZ       = 1
    ODR_10_HZ      = 2
    ODR_25_HZ      = 3
    ODR_50_HZ      = 4
    ODR_75_HZ      = 5

    LPFP_BW_ODR_9   = 0
    LPFP_BW_ODR_20  = 1

    FIFO_MODE_BYPASS              = 0
    FIFO_MODE_FIFO                = 1
    FIFO_MODE_STREAM              = 2
    FIFO_MODE_STREAM_TO_FIFO      = 3
    FIFO_MODE_BYPASS_TO_STREAM    = 4
    FIFO_MODE_DYNAMIC_STREAM      = 6
    FIFO_MODE_BYPASS_TO_FIFO      = 7

    INT_S_DATA_SIGNALS    = 0
    INT_S_PRESSURE_HIGH   = 1
    INT_S_PRESSURE_LOW    = 2
    INT_S_PRESSURE_BOTH   = 3

    def __init__(self, connection):
        super().__init__(connection)

    def configure(self, odr=1, bdu=True, en_lpfp=False, lpfp_cfg=0, lc_en=False, sim=False):
        """Write CTRL_REG1, RES_CONF, and (if needed) CTRL_REG2 SIM bit.

        Args:
            odr: Output data rate (0=power-down, 1=1 Hz, 2=10 Hz, 3=25 Hz,
                4=50 Hz, 5=75 Hz).
            bdu: Block data update (True=hold until PRESS_OUT_H read).
            en_lpfp: Enable additional low-pass filter on pressure.
            lpfp_cfg: LPF bandwidth when enabled (0=ODR/9, 1=ODR/20).
            lc_en: Low-current mode (only writable when ODR=0).
            sim: SPI mode (False=4-wire, True=3-wire).
        """
        ctrl1 = ((odr & 7) << 4) | (1 << 3 if en_lpfp else 0) \
              | ((lpfp_cfg & 1) << 2) | (1 << 1 if bdu else 0) \
              | (1 if sim else 0)
        self._write_reg(self._REG_CTRL_REG1, ctrl1)

        current = self._read_reg(self._REG_RES_CONF, 1)[0]
        new_res = (current & 0xFE) | (1 if lc_en else 0)
        self._write_reg(self._REG_RES_CONF, new_res)

    def one_shot(self):
        """Trigger a single pressure+temperature measurement.

        Requires ODR=000 (power-down). Writes ONE_SHOT in CTRL_REG2 and
        polls STATUS until both P_DA and T_DA are set.

        Returns:
            tuple: (pressure_Pa, temperature_C) from the same conversion.
        """
        current = self._read_reg(self._REG_CTRL_REG2, 1)[0]
        self._write_reg(self._REG_CTRL_REG2, current | 0x01)
        for _ in range(50):
            status = self._read_reg(self._REG_STATUS, 1)[0]
            if (status & 0x03) == 0x03:
                raw = self._read_reg(self._REG_PRESS_XL, 5)
                raw_press = (raw[2] << 16) | (raw[1] << 8) | raw[0]
                if raw_press >= 0x800000:
                    raw_press -= 0x1000000
                pressure_Pa = raw_press * 100.0 / 4096.0
                raw_temp = (raw[4] << 8) | raw[3]
                if raw_temp >= 0x8000:
                    raw_temp -= 0x10000
                temperature_C = raw_temp / 100.0
                return pressure_Pa, temperature_C
            time.sleep(0.005)
        raise OSError('LPS33HW one-shot timeout (STATUS=0x{:02X})'.format(status))

    def status(self):
        """Read the STATUS register.

        Returns:
            dict: ``{p_da, t_da, p_or, t_or}`` flags from STATUS.
        """
        raw = self._read_reg(self._REG_STATUS, 1)[0]
        return {
            'p_da': bool(raw & 0x01),
            't_da': bool(raw & 0x02),
            'p_or': bool(raw & 0x10),
            't_or': bool(raw & 0x20),
        }

    def reset(self):
        """Software-reset via SWRESET, wait for self-clear, restore defaults."""
        self._write_reg(self._REG_CTRL_REG2, self._CTRL_REG2_RESET)
        for _ in range(50):
            current = self._read_reg(self._REG_CTRL_REG2, 1)[0]
            if not (current & 0x04):
                break
            time.sleep(0.001)
        self._write_reg(self._REG_CTRL_REG2, self._CTRL_REG2_DEFAULT)
        self._write_reg(self._REG_CTRL_REG1, self._CTRL_REG1_DEFAULT)

    def reboot(self):
        """Reload factory trimming from internal Flash via BOOT bit."""
        self._write_reg(self._REG_CTRL_REG2, 0x80)
        for _ in range(100):
            status = self._read_reg(self._REG_INT_SOURCE, 1)[0]
            if not (status & 0x80):
                break
            time.sleep(0.005)

    def set_pressure_offset(self, offset_hPa):
        """Write RPDS to apply a one-point calibration offset.

        Args:
            offset_hPa: Pressure offset in hectopascals. 1 RPDS LSB = 1/16 hPa.
        """
        raw = int(round(offset_hPa * 16))
        if raw < 0:
            raw += 0x10000
        self._write_reg(self._REG_RPDS_L, raw & 0xFF)
        self._write_reg(self._REG_RPDS_H, (raw >> 8) & 0xFF)

    def set_autozero(self):
        """Set AUTOZERO=1; the current pressure is stored in REF_P."""
        current = self._read_reg(self._REG_INTERRUPT_CFG, 1)[0]
        self._write_reg(self._REG_INTERRUPT_CFG, current | 0x20)

    def clear_autozero(self):
        """Clear AUTOZERO mode and reset REF_P to 0."""
        current = self._read_reg(self._REG_INTERRUPT_CFG, 1)[0]
        self._write_reg(self._REG_INTERRUPT_CFG, current | 0x10)

    def set_autorifp(self):
        """Set AUTORIFP=1; next measurement value is stored in RPDS."""
        current = self._read_reg(self._REG_INTERRUPT_CFG, 1)[0]
        self._write_reg(self._REG_INTERRUPT_CFG, current | 0x80)

    def clear_autorifp(self):
        """Clear AUTORIFP mode and reset RPDS to 0."""
        current = self._read_reg(self._REG_INTERRUPT_CFG, 1)[0]
        self._write_reg(self._REG_INTERRUPT_CFG, current | 0x40)

    def configure_interrupt(self, drdy=False, f_fth=False, f_ovr=False,
                            f_fss5=False, int_s=0, active_low=False,
                            open_drain=False):
        """Route CTRL_REG3 events to the INT_DRDY pin.

        Args:
            drdy: Route data-ready.
            f_fth: Route FIFO threshold.
            f_ovr: Route FIFO overrun.
            f_fss5: Route FIFO full (32 samples).
            int_s: Signal selection (0=data signals, 1=high, 2=low, 3=both).
            active_low: INT_DRDY active-low.
            open_drain: INT_DRDY open-drain.
        """
        ctrl3 = ((1 if active_low else 0) << 7) \
              | ((1 if open_drain else 0) << 6) \
              | ((1 if f_fss5 else 0) << 5) \
              | ((1 if f_fth else 0) << 4) \
              | ((1 if f_ovr else 0) << 3) \
              | ((1 if drdy else 0) << 2) \
              | (int_s & 0x03)
        self._write_reg(self._REG_CTRL_REG3, ctrl3)

    def configure_pressure_interrupt(self, high_en=False, low_en=False,
                                     threshold_hPa=0.0, latch=False):
        """Configure differential pressure threshold interrupt.

        Args:
            high_en: Enable interrupt on pressure above threshold.
            low_en: Enable interrupt on pressure below threshold.
            threshold_hPa: Pressure threshold in hPa. 1 LSB = 1/16 hPa.
            latch: Latch the interrupt request until INT_SOURCE is read.
        """
        raw_ths = int(round(threshold_hPa * 16)) & 0xFFFF
        self._write_reg(self._REG_THS_P_L, raw_ths & 0xFF)
        self._write_reg(self._REG_THS_P_H, (raw_ths >> 8) & 0xFF)

        current = self._read_reg(self._REG_INTERRUPT_CFG, 1)[0]
        new_cfg = (current & 0xF0) \
                | (0x04 if latch else 0) \
                | (0x02 if high_en else 0) \
                | (0x01 if low_en else 0)
        self._write_reg(self._REG_INTERRUPT_CFG, new_cfg)

    def interrupt_status(self):
        """Read the INT_SOURCE register.

        Returns:
            dict: ``{ia, p_high, p_low, boot_status}`` flags.
        """
        raw = self._read_reg(self._REG_INT_SOURCE, 1)[0]
        return {
            'ia': bool(raw & 0x04),
            'p_high': bool(raw & 0x01),
            'p_low': bool(raw & 0x02),
            'boot_status': bool(raw & 0x80),
        }

    def enable_fifo(self, mode=1, watermark=0):
        """Enable the FIFO.

        Args:
            mode: FIFO mode (0–7, excluding reserved value 5).
            watermark: FIFO watermark level (0–31).
        """
        if mode == 5:
            raise ValueError('FIFO mode 5 is reserved')
        ctrl = ((mode & 7) << 5) | (watermark & 0x1F)
        self._write_reg(self._REG_FIFO_CTRL, ctrl)
        current = self._read_reg(self._REG_CTRL_REG2, 1)[0]
        self._write_reg(self._REG_CTRL_REG2, current | 0x40)

    def disable_fifo(self):
        """Disable the FIFO and reset to Bypass mode."""
        current = self._read_reg(self._REG_CTRL_REG2, 1)[0]
        self._write_reg(self._REG_CTRL_REG2, current & ~0x40)
        self._write_reg(self._REG_FIFO_CTRL, 0)

    def fifo_status(self):
        """Read the FIFO_STATUS register.

        Returns:
            dict: ``{fth, ovr, count}`` from FIFO_STATUS.
        """
        raw = self._read_reg(self._REG_FIFO_STATUS, 1)[0]
        return {
            'fth': bool(raw & 0x80),
            'ovr': bool(raw & 0x40),
            'count': raw & 0x3F,
        }

    def read_fifo(self):
        """Drain the FIFO and return all available samples.

        Each FIFO level is 5 bytes (PRESS_XL/L/H + TEMP_L/H). The address
        wraps automatically from 0x2C back to 0x28 for subsequent levels.

        Returns:
            list: List of (pressure_Pa, temperature_C) tuples, one per FIFO slot.
        """
        status = self.fifo_status()
        count = status['count']
        if count == 0:
            return []
        raw = self._read_reg(self._REG_PRESS_XL, count * 5)
        samples = []
        for i in range(count):
            chunk = raw[i * 5:(i + 1) * 5]
            raw_press = (chunk[2] << 16) | (chunk[1] << 8) | chunk[0]
            if raw_press >= 0x800000:
                raw_press -= 0x1000000
            pressure_Pa = raw_press * 100.0 / 4096.0
            raw_temp = (chunk[4] << 8) | chunk[3]
            if raw_temp >= 0x8000:
                raw_temp -= 0x10000
            temperature_C = raw_temp / 100.0
            samples.append((pressure_Pa, temperature_C))
        return samples

    def reset_lpf(self):
        """Read LPFP_RES to flush any transitory LPF state."""
        self._read_reg(self._REG_LPFP_RES, 1)