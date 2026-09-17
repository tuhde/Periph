"""MCP2515 stand-alone CAN 2.0B controller driver (SPI).

Supports standard (11-bit) and extended (29-bit) identifiers, data frames
of 0–8 bytes, and CAN bus speeds up to 1 Mbit/s. Three TX buffers, two RX
buffers, six acceptance filters, two acceptance masks.

The driver is structured around a `_MCP2515Base` class that owns the
register-level logic and the SPI instruction helpers. `MCP2515Minimal`
adds the primary send/recv API; `MCP2515Full` extends Minimal with mode
control, error counters, acceptance filters/masks, abort and one-shot
support, and explicit per-buffer TX selection.

Default configuration baked into Minimal:
    - Standard 11-bit or extended 29-bit ID accepted (set per-call)
    - All three TX buffers enabled; TXB0 used by default with priority 3
    - All acceptance filters and masks pass every ID (RXM[1:0]=11 in
      RXB0CTRL and RXB1CTRL; RXM0SIDH..RXM1EID0 = 0x00)
    - BUKT=1 in RXB0CTRL (RXB0 → RXB1 overflow rollover)
    - CANINTE=0x00 (polled operation; INT pin not used)
    - OSM=0 (retransmit on error or loss of arbitration)
    - Loopback mode not enabled — Normal mode at init
    - Bit timing: 125 kbit/s with 8 MHz oscillator (CNF1=0x01, CNF2=0xBA,
      CNF3=0x03); overridable via `init()` arguments.

All SPI transactions follow the chip's instruction set:
    RESET              0xC0
    READ               0x03 + addr + n bytes out
    READ RX BUFFER     0x90|n + bytes out
    WRITE              0x02 + addr + n bytes in
    LOAD TX BUFFER     0x40|n + bytes in
    RTS                0x80|mask
    READ STATUS        0xA0 + 1 byte out
    RX STATUS          0xB0 + 1 byte out
    BIT MODIFY         0x05 + addr + mask + data
"""


class CanFrame:
    """A CAN 2.0B data or remote frame.

    Attributes:
        id: 11-bit (standard) or 29-bit (extended) CAN identifier.
        data: Payload bytes, 0–8 bytes.
        extended: True if this is a 29-bit extended-ID frame.
        rtr: True if this is a remote transmission request frame.
    """

    __slots__ = ("id", "data", "extended", "rtr")

    def __init__(self, id, data=b"", extended=False, rtr=False):
        if extended and (id < 0 or id > 0x1FFFFFFF):
            raise ValueError("extended id 0x%X out of range [0, 0x1FFFFFFF]" % id)
        if not extended and (id < 0 or id > 0x7FF):
            raise ValueError("standard id 0x%X out of range [0, 0x7FF]" % id)
        if len(data) > 8:
            raise ValueError("CAN data length %d exceeds 8 bytes" % len(data))
        self.id = id
        self.data = bytes(data)
        self.extended = bool(extended)
        self.rtr = bool(rtr)

    def __repr__(self):
        fmt = "0x%08X" if self.extended else "0x%03X"
        kind = "EXT" if self.extended else "STD"
        rtr = "+RTR" if self.rtr else ""
        return "CanFrame(%s=%s%s, dlc=%d, data=%s)" % (
            kind, fmt % self.id, rtr, len(self.data), self.data.hex(),
        )


