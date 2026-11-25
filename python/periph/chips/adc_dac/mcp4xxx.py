"""MCP4XXX — MCP453X/454X/455X/456X/463X/464X/465X/466X I²C digital potentiometers (Microchip).

One driver for the whole sixteen-part family of 7-bit (129 steps) and 8-bit (257
steps) digital potentiometers (X1) and rheostats (X2), single or dual network,
volatile (453X / 455X / 463X / 465X) or non-volatile (454X / 456X / 464X / 466X).
The part is selected by name; a small part descriptor (``PARTS``) carries the
channel count, resolution, pot / rheostat pinout, memory type and address range,
everything else is one code path. The same driver file is used on MicroPython,
CircuitPython, and Linux hosts.

The chip has no flat register map: a transaction starts with a command byte
``AD3 AD2 AD1 AD0 C1 C0 D9 D8`` (memory address, operation, data bits 9:8). That
byte is passed as the "register" of the RegisterConnection; a read returns two bytes
(``0000 000D8``, ``D7..D0``).

Args:
    connection: RegisterConnection (I²C or SMBus) pointing at the device. Valid
        addresses per part are in ``PARTS`` / ``default_address()``.
"""

import time

# Part descriptor fields: channels, max_code, potentiometer, nonvolatile, wp_pin,
# address range (lo, hi) and the default address (all address pins low).
_ADDR_X1 = (0x2E, 0x2F)
_ADDR_X2 = (0x58, 0x5B)
_ADDR_46X1 = (0x28, 0x2F)

PARTS = {
    'MCP4531': {'channels': 1, 'max_code': 128, 'pot': True, 'nv': False, 'wp_pin': False, 'addr': _ADDR_X1},
    'MCP4532': {'channels': 1, 'max_code': 128, 'pot': False, 'nv': False, 'wp_pin': False, 'addr': _ADDR_X2},
    'MCP4551': {'channels': 1, 'max_code': 256, 'pot': True, 'nv': False, 'wp_pin': False, 'addr': _ADDR_X1},
    'MCP4552': {'channels': 1, 'max_code': 256, 'pot': False, 'nv': False, 'wp_pin': False, 'addr': _ADDR_X2},
    'MCP4631': {'channels': 2, 'max_code': 128, 'pot': True, 'nv': False, 'wp_pin': False, 'addr': _ADDR_46X1},
    'MCP4632': {'channels': 2, 'max_code': 128, 'pot': False, 'nv': False, 'wp_pin': False, 'addr': _ADDR_X2},
    'MCP4651': {'channels': 2, 'max_code': 256, 'pot': True, 'nv': False, 'wp_pin': False, 'addr': _ADDR_46X1},
    'MCP4652': {'channels': 2, 'max_code': 256, 'pot': False, 'nv': False, 'wp_pin': False, 'addr': _ADDR_X2},
    'MCP4541': {'channels': 1, 'max_code': 128, 'pot': True, 'nv': True, 'wp_pin': False, 'addr': _ADDR_X1},
    'MCP4542': {'channels': 1, 'max_code': 128, 'pot': False, 'nv': True, 'wp_pin': False, 'addr': _ADDR_X2},
    'MCP4561': {'channels': 1, 'max_code': 256, 'pot': True, 'nv': True, 'wp_pin': False, 'addr': _ADDR_X1},
    'MCP4562': {'channels': 1, 'max_code': 256, 'pot': False, 'nv': True, 'wp_pin': False, 'addr': _ADDR_X2},
    'MCP4641': {'channels': 2, 'max_code': 128, 'pot': True, 'nv': True, 'wp_pin': True, 'addr': _ADDR_46X1},
    'MCP4642': {'channels': 2, 'max_code': 128, 'pot': False, 'nv': True, 'wp_pin': False, 'addr': _ADDR_X2},
    'MCP4661': {'channels': 2, 'max_code': 256, 'pot': True, 'nv': True, 'wp_pin': True, 'addr': _ADDR_46X1},
    'MCP4662': {'channels': 2, 'max_code': 256, 'pot': False, 'nv': True, 'wp_pin': False, 'addr': _ADDR_X2},
}

# Memory addresses (command byte bits 7:4).
_ADDR_WIPER0 = 0x00
_ADDR_NVWIPER0 = 0x02
_ADDR_TCON = 0x04
_ADDR_STATUS = 0x05
_ADDR_DATA0 = 0x06
_ADDR_WP = 0x0F

# Operations (command byte bits 3:2).
_OP_WRITE = 0x00
_OP_INC = 0x04
_OP_DEC = 0x08
_OP_READ = 0x0C

_TCON_DEFAULT = 0x1FF
_TCON_GCEN = 0x100
# Per-channel TCON bit positions: (HW, A, W, B).
_TCON_BITS = ((3, 2, 1, 0), (7, 6, 5, 4))

_STATUS_EEWA = 0x08
_STATUS_WL = (0x02, 0x04)
_STATUS_WP = 0x01


def default_address(part):
    """Return the default 7-bit I²C address (all address pins low) of a part.

    Args:
        part: Part name, e.g. ``"MCP4532"`` (case-insensitive).

    Returns:
        int: ``0x2E`` (45X1), ``0x58`` (45X2 / 46X2) or ``0x28`` (46X1).
    """
    return _descriptor(part)['addr'][0]


def _descriptor(part):
    key = str(part).upper()
    if key not in PARTS:
        raise ValueError('MCP4XXX: unknown part ' + str(part))
    return PARTS[key]


