class HX711Minimal:
    """HX711 24-bit ADC — minimal interface.

    Reads signed 24-bit ADC values using Channel A, Gain 128. No configuration
    beyond the transport is required. The first conversion after power-up is
    discarded during construction.

    Args:
        transport: Configured HX711 transport (HX711Transport for the target platform).
    """

    def __init__(self, transport):
        """Initialize HX711Minimal and discard the first post-power-up conversion.

        Args:
            transport: Configured HX711 transport.
        """
        self._transport = transport
        self._transport.read_raw(25)

    def is_ready(self):
        """Return True if a conversion result is available (DOUT is LOW).

        Non-blocking.

        Returns:
            bool: True when DOUT is LOW (data ready).
        """
        return self._transport.is_ready()

    def read_raw(self):
        """Block until data is ready and return a signed 24-bit ADC value.

        Reads Channel A at Gain 128.

        Returns:
            int: Signed 24-bit ADC value (-8 388 608 to +8 388 607).
        """
        return self._transport.read_raw(25)


class HX711Full(HX711Minimal):
    """HX711 full interface — extends HX711Minimal with gain, tare, and calibration.

    Adds gain selection (Channel A Gain 128/64, Channel B Gain 32), multi-sample
    averaging, tare offset capture, scale factor calibration, and power management.

    Args:
        transport: Configured HX711 transport (HX711Transport for the target platform).
    """

    _GAIN_TO_PULSES = {128: 25, 32: 26, 64: 27}

    def __init__(self, transport):
        """Initialize HX711Full with default gain 128, offset 0, and scale 1.0.

        Args:
            transport: Configured HX711 transport.
        """
        self._pulses = 25
        self._offset = 0
        self._scale = 1.0
        super().__init__(transport)

    def read_raw(self):
        """Block until data is ready and return a signed 24-bit ADC value.

        Uses the currently selected channel and gain.

        Returns:
            int: Signed 24-bit ADC value (-8 388 608 to +8 388 607).
        """
        return self._transport.read_raw(self._pulses)

    def set_gain(self, gain):
        """Select the input channel and gain.

        The new setting takes effect after the next conversion. This method
        issues one dummy read to apply the new gain before returning.

        Args:
            gain: 128 (Channel A), 64 (Channel A), or 32 (Channel B).

        Raises:
            ValueError: If gain is not 128, 64, or 32.
        """
        if gain not in self._GAIN_TO_PULSES:
            raise ValueError("gain must be 128, 64, or 32")
        self._pulses = self._GAIN_TO_PULSES[gain]
        self._transport.read_raw(self._pulses)

    def read_average(self, times=10):
        """Return the average of multiple raw ADC readings.

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

    def power_down(self):
        """Enter power-down mode.

        Holds PD_SCK HIGH for >60 µs via the transport.
        """
        self._transport.power_down()

    def power_up(self):
        """Exit power-down mode, reset the chip, and discard the settling conversion.

        Resets the chip to Channel A, Gain 128 and discards the first post-reset
        conversion. Resets the internal pulse count to 25 regardless of the
        previously selected gain.
        """
        self._transport.power_up()
        self._pulses = 25
        self._transport.read_raw(25)
