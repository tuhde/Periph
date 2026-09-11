import time


class LPS22DFMinimal:
    """LPS22DF absolute pressure and temperature sensor — minimal interface.

    Provides pressure (Pa) and temperature (°C) readings with no
    configuration beyond the connection. I²C address is 0x5C (SDO=GND) or
    0x5D (SDO=VDDIO). SPI uses 4-wire mode by default (SIM bit clear).

    Default configuration (baked in at construction):
        - ODR: 10 Hz (CTRL_REG1 ODR[3:0] = 0011)
        - AVG: 4 samples (AVG[2:0] = 000)
        - BDU: enabled (block data update — hold until MSB read)
        - Low-pass filter: disabled
        - FIFO: bypass mode

    Args:
        connection: Configured I²C or SPI connection pointing at the device.
        bus_type: Bus type string, ``'i2c'`` (default) or ``'spi'``.
            SPI writes mask bit 7 of the register address.
    """

    _REG_INTERRUPT_CFG = 0x0B
    _REG_THS_P_L       = 0x0C
    _REG_THS_P_H       = 0x0D
    _REG_IF_CTRL       = 0x0E
    _REG_WHO_AM_I      = 0x0F
    _REG_CTRL_REG1     = 0x10
    _REG_CTRL_REG2     = 0x11
    _REG_CTRL_REG3     = 0x12
    _REG_CTRL_REG4     = 0x13
    _REG_FIFO_CTRL     = 0x14
    _REG_FIFO_WTM      = 0x15
    _REG_REF_P_L       = 0x16
    _REG_REF_P_H       = 0x17
    _REG_I3C_IF_CTRL   = 0x19
    _REG_RPDS_L        = 0x1A
    _REG_RPDS_H        = 0x1B
    _REG_INT_SOURCE    = 0x24
    _REG_FIFO_STATUS1  = 0x25
    _REG_FIFO_STATUS2  = 0x26
    _REG_STATUS        = 0x27
    _REG_PRESS_OUT_XL  = 0x28
    _REG_PRESS_OUT_L   = 0x29
    _REG_PRESS_OUT_H   = 0x2A
    _REG_TEMP_OUT_L    = 0x2B
    _REG_TEMP_OUT_H    = 0x2C
    _REG_FIFO_PRESS_XL = 0x78
    _REG_FIFO_PRESS_L  = 0x79
    _REG_FIFO_PRESS_H  = 0x7A

    _CHIP_ID = 0xB4

    _DEFAULT_ODR = 0x03   # 10 Hz
    _DEFAULT_AVG = 0x00   # 4 samples

    _STATUS_P_DA = 0x01
    _STATUS_T_DA = 0x02

    def __init__(self, connection, bus_type='i2c'):
        self._connection = connection
        self._bus_type = bus_type
        if self._read_reg(self._REG_WHO_AM_I, 1)[0] != self._CHIP_ID:
            raise ValueError('LPS22DF not found: WHO_AM_I expected 0x{:02X}'.format(self._CHIP_ID))
        self._write_reg(self._REG_CTRL_REG2, 0x04)  # SWRESET=1
        time.sleep(0.001)
        # CTRL_REG1: ODR[3:0]=0011 (10 Hz), AVG[2:0]=000 (4 samples), no LP filter, normal mode
        self._write_reg(self._REG_CTRL_REG1, (self._DEFAULT_ODR << 3) | self._DEFAULT_AVG)
        # CTRL_REG2: BDU=1 (block data update); leave everything else at reset
        self._write_reg(self._REG_CTRL_REG2, 0x08)

    def _write_reg(self, reg, value):
        if self._bus_type == 'spi':
            reg = reg & 0x7F
        self._connection.write(bytes([reg, value & 0xFF]))

    def _read_reg(self, reg, n):
        if self._bus_type == 'spi':
            reg = reg | 0x80
        return self._connection.write_read(bytes([reg]), n)

    def _wait_p_da(self):
        while not (self._read_reg(self._REG_STATUS, 1)[0] & self._STATUS_P_DA):
            time.sleep(0.001)

    def pressure(self):
        """Read absolute pressure.

        Polls STATUS.P_DA then burst-reads PRESS_OUT_XL..H (registers
        0x28..0x2A) and combines the 24-bit two's complement value into
        Pascals (4096 LSB/hPa).

        Returns:
            float: Pressure in pascals.
        """
        self._wait_p_da()
        raw = self._read_reg(self._REG_PRESS_OUT_XL, 3)
        value = (raw[0] | (raw[1] << 8) | (raw[2] << 16))
        if value & 0x800000:
            value -= 0x1000000
        hPa = value / 4096.0
        return hPa * 100.0

    def temperature(self):
        """Read temperature.

        Burst-reads TEMP_OUT_L..H (registers 0x2B..0x2C) and converts the
        16-bit two's complement value to °C (100 LSB/°C).

        Returns:
            float: Temperature in degrees Celsius.
        """
        raw = self._read_reg(self._REG_TEMP_OUT_L, 2)
        value = raw[0] | (raw[1] << 8)
        if value & 0x8000:
            value -= 0x10000
        return value / 100.0


