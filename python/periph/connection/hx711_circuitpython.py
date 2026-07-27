import time


class HX711Connection:
    """HX711 GPIO bit-bang connection for CircuitPython (wraps digitalio.DigitalInOut).

    Implements the 2-wire bit-bang protocol used exclusively by the HX711
    24-bit ADC. DOUT is sampled on each falling edge of PD_SCK; the pulse
    count selects the channel and gain for the next conversion.

    This is a custom protocol with no generic byte read/write, so it does not
    extend the shared Connection base — it carries its own enabled flag and
    en_pin instead.

    Args:
        dout:   digitalio.DigitalInOut configured as Direction.INPUT.
        pd_sck: digitalio.DigitalInOut configured as Direction.OUTPUT.
        en_pin: Optional OutputPin for hardware enable/power control.
    """

    def __init__(self, dout, pd_sck, en_pin=None):
        self._dout = dout
        self._sck = pd_sck
        self.en_pin = en_pin
        self._enabled = True
        self._sck.value = False

    def enable(self):
        """Resume conversions; drives the hardware EN pin high if wired."""
        self._enabled = True
        if self.en_pin:
            self.en_pin.set(True)

    def disable(self):
        """Gate read_raw(); drives the hardware EN pin low if wired."""
        self._enabled = False
        if self.en_pin:
            self.en_pin.set(False)

    def is_enabled(self):
        """Return the current software-gate state.

        Returns:
            bool: True if enabled.
        """
        return self._enabled

    def is_ready(self):
        """Return True if a conversion result is available (DOUT is LOW).

        Non-blocking.

        Returns:
            bool: True when DOUT is LOW (data ready).
        """
        return not self._dout.value

    def read_raw(self, num_pulses):
        """Block until data is ready, then clock out a conversion.

        Waits up to 1 second for DOUT to go LOW (conversion ready), then sends
        exactly num_pulses PD_SCK pulses and samples DOUT at each falling edge
        (HIGH→LOW transition). The pulse count programs the channel and gain for
        the next conversion: 25 → Channel A Gain 128, 26 → Channel B Gain 32,
        27 → Channel A Gain 64. PD_SCK is left LOW after the last pulse.
        Returns 0 without touching the bus if this connection is disabled.

        Args:
            num_pulses: Number of PD_SCK pulses to send (must be 25, 26, or 27).

        Returns:
            int: Signed 24-bit ADC value, or 0 if disabled.

        Raises:
            ValueError: If num_pulses is not 25, 26, or 27.
            TimeoutError: If DOUT does not go LOW within 1 second.
        """
        if not self._enabled:
            return 0
        if num_pulses not in (25, 26, 27):
            raise ValueError("num_pulses must be 25, 26, or 27")
        deadline = time.monotonic() + 1.0
        while self._dout.value:
            if time.monotonic() >= deadline:
                raise TimeoutError("HX711 DOUT did not go low within 1 second")
        raw = 0
        for _ in range(num_pulses):
            self._sck.value = True
            time.sleep(0.000001)
            self._sck.value = False
            time.sleep(0.000001)
            raw = (raw << 1) | (1 if self._dout.value else 0)
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
