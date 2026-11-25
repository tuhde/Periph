import time


def _crc4(words):
    """CRC-4 of the MS5611 PROM (application note AN520).

    Polynomial x^4 + x^3 + 1 over the 16 bytes of the eight PROM words, MSB
    first; the CRC nibble (bits 3:0 of word 7) and the low byte of word 7 are
    treated as zero.

    Args:
        words: The eight 16-bit PROM words.

    Returns:
        int: The 4-bit CRC.
    """
    prom = list(words)
    prom[7] &= 0xFF00
    n_rem = 0
    for cnt in range(16):
        if cnt & 1:
            n_rem ^= prom[cnt >> 1] & 0x00FF
        else:
            n_rem ^= prom[cnt >> 1] >> 8
        for _ in range(8):
            if n_rem & 0x8000:
                n_rem = ((n_rem << 1) ^ 0x3000) & 0xFFFF
            else:
                n_rem = (n_rem << 1) & 0xFFFF
    return (n_rem >> 12) & 0x0F


def _div(a, b):
    """Integer division truncating toward zero, like C."""
    q = abs(a) // abs(b)
    return q if (a >= 0) == (b >= 0) else -q


class MS5611Minimal:
    """MS5611 barometric pressure sensor — minimal interface.

    init() resets the chip, reads the eight PROM words and verifies the
    CRC-4. Each read() converts the temperature (D2) and then the pressure
    (D1) at oversampling ratio 4096 (waiting 10 ms each) and applies the
    first- and second-order compensation. Pressure is in pascals
    (1 Pa = 0.01 mbar, the chip's native unit).

    The device is command driven (no register pointer), so a plain I²C
    connection is used. The constructor does not touch the bus.

    Args:
        connection: Configured I²C connection pointing at the device (address 0x77, or 0x76 with CSB high).
    """

    _CMD_RESET = 0x1E
    _CMD_CONVERT_D1 = 0x40
    _CMD_CONVERT_D2 = 0x50
    _CMD_ADC_READ = 0x00
    _CMD_PROM_READ = 0xA0
    _OSR_VALUES = (256, 512, 1024, 2048, 4096)
    # maximum conversion time per OSR (s), datasheet
    _T_CONV = (0.00060, 0.00117, 0.00228, 0.00454, 0.00904)
    _T_RESET = 0.003
    _MARGIN = 0.001

    def __init__(self, connection):
        self._connection = connection
        self._osr_index = 4
        self._prom = [0] * 8
        self._c = (0, 0, 0, 0, 0, 0)

    def _load_prom(self):
        words = self.read_prom()
        if _crc4(words) != (words[7] & 0x0F):
            raise ValueError('MS5611 PROM CRC mismatch')
        self._prom = words
        self._c = tuple(words[1:7])

    def read_prom(self):
        """Read the eight 16-bit PROM words (commands 0xA0 ... 0xAE).

        Returns:
            list: The eight PROM words.
        """
        words = []
        for n in range(8):
            self._connection.write(bytes([self._CMD_PROM_READ + 2 * n]))
            data = bytes(self._connection.read(2))
            words.append((data[0] << 8) | data[1])
        return words

    def _convert(self, kind):
        base = self._CMD_CONVERT_D2 if kind else self._CMD_CONVERT_D1
        self._connection.write(bytes([base | (self._osr_index << 1)]))
        time.sleep(self._T_CONV[self._osr_index] + self._MARGIN)
        return self._read_adc()

    def _read_adc(self):
        self._connection.write(bytes([self._CMD_ADC_READ]))
        data = bytes(self._connection.read(3))
        value = (data[0] << 16) | (data[1] << 8) | data[2]
        if value == 0:
            raise ValueError('MS5611 ADC returned 0 (conversion not finished or not started)')
        return value

    def _temperature_terms(self, d2):
        c = self._c
        dt = d2 - c[4] * 256
        temp = 2000 + _div(dt * c[5], 1 << 23)
        return dt, temp

    def _second_order(self, dt, temp):
        """Return (t2, off2, sens2) of the low-temperature correction."""
        t2 = off2 = sens2 = 0
        if temp < 2000:
            d = temp - 2000
            t2 = _div(dt * dt, 1 << 31)
            off2 = _div(5 * d * d, 2)
            sens2 = _div(5 * d * d, 4)
            if temp < -1500:
                e = temp + 1500
                off2 += 7 * e * e
                sens2 += _div(11 * e * e, 2)
        return t2, off2, sens2

    def _compensate(self, d1, d2, second_order=True):
        c = self._c
        dt, temp = self._temperature_terms(d2)
        off = c[1] * (1 << 16) + _div(c[3] * dt, 1 << 7)
        sens = c[0] * (1 << 15) + _div(c[2] * dt, 1 << 8)
        if second_order:
            t2, off2, sens2 = self._second_order(dt, temp)
            temp -= t2
            off -= off2
            sens -= sens2
        p = _div(_div(d1 * sens, 1 << 21) - off, 1 << 15)
        return temp / 100.0, float(p)

    def init(self):
        """Reset (0x1E), wait 3 ms, read the PROM and verify its CRC-4.

        Raises:
            ValueError: If the PROM CRC does not match.
        """
        self._connection.write(bytes([self._CMD_RESET]))
        time.sleep(self._T_RESET)
        self._load_prom()

    def read(self):
        """Measure temperature and pressure (OSR 4096, second-order compensation).

        Returns:
            dict: {'temperature_c': float °C, 'pressure_pa': float Pa}.

        Raises:
            ValueError: If the ADC returns 0.
        """
        d2 = self._convert(1)
        d1 = self._convert(0)
        t, p = self._compensate(d1, d2)
        return {'temperature_c': t, 'pressure_pa': p}


