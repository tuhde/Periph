import time


class HX711Transport:
    """HX711 GPIO bit-bang transport for CircuitPython (wraps digitalio.DigitalInOut).

    Implements the 2-wire bit-bang protocol used exclusively by the HX711
    24-bit ADC. DOUT is sampled on each rising edge of PD_SCK; the pulse
    count selects the channel and gain for the next conversion.

    Args:
        dout:   digitalio.DigitalInOut configured as Direction.INPUT.
        pd_sck: digitalio.DigitalInOut configured as Direction.OUTPUT.
    """

    def __init__(self, dout, pd_sck):
        self._dout = dout
        self._sck = pd_sck
        self._sck.value = False

    def is_ready(self):
        """Return True if a conversion result is available (DOUT is LOW).

        Non-blocking.

        Returns:
            bool: True when DOUT is LOW (data ready).
        """
        return not self._dout.value

    def read_raw(self, num_pulses):
        """Block until data is ready, then clock out a conversion.

        Sends exactly num_pulses rising edges on PD_SCK and samples DOUT on
        each one. The pulse count programs the channel and gain for the next
        conversion: 25 → Channel A Gain 128, 26 → Channel B Gain 32,
        27 → Channel A Gain 64.

        Args:
            num_pulses: Number of PD_SCK pulses to send (must be 25, 26, or 27).

        Returns:
            int: Signed 24-bit ADC value.

        Raises:
            ValueError: If num_pulses is not 25, 26, or 27.
        """
        if num_pulses not in (25, 26, 27):
            raise ValueError("num_pulses must be 25, 26, or 27")
        while self._dout.value:
            pass
        raw = 0
        for _ in range(num_pulses):
            self._sck.value = True
            raw = (raw << 1) | (0 if self._dout.value else 1)
            self._sck.value = False
        raw >>= num_pulses - 24
        if raw >= 0x800000:
            raw -= 0x1000000
        return raw

    def power_down(self):
        """Enter power-down mode by holding PD_SCK HIGH for >60 µs."""
        self._sck.value = True
        time.sleep(0.000065)

    def power_up(self):
        """Exit power-down mode and reset the chip.

        Drives PD_SCK LOW. The chip resets to Channel A, Gain 128. The first
        conversion after power-up must be discarded.
        """
        self._sck.value = False

    def close(self):
        """Deinit both pins and release them back to the system."""
        self._dout.deinit()
        self._sck.deinit()
