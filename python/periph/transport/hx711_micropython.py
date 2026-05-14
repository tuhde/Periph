import time


class HX711Transport:
    """HX711 GPIO bit-bang transport for MicroPython (wraps machine.Pin).

    Implements the 2-wire bit-bang protocol used exclusively by the HX711
    24-bit ADC. DOUT is sampled on each rising edge of PD_SCK; the pulse
    count selects the channel and gain for the next conversion.

    Args:
        dout:   machine.Pin configured as input (the data-out pin from the chip).
        pd_sck: machine.Pin configured as output (the clock / power-down pin).
    """

    def __init__(self, dout, pd_sck):
        self._dout = dout
        self._sck = pd_sck
        self._sck.value(0)

    def is_ready(self):
        """Return True if a conversion result is available (DOUT is LOW).

        Non-blocking.

        Returns:
            bool: True when DOUT is LOW (data ready).
        """
        return self._dout.value() == 0

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
        while self._dout.value() != 0:
            pass
        raw = 0
        for _ in range(num_pulses):
            self._sck.value(1)
            raw = (raw << 1) | self._dout.value()
            self._sck.value(0)
        raw >>= num_pulses - 24
        if raw >= 0x800000:
            raw -= 0x1000000
        return raw

    def power_down(self):
        """Enter power-down mode by holding PD_SCK HIGH for >60 µs."""
        self._sck.value(1)
        time.sleep_us(65)

    def power_up(self):
        """Exit power-down mode and reset the chip.

        Drives PD_SCK LOW. The chip resets to Channel A, Gain 128. The first
        conversion after power-up must be discarded.
        """
        self._sck.value(0)

    def close(self):
        """Release pins. No-op on MicroPython; provided for interface consistency."""
        pass
