import time

from gpiod.line import Value


class HX711Transport:
    """HX711 GPIO bit-bang transport for Linux (wraps a gpiod v2 LineRequest).

    Implements the 2-wire bit-bang protocol used exclusively by the HX711
    24-bit ADC. DOUT is sampled on each falling edge of PD_SCK; the pulse
    count selects the channel and gain for the next conversion.

    The DOUT poll loop sleeps 1 ms between checks to avoid busy-waiting a
    CPU core.

    Args:
        request:        gpiod.LineRequest owning both lines.
        dout_offset:    GPIO line offset for DOUT (input from chip).
        pd_sck_offset:  GPIO line offset for PD_SCK (clock / power-down output).
    """

    def __init__(self, request, dout_offset, pd_sck_offset):
        self._req  = request
        self._dout = dout_offset
        self._sck  = pd_sck_offset
        self._req.set_value(self._sck, Value.INACTIVE)

    def is_ready(self):
        """Return True if a conversion result is available (DOUT is LOW).

        Non-blocking.

        Returns:
            bool: True when DOUT is LOW (data ready).
        """
        return self._req.get_value(self._dout) == Value.INACTIVE

    def read_raw(self, num_pulses):
        """Block until data is ready, then clock out a conversion.

        Waits up to 1 second for DOUT to go LOW (conversion ready), then sends
        exactly num_pulses PD_SCK pulses and samples DOUT at each falling edge
        (HIGH→LOW transition). The pulse count programs the channel and gain for
        the next conversion: 25 → Channel A Gain 128, 26 → Channel B Gain 32,
        27 → Channel A Gain 64. PD_SCK is left LOW after the last pulse.

        Polls DOUT with a 1 ms sleep between checks to avoid spinning a CPU core.

        Args:
            num_pulses: Number of PD_SCK pulses to send (must be 25, 26, or 27).

        Returns:
            int: Signed 24-bit ADC value.

        Raises:
            ValueError: If num_pulses is not 25, 26, or 27.
            TimeoutError: If DOUT does not go LOW within 1 second.
        """
        if num_pulses not in (25, 26, 27):
            raise ValueError("num_pulses must be 25, 26, or 27")
        deadline = time.monotonic() + 1.0
        while self._req.get_value(self._dout) != Value.INACTIVE:
            if time.monotonic() >= deadline:
                raise TimeoutError("HX711 DOUT did not go low within 1 second")
            time.sleep(0.001)
        raw = 0
        for _ in range(num_pulses):
            self._req.set_value(self._sck, Value.ACTIVE)
            time.sleep(0.000001)
            self._req.set_value(self._sck, Value.INACTIVE)
            time.sleep(0.000001)
            raw = (raw << 1) | self._req.get_value(self._dout).value
        raw >>= num_pulses - 24
        if raw >= 0x800000:
            raw -= 0x1000000
        return raw

    def power_down(self):
        """Enter power-down mode by holding PD_SCK HIGH for >60 µs."""
        self._req.set_value(self._sck, Value.ACTIVE)
        time.sleep(0.000065)

    def power_up(self):
        """Exit power-down mode and reset the chip.

        Drives PD_SCK LOW. The chip resets to Channel A, Gain 128. The first
        conversion after power-up must be discarded.
        """
        self._req.set_value(self._sck, Value.INACTIVE)

    def close(self):
        """Release both GPIO lines back to the kernel."""
        self._req.release()
