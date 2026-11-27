"""PN532 NFC/RFID controller (I²C frame protocol).

The PN532 is a 13.56 MHz NFC transceiver with an embedded 80C51 running
NXP's firmware. The host does not access registers directly; it exchanges
**frames** with the firmware:

- a command frame ``00 00 FF LEN LCS D4 <code> <data> DCS 00``,
- the chip's ACK frame ``00 00 FF 00 FF 00``,
- the response frame ``00 00 FF LEN LCS D5 <code + 1> <data> DCS 00``.

On I²C (fixed address ``0x24``) every read starts with a status byte
(bit 0 = frame ready). The driver polls that byte (a 1-byte read does not
consume the frame), reads the 6-byte response header, sends a NACK frame so
the chip resends the response, and then reads exactly ``1 + LEN + 7`` bytes.
"""

import time


def _delay_ms(ms):
    """Sleep for `ms` milliseconds."""
    if hasattr(time, 'sleep_ms'):
        time.sleep_ms(ms)
    else:
        time.sleep(ms / 1000.0)


class PN532Error(OSError):
    """PN532 protocol or status error.

    Attributes:
        code: The PN532 status error code (``Status & 0x3F``), or ``None`` for
            framing / timeout errors.
    """

    def __init__(self, message, code=None):
        super().__init__(message)
        self.code = code


class Target:
    """A target found by ``InListPassiveTarget``.

    Attributes:
        tg: Logical target number assigned by the PN532 (used by
            ``data_exchange``, ``release``, ...).
        sens_res: SENS_RES (ATQA), 16 bits, first byte high (type A only).
        sel_res: SEL_RES (SAK) (type A only).
        uid: NFCID1 (type A), NFCID2 (FeliCa) or PUPI (type B).
        ats: ATS (type A), PAD + system code (FeliCa) or ATTRIB_RES (type B);
            empty when absent.
    """

    def __init__(self, tg, sens_res, sel_res, uid, ats):
        self.tg = tg
        self.sens_res = sens_res
        self.sel_res = sel_res
        self.uid = bytes(uid)
        self.ats = bytes(ats)

    def __repr__(self):
        return 'Target(tg=%d, sens_res=0x%04X, sel_res=0x%02X, uid=%s)' % (
            self.tg, self.sens_res, self.sel_res,
            ''.join('%02X' % b for b in self.uid))