class Mcp4xxxMinimal:
    """MCP4XXX family — minimal interface.

    Sets and reads back the volatile wiper position (0–128 or 0–256) or the W–B
    resistance of either network. Never touches the EEPROM.

    Default behaviour:
        - ``part`` MCP4532, RAB = 10 kΩ, RW = 75 Ω
        - wipers stay at their power-up value (mid-scale, or the stored EEPROM value
          on non-volatile parts) until written; all terminals connected

    Args:
        connection: RegisterConnection (I²C) pointing at the device.
        part: Part name ``"MCP4531"`` … ``"MCP4662"`` (case-insensitive); an unknown
            part raises ValueError.
        r_ab: End-to-end resistance RAB in ohms (5 / 10 / 50 / 100 kΩ).
        r_wiper: Wiper resistance RW in ohms.
    """

    def __init__(self, connection, part='MCP4532', r_ab=10000.0, r_wiper=75.0):
        desc = _descriptor(part)
        self._connection = connection
        self._part = str(part).upper()
        self._channels = desc['channels']
        self._max_code = desc['max_code']
        self._pot = desc['pot']
        self._nv = desc['nv']
        self._r_ab = float(r_ab)
        self._r_wiper = float(r_wiper)
        self._code = [self._max_code // 2] * self._channels
        for channel in range(self._channels):
            self._code[channel] = self._read_wiper(channel)
        self._tcon = self._read_register(_ADDR_TCON)
        if self._nv:
            self._read_register(_ADDR_STATUS)

    # -- low-level helpers -------------------------------------------------

    def _check_channel(self, channel):
        if channel < 0 or channel >= self._channels:
            raise ValueError('MCP4XXX: channel out of range for ' + self._part)

    def _read_register(self, address):
        data = self._connection.read_reg((address << 4) | _OP_READ, 2)
        return ((data[0] & 0x01) << 8) | data[1]

    def _write_register(self, address, value):
        self._connection.write_reg((address << 4) | _OP_WRITE | ((value >> 8) & 0x01), bytes([value & 0xFF]))

    def _read_wiper(self, channel):
        value = self._read_register(_ADDR_WIPER0 + channel)
        return min(value, self._max_code)

    def _clamp(self, code):
        return int(max(0, min(self._max_code, code)))

    # -- public API --------------------------------------------------------

    def set_wiper(self, code, channel=0):
        """Set the volatile wiper position of a network.

        Clamps the code to [0, max code]. A network locked by WiperLock ignores the write.

        Args:
            code: Wiper position (0 = W at B, max code 128 / 256 = W at A).
            channel: Network index, 0 or 1 (dual parts only).
        """
        self._check_channel(channel)
        code = self._clamp(code)
        self._write_register(_ADDR_WIPER0 + channel, code)
        self._code[channel] = code

    def get_wiper(self, channel=0):
        """Read the volatile wiper position of a network.

        Args:
            channel: Network index, 0 or 1 (dual parts only).

        Returns:
            int: Wiper position 0 … max code (values above full scale are clamped).
        """
        self._check_channel(channel)
        code = self._read_wiper(channel)
        self._code[channel] = code
        return code

    def set_resistance(self, ohms, channel=0):
        """Set the W–B resistance to the nearest reachable value.

        Args:
            ohms: Target wiper-to-B resistance in ohms (clamped to the reachable range).
            channel: Network index, 0 or 1.
        """
        self._check_channel(channel)
        self.set_wiper(int(round((ohms - self._r_wiper) / self._r_ab * self._max_code)), channel)

    def get_resistance(self, channel=0):
        """Read the W–B resistance for the current wiper position.

        Args:
            channel: Network index, 0 or 1.

        Returns:
            float: Resistance in ohms, N / max_code × RAB + RW.
        """
        return self.get_wiper(channel) / self._max_code * self._r_ab + self._r_wiper


class Mcp4xxxFull(Mcp4xxxMinimal):
    """MCP4XXX family — full interface.

    Extends Mcp4xxxMinimal with ratio helpers, increment / decrement, shutdown and
    terminal control, General Call, reset and raw register access. The operations marked
    **NV** exist only on the non-volatile parts (454X / 456X / 464X / 466X) and raise
    ``ValueError`` on the volatile parts.

    Args:
        connection: RegisterConnection (I²C) pointing at the device.
        part: Part name ``"MCP4531"`` … ``"MCP4662"`` (case-insensitive).
        r_ab: End-to-end resistance RAB in ohms.
        r_wiper: Wiper resistance RW in ohms.
        hvc: Optional OutputPin switching an external circuit that drives HVC/A0 to
            8.5–12.5 V; needed only by ``set_wiper_lock`` / ``set_write_protect``.
        hvc_settle_ms: Delay in milliseconds between asserting ``hvc`` and the first
            SCL edge (the datasheet gives no value; default 1 ms).
    """

    def __init__(self, connection, part='MCP4532', r_ab=10000.0, r_wiper=75.0, hvc=None, hvc_settle_ms=1):
        super().__init__(connection, part, r_ab, r_wiper)
        self._hvc = hvc if self._nv else None
        self._hvc_settle_ms = hvc_settle_ms

    def _require_nv(self):
        if not self._nv:
            raise ValueError('MCP4XXX: ' + self._part + ' has no EEPROM')

    def _require_pot(self):
        if not self._pot:
            raise ValueError('MCP4XXX: ' + self._part + ' is a rheostat (terminal A not available)')

    # -- properties of the part --------------------------------------------

    def channels(self):
        """Return the number of resistor networks (1 or 2)."""
        return self._channels

    def steps(self):
        """Return the number of wiper positions (129 or 257)."""
        return self._max_code + 1

    def is_potentiometer(self):
        """Return True for the X1 parts (terminal A on a pin)."""
        return self._pot

    def is_nonvolatile(self):
        """Return True for the 454X / 456X / 464X / 466X parts."""
        return self._nv

    # -- ratio / resistance helpers ----------------------------------------

    def set_ratio(self, ratio, channel=0):
        """Set the wiper to a fraction of the full scale.

        Args:
            ratio: 0.0–1.0 (fraction of RAB between W and B).
            channel: Network index.
        """
        ratio = max(0.0, min(1.0, ratio))
        self.set_wiper(int(round(ratio * self._max_code)), channel)

    def get_ratio(self, channel=0):
        """Read the wiper position as a ratio.

        Returns:
            float: code / max_code.
        """
        return self.get_wiper(channel) / self._max_code

    def get_resistance_wa(self, channel=0):
        """Read the W–A resistance (potentiometer parts only).

        Returns:
            float: (max_code − N) / max_code × RAB + RW in ohms.
        """
        self._require_pot()
        return (self._max_code - self.get_wiper(channel)) / self._max_code * self._r_ab + self._r_wiper

    def set_resistance_wa(self, ohms, channel=0):
        """Set the W–A resistance to the nearest reachable value (potentiometer parts only).

        Args:
            ohms: Target wiper-to-A resistance in ohms.
            channel: Network index.
        """
        self._require_pot()
        self._check_channel(channel)
        code = self._max_code - int(round((ohms - self._r_wiper) / self._r_ab * self._max_code))
        self.set_wiper(code, channel)

    def get_resistance_step(self):
        """Return the resistance of one step, RAB / max_code, in ohms."""
        return self._r_ab / self._max_code

    def set_both(self, code0, code1):
        """Write both wipers of a dual part (two transactions).

        Args:
            code0: Network 0 wiper position.
            code1: Network 1 wiper position.
        """
        if self._channels < 2:
            raise ValueError('MCP4XXX: ' + self._part + ' has a single network')
        self.set_wiper(code0, 0)
        self.set_wiper(code1, 1)

    def _step(self, op, steps, channel):
        self._check_channel(channel)
        steps = int(steps)
        if steps < 1:
            return self.get_wiper(channel)
        command = ((_ADDR_WIPER0 + channel) << 4) | op
        # Continuous mode: one command byte per step, no data bytes, one transaction.
        self._connection.write_reg(command, bytes([command] * (steps - 1)))
        return self.get_wiper(channel)

    def increment(self, steps=1, channel=0):
        """Increment the wiper by ``steps`` in one continuous transaction.

        Args:
            steps: Number of increment commands (saturates at the max code).
            channel: Network index.

        Returns:
            int: New wiper code (read back).
        """
        return self._step(_OP_INC, steps, channel)

    def decrement(self, steps=1, channel=0):
        """Decrement the wiper by ``steps`` in one continuous transaction.

        Args:
            steps: Number of decrement commands (saturates at 0).
            channel: Network index.

        Returns:
            int: New wiper code (read back).
        """
        return self._step(_OP_DEC, steps, channel)

    # -- TCON: shutdown / terminals / General Call -------------------------

    def _write_tcon(self, value):
        self._write_register(_ADDR_TCON, value)
        self._tcon = value

    def _modify_tcon(self, bit, on):
        value = self._read_register(_ADDR_TCON)
        value = (value | (1 << bit)) if on else (value & ~(1 << bit))
        self._write_tcon(value & 0x1FF)

    def shutdown(self, channel=0):
        """Shut a network down (TCON.RxHW = 0): A disconnected, W connected to B; wiper kept.

        Args:
            channel: Network index.
        """
        self._check_channel(channel)
        self._modify_tcon(_TCON_BITS[channel][0], False)

    def resume(self, channel=0):
        """Leave shutdown (TCON.RxHW = 1).

        Args:
            channel: Network index.
        """
        self._check_channel(channel)
        self._modify_tcon(_TCON_BITS[channel][0], True)

    def is_shutdown(self, channel=0):
        """Return True if the network is in shutdown (read from TCON).

        Args:
            channel: Network index.
        """
        self._check_channel(channel)
        self._tcon = self._read_register(_ADDR_TCON)
        return (self._tcon >> _TCON_BITS[channel][0]) & 1 == 0

    def set_terminals(self, a=True, w=True, b=True, channel=0):
        """Connect or disconnect the A, W and B terminals of a network (TCON.RxA/RxW/RxB).

        Args:
            a: True to connect terminal A.
            w: True to connect the wiper terminal.
            b: True to connect terminal B.
            channel: Network index.
        """
        self._check_channel(channel)
        _, bit_a, bit_w, bit_b = _TCON_BITS[channel]
        value = self._read_register(_ADDR_TCON)
        for bit, on in ((bit_a, a), (bit_w, w), (bit_b, b)):
            value = (value | (1 << bit)) if on else (value & ~(1 << bit))
        self._write_tcon(value & 0x1FF)

    def get_terminals(self, channel=0):
        """Return ``(a, w, b)`` connection state of a network, read from TCON.

        Args:
            channel: Network index.
        """
        self._check_channel(channel)
        self._tcon = self._read_register(_ADDR_TCON)
        _, bit_a, bit_w, bit_b = _TCON_BITS[channel]
        return ((self._tcon >> bit_a) & 1 == 1, (self._tcon >> bit_w) & 1 == 1, (self._tcon >> bit_b) & 1 == 1)

    def get_tcon(self):
        """Return the raw 9-bit TCON register."""
        self._tcon = self._read_register(_ADDR_TCON)
        return self._tcon

    def set_general_call(self, enabled):
        """Enable or disable the General Call address (TCON.GCEN).

        Args:
            enabled: True to answer General Call (address 0x00) commands.
        """
        self._modify_tcon(8, bool(enabled))

    def reset(self):
        """Emulate a power-on reset.

        Writes every volatile wiper with its power-up value (mid-scale on volatile parts,
        the EEPROM wiper on non-volatile parts) and TCON 0x1FF. Networks locked by
        WiperLock are skipped.
        """
        locked = self.read_status()['wiper_lock'] if self._nv else [False] * self._channels
        for channel in range(self._channels):
            if locked[channel]:
                continue
            code = self.get_nv_wiper(channel) if self._nv else self._max_code // 2
            self.set_wiper(code, channel)
        if not any(locked):
            self._write_tcon(_TCON_DEFAULT)

    def read_register(self, address):
        """Raw 9-bit read of a memory address the part has.

        Args:
            address: Memory address 0–15.

        Returns:
            int: 9-bit value.
        """
        self._check_address(address, writable=False)
        return self._read_register(address)

    def write_register(self, address, value):
        """Raw write of a writable memory address the part has.

        Args:
            address: Memory address (volatile wipers, TCON; NV wipers and DATA on NV parts).
            value: Value to write (0–0x3FF for wipers, 0–0x1FF otherwise).
        """
        self._check_address(address, writable=True)
        limit = 0x3FF if address in (0, 1) else 0x1FF
        self._write_register(address, max(0, min(limit, int(value))))

    def _check_address(self, address, writable):
        if address < 0 or address > 0x0F:
            raise ValueError('MCP4XXX: address out of range')
        ok = address in (0x04,) or address < self._channels
        if self._nv:
            ok = ok or address in (0x02, 0x03) and address - 2 < self._channels or address >= _ADDR_DATA0
            if address == _ADDR_STATUS:
                ok = not writable
        if not ok:
            raise ValueError('MCP4XXX: address 0x%02X not available on %s' % (address, self._part))

    def set_rab(self, r_ab, r_wiper=None):
        """Change the resistance model used by the conversions (no I²C traffic).

        Args:
            r_ab: End-to-end resistance RAB in ohms.
            r_wiper: Wiper resistance RW in ohms; unchanged if None.
        """
        self._r_ab = float(r_ab)
        if r_wiper is not None:
            self._r_wiper = float(r_wiper)

    # -- non-volatile parts ------------------------------------------------

    def read_status(self):
        """**NV.** Read STATUS.

        Returns:
            dict: ``{'eeprom_busy': bool, 'wiper_lock': [wl0(, wl1)], 'write_protect': bool}``.
        """
        self._require_nv()
        value = self._read_register(_ADDR_STATUS)
        return {
            'eeprom_busy': bool(value & _STATUS_EEWA),
            'wiper_lock': [bool(value & _STATUS_WL[c]) for c in range(self._channels)],
            'write_protect': bool(value & _STATUS_WP),
        }

    def wait_ready(self, timeout_ms=20):
        """**NV.** Poll STATUS.EEWA until the EEPROM write cycle has finished.

        Args:
            timeout_ms: Maximum wait in milliseconds (tWC is 5 ms typical, 10 ms maximum).

        Raises:
            TimeoutError: If the cycle does not finish in time.
        """
        self._require_nv()
        for _ in range(int(timeout_ms) + 1):
            if not self._read_register(_ADDR_STATUS) & _STATUS_EEWA:
                return
            time.sleep(0.001)
        raise TimeoutError('MCP4XXX: EEPROM write did not finish')

    def get_nv_wiper(self, channel=0):
        """**NV.** Read the EEPROM wiper (power-up value).

        Args:
            channel: Network index.

        Returns:
            int: Stored code, clamped to the max code.
        """
        self._require_nv()
        self._check_channel(channel)
        return min(self._read_register(_ADDR_NVWIPER0 + channel), self._max_code)

    def set_nv_wiper(self, code, channel=0):
        """**NV.** Write the EEPROM wiper; the volatile wiper is not changed.

        Args:
            code: Power-up code (clamped to the max code).
            channel: Network index.
        """
        self._require_nv()
        self._check_channel(channel)
        self._write_register(_ADDR_NVWIPER0 + channel, self._clamp(code))
        self.wait_ready()

    def store_wiper(self, channel=0):
        """**NV.** Write the current volatile wiper to the EEPROM wiper.

        Args:
            channel: Network index.

        Returns:
            int: The stored code.
        """
        self._require_nv()
        code = self.get_wiper(channel)
        self.set_nv_wiper(code, channel)
        return code

    def read_data(self, index):
        """**NV.** Read general-purpose EEPROM register ``index`` (0–9).

        Returns:
            int: 9-bit value.
        """
        self._require_nv()
        if index < 0 or index > 9:
            raise ValueError('MCP4XXX: data index must be 0-9')
        return self._read_register(_ADDR_DATA0 + index)

    def write_data(self, index, value):
        """**NV.** Write general-purpose EEPROM register ``index`` (0–9); uses one write cycle.

        Args:
            index: Register index 0–9.
            value: 9-bit value 0–0x1FF.
        """
        self._require_nv()
        if index < 0 or index > 9:
            raise ValueError('MCP4XXX: data index must be 0-9')
        self._write_register(_ADDR_DATA0 + index, max(0, min(0x1FF, int(value))))
        self.wait_ready()

    def is_wiper_locked(self, channel=0):
        """**NV.** Return STATUS.WLx for a network."""
        self._check_channel(channel)
        return self.read_status()['wiper_lock'][channel]

    def is_write_protected(self):
        """**NV.** Return STATUS.WP (hardware WP pin OR the nonvolatile bit)."""
        return self.read_status()['write_protect']

    def _hv_command(self, address, decrement):
        if self._hvc is None:
            raise ValueError('MCP4XXX: a high-voltage command needs the hvc pin')
        command = (address << 4) | (_OP_DEC if decrement else _OP_INC)
        self._hvc.set(True)
        try:
            time.sleep(self._hvc_settle_ms / 1000.0)
            self._connection.write_reg(command, b'')
        finally:
            self._hvc.set(False)
        self.wait_ready()

    def set_wiper_lock(self, locked, channel=0):
        """**NV, needs ``hvc``.** Enable or disable WiperLock of a network (high-voltage command).

        Args:
            locked: True to lock (decrement command), False to unlock (increment).
            channel: Network index.
        """
        self._require_nv()
        self._check_channel(channel)
        self._hv_command(_ADDR_NVWIPER0 + channel, bool(locked))

    def set_write_protect(self, enabled):
        """**NV, needs ``hvc``.** Set the nonvolatile EEPROM write-protect bit (high-voltage command).

        Args:
            enabled: True to enable write protect, False to disable.
        """
        self._require_nv()
        self._hv_command(_ADDR_WP, bool(enabled))


# ---------------------------------------------------------------------------
# Named aliases: one thin Minimal / Full subclass per family member. They only
# preset the ``part`` argument; behaviour is identical to Mcp4xxxMinimal / Mcp4xxxFull.
# ---------------------------------------------------------------------------


class Mcp4531Minimal(Mcp4xxxMinimal):
    """MCP4531 minimal interface: ``Mcp4xxxMinimal`` with the part preset.

    Args:
        connection: RegisterConnection (I²C) pointing at the device.
        r_ab: End-to-end resistance RAB in ohms.
        r_wiper: Wiper resistance RW in ohms.
    """

    def __init__(self, connection, r_ab=10000.0, r_wiper=75.0):
        super().__init__(connection, 'MCP4531', r_ab, r_wiper)


class Mcp4531Full(Mcp4xxxFull):
    """MCP4531 full interface: ``Mcp4xxxFull`` with the part preset.

    Args:
        connection: RegisterConnection (I²C) pointing at the device.
        r_ab: End-to-end resistance RAB in ohms.
        r_wiper: Wiper resistance RW in ohms.
        hvc: Optional OutputPin for the high-voltage command circuit (non-volatile parts).
        hvc_settle_ms: Delay between asserting ``hvc`` and the first SCL edge.
    """

    def __init__(self, connection, r_ab=10000.0, r_wiper=75.0, hvc=None, hvc_settle_ms=1):
        super().__init__(connection, 'MCP4531', r_ab, r_wiper, hvc, hvc_settle_ms)


class Mcp4532Minimal(Mcp4xxxMinimal):
    """MCP4532 minimal interface: ``Mcp4xxxMinimal`` with the part preset.

    Args:
        connection: RegisterConnection (I²C) pointing at the device.
        r_ab: End-to-end resistance RAB in ohms.
        r_wiper: Wiper resistance RW in ohms.
    """

    def __init__(self, connection, r_ab=10000.0, r_wiper=75.0):
        super().__init__(connection, 'MCP4532', r_ab, r_wiper)


class Mcp4532Full(Mcp4xxxFull):
    """MCP4532 full interface: ``Mcp4xxxFull`` with the part preset.

    Args:
        connection: RegisterConnection (I²C) pointing at the device.
        r_ab: End-to-end resistance RAB in ohms.
        r_wiper: Wiper resistance RW in ohms.
        hvc: Optional OutputPin for the high-voltage command circuit (non-volatile parts).
        hvc_settle_ms: Delay between asserting ``hvc`` and the first SCL edge.
    """

    def __init__(self, connection, r_ab=10000.0, r_wiper=75.0, hvc=None, hvc_settle_ms=1):
        super().__init__(connection, 'MCP4532', r_ab, r_wiper, hvc, hvc_settle_ms)


class Mcp4551Minimal(Mcp4xxxMinimal):
    """MCP4551 minimal interface: ``Mcp4xxxMinimal`` with the part preset.

    Args:
        connection: RegisterConnection (I²C) pointing at the device.
        r_ab: End-to-end resistance RAB in ohms.
        r_wiper: Wiper resistance RW in ohms.
    """

    def __init__(self, connection, r_ab=10000.0, r_wiper=75.0):
        super().__init__(connection, 'MCP4551', r_ab, r_wiper)


class Mcp4551Full(Mcp4xxxFull):
    """MCP4551 full interface: ``Mcp4xxxFull`` with the part preset.

    Args:
        connection: RegisterConnection (I²C) pointing at the device.
        r_ab: End-to-end resistance RAB in ohms.
        r_wiper: Wiper resistance RW in ohms.
        hvc: Optional OutputPin for the high-voltage command circuit (non-volatile parts).
        hvc_settle_ms: Delay between asserting ``hvc`` and the first SCL edge.
    """

    def __init__(self, connection, r_ab=10000.0, r_wiper=75.0, hvc=None, hvc_settle_ms=1):
        super().__init__(connection, 'MCP4551', r_ab, r_wiper, hvc, hvc_settle_ms)


class Mcp4552Minimal(Mcp4xxxMinimal):
    """MCP4552 minimal interface: ``Mcp4xxxMinimal`` with the part preset.

    Args:
        connection: RegisterConnection (I²C) pointing at the device.
        r_ab: End-to-end resistance RAB in ohms.
        r_wiper: Wiper resistance RW in ohms.
    """

    def __init__(self, connection, r_ab=10000.0, r_wiper=75.0):
        super().__init__(connection, 'MCP4552', r_ab, r_wiper)


class Mcp4552Full(Mcp4xxxFull):
    """MCP4552 full interface: ``Mcp4xxxFull`` with the part preset.

    Args:
        connection: RegisterConnection (I²C) pointing at the device.
        r_ab: End-to-end resistance RAB in ohms.
        r_wiper: Wiper resistance RW in ohms.
        hvc: Optional OutputPin for the high-voltage command circuit (non-volatile parts).
        hvc_settle_ms: Delay between asserting ``hvc`` and the first SCL edge.
    """

    def __init__(self, connection, r_ab=10000.0, r_wiper=75.0, hvc=None, hvc_settle_ms=1):
        super().__init__(connection, 'MCP4552', r_ab, r_wiper, hvc, hvc_settle_ms)


class Mcp4631Minimal(Mcp4xxxMinimal):
    """MCP4631 minimal interface: ``Mcp4xxxMinimal`` with the part preset.

    Args:
        connection: RegisterConnection (I²C) pointing at the device.
        r_ab: End-to-end resistance RAB in ohms.
        r_wiper: Wiper resistance RW in ohms.
    """

    def __init__(self, connection, r_ab=10000.0, r_wiper=75.0):
        super().__init__(connection, 'MCP4631', r_ab, r_wiper)


class Mcp4631Full(Mcp4xxxFull):
    """MCP4631 full interface: ``Mcp4xxxFull`` with the part preset.

    Args:
        connection: RegisterConnection (I²C) pointing at the device.
        r_ab: End-to-end resistance RAB in ohms.
        r_wiper: Wiper resistance RW in ohms.
        hvc: Optional OutputPin for the high-voltage command circuit (non-volatile parts).
        hvc_settle_ms: Delay between asserting ``hvc`` and the first SCL edge.
    """

    def __init__(self, connection, r_ab=10000.0, r_wiper=75.0, hvc=None, hvc_settle_ms=1):
        super().__init__(connection, 'MCP4631', r_ab, r_wiper, hvc, hvc_settle_ms)


class Mcp4632Minimal(Mcp4xxxMinimal):
    """MCP4632 minimal interface: ``Mcp4xxxMinimal`` with the part preset.

    Args:
        connection: RegisterConnection (I²C) pointing at the device.
        r_ab: End-to-end resistance RAB in ohms.
        r_wiper: Wiper resistance RW in ohms.
    """

    def __init__(self, connection, r_ab=10000.0, r_wiper=75.0):
        super().__init__(connection, 'MCP4632', r_ab, r_wiper)


class Mcp4632Full(Mcp4xxxFull):
    """MCP4632 full interface: ``Mcp4xxxFull`` with the part preset.

    Args:
        connection: RegisterConnection (I²C) pointing at the device.
        r_ab: End-to-end resistance RAB in ohms.
        r_wiper: Wiper resistance RW in ohms.
        hvc: Optional OutputPin for the high-voltage command circuit (non-volatile parts).
        hvc_settle_ms: Delay between asserting ``hvc`` and the first SCL edge.
    """

    def __init__(self, connection, r_ab=10000.0, r_wiper=75.0, hvc=None, hvc_settle_ms=1):
        super().__init__(connection, 'MCP4632', r_ab, r_wiper, hvc, hvc_settle_ms)


class Mcp4651Minimal(Mcp4xxxMinimal):
    """MCP4651 minimal interface: ``Mcp4xxxMinimal`` with the part preset.

    Args:
        connection: RegisterConnection (I²C) pointing at the device.
        r_ab: End-to-end resistance RAB in ohms.
        r_wiper: Wiper resistance RW in ohms.
    """

    def __init__(self, connection, r_ab=10000.0, r_wiper=75.0):
        super().__init__(connection, 'MCP4651', r_ab, r_wiper)


class Mcp4651Full(Mcp4xxxFull):
    """MCP4651 full interface: ``Mcp4xxxFull`` with the part preset.

    Args:
        connection: RegisterConnection (I²C) pointing at the device.
        r_ab: End-to-end resistance RAB in ohms.
        r_wiper: Wiper resistance RW in ohms.
        hvc: Optional OutputPin for the high-voltage command circuit (non-volatile parts).
        hvc_settle_ms: Delay between asserting ``hvc`` and the first SCL edge.
    """

    def __init__(self, connection, r_ab=10000.0, r_wiper=75.0, hvc=None, hvc_settle_ms=1):
        super().__init__(connection, 'MCP4651', r_ab, r_wiper, hvc, hvc_settle_ms)


class Mcp4652Minimal(Mcp4xxxMinimal):
    """MCP4652 minimal interface: ``Mcp4xxxMinimal`` with the part preset.

    Args:
        connection: RegisterConnection (I²C) pointing at the device.
        r_ab: End-to-end resistance RAB in ohms.
        r_wiper: Wiper resistance RW in ohms.
    """

    def __init__(self, connection, r_ab=10000.0, r_wiper=75.0):
        super().__init__(connection, 'MCP4652', r_ab, r_wiper)


class Mcp4652Full(Mcp4xxxFull):
    """MCP4652 full interface: ``Mcp4xxxFull`` with the part preset.

    Args:
        connection: RegisterConnection (I²C) pointing at the device.
        r_ab: End-to-end resistance RAB in ohms.
        r_wiper: Wiper resistance RW in ohms.
        hvc: Optional OutputPin for the high-voltage command circuit (non-volatile parts).
        hvc_settle_ms: Delay between asserting ``hvc`` and the first SCL edge.
    """

    def __init__(self, connection, r_ab=10000.0, r_wiper=75.0, hvc=None, hvc_settle_ms=1):
        super().__init__(connection, 'MCP4652', r_ab, r_wiper, hvc, hvc_settle_ms)


class Mcp4541Minimal(Mcp4xxxMinimal):
    """MCP4541 minimal interface: ``Mcp4xxxMinimal`` with the part preset.

    Args:
        connection: RegisterConnection (I²C) pointing at the device.
        r_ab: End-to-end resistance RAB in ohms.
        r_wiper: Wiper resistance RW in ohms.
    """

    def __init__(self, connection, r_ab=10000.0, r_wiper=75.0):
        super().__init__(connection, 'MCP4541', r_ab, r_wiper)


class Mcp4541Full(Mcp4xxxFull):
    """MCP4541 full interface: ``Mcp4xxxFull`` with the part preset.

    Args:
        connection: RegisterConnection (I²C) pointing at the device.
        r_ab: End-to-end resistance RAB in ohms.
        r_wiper: Wiper resistance RW in ohms.
        hvc: Optional OutputPin for the high-voltage command circuit (non-volatile parts).
        hvc_settle_ms: Delay between asserting ``hvc`` and the first SCL edge.
    """

    def __init__(self, connection, r_ab=10000.0, r_wiper=75.0, hvc=None, hvc_settle_ms=1):
        super().__init__(connection, 'MCP4541', r_ab, r_wiper, hvc, hvc_settle_ms)


class Mcp4542Minimal(Mcp4xxxMinimal):
    """MCP4542 minimal interface: ``Mcp4xxxMinimal`` with the part preset.

    Args:
        connection: RegisterConnection (I²C) pointing at the device.
        r_ab: End-to-end resistance RAB in ohms.
        r_wiper: Wiper resistance RW in ohms.
    """

    def __init__(self, connection, r_ab=10000.0, r_wiper=75.0):
        super().__init__(connection, 'MCP4542', r_ab, r_wiper)


class Mcp4542Full(Mcp4xxxFull):
    """MCP4542 full interface: ``Mcp4xxxFull`` with the part preset.

    Args:
        connection: RegisterConnection (I²C) pointing at the device.
        r_ab: End-to-end resistance RAB in ohms.
        r_wiper: Wiper resistance RW in ohms.
        hvc: Optional OutputPin for the high-voltage command circuit (non-volatile parts).
        hvc_settle_ms: Delay between asserting ``hvc`` and the first SCL edge.
    """

    def __init__(self, connection, r_ab=10000.0, r_wiper=75.0, hvc=None, hvc_settle_ms=1):
        super().__init__(connection, 'MCP4542', r_ab, r_wiper, hvc, hvc_settle_ms)


class Mcp4561Minimal(Mcp4xxxMinimal):
    """MCP4561 minimal interface: ``Mcp4xxxMinimal`` with the part preset.

    Args:
        connection: RegisterConnection (I²C) pointing at the device.
        r_ab: End-to-end resistance RAB in ohms.
        r_wiper: Wiper resistance RW in ohms.
    """

    def __init__(self, connection, r_ab=10000.0, r_wiper=75.0):
        super().__init__(connection, 'MCP4561', r_ab, r_wiper)


class Mcp4561Full(Mcp4xxxFull):
    """MCP4561 full interface: ``Mcp4xxxFull`` with the part preset.

    Args:
        connection: RegisterConnection (I²C) pointing at the device.
        r_ab: End-to-end resistance RAB in ohms.
        r_wiper: Wiper resistance RW in ohms.
        hvc: Optional OutputPin for the high-voltage command circuit (non-volatile parts).
        hvc_settle_ms: Delay between asserting ``hvc`` and the first SCL edge.
    """

    def __init__(self, connection, r_ab=10000.0, r_wiper=75.0, hvc=None, hvc_settle_ms=1):
        super().__init__(connection, 'MCP4561', r_ab, r_wiper, hvc, hvc_settle_ms)


class Mcp4562Minimal(Mcp4xxxMinimal):
    """MCP4562 minimal interface: ``Mcp4xxxMinimal`` with the part preset.

    Args:
        connection: RegisterConnection (I²C) pointing at the device.
        r_ab: End-to-end resistance RAB in ohms.
        r_wiper: Wiper resistance RW in ohms.
    """

    def __init__(self, connection, r_ab=10000.0, r_wiper=75.0):
        super().__init__(connection, 'MCP4562', r_ab, r_wiper)


class Mcp4562Full(Mcp4xxxFull):
    """MCP4562 full interface: ``Mcp4xxxFull`` with the part preset.

    Args:
        connection: RegisterConnection (I²C) pointing at the device.
        r_ab: End-to-end resistance RAB in ohms.
        r_wiper: Wiper resistance RW in ohms.
        hvc: Optional OutputPin for the high-voltage command circuit (non-volatile parts).
        hvc_settle_ms: Delay between asserting ``hvc`` and the first SCL edge.
    """

    def __init__(self, connection, r_ab=10000.0, r_wiper=75.0, hvc=None, hvc_settle_ms=1):
        super().__init__(connection, 'MCP4562', r_ab, r_wiper, hvc, hvc_settle_ms)


class Mcp4641Minimal(Mcp4xxxMinimal):
    """MCP4641 minimal interface: ``Mcp4xxxMinimal`` with the part preset.

    Args:
        connection: RegisterConnection (I²C) pointing at the device.
        r_ab: End-to-end resistance RAB in ohms.
        r_wiper: Wiper resistance RW in ohms.
    """

    def __init__(self, connection, r_ab=10000.0, r_wiper=75.0):
        super().__init__(connection, 'MCP4641', r_ab, r_wiper)


class Mcp4641Full(Mcp4xxxFull):
    """MCP4641 full interface: ``Mcp4xxxFull`` with the part preset.

    Args:
        connection: RegisterConnection (I²C) pointing at the device.
        r_ab: End-to-end resistance RAB in ohms.
        r_wiper: Wiper resistance RW in ohms.
        hvc: Optional OutputPin for the high-voltage command circuit (non-volatile parts).
        hvc_settle_ms: Delay between asserting ``hvc`` and the first SCL edge.
    """

    def __init__(self, connection, r_ab=10000.0, r_wiper=75.0, hvc=None, hvc_settle_ms=1):
        super().__init__(connection, 'MCP4641', r_ab, r_wiper, hvc, hvc_settle_ms)


class Mcp4642Minimal(Mcp4xxxMinimal):
    """MCP4642 minimal interface: ``Mcp4xxxMinimal`` with the part preset.

    Args:
        connection: RegisterConnection (I²C) pointing at the device.
        r_ab: End-to-end resistance RAB in ohms.
        r_wiper: Wiper resistance RW in ohms.
    """

    def __init__(self, connection, r_ab=10000.0, r_wiper=75.0):
        super().__init__(connection, 'MCP4642', r_ab, r_wiper)


class Mcp4642Full(Mcp4xxxFull):
    """MCP4642 full interface: ``Mcp4xxxFull`` with the part preset.

    Args:
        connection: RegisterConnection (I²C) pointing at the device.
        r_ab: End-to-end resistance RAB in ohms.
        r_wiper: Wiper resistance RW in ohms.
        hvc: Optional OutputPin for the high-voltage command circuit (non-volatile parts).
        hvc_settle_ms: Delay between asserting ``hvc`` and the first SCL edge.
    """

    def __init__(self, connection, r_ab=10000.0, r_wiper=75.0, hvc=None, hvc_settle_ms=1):
        super().__init__(connection, 'MCP4642', r_ab, r_wiper, hvc, hvc_settle_ms)


class Mcp4661Minimal(Mcp4xxxMinimal):
    """MCP4661 minimal interface: ``Mcp4xxxMinimal`` with the part preset.

    Args:
        connection: RegisterConnection (I²C) pointing at the device.
        r_ab: End-to-end resistance RAB in ohms.
        r_wiper: Wiper resistance RW in ohms.
    """

    def __init__(self, connection, r_ab=10000.0, r_wiper=75.0):
        super().__init__(connection, 'MCP4661', r_ab, r_wiper)


class Mcp4661Full(Mcp4xxxFull):
    """MCP4661 full interface: ``Mcp4xxxFull`` with the part preset.

    Args:
        connection: RegisterConnection (I²C) pointing at the device.
        r_ab: End-to-end resistance RAB in ohms.
        r_wiper: Wiper resistance RW in ohms.
        hvc: Optional OutputPin for the high-voltage command circuit (non-volatile parts).
        hvc_settle_ms: Delay between asserting ``hvc`` and the first SCL edge.
    """

    def __init__(self, connection, r_ab=10000.0, r_wiper=75.0, hvc=None, hvc_settle_ms=1):
        super().__init__(connection, 'MCP4661', r_ab, r_wiper, hvc, hvc_settle_ms)


class Mcp4662Minimal(Mcp4xxxMinimal):
    """MCP4662 minimal interface: ``Mcp4xxxMinimal`` with the part preset.

    Args:
        connection: RegisterConnection (I²C) pointing at the device.
        r_ab: End-to-end resistance RAB in ohms.
        r_wiper: Wiper resistance RW in ohms.
    """

    def __init__(self, connection, r_ab=10000.0, r_wiper=75.0):
        super().__init__(connection, 'MCP4662', r_ab, r_wiper)


class Mcp4662Full(Mcp4xxxFull):
    """MCP4662 full interface: ``Mcp4xxxFull`` with the part preset.

    Args:
        connection: RegisterConnection (I²C) pointing at the device.
        r_ab: End-to-end resistance RAB in ohms.
        r_wiper: Wiper resistance RW in ohms.
        hvc: Optional OutputPin for the high-voltage command circuit (non-volatile parts).
        hvc_settle_ms: Delay between asserting ``hvc`` and the first SCL edge.
    """

    def __init__(self, connection, r_ab=10000.0, r_wiper=75.0, hvc=None, hvc_settle_ms=1):
        super().__init__(connection, 'MCP4662', r_ab, r_wiper, hvc, hvc_settle_ms)