class MS5611Full(MS5611Minimal):
    """MS5611 full interface — single values, raw codes, oversampling, non-blocking conversion, PROM access, altitude.

    Extends MS5611Minimal with single values, raw codes, the oversampling
    ratio, explicit conversion start / ADC read, pure compensation, reset,
    PROM and coefficient access and the barometric altitude.

    Args:
        connection: Configured I²C connection pointing at the device (address 0x77, or 0x76 with CSB high).
    """

    PRESSURE = 0
    TEMPERATURE = 1

    def __init__(self, connection):
        super().__init__(connection)

    def read_raw(self):
        """Convert D2 and D1 at the current OSR and return the raw ADC codes.

        Returns:
            tuple: (d1, d2), both 24-bit unsigned (pressure, temperature).
        """
        d2 = self._convert(1)
        d1 = self._convert(0)
        return d1, d2

    def read_pressure(self):
        """Measure and return the compensated pressure (D2 first, then D1).

        Returns:
            float: Pressure in pascals.
        """
        return self.read()['pressure_pa']

    def read_temperature(self):
        """Convert D2 only and return the temperature (with the low-temperature correction).

        Returns:
            float: Temperature in degrees Celsius.
        """
        dt, temp = self._temperature_terms(self._convert(1))
        temp -= self._second_order(dt, temp)[0]
        return temp / 100.0

    def set_oversampling(self, osr):
        """Set the oversampling ratio used for both conversions.

        Args:
            osr: 256, 512, 1024, 2048 or 4096.

        Raises:
            ValueError: For any other value.
        """
        if osr not in self._OSR_VALUES:
            raise ValueError('OSR must be 256, 512, 1024, 2048 or 4096')
        self._osr_index = self._OSR_VALUES.index(osr)

    def get_oversampling(self):
        """Return the current oversampling ratio (256 ... 4096)."""
        return self._OSR_VALUES[self._osr_index]

    def conversion_time(self, osr=None):
        """Maximum conversion time for an oversampling ratio.

        Args:
            osr: 256 ... 4096; None for the current ratio.

        Returns:
            float: Seconds (0.60 / 1.17 / 2.28 / 4.54 / 9.04 ms).

        Raises:
            ValueError: For an invalid ratio.
        """
        if osr is None:
            return self._T_CONV[self._osr_index]
        if osr not in self._OSR_VALUES:
            raise ValueError('OSR must be 256, 512, 1024, 2048 or 4096')
        return self._T_CONV[self._OSR_VALUES.index(osr)]

    def start_conversion(self, kind, osr=None):
        """Write a conversion command without waiting (non-blocking use).

        Wait at least conversion_time() before read_adc().

        Args:
            kind: PRESSURE (D1) or TEMPERATURE (D2).
            osr: Oversampling ratio; None for the current one.
        """
        index = self._osr_index if osr is None else self._OSR_VALUES.index(osr) if osr in self._OSR_VALUES else -1
        if index < 0:
            raise ValueError('OSR must be 256, 512, 1024, 2048 or 4096')
        base = self._CMD_CONVERT_D2 if kind == self.TEMPERATURE else self._CMD_CONVERT_D1
        self._connection.write(bytes([base | (index << 1)]))

    def read_adc(self):
        """ADC read command plus 3 bytes: the result of the last conversion.

        Only valid after the conversion time; a read during the conversion or
        a repeated read returns 0.

        Returns:
            int: 24-bit result.

        Raises:
            ValueError: If the chip returns 0.
        """
        return self._read_adc()

    def compensate(self, d1, d2, second_order=True):
        """Pure computation with the stored coefficients (no bus access).

        Args:
            d1: 24-bit raw pressure.
            d2: 24-bit raw temperature.
            second_order: False for the first-order result only.

        Returns:
            dict: {'temperature_c': float °C, 'pressure_pa': float Pa}.
        """
        t, p = self._compensate(d1, d2, second_order)
        return {'temperature_c': t, 'pressure_pa': p}

    def reset(self):
        """Reset (0x1E), wait 3 ms and reload and verify the PROM."""
        self.init()

    def coefficients(self):
        """Return the calibration coefficients.

        Returns:
            tuple: (c1, c2, c3, c4, c5, c6).
        """
        return self._c

    def prom_crc_ok(self):
        """Verify the CRC-4 of the PROM words read at init (no bus access).

        Returns:
            bool: True if the stored CRC nibble matches.
        """
        return _crc4(self._prom) == (self._prom[7] & 0x0F)

    def serial_code(self):
        """Return the serial code (PROM word 7, bits 15:4).

        Returns:
            int: 12-bit serial code.
        """
        return (self._prom[7] >> 4) & 0x0FFF

    def altitude(self, sea_level_pa=101325.0):
        """Altitude from one pressure reading (international barometric formula).

        Args:
            sea_level_pa: Reference sea-level pressure in pascals.

        Returns:
            float: Altitude in metres.
        """
        return 44330.0 * (1.0 - (self.read_pressure() / sea_level_pa) ** (1.0 / 5.255))