class PN532Minimal:
    """PN532 NFC/RFID reader — minimal interface.

    Detects one ISO/IEC 14443 A card or tag at 106 kbps and returns its UID.

    Default configuration (written by :meth:`init`):
        - ``SAMConfiguration``: normal mode (SAM not used), IRQ pin enabled
        - ``RFConfiguration`` MaxRetries: ``MxRtyATR = 0xFF``,
          ``MxRtyPSL = 0x01``, ``MxRtyPassiveActivation = 0x02`` (a finite
          count so that a read without a card returns)
        - RF field on (switched on by ``SAMConfiguration``)

    The chip must be wired for I²C (``I0`` low, ``I1`` high); the address is
    fixed at ``0x24``.

    Args:
        connection: Plain I²C ``Connection`` bound to address 0x24.
    """

    _PREAMBLE = b'\x00\x00\xff'
    _ACK = b'\x00\x00\xff\x00\xff\x00'
    _NACK = b'\x00\x00\xff\xff\x00\x00'

    _TFI_HOST = 0xD4
    _TFI_CHIP = 0xD5
    _TFI_ERROR = 0x7F

    _CMD_DIAGNOSE = 0x00
    _CMD_GET_FIRMWARE_VERSION = 0x02
    _CMD_GET_GENERAL_STATUS = 0x04
    _CMD_READ_REGISTER = 0x06
    _CMD_WRITE_REGISTER = 0x08
    _CMD_SAM_CONFIGURATION = 0x14
    _CMD_POWER_DOWN = 0x16
    _CMD_RF_CONFIGURATION = 0x32
    _CMD_IN_DATA_EXCHANGE = 0x40
    _CMD_IN_COMMUNICATE_THRU = 0x42
    _CMD_IN_DESELECT = 0x44
    _CMD_IN_LIST_PASSIVE_TARGET = 0x4A
    _CMD_IN_RELEASE = 0x52
    _CMD_IN_SELECT = 0x54

    _IC = 0x32

    _WRITE_RETRIES = 10      # 2 ms apart; the first write after power-down may be NACKed
    _ACK_TIMEOUT = 0.015     # s
    _ACK_ATTEMPTS = 3

    def __init__(self, connection):
        self._connection = connection

    def init(self):
        """Wake the chip, check the firmware and configure SAM and retries.

        Raises:
            PN532Error: If the chip does not answer or ``IC`` is not 0x32.
        """
        data = self._command(self._CMD_GET_FIRMWARE_VERSION)
        if len(data) < 4 or data[0] != self._IC:
            raise PN532Error('Unexpected PN532 IC 0x%02X, expected 0x32' % (data[0] if data else 0))
        self._command(self._CMD_SAM_CONFIGURATION, b'\x01\x14\x01')
        self._command(self._CMD_RF_CONFIGURATION, b'\x05\xff\x01\x02')

    def read_uid(self, timeout=1.0):
        """Detect one ISO/IEC 14443 A card and return its UID.

        Args:
            timeout: Maximum time to wait for the response, in seconds.

        Returns:
            bytes: The NFCID1 (4, 7 or 10 bytes), or ``None`` when no card
            answered.
        """
        data = self._list(1, 0x00, b'', timeout)
        if data is None or len(data) < 6 or data[0] == 0:
            return None
        n = data[5]
        uid = bytes(data[6:6 + n])
        self._command(self._CMD_IN_RELEASE, bytes([data[1]]))
        return uid

    # --- frame protocol ----------------------------------------------------

    def _list(self, max_targets, baud, initiator_data, timeout):
        """Run InListPassiveTarget; return the response data or None on timeout."""
        payload = bytes([max_targets, baud]) + bytes(initiator_data)
        self._start(self._CMD_IN_LIST_PASSIVE_TARGET, payload)
        return self._response(self._CMD_IN_LIST_PASSIVE_TARGET, timeout, True)

    def _write(self, data):
        for attempt in range(self._WRITE_RETRIES):
            try:
                self._connection.write(data)
                return
            except OSError:
                if attempt == self._WRITE_RETRIES - 1:
                    raise
                _delay_ms(2)

    @staticmethod
    def _frame(command, data):
        body = bytes([PN532Minimal._TFI_HOST, command]) + bytes(data)
        length = len(body)
        lcs = (0x100 - length) & 0xFF
        dcs = (0x100 - (sum(body) & 0xFF)) & 0xFF
        return b'\x00\x00\xff' + bytes([length, lcs]) + body + bytes([dcs, 0x00])

    def _ready(self):
        return bool(self._connection.read(1)[0] & 0x01)

    def _wait(self, timeout):
        polls = int(timeout * 1000) + 1
        for _ in range(polls):
            if self._ready():
                return True
            _delay_ms(1)
        return False

    def _start(self, command, data=b''):
        frame = self._frame(command, data)
        for _ in range(self._ACK_ATTEMPTS):
            self._write(frame)
            if self._wait(self._ACK_TIMEOUT):
                ack = self._connection.read(7)
                if bytes(ack[1:7]) == self._ACK:
                    return
        raise PN532Error('PN532 did not ACK command 0x%02X' % command)

    def _response(self, command, timeout, none_on_timeout=False):
        if not self._wait(timeout):
            self._write(self._ACK)   # abort the running command
            if none_on_timeout:
                return None
            raise PN532Error('PN532 response timeout (command 0x%02X)' % command)
        header = self._connection.read(6)
        if bytes(header[1:4]) != self._PREAMBLE:
            raise PN532Error('PN532 bad frame preamble')
        length = header[4]
        if (length + header[5]) & 0xFF != 0:
            raise PN532Error('PN532 bad length checksum')
        self._write(self._NACK)      # have the chip resend the whole frame
        if not self._wait(timeout):
            raise PN532Error('PN532 response timeout (command 0x%02X)' % command)
        frame = self._connection.read(1 + length + 7)
        body = bytes(frame[6:6 + length])
        if length == 1 and body[0] == self._TFI_ERROR:
            raise PN532Error('PN532 error frame (command 0x%02X)' % command)
        if length < 2 or body[0] != self._TFI_CHIP or body[1] != command + 1:
            raise PN532Error('PN532 unexpected response to command 0x%02X' % command)
        if (sum(body) + frame[6 + length]) & 0xFF != 0:
            raise PN532Error('PN532 bad data checksum')
        return body[2:]

    def _command(self, command, data=b'', timeout=1.0):
        self._start(command, data)
        return self._response(command, timeout)


