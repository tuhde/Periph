"""ST25DV04K / ST25DV16K / ST25DV64K dynamic NFC/RFID tag driver.

The chip answers on two I²C addresses, both with 16-bit register addresses:
``0x53`` for the user memory, the dynamic registers and the fast-transfer-mode
mailbox, and ``0x57`` for the system configuration area (static registers,
UID, ``IC_REF``, ``MEM_SIZE``, the I²C password). The driver therefore takes
two register connections built on the same bus.

EEPROM writes (user memory and system area) are self-timed (5 ms per 4-byte
internal page); the chip does not acknowledge its address during the cycle,
so every write is completed by ACK polling. While an RF reader accesses the
EEPROM the chip NACKs I²C, so reads are retried a few times before raising.
"""

import time


def _delay_ms(ms):
    """Sleep for `ms` milliseconds."""
    if hasattr(time, 'sleep_ms'):
        time.sleep_ms(ms)
    else:
        time.sleep(ms / 1000.0)


def _ticks_ms():
    if hasattr(time, 'ticks_ms'):
        return time.ticks_ms()
    return int(time.time() * 1000)


def _elapsed_ms(start):
    if hasattr(time, 'ticks_diff'):
        return time.ticks_diff(time.ticks_ms(), start)
    return _ticks_ms() - start


class ST25DVSource:
    """Interrupt sources: bit layout of ``IT_STS_Dyn`` (0x2005)."""

    RF_USER = 0x01
    RF_ACTIVITY = 0x02
    RF_INTERRUPT = 0x04
    FIELD_FALLING = 0x08
    FIELD_RISING = 0x10
    RF_PUT_MSG = 0x20
    RF_GET_MSG = 0x40
    RF_WRITE = 0x80


class ST25DVMinimal:
    """ST25DV dynamic NFC tag — minimal interface: user memory as a byte store.

    On construction the driver reads ``MEM_SIZE``, ``BLK_SIZE`` and ``IC_REF``
    from the system area and derives the user memory size. No configuration
    is written; the chip keeps its delivery state (area 1 = the whole memory,
    no protection, ``GPO_EN = 1``, field-change interrupt only).

    Reads and writes are split into transfers of at most 16 bytes aligned to
    16-byte boundaries, so a transfer never crosses a 256-byte page or a
    memory-area boundary (the chip NACKs writes across an area border). Every
    write is completed by ACK polling.

    Args:
        user: RegisterConnection (I²C, ``reg_bytes=2``) at address ``0x53``
            (user memory, dynamic registers, mailbox).
        system: RegisterConnection (I²C, ``reg_bytes=2``) at address ``0x57``
            (system configuration area).
        gpo_active_high: True for the -JF variants (CMOS GPO, active high);
            False (default) for the -IE variants (open drain, active low).
            Only used to pick the interrupt edge.
    """

    _SYS_MEM_SIZE = 0x0014
    _CHUNK = 16
    _WRITE_TIMEOUT_MS = 50
    _READ_RETRIES = 3

    def __init__(self, user, system, gpo_active_high=False):
        self._user = user
        self._system = system
        self._gpo_active_high = gpo_active_high
        ident = self._read(self._system, self._SYS_MEM_SIZE, 4)
        self._mem_size_reg = ident[0] | (ident[1] << 8)
        self._blk_size_reg = ident[2]
        self._ic_ref = ident[3]
        self._size = (self._mem_size_reg + 1) * (self._blk_size_reg + 1)

    # --- helpers --------------------------------------------------------------

    def _read(self, connection, reg, length):
        attempt = 0
        while True:
            try:
                return bytes(connection.read_reg(reg, length))
            except OSError:
                attempt += 1
                if attempt >= self._READ_RETRIES:
                    raise
                _delay_ms(5)

    def _ack_poll(self, connection, timeout_ms):
        start = _ticks_ms()
        while True:
            try:
                connection.write_reg(0, b'')
                return True
            except OSError:
                pass
            if _elapsed_ms(start) >= timeout_ms:
                return False
            _delay_ms(1)

    def _commit(self, connection):
        if not self._ack_poll(connection, self._WRITE_TIMEOUT_MS):
            raise OSError('ST25DV write cycle did not complete')

    def _check_range(self, address, length):
        if address < 0 or length < 0 or address + length > self._size:
            raise ValueError('range outside the user memory 0-%d' % (self._size - 1))

    def _read_chunked(self, address, length):
        out = bytearray()
        while length > 0:
            n = self._CHUNK - (address % self._CHUNK)
            if n > length:
                n = length
            out += self._read(self._user, address, n)
            address += n
            length -= n
        return bytes(out)

    # --- public API -----------------------------------------------------------

    def read(self, address, length):
        """Read user memory.

        Args:
            address: Start byte address.
            length: Number of bytes.

        Returns:
            bytes: ``length`` bytes.

        Raises:
            ValueError: If the range lies outside the user memory.
        """
        self._check_range(address, length)
        return self._read_chunked(address, length)

    def write(self, address, data):
        """Write user memory, split into 16-byte-aligned writes, each ACK-polled.

        Args:
            address: Start byte address.
            data: Bytes to write.

        Raises:
            ValueError: If the range lies outside the user memory.
            OSError: If a write is NACKed (protected area, mailbox active) or
                the write cycle does not complete.
        """
        data = bytes(data)
        self._check_range(address, len(data))
        offset = 0
        while offset < len(data):
            a = address + offset
            n = self._CHUNK - (a % self._CHUNK)
            if n > len(data) - offset:
                n = len(data) - offset
            self._user.write_reg(a, data[offset:offset + n])
            self._commit(self._user)
            offset += n


