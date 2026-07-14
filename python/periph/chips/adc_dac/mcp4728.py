class MCP4728Minimal:
    """MCP4728 quad-channel 12-bit voltage-output DAC — minimal interface.

    Provides simple voltage output as a fraction of V_DD for any of the four
    channels (A–D) plus a convenience method to update all four channels
    simultaneously. No configuration required beyond the transport. V_REF is
    fixed at external (V_DD), gain is fixed at ×1, and power-down is off.
    EEPROM is never written by this class.

    Args:
        transport: Configured I²C transport pointing at the device (0x60–0x67).
    """

    _CMD_MULTI_WRITE = 0x40  # C2=0 C1=0 C0=0 — see spec; Multi-Write is 010
    # Multi-Write byte 1: [0 1 0 0 0 DAC1 DAC0 UDAC] = 0x40 | (channel << 1)
    _CMD_FAST_WRITE_BASE = 0x00  # Per-channel fast write: 2 bytes each, A→D

    def __init__(self, transport):
        """Initialize MCP4728Minimal and store the transport.

        Args:
            transport: Configured I²C transport pointing at the device.
        """
        self._transport = transport

    def set_voltage(self, channel, fraction):
        """Set one channel's DAC output as a fraction of V_DD.

        Uses Multi-Write to update a single channel's volatile DAC register.
        V_REF=0 (external V_DD), gain=1, PD=00, UDAC=0 (immediate V_OUT
        update). EEPROM is not written.

        Args:
            channel: Channel index 0 (A) – 3 (D).
            fraction: Output voltage as a fraction of V_DD (0.0–1.0).
        """
        code = int(round(max(0.0, min(1.0, fraction)) * 4095))
        self.set_raw(channel, code)

    def set_raw(self, channel, code):
        """Set one channel's raw 12-bit DAC code.

        Uses Multi-Write. V_REF=0 (external V_DD), gain=1, PD=00, UDAC=0.
        EEPROM is not written.

        Args:
            channel: Channel index 0 (A) – 3 (D).
            code: Raw 12-bit DAC code (0–4095).
        """
        ch = max(0, min(3, channel))
        c = int(max(0, min(4095, code)))
        self._multi_write(ch, c, vref=0, pd=0, gain=0, udac=0)

    def set_all(self, fractions):
        """Update all four channels simultaneously using Fast Write.

        Issues a single 8-byte Fast Write transaction that updates channels
        A→D at once. V_REF and gain bits are not carried by Fast Write; they
        retain whatever values are currently in each channel's input
        register. PD=00, UDAC=0 for all four channels. EEPROM is not written.

        Args:
            fractions: Iterable of 4 fractions (0.0–1.0), index 0 = A.
        """
        if len(fractions) != 4:
            raise ValueError("fractions must have exactly 4 elements")
        out = bytearray(8)
        for i in range(4):
            code = int(round(max(0.0, min(1.0, fractions[i])) * 4095))
            code = max(0, min(4095, code))
            # First channel: byte1 = [0 0 PD1 PD0 D11-D8]; channels B-D: same
            out[i * 2] = ((code >> 8) & 0x0F)  # PD1=PD0=0
            out[i * 2 + 1] = code & 0xFF
        self._transport.write(bytes(out))

    def _multi_write(self, channel, code, vref, pd, gain, udac):
        byte1 = 0x40 | ((channel & 0x03) << 1) | (udac & 0x01)
        byte2 = ((vref & 0x01) << 7) | ((pd & 0x03) << 5) | \
                ((gain & 0x01) << 4) | ((code >> 8) & 0x0F)
        byte3 = code & 0xFF
        self._transport.write(bytes([byte1, byte2, byte3]))


