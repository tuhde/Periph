import struct
import time


class HMC5883LMinimal:
    """HMC5883L 3-axis magnetometer — minimal interface.

    Reads magnetic field on all three axes in continuous mode with sensible
    defaults baked in. No configuration required beyond the connection.

    Default behaviour (baked into Minimal):
        - Averaging: 8 samples (MA=11)
        - ODR: 15 Hz (DO=100)
        - Gain: ±1.3 Ga (GN=001), 1090 LSb/Gauss
        - Mode: continuous measurement

    Args:
        connection: Configured I²C connection pointing at the device (fixed address 0x1E).
    """

    _REG_CONFIG_A = 0x00
    _REG_CONFIG_B = 0x01
    _REG_MODE = 0x02
    _REG_DATA_X_MSB = 0x03
    _REG_STATUS = 0x09
    _REG_ID_A = 0x0A
    _REG_ID_B = 0x0B
    _REG_ID_C = 0x0C

    _GAIN_LSB_PER_GAUSS = {
        0: 1370,
        1: 1090,
        2: 820,
        3: 660,
        4: 440,
        5: 390,
        6: 330,
        7: 230,
    }

    def __init__(self, connection):
        self._connection = connection
        self._gain = 1
        self._gain_lsb_per_gauss = self._GAIN_LSB_PER_GAUSS[self._gain]
        self._init_minimal()

    def _init_minimal(self):
        self._connection.write(bytes([self._REG_CONFIG_A, 0x70]))
        self._connection.write(bytes([self._REG_CONFIG_B, 0x20]))
        self._connection.write(bytes([self._REG_MODE, 0x00]))
        time.sleep(0.006)

    def _read_reg8(self, reg):
        return self._connection.write_read(bytes([reg]), 1)[0]

    def _read_reg16(self, reg):
        raw = self._connection.write_read(bytes([reg]), 2)
        return (raw[0] << 8) | raw[1]

    def _read_data_burst(self):
        raw = self._connection.write_read(bytes([self._REG_DATA_X_MSB]), 6)
        raw_x = struct.unpack('>h', raw[0:2])[0]
        raw_z = struct.unpack('>h', raw[2:4])[0]
        raw_y = struct.unpack('>h', raw[4:6])[0]
        return raw_x, raw_y, raw_z

    def magnetic_field(self):
        """Read magnetic field on all three axes.

        Returns:
            tuple: (x, y, z) magnetic field strength in Tesla.
                   Returns None for any axis that overflows (raw == -4096).
        """
        raw_x, raw_y, raw_z = self._read_data_burst()

        def to_tesla(raw):
            if raw == -4096:
                return None
            return (raw / self._gain_lsb_per_gauss) * 1e-4

        return (to_tesla(raw_x), to_tesla(raw_y), to_tesla(raw_z))


class HMC5883LFull(HMC5883LMinimal):
    """HMC5883L full interface — extends HMC5883LMinimal with complete chip functionality.

    Adds configuration, single-shot mode, self-test, identification, and status access.

    Args:
        connection: Configured I²C connection pointing at the device (fixed address 0x1E).
    """

    def __init__(self, connection):
        super().__init__(connection)

    def configure(self, odr=15, averaging=8, gain=1):
        """Write Configuration Registers A and B.

        Args:
            odr: Data output rate in Hz (continuous mode). Valid: 0.75, 1.5, 3, 7.5, 15, 30, 75.
            averaging: Samples averaged per output. Valid: 1, 2, 4, 8.
            gain: Gain index 0–7 (see gain table in datasheet).
        """
        ma_map = {1: 0b00, 2: 0b01, 4: 0b10, 8: 0b11}
        do_map = {0.75: 0b000, 1.5: 0b001, 3: 0b010, 7.5: 0b011,
                  15: 0b100, 30: 0b101, 75: 0b110}

        if averaging not in ma_map:
            raise ValueError('averaging must be 1, 2, 4, or 8')
        if odr not in do_map:
            raise ValueError('odr must be 0.75, 1.5, 3, 7.5, 15, 30, or 75')
        if not 0 <= gain <= 7:
            raise ValueError('gain must be 0–7')

        ma = ma_map[averaging]
        do = do_map[odr]
        config_a = (ma << 5) | (do << 2)
        self._connection.write(bytes([self._REG_CONFIG_A, config_a]))

        config_b = (gain << 5)
        self._connection.write(bytes([self._REG_CONFIG_B, config_b]))

        self._gain = gain
        self._gain_lsb_per_gauss = self._GAIN_LSB_PER_GAUSS[gain]

    def set_gain(self, gain):
        """Update the gain setting (GN bits in Config B).

        Args:
            gain: Gain index 0–7.
        """
        if not 0 <= gain <= 7:
            raise ValueError('gain must be 0–7')
        config_b = (gain << 5)
        self._connection.write(bytes([self._REG_CONFIG_B, config_b]))
        self._gain = gain
        self._gain_lsb_per_gauss = self._GAIN_LSB_PER_GAUSS[gain]

    def set_mode(self, mode):
        """Set the operating mode.

        Args:
            mode: 'continuous', 'single', or 'idle'.
        """
        mode_map = {'continuous': 0b00, 'single': 0b01, 'idle': 0b10}
        if mode not in mode_map:
            raise ValueError("mode must be 'continuous', 'single', or 'idle'")
        self._connection.write(bytes([self._REG_MODE, mode_map[mode]]))

    def data_ready(self):
        """Check if new measurement data is ready.

        Returns:
            bool: True if RDY bit is set in Status Register.
        """
        status = self._read_reg8(self._REG_STATUS)
        return bool(status & 0x01)

    def status(self):
        """Read the raw Status Register.

        Returns:
            int: Raw STATUS register byte (RDY in bit 0, LOCK in bit 1).
        """
        return self._read_reg8(self._REG_STATUS)

    def single_measurement(self):
        """Take a single measurement in single-shot mode.

        Returns:
            tuple: (x, y, z) magnetic field strength in Tesla.
                   Returns None for any axis that overflows (raw == -4096).
        """
        self._connection.write(bytes([self._REG_MODE, 0x01]))
        time.sleep(0.006)
        return self.magnetic_field()

    def identify(self):
        """Read the identification registers.

        Returns:
            tuple: (id_a, id_b, id_c) — expected (0x48, 0x34, 0x33) = ASCII 'H43'.
        """
        id_a = self._read_reg8(self._REG_ID_A)
        id_b = self._read_reg8(self._REG_ID_B)
        id_c = self._read_reg8(self._REG_ID_C)
        return (id_a, id_b, id_c)

    def self_test(self, positive=True):
        """Run self-test with positive or negative bias.

        Args:
            positive: True for positive bias (MS=01), False for negative bias (MS=10).

        Returns:
            tuple: (x, y, z) magnetic field deflection in Tesla during self-test.
                   Returns None for any axis that overflows.
        """
        ms = 0b01 if positive else 0b10
        config_a = self._connection.write_read(bytes([self._REG_CONFIG_A]), 1)[0]
        config_a = (config_a & 0xFC) | ms
        self._connection.write(bytes([self._REG_CONFIG_A, config_a]))

        self._connection.write(bytes([self._REG_MODE, 0x01]))
        time.sleep(0.006)
        result = self.magnetic_field()

        config_a = (config_a & 0xFC) | 0b00
        self._connection.write(bytes([self._REG_CONFIG_A, config_a]))
        return result