class PN532Full(PN532Minimal):
    """PN532 NFC/RFID controller — full interface.

    Adds target listing at every baud rate, data exchange, MIFARE Classic and
    NTAG helpers, RF and retry configuration, register access, power-down,
    the self test, raw commands and the IRQ interrupt API.

    Args:
        connection: Plain I²C ``Connection`` bound to address 0x24.
    """

    BAUD_106_A = 0x00
    BAUD_212_F = 0x01
    BAUD_424_F = 0x02
    BAUD_106_B = 0x03

    _PATTERN = b'PERIPH01'

    def __init__(self, connection):
        super().__init__(connection)
        self._int_callback = None
        self._int_pin = None
        self._int_handler = None
        self._poll_stop = None

    # --- information -------------------------------------------------------

    def firmware_version(self):
        """Read the firmware version (``GetFirmwareVersion``).

        Returns:
            dict: ``ic``, ``version``, ``revision``, ``support`` (raw byte) and
            the decoded booleans ``iso14443a``, ``iso14443b``, ``iso18092``.
        """
        d = self._command(self._CMD_GET_FIRMWARE_VERSION)
        return {
            'ic': d[0], 'version': d[1], 'revision': d[2], 'support': d[3],
            'iso14443a': bool(d[3] & 0x01),
            'iso14443b': bool(d[3] & 0x02),
            'iso18092': bool(d[3] & 0x04),
        }

    def general_status(self):
        """Read the general status (``GetGeneralStatus``).

        Returns:
            dict: ``error`` (last error code), ``field`` (external RF field
            present, bool), ``targets`` (number of active targets) and
            ``sam_status``.
        """
        d = self._command(self._CMD_GET_GENERAL_STATUS)
        n = d[2]
        sam_index = 3 + 4 * n
        return {
            'error': d[0],
            'field': bool(d[1]),
            'targets': n,
            'sam_status': d[sam_index] if sam_index < len(d) else 0,
        }

    # --- targets -----------------------------------------------------------

    def list_passive_targets(self, baud=0x00, max_targets=1, initiator_data=b'', timeout=1.0):
        """List passive targets in the field (``InListPassiveTarget``).

        Args:
            baud: ``BAUD_106_A`` (default), ``BAUD_212_F``, ``BAUD_424_F`` or
                ``BAUD_106_B``.
            max_targets: 1 or 2.
            initiator_data: Optional InitiatorData (e.g. a UID to select, the
                FeliCa polling payload or the type B AFI).
            timeout: Maximum time to wait for the response, in seconds.

        Returns:
            list[Target]: Targets found; empty when none answered.
        """
        if max_targets not in (1, 2):
            raise ValueError('max_targets must be 1 or 2')
        if not 0 <= baud <= 4:
            raise ValueError('baud must be 0..4')
        d = self._list(max_targets, baud, initiator_data, timeout)
        if d is None or len(d) == 0:
            return []
        targets = []
        i = 1
        for _ in range(d[0]):
            if i >= len(d):
                break
            tg = d[i]
            if baud == self.BAUD_106_A:
                sens = (d[i + 1] << 8) | d[i + 2]
                sel = d[i + 3]
                n = d[i + 4]
                uid = d[i + 5:i + 5 + n]
                i += 5 + n
                ats = b''
                if sel & 0x20 and i < len(d):
                    tl = d[i]
                    ats = d[i:i + tl]
                    i += tl
                targets.append(Target(tg, sens, sel, uid, ats))
            elif baud in (self.BAUD_212_F, self.BAUD_424_F):
                ln = d[i + 1]                    # POL_RES length incl. itself
                pol = d[i + 2:i + 1 + ln]        # 0x01, NFCID2(8), PAD(8), [SYST(2)]
                targets.append(Target(tg, 0, 0, pol[1:9], pol[9:]))
                i += 1 + ln
            else:
                atqb = d[i + 1:i + 13]
                ln = d[i + 13]
                attrib = d[i + 14:i + 14 + ln]
                targets.append(Target(tg, 0, 0, atqb[1:5], attrib))
                i += 14 + ln
        return targets

    def _status_command(self, command, data):
        d = self._command(command, data)
        if not d:
            raise PN532Error('PN532 empty status response')
        code = d[0] & 0x3F
        if code:
            raise PN532Error('PN532 status error 0x%02X (command 0x%02X)' % (code, command), code)
        return d[1:]

    def data_exchange(self, target, data):
        """Exchange data with an activated target (``InDataExchange``).

        Args:
            target: Logical target number ``Tg``.
            data: Data to send (the PN532 adds CRC and protocol framing).

        Returns:
            bytes: Data received from the target.

        Raises:
            PN532Error: On a non-zero status (``code`` holds it).
        """
        return self._status_command(self._CMD_IN_DATA_EXCHANGE, bytes([target]) + bytes(data))

    def communicate_thru(self, data):
        """Send a raw frame to the current target (``InCommunicateThru``).

        Args:
            data: Raw bytes to transmit.

        Returns:
            bytes: Raw bytes received.
        """
        return self._status_command(self._CMD_IN_COMMUNICATE_THRU, bytes(data))

    def release(self, target=0):
        """Release a target (``InRelease``); 0 releases all.

        Args:
            target: Logical target number, 0 = all.
        """
        self._status_command(self._CMD_IN_RELEASE, bytes([target]))

    def select(self, target=0):
        """Select a listed target (``InSelect``).

        Args:
            target: Logical target number.
        """
        self._status_command(self._CMD_IN_SELECT, bytes([target]))

    def deselect(self, target=0):
        """Deselect a target, keeping its information (``InDeselect``).

        Args:
            target: Logical target number, 0 = all.
        """
        self._status_command(self._CMD_IN_DESELECT, bytes([target]))

    # --- MIFARE / NTAG -----------------------------------------------------

    def mifare_authenticate(self, target, block, key, uid, key_b=False):
        """Authenticate a MIFARE Classic sector.

        Args:
            target: Logical target number.
            block: Any block number of the sector.
            key: 6-byte key.
            uid: Card UID; its last 4 bytes are used.
            key_b: Use key B (``0x61``) instead of key A (``0x60``).
        """
        if len(key) != 6:
            raise ValueError('key must be 6 bytes')
        if len(uid) < 4:
            raise ValueError('uid must have at least 4 bytes')
        cmd = 0x61 if key_b else 0x60
        self.data_exchange(target, bytes([cmd, block & 0xFF]) + bytes(key) + bytes(uid[-4:]))

    def mifare_read_block(self, target, block):
        """Read a 16-byte MIFARE Classic block.

        Returns:
            bytes: 16 bytes.
        """
        return self.data_exchange(target, bytes([0x30, block & 0xFF]))[:16]

    def mifare_write_block(self, target, block, data):
        """Write a 16-byte MIFARE Classic block.

        Args:
            target: Logical target number.
            block: Block number (the sector must be authenticated).
            data: 16 bytes.
        """
        if len(data) != 16:
            raise ValueError('data must be 16 bytes')
        self.data_exchange(target, bytes([0xA0, block & 0xFF]) + bytes(data))

    def ntag_read_page(self, target, page):
        """Read four NTAG / MIFARE Ultralight pages starting at ``page``.

        Returns:
            bytes: 16 bytes (pages ``page`` .. ``page + 3``).
        """
        return self.data_exchange(target, bytes([0x30, page & 0xFF]))[:16]

    def ntag_write_page(self, target, page, data):
        """Write one 4-byte NTAG / MIFARE Ultralight page.

        Args:
            target: Logical target number.
            page: Page number.
            data: 4 bytes.
        """
        if len(data) != 4:
            raise ValueError('data must be 4 bytes')
        self.data_exchange(target, bytes([0xA2, page & 0xFF]) + bytes(data))

    # --- configuration -----------------------------------------------------

    def set_rf_field(self, on, auto_rfca=False):
        """Switch the RF field (``RFConfiguration`` item 0x01).

        Args:
            on: RF field on.
            auto_rfca: Enable automatic RF collision avoidance.
        """
        value = (0x02 if on else 0x00) | (0x01 if auto_rfca else 0x00)
        self._command(self._CMD_RF_CONFIGURATION, bytes([0x01, value]))

    def set_max_retries(self, passive_activation=2, atr=0xFF, psl=1):
        """Set retry counts (``RFConfiguration`` item 0x05); ``0xFF`` = infinite.

        Args:
            passive_activation: ``MxRtyPassiveActivation``.
            atr: ``MxRtyATR``.
            psl: ``MxRtyPSL``.
        """
        self._command(self._CMD_RF_CONFIGURATION,
                      bytes([0x05, atr & 0xFF, psl & 0xFF, passive_activation & 0xFF]))

    def read_register(self, address):
        """Read one internal register (``ReadRegister``).

        Args:
            address: 16-bit register address.

        Returns:
            int: Register value.
        """
        d = self._command(self._CMD_READ_REGISTER, bytes([(address >> 8) & 0xFF, address & 0xFF]))
        return d[0]

    def write_register(self, address, value):
        """Write one internal register (``WriteRegister``).

        Args:
            address: 16-bit register address.
            value: Byte value.
        """
        self._command(self._CMD_WRITE_REGISTER,
                      bytes([(address >> 8) & 0xFF, address & 0xFF, value & 0xFF]))

    def power_down(self, wakeup_sources=0x20):
        """Enter power-down (``PowerDown``).

        Args:
            wakeup_sources: ``WakeUpEnable`` bit mask (default 0x20 = I²C).
        """
        self._status_command(self._CMD_POWER_DOWN, bytes([wakeup_sources & 0xFF]))

    def self_test(self, test=0x00):
        """Run a ``Diagnose`` self test.

        Args:
            test: 0x00 communication line (echo), 0x01 ROM, 0x02 RAM, ...

        Returns:
            bool: True when the test passed.
        """
        if test == 0x00:
            d = self._command(self._CMD_DIAGNOSE, b'\x00' + self._PATTERN)
            return bytes(d) == b'\x00' + self._PATTERN
        d = self._command(self._CMD_DIAGNOSE, bytes([test & 0xFF]))
        return len(d) > 0 and d[0] == 0x00

    # --- raw commands ------------------------------------------------------

    def send_command(self, command, data=b'', timeout=1.0):
        """Send a command and return its response data.

        Args:
            command: Command code (``TFI 0xD4`` is added).
            data: Command parameters.
            timeout: Response timeout in seconds.

        Returns:
            bytes: Response data after the response code.
        """
        return self._command(command, data, timeout)

    def start_command(self, command, data=b''):
        """Send a command frame and read its ACK without waiting for the response.

        Args:
            command: Command code.
            data: Command parameters.
        """
        self._start(command, data)

    def read_response(self, command, timeout=1.0):
        """Read the response to a command started with :meth:`start_command`.

        Args:
            command: The command code that was started.
            timeout: Response timeout in seconds.

        Returns:
            bytes: Response data after the response code.
        """
        return self._response(command, timeout)

    def wait_ready(self, timeout):
        """Poll the status byte until a frame is ready.

        Args:
            timeout: Maximum wait in seconds.

        Returns:
            bool: True when a frame is ready.
        """
        return self._wait(timeout)

    # --- interrupt ---------------------------------------------------------

    def poll_interrupt(self):
        """Check the status byte.

        Returns:
            bool: True when an ACK or response frame is ready.
        """
        return self._ready()

    def _dispatch_int(self):
        callback = self._int_callback
        if callback is not None:
            callback()

    def on_interrupt(self, callback, int_pin=None):
        """Call ``callback()`` when the chip has a frame ready.

        Uses ``int_pin`` (or the connection's ``int_pin``) on its falling edge
        when present; otherwise a 10 ms polling thread on the status byte
        that fires on each not-ready → ready transition. The callback should
        read the frame (e.g. :meth:`read_response`).

        Args:
            callback: Function called with no arguments.
            int_pin: Optional InputPin wired to IRQ (P70_IRQ).
        """
        self.off_interrupt()
        self._int_callback = callback
        pin = int_pin if int_pin is not None else getattr(self._connection, 'int_pin', None)
        if pin is not None and hasattr(pin, 'on_edge'):
            self._int_pin = pin
            self._int_handler = lambda *a: self._dispatch_int()
            pin.on_edge(self._int_handler, getattr(pin, 'FALLING', 2))
        else:
            self._start_polling()

    def _start_polling(self):
        try:
            import _thread
        except ImportError:
            return
        stop = [False]
        self._poll_stop = stop

        def _loop():
            was = False
            while not stop[0]:
                try:
                    now = self._ready()
                except OSError:
                    now = False
                if now and not was:
                    self._dispatch_int()
                was = now
                _delay_ms(10)

        _thread.start_new_thread(_loop, ())

    def off_interrupt(self):
        """Unsubscribe from the interrupt."""
        self._int_callback = None
        pin = self._int_pin
        handler = self._int_handler
        self._int_pin = None
        self._int_handler = None
        if pin is not None and handler is not None and hasattr(pin, 'off_edge'):
            pin.off_edge(handler)
        if self._poll_stop is not None:
            self._poll_stop[0] = True
            self._poll_stop = None