class _MCP2515Base:
    """Base class for the MCP2515 CAN controller — register logic and SPI helpers.

    Owns the connection and exposes private register read/write helpers plus
    the SPI instruction set (RESET, READ, WRITE, RTS, READ STATUS, RX
    STATUS, BIT MODIFY, LOAD TX BUFFER, READ RX BUFFER). The Minimal-stage
    public API (`init`, `send`, `recv`) lives in `MCP2515Minimal`; Full
    adds mode/filter/error-counter methods as private functions here and
    re-exposes them via `MCP2515FullMixin`.

    Args:
        connection: Configured SPI connection bound to the device.
        bitrate_kbps: Bus bitrate in kbit/s (125, 250, 500, or 1000).
        osc_mhz: Oscillator frequency in MHz (8 or 16).
    """

    _INSTR_RESET       = 0xC0
    _INSTR_READ        = 0x03
    _INSTR_READ_RX_BUF = 0x90
    _INSTR_WRITE       = 0x02
    _INSTR_LOAD_TX_BUF = 0x40
    _INSTR_RTS         = 0x80
    _INSTR_READ_STATUS = 0xA0
    _INSTR_RX_STATUS   = 0xB0
    _INSTR_BIT_MODIFY  = 0x05

    _REG_CANSTAT  = 0x0E
    _REG_CANCTRL  = 0x0F
    _REG_CNF3     = 0x28
    _REG_CNF2     = 0x29
    _REG_CNF1     = 0x2A
    _REG_CANINTE  = 0x2B
    _REG_CANINTF  = 0x2C
    _REG_EFLG     = 0x2D

    _REG_RXB0CTRL = 0x60
    _REG_RXB1CTRL = 0x70

    _REG_TXB0CTRL = 0x30
    _REG_TXB1CTRL = 0x40
    _REG_TXB2CTRL = 0x50

    _REG_RXM0SIDH = 0x20
    _REG_RXM1SIDH = 0x24

    _REG_TEC = 0x1C
    _REG_REC = 0x1D

    _CANSTAT_OPMOD_MASK = 0xE0
    _CANSTAT_OPMOD_NORMAL      = 0x00
    _CANSTAT_OPMOD_SLEEP       = 0x20
    _CANSTAT_OPMOD_LOOPBACK    = 0x40
    _CANSTAT_OPMOD_LISTEN_ONLY = 0x60
    _CANSTAT_OPMOD_CONFIG      = 0x80

    _CANCTRL_REQOP_NORMAL      = 0x00
    _CANCTRL_REQOP_SLEEP       = 0x20
    _CANCTRL_REQOP_LOOPBACK    = 0x40
    _CANCTRL_REQOP_LISTEN_ONLY = 0x60
    _CANCTRL_REQOP_CONFIG      = 0x80

    _TXBnCTRL_TXREQ = 0x08
    _TXBnCTRL_TXP_MASK = 0x03

    _RTS_MASK_TXB0 = 0x01
    _RTS_MASK_TXB1 = 0x02
    _RTS_MASK_TXB2 = 0x04

    _RXB0CTRL_RXM_MASK = 0x60
    _RXB0CTRL_RXM_ANY  = 0x60
    _RXB0CTRL_BUKT     = 0x04

    _CANINTF_RX0IF = 0x01
    _CANINTF_RX1IF = 0x02

    _EFLG_RX0OVR = 0x40
    _EFLG_RX1OVR = 0x80

    _CNF_PRESCALER_8MHZ = {
        125: (0x01, 0xBA, 0x03),
        250: (0x00, 0xBA, 0x03),
        500: (0x00, 0x91, 0x01),
        1000: (0x00, 0x80, 0x00),
    }
    _CNF_PRESCALER_16MHZ = {
        125: (0x03, 0xBA, 0x03),
        250: (0x01, 0xBA, 0x03),
        500: (0x00, 0xBA, 0x03),
        1000: (0x00, 0x91, 0x01),
    }

    def __init__(self, connection, bitrate_kbps=125, osc_mhz=8):
        self._connection = connection
        if bitrate_kbps not in (125, 250, 500, 1000):
            raise ValueError("bitrate_kbps must be one of 125, 250, 500, 1000")
        if osc_mhz not in (8, 16):
            raise ValueError("osc_mhz must be 8 or 16")
        self._bitrate_kbps = bitrate_kbps
        self._osc_mhz = osc_mhz

        self._reset()
        self._sleep_ms(0.005)
        self._wait_op_mode(self._CANSTAT_OPMOD_CONFIG)

        cnf1, cnf2, cnf3 = self._cnp_for(bitrate_kbps, osc_mhz)
        self._write_reg(self._REG_CNF1, cnf1)
        self._write_reg(self._REG_CNF2, cnf2)
        self._write_reg(self._REG_CNF3, cnf3)

        self._write_reg(self._REG_RXB0CTRL, self._RXB0CTRL_RXM_ANY | self._RXB0CTRL_BUKT)
        self._write_reg(self._REG_RXB1CTRL, self._RXB0CTRL_RXM_ANY)

        self._write_reg(self._REG_CANINTE, 0x00)

        self._write_reg(self._REG_TXB0CTRL, self._TXBnCTRL_TXP_MASK)
        self._write_reg(self._REG_TXB1CTRL, self._TXBnCTRL_TXP_MASK)
        self._write_reg(self._REG_TXB2CTRL, self._TXBnCTRL_TXP_MASK)

        self._write_reg(self._REG_CANCTRL, self._CANCTRL_REQOP_NORMAL)
        self._wait_op_mode(self._CANSTAT_OPMOD_NORMAL)

    def _cnp_for(self, bitrate_kbps, osc_mhz):
        table = self._CNF_PRESCALER_8MHZ if osc_mhz == 8 else self._CNF_PRESCALER_16MHZ
        return table[bitrate_kbps]

    def _reset(self):
        self._connection.write(bytes([self._INSTR_RESET]))

    @staticmethod
    def _sleep_ms(ms):
        import time
        time.sleep(ms / 1000.0)

    def _write_reg(self, reg, value):
        self._connection.write(bytes([self._INSTR_WRITE, reg & 0xFF, value & 0xFF]))

    def _read_reg(self, reg):
        return self._connection.write_read(bytes([self._INSTR_READ, reg & 0xFF]), 1)[0]

    def _modify_reg(self, reg, mask, value):
        self._connection.write(bytes([
            self._INSTR_BIT_MODIFY, reg & 0xFF, mask & 0xFF, value & 0xFF,
        ]))

    def _read_status(self):
        return self._connection.write_read(bytes([self._INSTR_READ_STATUS]), 1)[0]

    def _rts(self, mask):
        self._connection.write(bytes([self._INSTR_RTS | (mask & 0x07)]))

    def _wait_op_mode(self, target, timeout_ms=100):
        target_masked = target & self._CANSTAT_OPMOD_MASK
        elapsed = 0
        step = 0.005
        while elapsed < timeout_ms:
            if (self._read_reg(self._REG_CANSTAT) & self._CANSTAT_OPMOD_MASK) == target_masked:
                return
            self._sleep_ms(step)
            elapsed += int(step * 1000)
        raise OSError("timed out waiting for CANSTAT.OPMOD = 0x%02X" % target_masked)

    def _pack_id(self, can_id, extended):
        if extended:
            sidh = (can_id >> 21) & 0xFF
            sidl = (((can_id >> 18) & 0x07) << 5) | 0x08 | ((can_id >> 16) & 0x03)
            eid8 = (can_id >> 8) & 0xFF
            eid0 = can_id & 0xFF
        else:
            sidh = (can_id >> 3) & 0xFF
            sidl = ((can_id & 0x07) << 5)
            eid8 = 0
            eid0 = 0
        return sidh, sidl, eid8, eid0

    def _unpack_id(self, sidh, sidl, eid8, eid0, ide):
        if ide:
            return (sidh << 21) | ((sidl >> 5) << 18) | ((sidl & 0x03) << 16) | (eid8 << 8) | eid0
        return (sidh << 3) | (sidl >> 5)

    def _load_tx_buffer(self, buf_index, can_id, data, extended, rtr):
        sidh, sidl, eid8, eid0 = self._pack_id(can_id, extended)
        dlc = len(data) | (0x40 if rtr else 0x00)
        base = self._tx_buf_offset(buf_index)
        payload = bytes([sidh, sidl, eid8, eid0, dlc]) + bytes(data) + bytes(8 - len(data))
        self._connection.write(bytes([self._INSTR_LOAD_TX_BUF | base]) + payload)

    def _tx_buf_offset(self, buf_index):
        if buf_index == 0:
            return 0
        if buf_index == 1:
            return 2
        if buf_index == 2:
            return 4
        raise ValueError("buf_index must be 0, 1, or 2")

    def _tx_reg_base(self, buf_index):
        return (self._REG_TXB0CTRL, self._REG_TXB1CTRL, self._REG_TXB2CTRL)[buf_index]

    def _tx_free_buf(self, timeout_ms=10):
        import time
        step_ms = 1
        elapsed = 0
        while elapsed < timeout_ms:
            status = self._read_status()
            if not (status & 0x04):
                return 0
            if not (status & 0x10):
                return 1
            if not (status & 0x40):
                return 2
            time.sleep(step_ms / 1000.0)
            elapsed += step_ms
        return None

    def _send(self, can_id, data, extended, rtr, buf_index):
        free_buf = buf_index if buf_index is not None else self._tx_free_buf()
        if free_buf is None:
            raise OSError("all TX buffers busy")
        self._load_tx_buffer(free_buf, can_id, data, extended, rtr)
        self._rts(1 << free_buf)
        wait_step = 0.001
        elapsed = 0
        base = self._tx_reg_base(free_buf)
        while elapsed < 1.0:
            ctrl = self._read_reg(base)
            if not (ctrl & self._TXBnCTRL_TXREQ):
                return
            self._sleep_ms(wait_step)
            elapsed += int(wait_step * 1000)
        raise OSError("TX buffer %d did not clear TXREQ within 1 s" % free_buf)

    def _read_rx_buffer(self, rx_index):
        offset = 0 if rx_index == 0 else 4
        data = self._connection.write_read(
            bytes([self._INSTR_READ_RX_BUF | offset]), 13,
        )
        sidh, sidl, eid8, eid0, dlc = data[0], data[1], data[2], data[3], data[4]
        ide = bool(sidl & 0x08)
        rtr = bool(dlc & 0x40) if not ide else bool(sidl & 0x10)
        can_id = self._unpack_id(sidh, sidl, eid8, eid0, ide)
        length = dlc & 0x0F
        payload = bytes(data[5:5 + length])
        return CanFrame(can_id, payload, extended=ide, rtr=rtr)

    def _poll_rx(self, timeout_ms):
        import time
        step_ms = 1
        elapsed = 0
        if timeout_ms <= 0:
            status = self._read_status()
            if status & self._CANINTF_RX0IF:
                return self._read_rx_buffer(0)
            if status & self._CANINTF_RX1IF:
                return self._read_rx_buffer(1)
            return None
        while elapsed < timeout_ms:
            status = self._read_status()
            if status & self._CANINTF_RX0IF:
                return self._read_rx_buffer(0)
            if status & self._CANINTF_RX1IF:
                return self._read_rx_buffer(1)
            time.sleep(step_ms / 1000.0)
            elapsed += step_ms
        return None

    def _set_mode(self, mode):
        if mode == "normal":
            reqop = self._CANCTRL_REQOP_NORMAL
        elif mode == "loopback":
            reqop = self._CANCTRL_REQOP_LOOPBACK
        elif mode == "listen_only":
            reqop = self._CANCTRL_REQOP_LISTEN_ONLY
        elif mode == "sleep":
            reqop = self._CANCTRL_REQOP_SLEEP
        elif mode == "config":
            reqop = self._CANCTRL_REQOP_CONFIG
        else:
            raise ValueError("mode must be 'normal', 'loopback', 'listen_only', 'sleep', or 'config'")
        self._modify_reg(self._REG_CANCTRL, 0xE0, reqop)
        self._wait_op_mode(reqop)

    def _get_mode(self):
        opmod = self._read_reg(self._REG_CANSTAT) & self._CANSTAT_OPMOD_MASK
        if opmod == self._CANSTAT_OPMOD_NORMAL:
            return "normal"
        if opmod == self._CANSTAT_OPMOD_SLEEP:
            return "sleep"
        if opmod == self._CANSTAT_OPMOD_LOOPBACK:
            return "loopback"
        if opmod == self._CANSTAT_OPMOD_LISTEN_ONLY:
            return "listen_only"
        if opmod == self._CANSTAT_OPMOD_CONFIG:
            return "config"
        return "unknown"

    def _pack_filter(self, can_id, extended):
        sidh, sidl, eid8, eid0 = self._pack_id(can_id, extended)
        return bytes([sidh, sidl, eid8, eid0])

    def _set_filter(self, n, can_id, extended):
        if n < 0 or n > 5:
            raise ValueError("filter_num must be 0–5")
        base = n * 4
        self._write_reg(base,     self._pack_filter(can_id, extended)[0])
        self._write_reg(base + 1, self._pack_filter(can_id, extended)[1])
        self._write_reg(base + 2, self._pack_filter(can_id, extended)[2])
        self._write_reg(base + 3, self._pack_filter(can_id, extended)[3])

    def _pack_mask(self, mask, extended):
        sidh, sidl, eid8, eid0 = self._pack_id(mask, extended)
        return bytes([sidh, sidl, eid8, eid0])

    def _set_mask(self, n, mask, extended):
        if n < 0 or n > 1:
            raise ValueError("mask_num must be 0 or 1")
        base = (self._REG_RXM0SIDH, self._REG_RXM1SIDH)[n]
        packed = self._pack_mask(mask, extended)
        self._write_reg(base,     packed[0])
        self._write_reg(base + 1, packed[1])
        self._write_reg(base + 2, packed[2])
        self._write_reg(base + 3, packed[3])

    def _set_rx_mode(self, buf, mode):
        if buf not in (0, 1):
            raise ValueError("buf must be 0 or 1")
        if mode not in (0, 1, 3):
            raise ValueError("mode must be 0 (std filter), 1 (ext filter), or 3 (accept all)")
        reg = self._REG_RXB0CTRL if buf == 0 else self._REG_RXB1CTRL
        value = mode << 5
        self._modify_reg(reg, self._RXB0CTRL_RXM_MASK, value)

    def _read_errors(self):
        tec = self._read_reg(self._REG_TEC)
        rec = self._read_reg(self._REG_REC)
        eflg = self._read_reg(self._REG_EFLG)
        return {"tec": tec, "rec": rec, "eflg": eflg}

    def _clear_overflow(self, buf):
        if buf == 0:
            self._modify_reg(self._REG_EFLG, self._EFLG_RX0OVR, 0x00)
        elif buf == 1:
            self._modify_reg(self._REG_EFLG, self._EFLG_RX1OVR, 0x00)
        else:
            raise ValueError("buf must be 0 or 1")

    def _abort_tx(self):
        self._modify_reg(self._REG_CANCTRL, 0x10, 0x10)
        for _ in range(100):
            if not (self._read_reg(self._REG_CANCTRL) & 0x10):
                return
            self._sleep_ms(0.005)
        raise OSError("ABAT did not clear within 500 ms")

    def _set_one_shot(self, enable):
        self._modify_reg(self._REG_CANCTRL, 0x08, 0x08 if enable else 0x00)


