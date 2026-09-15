class HX710AMinimal:
    """HX710A 24-bit ADC — minimal interface.

    Reads signed 24-bit ADC values from the single differential input
    (INN/INP) at the chip's fixed Gain 128, 10 SPS default. No configuration
    beyond the connection is required. The first conversion after power-up
    is discarded during construction.

    Args:
        connection: Configured HX711 connection (HX711Connection for the target platform).
    """

    def __init__(self, connection):
        """Initialize HX710AMinimal and discard the first post-power-up conversion.

        Args:
            connection: Configured HX711 connection.
        """
        self._connection = connection
        self._connection.read_raw(25)

    def is_ready(self):
        """Return True if a conversion result is available (DOUT is LOW).

        Non-blocking.

        Returns:
            bool: True when DOUT is LOW (data ready).
        """
        return self._connection.is_ready()

    def read_raw(self):
        """Block until data is ready and return a signed 24-bit ADC value.

        Reads the differential input at Gain 128, 10 SPS.

        Returns:
            int: Signed 24-bit ADC value (-8 388 608 to +8 388 607).
        """
        return self._connection.read_raw(25)


class HX710AFull(HX710AMinimal):
    """HX710A full interface — extends HX710AMinimal with rate selection, tare,
    calibration, temperature, and power management.

    The HX710A has no software-selectable gain and no second input channel —
    unlike the HX711, only the output rate (10 or 40 SPS) is selectable for
    the differential-input reading.

    Args:
        connection: Configured HX711 connection (HX711Connection for the target platform).
    """

    _RATE_TO_PULSES = {10: 25, 40: 27}

    def __init__(self, connection):
        """Initialize HX710AFull with default 10 SPS rate, offset 0, and scale 1.0.

        Args:
            connection: Configured HX711 connection.
        """
        self._pulses = 25
        self._offset = 0
        self._scale = 1.0
        super().__init__(connection)

    def read_raw(self):
        """Block until data is ready and return a signed 24-bit ADC value.

        Uses the currently selected output rate.

        Returns:
            int: Signed 24-bit ADC value (-8 388 608 to +8 388 607).
        """
        return self._connection.read_raw(self._pulses)

    def set_rate(self, rate):
        """Select the differential-input output rate.

        The new rate takes effect after the next conversion. This method
        issues one dummy read to apply the new rate before returning.

        Args:
            rate: 10 or 40 (samples per second).

        Raises:
            ValueError: If rate is not 10 or 40.
        """
        if rate not in self._RATE_TO_PULSES:
            raise ValueError("rate must be 10 or 40")
        self._pulses = self._RATE_TO_PULSES[rate]
        self._connection.read_raw(self._pulses)

    def read_average(self, times=10):
        """Return the average of multiple raw differential-input readings.

        Args:
            times: Number of readings to average (default 10).

        Returns:
            int: Average signed 24-bit ADC value.
        """
        total = 0
        for _ in range(times):
            total += self.read_raw()
        return total // times

    def tare(self, times=10):
        """Capture the current average reading as the zero offset.

        Args:
            times: Number of readings to average for the tare (default 10).
        """
        self._offset = self.read_average(times)

    def get_offset(self):
        """Return the current tare offset.

        Returns:
            int: Stored tare offset captured by the last tare() call.
        """
        return self._offset

    def set_scale(self, factor):
        """Set the calibration scale factor.

        Calibrate by placing a known weight W on the scale after taring, then:
        factor = (read_average() - get_offset()) / W

        Args:
            factor: Scale factor (ADC counts per unit weight).
        """
        self._scale = float(factor)

    def get_scale(self):
        """Return the current calibration scale factor.

        Returns:
            float: Stored scale factor set by the last set_scale() call.
        """
        return self._scale

    def read_weight(self, times=1):
        """Return the calibrated weight in the units defined by the scale factor.

        Computes (read_average(times) - offset) / scale.

        Args:
            times: Number of readings to average (default 1).

        Returns:
            float: Calibrated weight value.
        """
        return (self.read_average(times) - self._offset) / self._scale

    def read_temperature_raw(self):
        """Block until data is ready and return the raw on-chip temperature code.

        Clocks 26 pulses (temperature channel, 40 Hz). This is **not** a
        calibrated degrees-Celsius value — the datasheet gives only a typical
        resolution of ~20.4 LSB/°C and states offset and gain vary
        significantly chip-to-chip. Use for relative drift tracking, or
        calibrate against a reference thermometer for absolute readings.

        Returns:
            int: Signed 24-bit raw ADC code from the temperature sensor.
        """
        return self._connection.read_raw(26)

    def power_down(self):
        """Enter power-down mode.

        Holds PD_SCK HIGH for >60 µs via the connection.
        """
        self._connection.power_down()

    def power_up(self):
        """Exit power-down mode, reset the chip, and discard the settling conversion.

        Resets the chip to the differential input at Gain 128 and discards
        the first post-reset conversion. Resets the internal pulse count to
        25 (10 SPS) regardless of the previously selected rate.
        """
        self._connection.power_up()
        self._pulses = 25
        self._connection.read_raw(25)
