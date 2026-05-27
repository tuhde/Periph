import struct
import time


class ENS160Minimal:
    """ENS160 digital multi-gas sensor — minimal interface.

    Provides calibrated air quality readings (AQI, TVOC, eCO2) with no
    configuration required beyond the transport. The sensor performs automatic
    baseline correction and on-chip signal processing.

    Default configuration (baked in at construction):
        - OPMODE: STANDARD (0x02) — gas sensing active
        - No interrupt pin configured (polling only)
        - No external T/RH compensation (device uses internal defaults)

    Args:
        transport: Configured I²C or SPI transport pointing at the device.
    """

    _REG_PART_ID       = 0x00
    _REG_OPMODE        = 0x10
    _REG_CONFIG        = 0x11
    _REG_COMMAND       = 0x12
    _REG_TEMP_IN       = 0x13
    _REG_RH_IN         = 0x15
    _REG_DEVICE_STATUS = 0x20
    _REG_DATA_AQI      = 0x21
    _REG_DATA_TVOC     = 0x22
    _REG_DATA_ECO2     = 0x24
    _REG_DATA_T        = 0x30
    _REG_DATA_RH       = 0x32
    _REG_DATA_MISR     = 0x38
    _REG_GPR_WRITE     = 0x40
    _REG_GPR_READ      = 0x48

    _OPMODE_DEEP_SLEEP = 0x00
    _OPMODE_IDLE       = 0x01
    _OPMODE_STANDARD   = 0x02
    _OPMODE_RESET      = 0xF0

    _PART_ID_EXPECTED  = 0x0160

    _WARMUP_POLL_MS    = 500

    def __init__(self, transport):
        self._transport = transport
        self._write_reg(self._REG_OPMODE, self._OPMODE_IDLE)
        time.sleep(0.001)
        part_id = self._read_reg_le16(self._REG_PART_ID)
        if part_id != self._PART_ID_EXPECTED:
            raise ValueError('ENS160 not found: expected PART_ID 0x0160, got 0x{:04X}'.format(part_id))
        self._write_reg(self._REG_OPMODE, self._OPMODE_STANDARD)

    def _write_reg(self, reg, value):
        self._transport.write(bytes([reg, value]))

    def _write_reg_le16(self, reg, value):
        self._transport.write(bytes([reg, value & 0xFF, (value >> 8) & 0xFF]))

    def _read_reg(self, reg, n):
        return self._transport.write_read(bytes([reg]), n)

    def _read_reg_le16(self, reg):
        data = self._read_reg(reg, 2)
        return data[0] | (data[1] << 8)

    def _read_device_status(self):
        data = self._read_reg(self._REG_DEVICE_STATUS, 1)
        return data[0]

    def _wait_for_new_data(self, timeout_ms=5000):
        start = time.ticks_ms() if hasattr(time, 'ticks_ms') else int(time.time() * 1000)
        while True:
            status = self._read_device_status()
            if status & 0x02:
                return status
            now = time.ticks_ms() if hasattr(time, 'ticks_ms') else int(time.time() * 1000)
            if now - start > timeout_ms:
                raise TimeoutError('ENS160: NEWDAT not set within {} ms'.format(timeout_ms))
            time.sleep(0.01)

    def status(self):
        """Read the VALIDITY_FLAG from DEVICE_STATUS.

        Returns:
            int: Validity flag (0=OK, 1=Warm-up, 2=Initial Start-up, 3=No valid output).
        """
        status = self._read_device_status()
        return (status >> 2) & 0x03

    def read_air_quality(self):
        """Read calibrated air quality values.

        Polls until NEWDAT is set, then checks VALIDITY_FLAG. Only returns
        data when validity is 0 (OK). Reads AQI, TVOC, and eCO2 in a single
        burst to ensure consistency.

        Returns:
            dict: Keys ``aqi`` (int, 1–5), ``tvoc_ppb`` (float), ``eco2_ppm`` (float).

        Raises:
            RuntimeError: If VALIDITY_FLAG is not 0 after NEWDAT is set.
        """
        status = self._wait_for_new_data()
        validity = (status >> 2) & 0x03
        if validity != 0:
            raise RuntimeError('ENS160: data not valid (VALIDITY_FLAG={})'.format(validity))
        data = self._read_reg(self._REG_DATA_AQI, 5)
        aqi = data[0] & 0x07
        tvoc_ppb = data[1] | (data[2] << 8)
        eco2_ppm = data[3] | (data[4] << 8)
        return {'aqi': aqi, 'tvoc_ppb': float(tvoc_ppb), 'eco2_ppm': float(eco2_ppm)}


