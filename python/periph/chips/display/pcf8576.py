class PCF8576Minimal:
    """PCF8576 40x4 universal LCD segment driver — minimal interface.

    Drives a single 7-segment LCD display (static or 1:4 multiplex) out of
    the box. The chip is write-only — the host never reads back. I2C address
    is 0x38 (SA0 = VSS) or 0x39 (SA0 = VDD).

    The chip is initialized with sensible defaults: 1:4 multiplex drive mode,
    1/3 bias, display enabled, and a 7-segment digit lookup table for the
    default multiplex mode.

    Args:
        transport: Configured I2C transport pointing at the device.
    """

    _ADDR_SA0_LOW  = 0x38
    _ADDR_SA0_HIGH = 0x39

    _CMD_MODE_SET      = 0x40
    _CMD_LOAD_PTR      = 0x00
    _CMD_DEVICE_SELECT = 0x60
    _CMD_BANK_SELECT   = 0x78
    _CMD_BLINK_SELECT  = 0x70

    _MODE_1_4    = 0x00
    _MODE_STATIC = 0x01
    _MODE_1_2    = 0x02
    _MODE_1_3    = 0x03

    _BIAS_1_3 = 0x00
    _BIAS_1_2 = 0x04

    _DISPLAY_OFF = 0x00
    _DISPLAY_ON  = 0x08

    _SEVEN_SEG = [
        0xED, 0x60, 0xA7, 0xE3, 0x6A,
        0xCB, 0xCF, 0xE0, 0xEF, 0xEB,
    ]

    def __init__(self, transport):
        self._transport = transport
        self._backplanes = 4
        self._clear()

    def _cmd_mode(self, enable=True, bias=_BIAS_1_3, mode=_MODE_1_4):
        return self._CMD_MODE_SET | (self._DISPLAY_ON if enable else self._DISPLAY_OFF) | bias | mode

    def _send_commands(self, *cmds):
        out = bytearray()
        for c in cmds[:-1]:
            out.append(0x80 | (c & 0x7F))
        out.append(cmds[-1] & 0x7F)
        self._transport.write(bytes(out))

    def _send_commands_with_data(self, *cmds_and_data):
        out = bytearray()
        for i, item in enumerate(cmds_and_data):
            if isinstance(item, (bytes, bytearray)):
                out.extend(item)
            else:
                is_last_command = (i == len(cmds_and_data) - 1) or isinstance(cmds_and_data[i + 1], (bytes, bytearray))
                if is_last_command:
                    out.append(item & 0x7F)
                else:
                    out.append(0x80 | (item & 0x7F))
        self._transport.write(bytes(out))

    def _clear(self):
        self._send_commands(self._cmd_mode(enable=True))
        self._send_commands_with_data(
            self._CMD_LOAD_PTR,
            bytearray(20),
        )

    def clear(self):
        """Zero all 40 columns of display RAM; all segments off.

        Sends load-data-pointer = 0 followed by 20 zero bytes (40 columns
        in 1:4 multiplex mode) to blank the entire display.
        """
        self._clear()

    def write_raw(self, address, data):
        """Set the data pointer to ``address`` and write raw data bytes.

        Args:
            address: RAM column address, 0-39.
            data: bytes to write to the display RAM; one byte covers two
                adjacent columns in 1:4 multiplex mode.
        """
        if address < 0 or address > 39:
            raise ValueError('address must be in 0..39')
        if not data:
            return
        self._send_commands_with_data(
            self._CMD_LOAD_PTR | (address & 0x3F),
            data,
        )

    def set_digit_7seg(self, position, segments):
        """Write one 7-segment byte at column ``position * 2``.

        Args:
            position: digit index, 0-19. Maps to RAM address ``position * 2``.
            segments: 7-segment byte (a/c/b/DP/f/e/g/d packed, MSB-first).
                Add ``0x10`` to set the decimal point. Use the lookup table
                from the spec or compose bytes manually.
        """
        if position < 0 or position > 19:
            raise ValueError('position must be in 0..19')
        self.write_raw(position * 2, bytes([segments & 0xFF]))