class MCP2515Minimal(_MCP2515Base):
    """MCP2515 minimal driver — send/recv with default configuration.

    Runs the chip's full init sequence at construction: software reset,
    Configuration mode, accept-all filters, RXB0→RXB1 rollover, polled
    operation (no interrupts), TXB0/1/2 priority 3, Normal mode.

    Args:
        connection: Configured SPI connection bound to the device.
        bitrate_kbps: Bus bitrate in kbit/s (125, 250, 500, or 1000). Default 125.
        osc_mhz: Oscillator frequency in MHz (8 or 16). Default 8.

    Raises:
        ValueError: If bitrate or oscillator value is unsupported.
        OSError: If the chip does not enter Normal mode within 100 ms.
    """

    def init(self, bitrate_kbps=125, osc_mhz=8):
        """Re-run the full init sequence with new bitrate/oscillator values.

        Args:
            bitrate_kbps: Bus bitrate in kbit/s (125, 250, 500, or 1000).
            osc_mhz: Oscillator frequency in MHz (8 or 16).
        """
        if bitrate_kbps not in (125, 250, 500, 1000):
            raise ValueError("bitrate_kbps must be one of 125, 250, 500, 1000")
        if osc_mhz not in (8, 16):
            raise ValueError("osc_mhz must be 8 or 16")
        self._bitrate_kbps = bitrate_kbps
        self._osc_mhz = osc_mhz

        self._reset()
        self._sleep_ms(0.005)
        self._wait_op_mode(self._CANSTAT_OPMOD_CONFIG)

        cnf1, cnf2, cnf3 = self._cnp_for(bitrate_kbps, osc_mhz)
        self._write_reg(self._REG_CNF1, cnf1)
        self._write_reg(self._REG_CNF2, cnf2)
        self._write_reg(self._REG_CNF3, cnf3)

        self._write_reg(self._REG_RXB0CTRL, self._RXB0CTRL_RXM_ANY | self._RXB0CTRL_BUKT)
        self._write_reg(self._REG_RXB1CTRL, self._RXB0CTRL_RXM_ANY)
        self._write_reg(self._REG_CANINTE, 0x00)

        self._write_reg(self._REG_CANCTRL, self._CANCTRL_REQOP_NORMAL)
        self._wait_op_mode(self._CANSTAT_OPMOD_NORMAL)

    def send(self, id, data=b"", extended=False):
        """Send a CAN frame.

        Loads the next free TX buffer (TXB0 first, then TXB1, TXB2),
        issues RTS, and waits up to 1 s for TXREQ to clear.

        Args:
            id: 11-bit (standard) or 29-bit (extended) identifier.
            data: Payload bytes, 0–8 bytes.
            extended: True for 29-bit extended-ID frame. Default False.

        Raises:
            ValueError: If `id` is out of range for the selected frame type.
            OSError: If no TX buffer is free within 10 ms, or TXREQ does not clear.
        """
        self._send(id, bytes(data), extended, False, None)

    def recv(self, timeout_ms=0):
        """Receive a single CAN frame.

        Polls READ STATUS until a frame is available in RXB0 or RXB1,
        or the timeout elapses.

        Args:
            timeout_ms: Receive timeout in milliseconds. 0 means return
                immediately if no frame is waiting (non-blocking poll).

        Returns:
            CanFrame | None: Received frame, or None on timeout.
        """
        return self._poll_rx(timeout_ms)


