import struct
import time


class MPL115A2Minimal:
    """MPL115A2 miniature I2C digital barometer (50-115 kPa) — minimal interface.

    Reads the compensated absolute pressure in pascals and an approximate
    temperature in degrees Celsius. The four per-device compensation
    coefficients (a0, b1, b2, c12) are read once at construction and cached;
    each reading writes CONVERT, waits 5 ms and reads the ADC registers. The
    chip has a fixed I2C address (0x60) and no configuration registers.

    Args:
        connection: RegisterConnection (I2C, address 0x60).
    """

    ADDRESS = 0x60

    _REG_PADC = 0x00
    _REG_COEF = 0x04
    _REG_CONVERT = 0x12

    _CONVERSION_TIME = 0.005
    _TEMP_COUNTS_PER_C = -5.35
    _TEMP_REF_COUNTS = 498
    _TEMP_REF_C = 25.0

    def __init__(self, connection):
        self._connection = connection
        self.reload_coefficients()

    def reload_coefficients(self):
        """Read the eight coefficient bytes and cache a0, b1, b2, c12."""
        data = self._connection.read_reg(self._REG_COEF, 8)
        a0, b1, b2, c12 = struct.unpack('>hhhh', bytes(data))
        self._a0 = a0 / 8.0
        self._b1 = b1 / 8192.0
        self._b2 = b2 / 16384.0
        self._c12 = c12 / 16777216.0

    def _convert(self):
        self._connection.write_reg(self._REG_CONVERT, b'\x00')
        time.sleep(self._CONVERSION_TIME)
        data = self._connection.read_reg(self._REG_PADC, 4)
        padc = ((data[0] << 8) | data[1]) >> 6
        tadc = ((data[2] << 8) | data[3]) >> 6
        return padc, tadc

    def _compensate(self, padc, tadc):
        pcomp = self._a0 + (self._b1 + self._c12 * tadc) * padc + self._b2 * tadc
        return (pcomp * (115.0 - 50.0) / 1023.0 + 50.0) * 1000.0

    @classmethod
    def _temperature_c(cls, tadc):
        return cls._TEMP_REF_C + (tadc - cls._TEMP_REF_COUNTS) / cls._TEMP_COUNTS_PER_C

    def read_pressure(self):
        """Run one conversion and return the compensated pressure.

        Returns:
            float: Pressure in pascals.
        """
        padc, tadc = self._convert()
        return self._compensate(padc, tadc)

    def read_temperature(self):
        """Run one conversion and return an approximate temperature.

        The datasheet gives no temperature formula; this uses the common
        approximation 25 + (Tadc - 498) / -5.35 and is accurate to only a few
        degrees. Use it for trends, not as a measurement.

        Returns:
            float: Approximate temperature in degrees Celsius.
        """
        _, tadc = self._convert()
        return self._temperature_c(tadc)


class MPL115A2Full(MPL115A2Minimal):
    """MPL115A2 full interface — extends MPL115A2Minimal.

    Adds a combined reading, raw ADC counts, coefficient access, the pure
    compensation function, hPa and altitude helpers. The chip has no
    configuration registers; power state is hardware (SHDN / RST pins).

    Args:
        connection: RegisterConnection (I2C, address 0x60).
    """

    def read(self):
        """Run one conversion and return both values.

        Returns:
            dict: ``{'pressure_pa': float, 'temperature_c': float}``.
        """
        padc, tadc = self._convert()
        return {'pressure_pa': self._compensate(padc, tadc),
                'temperature_c': self._temperature_c(tadc)}

    def read_raw(self):
        """Run one conversion and return the raw 10-bit ADC counts.

        Returns:
            tuple: ``(padc, tadc)``, each 0-1023.
        """
        return self._convert()

    def coefficients(self):
        """Return the cached compensation coefficients.

        Returns:
            tuple: ``(a0, b1, b2, c12)`` as floats.
        """
        return (self._a0, self._b1, self._b2, self._c12)

    def compensate(self, padc, tadc):
        """Compute pressure from raw counts and the cached coefficients (no bus access).

        Args:
            padc: 10-bit pressure ADC count.
            tadc: 10-bit temperature ADC count.

        Returns:
            float: Pressure in pascals.
        """
        return self._compensate(padc, tadc)

    def pressure_hpa(self):
        """Run one conversion and return the pressure in hectopascals.

        Returns:
            float: Pressure in hPa.
        """
        return self.read_pressure() / 100.0

    def altitude(self, sea_level_pa=101325.0):
        """Compute the altitude from a fresh pressure reading (barometric formula, informative).

        Args:
            sea_level_pa: Reference sea-level pressure in Pa (default 101325).

        Returns:
            float: Altitude in metres.
        """
        p = self.read_pressure()
        return 44330.0 * (1.0 - (p / sea_level_pa) ** (1.0 / 5.255))
