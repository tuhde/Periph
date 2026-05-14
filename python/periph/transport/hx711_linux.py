import time


class HX711Transport:
    """HX711 GPIO bit-bang transport for Linux (wraps gpiod lines).

    Implements the 2-wire bit-bang protocol used exclusively by the HX711
    24-bit ADC. DOUT is sampled on each rising edge of PD_SCK; the pulse
    count selects the channel and gain for the next conversion.

    The DOUT poll loop sleeps 1 ms between checks to avoid busy-waiting a
    CPU core.

    Args:
        dout:   gpiod.Line requested as INPUT (active_low=False).
        pd_sck: gpiod.Line requested as OUTPUT.
    """

    def __init__(self, dout, pd_sck):
        self._dout = dout
        self._sck = pd_sck
        self._sck.set_value(0)

    def is_ready(self):
        """Return True if a conversion result is available (DOUT is LOW).

        Non-blocking.

        Returns:
            bool: True when DOUT is LOW (data ready).
        """
        return self._dout.get_value() == 0

    def read_raw(self, num_pulses):
        """Block until data is ready, then clock out a conversion.

        Sends exactly num_pulses rising edges on PD_SCK and samples DOUT on
        each one. The pulse count programs the channel and gain for the next
        conversion: 25 → Channel A Gain 128, 26 → Channel B Gain 32,
        27 → Channel A Gain 64.

        Polls DOUT with a 1 ms sleep between checks to avoid spinning a CPU core.

        Args:
            num_pulses: Number of PD_SCK pulses to send (must be 25, 26, or 27).

        Returns:
            int: Signed 24-bit ADC value.

        Raises:
            ValueError: If num_pulses is not 25, 26, or 27.
        """
        if num_pulses not in (25, 26, 27):
            raise ValueError("num_pulses must be 25, 26, or 27")
        while self._dout.get_value() != 0:
            time.sleep(0.001)
        raw = 0
        for _ in range(num_pulses):
            self._sck.set_value(1)
            raw = (raw << 1) | self._dout.get_value()
            self._sck.set_value(0)
        raw >>= num_pulses - 24
        if raw >= 0x800000:
            raw -= 0x1000000
        return raw

    def power_down(self):
        """Enter power-down mode by holding PD_SCK HIGH for >60 µs."""
        self._sck.set_value(1)
        time.sleep(0.000065)

    def power_up(self):
        """Exit power-down mode and reset the chip.

        Drives PD_SCK LOW. The chip resets to Channel A, Gain 128. The first
        conversion after power-up must be discarded.
        """
        self._sck.set_value(0)

    def close(self):
        """Release both GPIO lines back to the kernel."""
        self._dout.release()
        self._sck.release()