class _MCP2515FullMixin:
    """Re-exposes `_MCP2515Base`'s Full-only functionality publicly.

    Combined with `MCP2515Minimal` via `class MCP2515Full(_MCP2515FullMixin, MCP2515Minimal)`.
    Adds mode control, error-counter access, acceptance filter/mask
    configuration, abort and one-shot support, and explicit per-buffer
    TX selection.
    """

    def send_buffered(self, id, data=b"", extended=False, buf=0):
        """Send a CAN frame using a specific TX buffer.

        Args:
            id: 11-bit (standard) or 29-bit (extended) identifier.
            data: Payload bytes, 0–8 bytes.
            extended: True for 29-bit extended-ID frame. Default False.
            buf: TX buffer index 0, 1, or 2.

        Raises:
            ValueError: If `buf` is not 0/1/2 or `id` is out of range.
            OSError: If TXREQ does not clear within 1 s.
        """
        self._send(id, bytes(data), extended, False, buf)

    def set_filter(self, filter_num, id, extended=False):
        """Configure an acceptance filter.

        Args:
            filter_num: Filter index 0–5.
            id: Identifier value to match.
            extended: True for 29-bit extended-ID filter. Default False.

        Raises:
            ValueError: If `filter_num` is not 0–5.
        """
        prev_mode = self._get_mode()
        if prev_mode != "config":
            self._set_mode("config")
        try:
            self._set_filter(filter_num, id, extended)
        finally:
            if prev_mode != "config":
                self._set_mode(prev_mode)

    def set_mask(self, mask_num, mask, extended=False):
        """Configure an acceptance mask.

        Mask bit = 1: filter bit must match. Mask bit = 0: don't care.

        Args:
            mask_num: 0 (RXB0; filters 0–1) or 1 (RXB1; filters 2–5).
            mask: Mask value.
            extended: True for 29-bit extended-ID mask. Default False.

        Raises:
            ValueError: If `mask_num` is not 0 or 1.
        """
        prev_mode = self._get_mode()
        if prev_mode != "config":
            self._set_mode("config")
        try:
            self._set_mask(mask_num, mask, extended)
        finally:
            if prev_mode != "config":
                self._set_mode(prev_mode)

    def set_rx_mode(self, buf, mode):
        """Set RXM[1:0] for the given RX buffer.

        Args:
            buf: RX buffer index 0 or 1.
            mode: 0 = standard filter, 1 = extended filter, 3 = accept all.
        """
        self._set_rx_mode(buf, mode)

    def set_mode(self, mode):
        """Switch operating mode and wait until CANSTAT confirms.

        Args:
            mode: 'normal', 'loopback', 'listen_only', 'sleep', or 'config'.
        """
        self._set_mode(mode)

    def get_mode(self):
        """Return the current operating mode from CANSTAT.OPMOD.

        Returns:
            str: 'normal', 'loopback', 'listen_only', 'sleep', 'config', or 'unknown'.
        """
        return self._get_mode()

    def reset(self):
        """Issue a SPI RESET command; chip returns to Configuration mode.

        After a reset, all configuration (bit timing, filters, masks,
        operating mode) is back to POR defaults. Re-run `init()` to set
        the configuration again.
        """
        self._reset()
        self._sleep_ms(0.005)

    def read_errors(self):
        """Return TEC, REC, and EFLG register values.

        Returns:
            dict: { 'tec': int, 'rec': int, 'eflg': int }.
        """
        return self._read_errors()

    def clear_overflow(self, buf):
        """Clear the RX0OVR or RX1OVR flag in EFLG.

        Args:
            buf: RX buffer 0 or 1.
        """
        self._clear_overflow(buf)

    def abort_tx(self):
        """Set ABAT in CANCTRL; poll until cleared by hardware."""
        self._abort_tx()

    def set_one_shot(self, enable):
        """Enable or disable one-shot mode (OSM in CANCTRL).

        In one-shot mode the chip does not retransmit on error or loss of
        arbitration.

        Args:
            enable: True to enable, False to disable.
        """
        self._set_one_shot(enable)


class MCP2515Full(_MCP2515FullMixin, MCP2515Minimal):
    """MCP2515 full driver — adds mode/filter/error-counter methods.

    Inherits send/recv/init from `MCP2515Minimal`; re-exposes the
    Full-only methods from `_MCP2515Base` via `_MCP2515FullMixin`.

    Args:
        connection: Configured SPI connection bound to the device.
        bitrate_kbps: Bus bitrate in kbit/s (125, 250, 500, or 1000). Default 125.
        osc_mhz: Oscillator frequency in MHz (8 or 16). Default 8.
    """