class ST25DVFull(ST25DVMinimal):
    """ST25DV full interface — extends Minimal with identification, system
    configuration, password session, memory areas, GPO interrupts, energy
    harvesting, RF state, fast-transfer-mode mailbox and a minimal NDEF writer.

    System-area writes need an open I²C security session (``present_password``).
    The GPO enable bits 0-6 of ``GPO_CTRL_Dyn`` are read-only over I²C, so
    interrupt sources are configured in the static ``GPO`` register (session
    required); only ``GPO_EN`` can be switched at runtime without a session.

    Args:
        user: RegisterConnection at ``0x53`` (``reg_bytes=2``).
        system: RegisterConnection at ``0x57`` (``reg_bytes=2``).
        gpo_active_high: True for -JF variants (rising GPO edge).
    """

    # System area (E2 = 1)
    _SYS_GPO = 0x0000
    _SYS_IT_TIME = 0x0001
    _SYS_EH_MODE = 0x0002
    _SYS_RF_MNGT = 0x0003
    _SYS_RFA1SS = 0x0004
    _SYS_ENDA1 = 0x0005
    _SYS_I2CSS = 0x000B
    _SYS_LOCK_CCFILE = 0x000C
    _SYS_MB_MODE = 0x000D
    _SYS_MB_WDG = 0x000E
    _SYS_IC_REF = 0x0017
    _SYS_UID = 0x0018
    _SYS_IC_REV = 0x0020
    _SYS_I2C_PWD = 0x0900

    # Dynamic registers (E2 = 0)
    _DYN_GPO_CTRL = 0x2000
    _DYN_EH_CTRL = 0x2002
    _DYN_RF_MNGT = 0x2003
    _DYN_I2C_SSO = 0x2004
    _DYN_IT_STS = 0x2005
    _DYN_MB_CTRL = 0x2006
    _DYN_MB_LEN = 0x2007
    _MAILBOX = 0x2008
    _MAILBOX_SIZE = 256

    # GPO / GPO_CTRL_Dyn bits
    GPO_RF_USER = 0x01
    GPO_RF_ACTIVITY = 0x02
    GPO_RF_INTERRUPT = 0x04
    GPO_FIELD_CHANGE = 0x08
    GPO_RF_PUT_MSG = 0x10
    GPO_RF_GET_MSG = 0x20
    GPO_RF_WRITE = 0x40
    GPO_EN = 0x80

    # MB_CTRL_Dyn bits
    _MB_EN = 0x01
    _MB_HOST_PUT = 0x02
    _MB_RF_PUT = 0x04
    _MB_HOST_MISS = 0x10
    _MB_RF_MISS = 0x20
    _MB_HOST_CURRENT = 0x40
    _MB_RF_CURRENT = 0x80

    # EH_CTRL_Dyn bits
    _EH_EN = 0x01
    _EH_ON = 0x02
    _FIELD_ON = 0x04
    _VCC_ON = 0x08

    def __init__(self, user, system, gpo_active_high=False):
        super().__init__(user, system, gpo_active_high)
        self._int_callback = None
        self._int_handler = None
        self._int_pin = None
        self._poll_stop = None

    # --- identification -------------------------------------------------------

    def memory_size(self):
        """Return the user memory size in bytes (512, 2048 or 8192).

        Returns:
            int: ``(MEM_SIZE + 1) * (BLK_SIZE + 1)``.
        """
        return self._size

    def ic_reference(self):
        """Return ``IC_REF``.

        Returns:
            int: ``0x24`` (ST25DV04K) or ``0x26`` (ST25DV16K/64K).
        """
        return self._ic_ref

    def uid(self):
        """Read the 64-bit unique ID.

        Returns:
            bytes: 8 bytes, most significant first (``E0 02 ...``).
        """
        raw = self._read(self._system, self._SYS_UID, 8)
        return bytes(reversed(raw))

    def ic_revision(self):
        """Read ``IC_REV``.

        Returns:
            int: IC revision.
        """
        return self._read(self._system, self._SYS_IC_REV, 1)[0]

    # --- raw system area ------------------------------------------------------

    def read_system(self, address, length):
        """Read raw bytes from the system configuration area (device 0x57).

        Args:
            address: System register address.
            length: Number of bytes.

        Returns:
            bytes: The register bytes.
        """
        return self._read(self._system, address, length)

    def write_system(self, address, data):
        """Write raw bytes to the system configuration area, then ACK-poll.

        Needs an open I²C security session; the chip NACKs otherwise.

        Args:
            address: System register address.
            data: Bytes (or one int) to write.

        Raises:
            OSError: If the write is NACKed or does not complete.
        """
        if isinstance(data, int):
            data = bytes([data & 0xFF])
        self._system.write_reg(address, bytes(data))
        self._commit(self._system)

    def _read_sys_byte(self, address):
        return self._read(self._system, address, 1)[0]

    def _read_dyn_byte(self, address):
        return self._read(self._user, address, 1)[0]

    def _write_dyn_byte(self, address, value):
        self._user.write_reg(address, bytes([value & 0xFF]))

    # --- password session -----------------------------------------------------

    @staticmethod
    def _password_frame(password, code):
        password = bytes(password)
        if len(password) != 8:
            raise ValueError('password must be 8 bytes')
        return password + bytes([code]) + password

    def present_password(self, password):
        """Present the I²C password (opens the security session).

        Args:
            password: 8 bytes, most significant first (factory ``00 * 8``).

        Returns:
            bool: True if the session is now open (``I2C_SSO_Dyn``).
        """
        self._system.write_reg(self._SYS_I2C_PWD, self._password_frame(password, 0x09))
        self._ack_poll(self._system, self._WRITE_TIMEOUT_MS)
        return self.session_open()

    def write_password(self, password):
        """Change the I²C password (session must be open).

        Args:
            password: New 8-byte password, most significant first.

        Raises:
            OSError: If no security session is open or the write fails.
        """
        frame = self._password_frame(password, 0x07)
        if not self.session_open():
            raise OSError('ST25DV security session is closed')
        self._system.write_reg(self._SYS_I2C_PWD, frame)
        self._commit(self._system)

    def session_open(self):
        """Return whether the I²C security session is open.

        Returns:
            bool: ``I2C_SSO_Dyn`` bit 0.
        """
        return bool(self._read_dyn_byte(self._DYN_I2C_SSO) & 0x01)

    # --- memory areas ---------------------------------------------------------

    def set_area_end(self, area, end_block):
        """Set the end of area 1-3 (``ENDA1..3``), session required.

        Area ``n`` ends at byte ``end_block * 32 + 31``; the chip requires
        ``ENDA1 <= ENDA2 <= ENDA3``.

        Args:
            area: 1-3.
            end_block: ``ENDAn`` value 0-255 (32-byte granularity).
        """
        if area < 1 or area > 3:
            raise ValueError('area must be 1-3')
        if end_block < 0 or end_block > 0xFF:
            raise ValueError('end_block must be 0-255')
        self.write_system(self._SYS_ENDA1 + 2 * (area - 1), end_block)

    def set_area_protection(self, area, rf, i2c):
        """Set the RF and I²C access protection of an area, session required.

        Args:
            area: 1-4.
            rf: ``RFAnSS`` value 0-15 (bits 3:2 RW protection, bits 1:0 the
                RF password that opens the area).
            i2c: ``I2CSS`` level 0-3 for the area (bit 0 write protection,
                bit 1 read protection; read protection does not apply to area 1).
        """
        if area < 1 or area > 4:
            raise ValueError('area must be 1-4')
        if rf < 0 or rf > 0x0F or i2c < 0 or i2c > 3:
            raise ValueError('rf must be 0-15 and i2c 0-3')
        self.write_system(self._SYS_RFA1SS + 2 * (area - 1), rf)
        shift = 2 * (area - 1)
        i2css = self._read_sys_byte(self._SYS_I2CSS)
        i2css = (i2css & ~(0x03 << shift) & 0xFF) | (i2c << shift)
        self.write_system(self._SYS_I2CSS, i2css)

    def get_area_protection(self, area):
        """Read the RF and I²C protection of an area.

        Args:
            area: 1-4.

        Returns:
            tuple: ``(rf, i2c)`` as for :meth:`set_area_protection`.
        """
        if area < 1 or area > 4:
            raise ValueError('area must be 1-4')
        rf = self._read_sys_byte(self._SYS_RFA1SS + 2 * (area - 1)) & 0x0F
        i2c = (self._read_sys_byte(self._SYS_I2CSS) >> (2 * (area - 1))) & 0x03
        return (rf, i2c)

    # --- GPO ------------------------------------------------------------------

    def set_gpo(self, sources, enable=True):
        """Write the static ``GPO`` register (session required).

        The chip copies it into ``GPO_CTRL_Dyn``.

        Args:
            sources: Mask of ``GPO_*`` enable bits (bits 0-6).
            enable: Sets ``GPO_EN`` (bit 7).
        """
        self.write_system(self._SYS_GPO, (sources & 0x7F) | (self.GPO_EN if enable else 0))

    def get_gpo(self):
        """Read the static ``GPO`` register.

        Returns:
            int: ``GPO_*`` bit mask.
        """
        return self._read_sys_byte(self._SYS_GPO)

    def set_gpo_runtime(self, enable):
        """Switch the GPO output at runtime (``GPO_CTRL_Dyn.GPO_EN``, no session).

        Bits 0-6 of ``GPO_CTRL_Dyn`` are read-only over I²C, so only the
        output enable can change here.

        Args:
            enable: True to enable the GPO output.
        """
        self._write_dyn_byte(self._DYN_GPO_CTRL, self.GPO_EN if enable else 0)

    def set_interrupt_pulse(self, value):
        """Set the GPO pulse duration ``IT_TIME`` (session required).

        Pulse = ``301 µs - value * 37.65 µs``.

        Args:
            value: 0-7.
        """
        if value < 0 or value > 7:
            raise ValueError('value must be 0-7')
        self.write_system(self._SYS_IT_TIME, value)

    # --- power, RF ------------------------------------------------------------

    def field_present(self):
        """Return whether an RF field is present (``EH_CTRL_Dyn.FIELD_ON``).

        Returns:
            bool: True when a reader field is detected.
        """
        return bool(self._read_dyn_byte(self._DYN_EH_CTRL) & self._FIELD_ON)

    def set_energy_harvesting(self, enabled, forced=False):
        """Control energy harvesting.

        Sets ``EH_CTRL_Dyn.EH_EN`` at runtime. ``forced`` selects the static
        ``EH_MODE`` after boot (True = forced, False = on demand); it is only
        written when it changes, which needs a security session.

        Args:
            enabled: Enable energy harvesting now.
            forced: Force energy harvesting after power-up.
        """
        mode = 0 if forced else 1
        if (self._read_sys_byte(self._SYS_EH_MODE) & 0x01) != mode:
            self.write_system(self._SYS_EH_MODE, mode)
        self._write_dyn_byte(self._DYN_EH_CTRL, self._EH_EN if enabled else 0)

    def set_rf_state(self, disable=False, sleep=False):
        """Set the RF interface state at runtime (``RF_MNGT_Dyn``).

        The static ``RF_MNGT`` (state after power-up) is left unchanged; use
        :meth:`write_system` on ``0x0003`` to persist it.

        Args:
            disable: ``RF_DISABLE`` - the tag answers RF commands with an error.
            sleep: ``RF_SLEEP`` - the RF interface is off.
        """
        self._write_dyn_byte(self._DYN_RF_MNGT, (0x01 if disable else 0) | (0x02 if sleep else 0))

    # --- mailbox --------------------------------------------------------------

    def enable_mailbox(self):
        """Enable the fast transfer mode.

        Writes the static ``MB_MODE`` (session required) only if it is not
        already set, then sets ``MB_CTRL_Dyn.MB_EN``.
        """
        if not (self._read_sys_byte(self._SYS_MB_MODE) & 0x01):
            self.write_system(self._SYS_MB_MODE, 0x01)
        self._write_dyn_byte(self._DYN_MB_CTRL, self._MB_EN)

    def disable_mailbox(self):
        """Disable the fast transfer mode (clears ``MB_CTRL_Dyn.MB_EN``).

        The static ``MB_MODE`` stays as it is.
        """
        self._write_dyn_byte(self._DYN_MB_CTRL, 0)

    def mailbox_status(self):
        """Decode ``MB_CTRL_Dyn``.

        Returns:
            dict: ``enabled``, ``host_put``, ``rf_put``, ``host_miss``,
            ``rf_miss``, ``host_current``, ``rf_current`` (bool each).
        """
        v = self._read_dyn_byte(self._DYN_MB_CTRL)
        return {
            'enabled': bool(v & self._MB_EN),
            'host_put': bool(v & self._MB_HOST_PUT),
            'rf_put': bool(v & self._MB_RF_PUT),
            'host_miss': bool(v & self._MB_HOST_MISS),
            'rf_miss': bool(v & self._MB_RF_MISS),
            'host_current': bool(v & self._MB_HOST_CURRENT),
            'rf_current': bool(v & self._MB_RF_CURRENT),
        }

    def mailbox_write(self, data):
        """Put a message into the mailbox (single write starting at 0x2008).

        Args:
            data: 1-256 bytes.

        Raises:
            ValueError: If the length is not 1-256.
            OSError: If the mailbox is disabled or still holds a message.
        """
        data = bytes(data)
        if len(data) < 1 or len(data) > self._MAILBOX_SIZE:
            raise ValueError('mailbox message must be 1-256 bytes')
        ctrl = self._read_dyn_byte(self._DYN_MB_CTRL)
        if not (ctrl & self._MB_EN):
            raise OSError('ST25DV mailbox is disabled')
        if ctrl & (self._MB_HOST_PUT | self._MB_RF_PUT):
            raise OSError('ST25DV mailbox is busy')
        self._user.write_reg(self._MAILBOX, data)

    def mailbox_read(self):
        """Read the message in the mailbox.

        Returns:
            bytes: ``MB_LEN_Dyn + 1`` bytes, or empty when no message is present.
        """
        ctrl = self._read_dyn_byte(self._DYN_MB_CTRL)
        if not (ctrl & (self._MB_HOST_PUT | self._MB_RF_PUT)):
            return b''
        length = self._read_dyn_byte(self._DYN_MB_LEN) + 1
        out = bytearray()
        offset = 0
        while offset < length:
            n = length - offset
            if n > self._CHUNK:
                n = self._CHUNK
            out += self._read(self._user, self._MAILBOX + offset, n)
            offset += n
        return bytes(out)

    def set_mailbox_watchdog(self, value):
        """Set the mailbox watchdog ``MB_WDG`` (session required).

        Watchdog = ``2 ** (value - 1) * 30 ms``; 0 disables it.

        Args:
            value: 0-7.
        """
        if value < 0 or value > 7:
            raise ValueError('value must be 0-7')
        self.write_system(self._SYS_MB_WDG, value)

    # --- CC file, NDEF --------------------------------------------------------

    def lock_ccfile(self, lock=True):
        """Write-protect blocks 0 and 1 against RF writes (``LOCK_CCFILE``).

        Session required.

        Args:
            lock: True locks both blocks, False unlocks them.
        """
        self.write_system(self._SYS_LOCK_CCFILE, 0x03 if lock else 0x00)

    def _capability_container(self):
        if self._size <= 2048:
            mlen = self._size // 8
            if mlen > 0xFF:
                mlen = 0xFF
            return bytes([0xE1, 0x40, mlen, 0x01])
        mlen = self._size // 8 - 1
        return bytes([0xE2, 0x40, 0x00, 0x01, 0x00, 0x00, (mlen >> 8) & 0xFF, mlen & 0xFF])

    def write_ndef(self, message):
        """Write a Type 5 capability container and one NDEF TLV.

        Layout: CC (4 bytes up to 2 KiB, 8 bytes above), then
        ``03 <len> <message> FE`` (``03 FF <hi> <lo> ...`` for 255 bytes and more).

        Args:
            message: Encoded NDEF message bytes.

        Raises:
            ValueError: If the message does not fit into the user memory.
        """
        message = bytes(message)
        cc = self._capability_container()
        if len(message) < 0xFF:
            tlv = bytes([0x03, len(message)])
        else:
            tlv = bytes([0x03, 0xFF, (len(message) >> 8) & 0xFF, len(message) & 0xFF])
        image = cc + tlv + message + b'\xFE'
        if len(image) > self._size:
            raise ValueError('NDEF message too large')
        self.write(0, image)

    def read_ndef(self):
        """Read the NDEF message from the first NDEF TLV.

        Returns:
            bytes: The message, or empty when no capability container or no
            NDEF TLV is found.
        """
        cc = self.read(0, 4)
        if cc[0] == 0xE1:
            pos = 4
        elif cc[0] == 0xE2:
            pos = 8
        else:
            return b''
        while pos < self._size:
            t = self.read(pos, 1)[0]
            if t == 0x00:
                pos += 1
                continue
            if t == 0xFE or pos + 2 > self._size:
                return b''
            length = self.read(pos + 1, 1)[0]
            pos += 2
            if length == 0xFF:
                if pos + 2 > self._size:
                    return b''
                lb = self.read(pos, 2)
                length = (lb[0] << 8) | lb[1]
                pos += 2
            if t == 0x03:
                if pos + length > self._size:
                    return b''
                return self.read(pos, length)
            pos += length
        return b''

    def wait_write_complete(self):
        """ACK-poll the user address until the write cycle has finished.

        Raises:
            OSError: If the chip does not answer within the timeout.
        """
        self._commit(self._user)

    # --- interrupt ------------------------------------------------------------

    @staticmethod
    def _source_to_gpo(source):
        gpo = source & 0x07
        if source & (ST25DVSource.FIELD_RISING | ST25DVSource.FIELD_FALLING):
            gpo |= 0x08
        gpo |= (source >> 1) & 0x70
        return gpo

    def enable_interrupt(self, source):
        """Enable GPO sources in the static ``GPO`` register and set ``GPO_EN``.

        Session required (the enable bits of ``GPO_CTRL_Dyn`` are read-only
        over I²C). ``FIELD_RISING`` and ``FIELD_FALLING`` share ``FIELD_CHANGE_EN``.

        Args:
            source: ``ST25DVSource`` mask.
        """
        gpo = self.get_gpo() | self._source_to_gpo(source) | self.GPO_EN
        self.write_system(self._SYS_GPO, gpo)
        self.set_gpo_runtime(True)

    def disable_interrupt(self, source):
        """Clear GPO sources in the static ``GPO`` register (session required).

        Args:
            source: ``ST25DVSource`` mask.
        """
        gpo = self.get_gpo() & ~self._source_to_gpo(source) & 0xFF
        self.write_system(self._SYS_GPO, gpo)

    def poll_interrupt(self):
        """Read ``IT_STS_Dyn`` (cleared on read).

        Returns:
            int: ``ST25DVSource`` mask, 0 = none.
        """
        return self._read_dyn_byte(self._DYN_IT_STS) & 0xFF

    def _dispatch_int(self):
        callback = self._int_callback
        if callback is None:
            return
        status = self.poll_interrupt()
        if status:
            callback(status)

    def on_interrupt(self, callback, int_pin=None):
        """Subscribe to GPO events.

        Uses ``int_pin`` (or the user connection's ``int_pin``) on the falling
        edge (-IE) or rising edge (-JF, ``gpo_active_high``); otherwise a 10 ms
        polling thread on ``IT_STS_Dyn``.

        Args:
            callback: Function called with the ``ST25DVSource`` mask.
            int_pin: Optional InputPin wired to GPO.
        """
        self.off_interrupt()
        self._int_callback = callback
        pin = int_pin if int_pin is not None else getattr(self._user, 'int_pin', None)
        if pin is not None and hasattr(pin, 'on_edge'):
            self._int_pin = pin
            self._int_handler = lambda *a: self._dispatch_int()
            if self._gpo_active_high:
                edge = getattr(pin, 'RISING', 1)
            else:
                edge = getattr(pin, 'FALLING', 2)
            pin.on_edge(self._int_handler, edge)
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
            while not stop[0]:
                self._dispatch_int()
                _delay_ms(10)

        _thread.start_new_thread(_loop, ())

    def off_interrupt(self):
        """Unsubscribe from GPO events."""
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
