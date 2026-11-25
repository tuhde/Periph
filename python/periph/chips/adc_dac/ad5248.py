class AD5248Minimal:
    """AD5248 dual 256-position I²C digital rheostat — minimal interface.

    Sets and reads back the wiper position (0–255) or the wiper-to-B resistance of
    either channel. The chip has no register map: the instruction byte (channel
    select bit A0 in bit 7, shutdown bit SD in bit 6) plays the role of the register
    address, so the driver uses read_reg/write_reg with the instruction byte.

    Default behaviour:
        - RAB = 10 kΩ, RW = 160 Ω; both wipers at the power-up midscale (0x80)
        - Both channels assumed active (not in shutdown); no I²C traffic at construction

    Args:
        connection: RegisterConnection (I²C) pointing at the device (0x2C–0x2F).
        r_ab: End-to-end resistance RAB in ohms (2500, 10000, 50000 or 100000).
        r_wiper: Wiper resistance RW in ohms (typically 160 Ω at 5 V).
    """

    _SD = 0x40

    def __init__(self, connection, r_ab=10000.0, r_wiper=160.0):
        self._connection = connection
        self._r_ab = float(r_ab)
        self._r_wiper = float(r_wiper)
        self._shutdown = [False, False]
        self._code = [0x80, 0x80]

    @staticmethod
    def _check_channel(channel):
        if channel not in (0, 1):
            raise ValueError('AD5248: channel must be 0 or 1')

    def _instruction(self, channel):
        return (channel << 7) | (self._SD if self._shutdown[channel] else 0)

    def set_wiper(self, channel, code):
        """Set the wiper position of a channel.

        Clamps the code to [0, 255]. On a shut-down channel the RDAC is updated and
        the channel stays in shutdown.

        Args:
            channel: Channel index, 0 or 1.
            code: Wiper position 0–255 (0 = at B, 255 = at A).
        """
        self._check_channel(channel)
        code = int(max(0, min(255, code)))
        self._connection.write_reg(self._instruction(channel), bytes([code]))
        self._code[channel] = code

    def get_wiper(self, channel):
        """Read the wiper position of a channel.

        Sends the channel's instruction byte alone, then reads one byte.

        Args:
            channel: Channel index, 0 or 1.

        Returns:
            int: Wiper position 0–255.
        """
        self._check_channel(channel)
        code = self._connection.read_reg(self._instruction(channel), 1)[0]
        self._code[channel] = code
        return code

    def set_resistance(self, channel, ohms):
        """Set the W–B resistance to the nearest reachable value.

        Args:
            channel: Channel index, 0 or 1.
            ohms: Target wiper-to-B resistance in ohms (clamped to the reachable range).
        """
        self._check_channel(channel)
        code = int(round((ohms - 2 * self._r_wiper) / self._r_ab * 256))
        self.set_wiper(channel, code)

    def get_resistance(self, channel):
        """Read the W–B resistance for the current wiper position.

        Returns:
            float: Resistance in ohms, D / 256 * RAB + 2 * RW.
        """
        code = self.get_wiper(channel)
        return code / 256.0 * self._r_ab + 2 * self._r_wiper


class AD5248Full(AD5248Minimal):
    """AD5248 full interface — extends AD5248Minimal with ratio, increment, shutdown and reset.

    Args:
        connection: RegisterConnection (I²C) pointing at the device (0x2C–0x2F).
        r_ab: End-to-end resistance RAB in ohms.
        r_wiper: Wiper resistance RW in ohms.
    """

    def set_both(self, code1, code2):
        """Write both wipers (two transactions).

        Args:
            code1: Channel 0 wiper position 0–255.
            code2: Channel 1 wiper position 0–255.
        """
        self.set_wiper(0, code1)
        self.set_wiper(1, code2)

    def set_ratio(self, channel, ratio):
        """Set the wiper to a divider ratio of the full scale.

        Wiper code = round(ratio × 256) clamped to 255. Only meaningful if terminal A
        is wired externally; the AD5248 does not pin out A, so prefer set_resistance.

        Args:
            channel: Channel index, 0 or 1.
            ratio: Ratio 0.0–1.0.
        """
        ratio = max(0.0, min(1.0, ratio))
        self.set_wiper(channel, min(255, int(round(ratio * 256))))

    def get_ratio(self, channel):
        """Read the wiper position as a ratio.

        Returns:
            float: code / 256.
        """
        return self.get_wiper(channel) / 256.0

    def increment(self, channel, steps=1):
        """Move the wiper by a number of steps.

        Args:
            channel: Channel index, 0 or 1.
            steps: Steps to add (negative to decrement).

        Returns:
            int: New wiper code, clamped to 0–255.
        """
        code = int(max(0, min(255, self.get_wiper(channel) + steps)))
        self.set_wiper(channel, code)
        return code

    def shutdown(self, channel):
        """Put a channel in shutdown (terminal A open, wiper shorted to B); RDAC is kept.

        Args:
            channel: Channel index, 0 or 1.
        """
        self._check_channel(channel)
        code = self.get_wiper(channel)
        self._shutdown[channel] = True
        self._connection.write_reg(self._instruction(channel), bytes([code]))

    def resume(self, channel):
        """Leave shutdown; the RDAC contents are restored to the wiper.

        Args:
            channel: Channel index, 0 or 1.
        """
        self._check_channel(channel)
        code = self.get_wiper(channel)
        self._shutdown[channel] = False
        self._connection.write_reg(self._instruction(channel), bytes([code]))

    def is_shutdown(self, channel):
        """Return the driver-tracked shutdown state (the chip cannot be read back).

        Returns:
            bool: True if the channel was shut down by this driver.
        """
        self._check_channel(channel)
        return self._shutdown[channel]

    def reset(self):
        """Write midscale (0x80) to both channels with SD = 0 (power-up preset)."""
        self._shutdown = [False, False]
        self.set_wiper(0, 0x80)
        self.set_wiper(1, 0x80)

    def set_rab(self, r_ab, r_wiper=None):
        """Change the resistance model used by the conversions (no I²C traffic).

        Args:
            r_ab: End-to-end resistance RAB in ohms.
            r_wiper: Wiper resistance RW in ohms; unchanged if None.
        """
        self._r_ab = float(r_ab)
        if r_wiper is not None:
            self._r_wiper = float(r_wiper)