class PCF8576Full(PCF8576Minimal):
    """PCF8576 full interface — extends PCF8576Minimal with drive mode, bias, and blink control.

    Adds the ability to switch drive modes (static, 1:2, 1:3, 1:4 multiplex),
    change bias (1:2 or 1/3), configure blinking, select RAM banks for
    static and 1:2 multiplex use, and change the device subaddress counter
    for cascaded displays.

    Args:
        transport: Configured I2C transport pointing at the device.
    """

    BLINK_OFF     = 0
    BLINK_2_HZ    = 1
    BLINK_1_HZ    = 2
    BLINK_0_5_HZ  = 3

    BIAS_1_3 = 0
    BIAS_1_2 = 1

    BACKPLANES_1 = 1
    BACKPLANES_2 = 2
    BACKPLANES_3 = 3
    BACKPLANES_4 = 4

    BANK_0 = 0
    BANK_1 = 1

    def __init__(self, transport):
        self._enabled = True
        self._bias = self.BIAS_1_3
        super().__init__(transport)

    def _mode_code(self, backplanes):
        return {
            self.BACKPLANES_1: self._MODE_STATIC,
            self.BACKPLANES_2: self._MODE_1_2,
            self.BACKPLANES_3: self._MODE_1_3,
            self.BACKPLANES_4: self._MODE_1_4,
        }[backplanes]

    def _apply_mode(self):
        bias_bits = self._BIAS_1_2 if self._bias == self.BIAS_1_2 else self._BIAS_1_3
        self._send_commands(
            self._cmd_mode(enable=self._enabled, bias=bias_bits,
                            mode=self._mode_code(self._backplanes))
        )

    def enable(self):
        """Turn the display on (E=1). RAM contents are preserved."""
        self._enabled = True
        self._apply_mode()

    def disable(self):
        """Blank the display output (E=0). RAM contents are preserved."""
        self._enabled = False
        self._apply_mode()

    def set_mode(self, backplanes, bias=0):
        """Reconfigure drive mode and bias at runtime.

        Args:
            backplanes: number of backplanes — 1 (static), 2 (1:2), 3 (1:3),
                4 (1:4 multiplex).
            bias: 0 = 1/3 bias (recommended for 1:3 and 1:4 multiplex),
                1 = 1/2 bias.
        """
        self._backplanes = backplanes
        self._bias = bias
        self._apply_mode()

    def set_blink(self, frequency, alternate_bank=False):
        """Set the blink frequency.

        Args:
            frequency: 0 = off, 1 = ~2 Hz, 2 = ~1 Hz, 3 = ~0.5 Hz.
            alternate_bank: ``True`` enables alternate-RAM-bank blinking
                (static and 1:2 multiplex only).
        """
        if frequency < 0 or frequency > 3:
            raise ValueError('frequency must be in 0..3')
        ab = 0x04 if alternate_bank else 0
        self._send_commands(self._CMD_BLINK_SELECT | ab | (frequency & 0x03))

    def set_bank(self, input_bank, output_bank):
        """Select the active RAM bank.

        Args:
            input_bank: 0 (rows 0-1) or 1 (rows 2-3).
            output_bank: 0 (rows 0-1) or 1 (rows 2-3).

        Only meaningful in static and 1:2 multiplex modes.
        """
        if input_bank not in (0, 1) or output_bank not in (0, 1):
            raise ValueError('bank values must be 0 or 1')
        self._send_commands(
            self._CMD_BANK_SELECT | ((input_bank & 1) << 1) | (output_bank & 1)
        )

    def device_select(self, subaddress):
        """Change the subaddress counter for cascaded displays.

        Args:
            subaddress: 0-7; must match the A0/A1/A2 pin state of the
                target device on the bus.
        """
        if subaddress < 0 or subaddress > 7:
            raise ValueError('subaddress must be in 0..7')
        self._send_commands(self._CMD_DEVICE_SELECT | (subaddress & 0x07))