class MCP4728Full(MCP4728Minimal):
    """MCP4728 full interface — extends MCP4728Minimal with EEPROM, V_REF, gain, power-down, and read-back.

    Adds per-channel V_REF and gain configuration, all-channel V_REF/gain/
    power-down commands, write-with-EEPROM persistence (Single and Sequential
    Write), General Call reset/wake-up/software-update, and full 24-byte
    read-back of all channel DAC input registers and EEPROM contents.

    Args:
        transport: Configured I²C transport pointing at the device (0x60–0x67).
    """

    _CMD_SINGLE_WRITE    = 0x58  # [0 1 0 1 1 DAC1 DAC0 UDAC]
    _CMD_SEQUENTIAL_BASE = 0x50  # [0 1 0 1 0 DAC1 DAC0 UDAC] (start channel)
    _CMD_WRITE_VREF      = 0x80  # [1 0 0 X Vref_A Vref_B Vref_C Vref_D]
    _CMD_WRITE_GAIN      = 0xC0  # [1 1 0 X Gx_A Gx_B Gx_C Gx_D]
    _CMD_WRITE_POWERDOWN = 0xA0  # [1 0 1 X ...]
    _ADDR_GENERAL_CALL   = 0x00

    _GC_RESET        = 0x06
    _GC_SOFTWARE_UPD = 0x08
    _GC_WAKE         = 0x09

    PD_NORMAL    = 0
    PD_1K_GND    = 1
    PD_100K_GND  = 2
    PD_500K_GND  = 3

    VREF_EXTERNAL = 0  # V_DD
    VREF_INTERNAL = 1  # 2.048 V

    GAIN_X1 = 0
    GAIN_X2 = 1

    def __init__(self, transport):
        """Initialize MCP4728Full and store the transport.

        Args:
            transport: Configured I²C transport pointing at the device.
        """
        super().__init__(transport)

    def set_voltage_eeprom(self, channel, fraction, vref, gain):
        """Set one channel's output and persist to EEPROM.

        Uses Single Write: updates the volatile DAC input register and the
        nonvolatile EEPROM copy. Output voltage is computed from the
        channel's configured V_REF and gain.

        Args:
            channel: Channel index 0 (A) – 3 (D).
            fraction: Output as a fraction of the configured full-scale
                     (V_DD for external, 2.048 V for internal ×1, 4.096 V for
                     internal ×2).
            vref: 0 = external (V_DD), 1 = internal (2.048 V).
            gain: 1 = ×1, 2 = ×2 (ignored when vref = external).
        """
        f = max(0.0, min(1.0, float(fraction)))
        code = int(round(f * 4095))
        self._single_write(channel, code, vref, pd=0, gain=gain, udac=0)

    def set_raw_eeprom(self, channel, code, vref, gain):
        """Set one channel's raw 12-bit code and persist to EEPROM.

        Args:
            channel: Channel index 0 (A) – 3 (D).
            code: Raw 12-bit DAC code (0–4095).
            vref: 0 = external (V_DD), 1 = internal (2.048 V).
            gain: 1 = ×1, 2 = ×2 (ignored when vref = external).
        """
        c = int(max(0, min(4095, code)))
        self._single_write(channel, c, vref, pd=0, gain=gain, udac=0)

    def set_all_eeprom(self, fractions, vrefs, gains):
        """Update all four channels and EEPROM starting from channel A.

        Uses Sequential Write from channel 0 to channel 3. Each channel
        receives one 2-byte (V_REF/PD/Gain/code-high, code-low) pair; the
        chip writes all four DAC registers and persists the EEPROM at the
        end of the transaction (8 data bytes plus the 1 command byte).

        Args:
            fractions: Iterable of 4 fractions (0.0–1.0), index 0 = A.
            vrefs: Iterable of 4 V_REF values (0/1).
            gains: Iterable of 4 gain values (1/2).
        """
        if len(fractions) != 4 or len(vrefs) != 4 or len(gains) != 4:
            raise ValueError("fractions, vrefs, gains must each have 4 elements")
        out = bytearray(9)
        # Command byte: Sequential Write starting at channel 0
        out[0] = self._CMD_SEQUENTIAL_BASE | 0x00  # DAC1=DAC0=0 (A), UDAC=0
        for i in range(4):
            f = max(0.0, min(1.0, float(fractions[i])))
            code = int(round(f * 4095))
            if code > 4095:
                code = 4095
            v = 1 if vrefs[i] else 0
            g = 1 if gains[i] == 2 else 0
            # Per-channel byte layout (Multi-Write format): [V_REF PD1 PD0 Gx D11-D8]
            out[1 + i * 2] = ((v & 0x01) << 7) | ((g & 0x01) << 4) | ((code >> 8) & 0x0F)
            out[1 + i * 2 + 1] = code & 0xFF
        self._transport.write(bytes(out))

    def set_vref(self, vref_a, vref_b, vref_c, vref_d):
        """Set V_REF for all four channels (volatile register only).

        Args:
            vref_a: VREF for channel A (0 = external/V_DD, 1 = internal 2.048 V).
            vref_b: VREF for channel B.
            vref_c: VREF for channel C.
            vref_d: VREF for channel D.
        """
        byte1 = self._CMD_WRITE_VREF | (
            (self._b1(vref_a) << 3) | (self._b1(vref_b) << 2) |
            (self._b1(vref_c) << 1) | self._b1(vref_d))
        self._transport.write(bytes([byte1]))

    def set_gain(self, gain_a, gain_b, gain_c, gain_d):
        """Set gain for all four channels (volatile register only).

        Args:
            gain_a: Gain for channel A (1 = ×1, 2 = ×2).
            gain_b: Gain for channel B.
            gain_c: Gain for channel C.
            gain_d: Gain for channel D.
        """
        byte1 = self._CMD_WRITE_GAIN | (
            (self._g(gain_a) << 3) | (self._g(gain_b) << 2) |
            (self._g(gain_c) << 1) | self._g(gain_d))
        self._transport.write(bytes([byte1]))

    def set_power_down(self, pd_a, pd_b, pd_c, pd_d):
        """Set power-down mode for all four channels (volatile register only).

        Args:
            pd_a: Power-down mode for A (0–3).
            pd_b: Power-down mode for B (0–3).
            pd_c: Power-down mode for C (0–3).
            pd_d: Power-down mode for D (0–3).
        """
        byte1 = self._CMD_WRITE_POWERDOWN | (
            (self._p2(pd_a) << 4) | (self._p1(pd_a) << 3) |
            (self._p2(pd_b) << 2) | (self._p1(pd_b) << 1))
        byte2 = ((self._p2(pd_c) << 6) | (self._p1(pd_c) << 5) |
                 (self._p2(pd_d) << 4) | (self._p1(pd_d) << 3))
        self._transport.write(bytes([byte1, byte2]))

    def read(self):
        """Read all four channels' DAC input registers and EEPROM contents.

        The chip returns 24 bytes: 4 channels × (3 bytes input register +
        3 bytes EEPROM). Each 3-byte group has the same layout: byte 1 holds
        RDY/BSY + POR + channel ID + I²C address bits; bytes 2–3 hold
        V_REF, PD, gain, and the 12-bit code.

        Returns:
            list: 4 dicts, one per channel, in order A, B, C, D. Each dict
                  contains: code, vref, gain, power_down, eeprom_code,
                  eeprom_vref, eeprom_gain, eeprom_power_down, eeprom_ready.
        """
        buf = self._transport.read(24)
        result = []
        eeprom_ready = bool(buf[0] & 0x80)
        for i in range(4):
            base = i * 3
            inp_vref  = (buf[base + 1] >> 7) & 0x01
            inp_pd    = (buf[base + 1] >> 5) & 0x03
            inp_gain  = (buf[base + 1] >> 4) & 0x01
            inp_code  = ((buf[base + 1] & 0x0F) << 8) | buf[base + 2]
            result.append({
                'code': inp_code,
                'vref': inp_vref,
                'gain': 2 if inp_gain else 1,
                'power_down': inp_pd,
            })
        for i in range(4):
            base = 12 + i * 3
            ee_vref  = (buf[base + 1] >> 7) & 0x01
            ee_pd    = (buf[base + 1] >> 5) & 0x03
            ee_gain  = (buf[base + 1] >> 4) & 0x01
            ee_code  = ((buf[base + 1] & 0x0F) << 8) | buf[base + 2]
            result[i]['eeprom_code'] = ee_code
            result[i]['eeprom_vref'] = ee_vref
            result[i]['eeprom_gain'] = 2 if ee_gain else 1
            result[i]['eeprom_power_down'] = ee_pd
            result[i]['eeprom_ready'] = eeprom_ready
        return result

    def is_eeprom_ready(self):
        """Check whether an EEPROM write is in progress.

        Returns:
            bool: True when no EEPROM write is pending (RDY/BSY = 1).
        """
        buf = self._transport.read(1)
        return bool(buf[0] & 0x80)

    def software_update(self):
        """Send General Call Software Update (0x00, 0x08) to latch all V_OUT."""
        self._transport.write(bytes([self._ADDR_GENERAL_CALL, self._GC_SOFTWARE_UPD]))

    def wake_up(self):
        """Send General Call Wake-Up (0x00, 0x09) to clear all PD bits."""
        self._transport.write(bytes([self._ADDR_GENERAL_CALL, self._GC_WAKE]))

    def reset(self):
        """Send General Call Reset (0x00, 0x06) to reload EEPROM into all DAC registers."""
        self._transport.write(bytes([self._ADDR_GENERAL_CALL, self._GC_RESET]))

    def _single_write(self, channel, code, vref, pd, gain, udac):
        ch = max(0, min(3, channel))
        c = int(max(0, min(4095, code)))
        byte1 = self._CMD_SINGLE_WRITE | ((ch & 0x03) << 1) | (udac & 0x01)
        byte2 = ((vref & 0x01) << 7) | ((pd & 0x03) << 5) | \
                ((gain & 0x01) << 4) | ((c >> 8) & 0x0F)
        byte3 = c & 0xFF
        self._transport.write(bytes([byte1, byte2, byte3]))

    @staticmethod
    def _b1(v):
        return 1 if v else 0

    @staticmethod
    def _g(g):
        return 1 if g == 2 else 0

    @staticmethod
    def _p1(pd):
        return (pd >> 0) & 0x01

    @staticmethod
    def _p2(pd):
        return (pd >> 1) & 0x01