class LPS22DFFull(LPS22DFMinimal):
    """LPS22DF full interface — extends LPS22DFMinimal with configuration,
    threshold/offset calibration, FIFO, interrupts, and AUTOZERO/AUTOREFP.

    Args:
        connection: Configured I²C or SPI connection pointing at the device.
        bus_type: Bus type string, ``'i2c'`` (default) or ``'spi'``.
    """

    ODR_POWER_DOWN = 0
    ODR_1_HZ       = 1
    ODR_4_HZ       = 2
    ODR_10_HZ      = 3
    ODR_25_HZ      = 4
    ODR_50_HZ      = 5
    ODR_75_HZ      = 6
    ODR_100_HZ     = 7
    ODR_200_HZ     = 8

    AVG_4   = 0
    AVG_8   = 1
    AVG_16  = 2
    AVG_32  = 3
    AVG_64  = 4
    AVG_128 = 5
    AVG_512 = 7

    FIFO_BYPASS             = 0
    FIFO_FIFO               = 1
    FIFO_CONTINUOUS         = 2
    FIFO_BYPASS_TO_FIFO     = 3
    FIFO_BYPASS_TO_CONT     = 4
    FIFO_CONT_TO_FIFO       = 5

    def __init__(self, connection, bus_type='i2c'):
        super().__init__(connection, bus_type)

    def configure(self, odr=3, avg=0, en_lpfp=False, lfpf_cfg=0, bdu=True):
        """Write CTRL_REG1 and CTRL_REG2.

        Args:
            odr: Output data rate (0=power-down, 1=1 Hz, ..., 7=100 Hz, 8=200 Hz).
            avg: Averaging filter (0=4, 1=8, 2=16, 3=32, 4=64, 5=128, 7=512).
            en_lpfp: Enable low-pass filter on pressure output.
            lfpf_cfg: 0 = ODR/4 cutoff, 1 = ODR/9 cutoff.
            bdu: Block data update — recommended True.
        """
        if odr < 0 or odr > 8:
            raise ValueError('odr must be 0..8')
        if avg not in (0, 1, 2, 3, 4, 5, 7):
            raise ValueError('avg must be 0..5 or 7')
        ctrl1 = ((odr & 0x0F) << 3) | (avg & 0x07)
        ctrl2 = 0
        if en_lpfp:
            ctrl2 |= 0x10
        if lfpf_cfg:
            ctrl2 |= 0x20
        if bdu:
            ctrl2 |= 0x08
        self._write_reg(self._REG_CTRL_REG1, ctrl1)
        self._write_reg(self._REG_CTRL_REG2, ctrl2)

    def oneshot(self):
        """Trigger a single measurement in power-down mode.

        Sets ODR to power-down then sets ONESHOT=1; blocks until STATUS.P_DA=1.
        """
        self._write_reg(self._REG_CTRL_REG1, 0x00)  # ODR=power-down, AVG=4
        self._write_reg(self._REG_CTRL_REG2, 0x08 | 0x01)  # BDU=1, ONESHOT=1
        self._wait_p_da()

    def altitude(self, sea_level_pa=101325.0):
        """Compute altitude above sea level from the current pressure.

        Args:
            sea_level_pa: Reference sea-level pressure in pascals (default 101325).

        Returns:
            float: Altitude in metres.
        """
        p = self.pressure()
        return 44330.0 * (1.0 - (p / sea_level_pa) ** (1.0 / 5.255))

    def software_reset(self):
        """Software-reset the chip and wait for self-clear."""
        self._write_reg(self._REG_CTRL_REG2, 0x04)  # SWRESET=1
        time.sleep(0.001)

    def set_pressure_offset(self, offset_pa):
        """Write a one-point calibration offset.

        Args:
            offset_pa: Offset in pascals (signed, applied to every reading).
        """
        offset_hpa = offset_pa / 100.0
        raw = int(round(offset_hpa * 4096.0))
        if raw < 0:
            raw += 0x10000
        self._write_reg(self._REG_RPDS_L, raw & 0xFF)
        self._write_reg(self._REG_RPDS_H, (raw >> 8) & 0xFF)

    def set_pressure_threshold(self, threshold_pa):
        """Write a 15-bit unsigned pressure threshold.

        When the absolute pressure exceeds this value, the THS_P interrupt
        flag is raised (if INT_EN is set in CTRL_REG4).

        Args:
            threshold_pa: Threshold in pascals.
        """
        threshold_hpa = threshold_pa / 100.0
        raw = int(round(threshold_hpa * 16.0)) & 0x7FFF
        self._write_reg(self._REG_THS_P_L, raw & 0xFF)
        self._write_reg(self._REG_THS_P_H, (raw >> 8) & 0xFF)

    def configure_interrupt(self, int_h_l=False, pp_od=False, drdy=False, drdy_pls=False,
                            int_en=False, int_f_wtm=False, int_f_full=False, int_f_ovr=False):
        """Configure the INT pin and routing.

        Args:
            int_h_l: Interrupt polarity (False=active-high, True=active-low).
            pp_od: Interrupt pin type (False=push-pull, True=open-drain).
            drdy: Route data-ready signal to INT pin.
            drdy_pls: Data-ready pulsed (~5 µs).
            int_en: Route pressure threshold interrupt to INT pin.
            int_f_wtm: Route FIFO-watermark flag to INT pin.
            int_f_full: Route FIFO-full flag to INT pin.
            int_f_ovr: Route FIFO-overrun flag to INT pin.
        """
        ctrl3 = 0x01  # IF_ADD_INC=1 (must stay 1)
        if int_h_l:
            ctrl3 |= 0x08
        if pp_od:
            ctrl3 |= 0x02
        ctrl4 = 0
        if drdy_pls:
            ctrl4 |= 0x40
        if drdy:
            ctrl4 |= 0x20
        if int_en:
            ctrl4 |= 0x10
        if int_f_full:
            ctrl4 |= 0x04
        if int_f_wtm:
            ctrl4 |= 0x02
        if int_f_ovr:
            ctrl4 |= 0x01
        self._write_reg(self._REG_CTRL_REG3, ctrl3)
        self._write_reg(self._REG_CTRL_REG4, ctrl4)

    def configure_pressure_event(self, phe=False, ple=False, lir=False):
        """Configure pressure-event interrupts.

        Args:
            phe: Enable interrupt on pressure-high event.
            ple: Enable interrupt on pressure-low event.
            lir: Latch interrupt request in INT_SOURCE.
        """
        cfg = 0
        if phe:
            cfg |= 0x01
        if ple:
            cfg |= 0x02
        if lir:
            cfg |= 0x04
        self._write_reg(self._REG_INTERRUPT_CFG, cfg)

    def autozero(self):
        """Capture the current pressure as the AUTOZERO reference.

        After this, PRESS_OUT reflects the differential pressure
        (current - reference).
        """
        self._write_reg(self._REG_INTERRUPT_CFG, 0x20)  # AUTOZERO=1

    def autorefp(self):
        """Capture the current pressure in REF_P for use as a comparator against
        THS_P. PRESS_OUT remains absolute.
        """
        self._write_reg(self._REG_INTERRUPT_CFG, 0x80)  # AUTOREFP=1

    def reset_reference(self):
        """Reset both AUTOZERO and AUTOREFP, returning PRESS_OUT to absolute."""
        self._write_reg(self._REG_INTERRUPT_CFG, 0x50)  # RESET_AZ=1 | RESET_ARP=1

    def reference_pressure(self):
        """Read the stored AUTOZERO/AUTOREFP reference pressure.

        Returns:
            float: Reference pressure in pascals.
        """
        raw = self._read_reg(self._REG_REF_P_L, 2)
        value = raw[0] | (raw[1] << 8)
        if value & 0x8000:
            value -= 0x10000
        return (value / 4096.0) * 100.0

    def set_fifo_mode(self, mode):
        """Set FIFO mode.

        Args:
            mode: 0=bypass, 1=FIFO, 2=continuous, 3=bypass-to-FIFO,
                4=bypass-to-continuous, 5=continuous-to-FIFO.
        """
        if mode < 0 or mode > 5:
            raise ValueError('mode must be 0..5')
        # Map mode to (TRIG_MODES, F_MODE[1:0]) per spec table.
        if mode == 0:
            trig, fm = 0, 0
        elif mode == 1:
            trig, fm = 0, 1
        elif mode == 2:
            trig, fm = 0, 2
        elif mode == 3:
            trig, fm = 1, 1
        elif mode == 4:
            trig, fm = 1, 2
        else:
            trig, fm = 1, 3
        self._write_reg(self._REG_FIFO_CTRL, (trig << 2) | (fm & 0x03))

    def set_fifo_watermark(self, level):
        """Set the FIFO watermark level.

        Args:
            level: 0..127.
        """
        if level < 0 or level > 127:
            raise ValueError('level must be 0..127')
        self._write_reg(self._REG_FIFO_WTM, level & 0x7F)

    def fifo_sample_count(self):
        """Read the number of stored FIFO samples.

        Returns:
            int: Sample count (0..127).
        """
        return self._read_reg(self._REG_FIFO_STATUS1, 1)[0]

    def read_fifo(self):
        """Read every available FIFO sample.

        Burst-reads 3 bytes per sample starting at FIFO_PRESS_XL (0x78);
        the address auto-wraps back to 0x78 after 0x7A.

        Returns:
            list: Pressure readings in pascals, oldest first.
        """
        count = self.fifo_sample_count()
        if count == 0:
            return []
        raw = self._read_reg(self._REG_FIFO_PRESS_XL, count * 3)
        samples = []
        for i in range(count):
            base = i * 3
            value = raw[base] | (raw[base + 1] << 8) | (raw[base + 2] << 16)
            if value & 0x800000:
                value -= 0x1000000
            samples.append((value / 4096.0) * 100.0)
        return samples

    def interrupt_source(self):
        """Read and clear the INT_SOURCE register.

        Returns:
            dict: Keys ``boot_on``, ``ia``, ``ph``, ``pl``.
        """
        raw = self._read_reg(self._REG_INT_SOURCE, 1)[0]
        return {
            'boot_on': bool(raw & 0x80),
            'ia':      bool(raw & 0x04),
            'ph':      bool(raw & 0x01),
            'pl':      bool(raw & 0x02),
        }