class ENS160Full(ENS160Minimal):
    """ENS160 full interface — extends ENS160Minimal with compensation, raw readings, and power control.

    Adds external temperature/humidity compensation, individual gas readings,
    raw sensor resistance, firmware version query, interrupt configuration,
    and sleep/wake control.

    Args:
        transport: Configured I²C or SPI transport pointing at the device.
    """

    VALIDITY_OK              = 0
    VALIDITY_WARMUP          = 1
    VALIDITY_INITIAL_STARTUP = 2
    VALIDITY_INVALID         = 3

    def __init__(self, transport):
        super().__init__(transport)

    def set_compensation(self, temp_celsius, rh_percent):
        """Write external temperature and humidity for compensation.

        Improves accuracy when ambient conditions differ from the sensor's
        internal defaults (25°C, 50%RH).

        Args:
            temp_celsius: Ambient temperature in degrees Celsius.
            rh_percent: Ambient relative humidity in percent (0–100).
        """
        temp_raw = int(round((temp_celsius + 273.15) * 64))
        rh_raw = int(round(rh_percent * 512))
        self._write_reg_le16(self._REG_TEMP_IN, temp_raw)
        self._write_reg_le16(self._REG_RH_IN, rh_raw)

    def read_tvoc(self):
        """Read TVOC concentration.

        Returns:
            float: TVOC in ppb.
        """
        self._wait_for_new_data()
        return float(self._read_reg_le16(self._REG_DATA_TVOC))

    def read_eco2(self):
        """Read equivalent CO2 concentration.

        Returns:
            float: eCO2 in ppm.
        """
        self._wait_for_new_data()
        return float(self._read_reg_le16(self._REG_DATA_ECO2))

    def read_aqi(self):
        """Read Air Quality Index (UBA scale).

        Returns:
            int: AQI value 1–5 (1=Excellent, 5=Unhealthy).
        """
        self._wait_for_new_data()
        data = self._read_reg(self._REG_DATA_AQI, 1)
        return data[0] & 0x07

    def read_ethanol(self):
        """Read ethanol concentration estimate.

        Returns:
            float: Ethanol estimate in ppb (alias of DATA_TVOC at 0x22).
        """
        self._wait_for_new_data()
        return float(self._read_reg_le16(self._REG_DATA_TVOC))

    def read_raw_resistance(self, sensor):
        """Read raw sensor resistance from GPR_READ registers.

        Args:
            sensor: Sensor number (1 or 4).

        Returns:
            float: Resistance in Ohms.

        Raises:
            ValueError: If sensor is not 1 or 4.
        """
        if sensor == 1:
            offset = 0
        elif sensor == 4:
            offset = 6
        else:
            raise ValueError('sensor must be 1 or 4, got {}'.format(sensor))
        data = self._read_reg(self._REG_GPR_READ + offset, 2)
        raw = data[0] | (data[1] << 8)
        return 2.0 ** (raw / 2048.0)

    def read_compensation_actuals(self):
        """Read the temperature and humidity values used by the sensor.

        Returns:
            dict: Keys ``temp_celsius`` (float) and ``rh_percent`` (float).
        """
        data = self._read_reg(self._REG_DATA_T, 4)
        temp_raw = data[0] | (data[1] << 8)
        rh_raw = data[2] | (data[3] << 8)
        temp_celsius = (temp_raw / 64.0) - 273.15
        rh_percent = rh_raw / 512.0
        return {'temp_celsius': temp_celsius, 'rh_percent': rh_percent}

    def get_firmware_version(self):
        """Query firmware version (requires IDLE mode).

        Switches to IDLE, issues GET_APPVER command, reads GPR_READ, then
        returns to STANDARD mode.

        Returns:
            tuple: (major, minor, release) as integers.
        """
        self._write_reg(self._REG_OPMODE, self._OPMODE_IDLE)
        time.sleep(0.001)
        self._write_reg(self._REG_COMMAND, 0x0E)
        time.sleep(0.001)
        data = self._read_reg(self._REG_GPR_READ + 4, 3)
        major = data[0]
        minor = data[1]
        release = data[2]
        self._write_reg(self._REG_OPMODE, self._OPMODE_STANDARD)
        return (major, minor, release)

    def configure_interrupt(self, enabled=True, active_high=False, push_pull=False, on_data=True, on_gpr=False):
        """Configure the INTn interrupt pin.

        Args:
            enabled: Enable interrupt pin.
            active_high: True for active-high polarity, False for active-low.
            push_pull: True for push-pull drive, False for open-drain.
            on_data: Assert on new DATA_xxx data.
            on_gpr: Assert on new GPR_READ data.
        """
        config = 0
        if enabled:
            config |= 0x01
        if on_data:
            config |= 0x02
        if on_gpr:
            config |= 0x08
        if push_pull:
            config |= 0x20
        if active_high:
            config |= 0x40
        self._write_reg(self._REG_CONFIG, config)

    def sleep(self):
        """Enter DEEP SLEEP mode for power saving."""
        self._write_reg(self._REG_OPMODE, self._OPMODE_DEEP_SLEEP)

    def wake(self):
        """Wake from DEEP SLEEP and resume STANDARD gas sensing."""
        self._write_reg(self._REG_OPMODE, self._OPMODE_IDLE)
        time.sleep(0.001)
        self._write_reg(self._REG_OPMODE, self._OPMODE_STANDARD)
