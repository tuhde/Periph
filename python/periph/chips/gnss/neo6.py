import struct


def _nmea_checksum_ok(sentence):
    """Validate the *XX checksum of a $...*XX\\r\\n NMEA sentence."""
    try:
        star = sentence.index(b'*')
    except ValueError:
        return False
    if star < 1 or star + 4 > len(sentence):
        return False
    checksum = 0
    for b in sentence[1:star]:
        checksum ^= b
    try:
        expected = int(sentence[star + 1:star + 3], 16)
    except ValueError:
        return False
    return checksum == expected


def _nmea_to_degrees(raw, hemisphere):
    """Convert NMEA ddmm.mmmm / dddmm.mmmm to signed decimal degrees."""
    value = float(raw)
    deg = int(value / 100)
    minutes = value - deg * 100
    decimal = deg + minutes / 60.0
    if hemisphere in ('S', 'W'):
        decimal = -decimal
    return decimal


def _ubx_checksum(data):
    """8-bit Fletcher checksum over class, id, length, and payload bytes."""
    ck_a = 0
    ck_b = 0
    for b in data:
        ck_a = (ck_a + b) & 0xFF
        ck_b = (ck_b + ck_a) & 0xFF
    return ck_a, ck_b


class NEO6Minimal:
    """u-blox NEO-6 GNSS receiver: NMEA position, altitude, and fix status.

    Reads bytes from the transport and assembles complete NMEA sentences
    terminated by CR/LF. Works out of the box with the module's factory
    defaults (NMEA output at 9600 baud, 1 Hz, all standard sentences
    enabled) -- no chip-side configuration is sent.

    The driver is transport-agnostic; pass bus_type to match the transport
    given at construction:

    - 'uart' (default): a UART transport. read() blocks with a timeout;
      a timeout is treated as "no new byte this call", not an error.
    - 'i2c': an I2C (DDC) transport. Each byte is fetched with a
      random-read to register 0xFF, per the DDC protocol.
    - 'spi': an SPI transport. Each byte is fetched with a full-duplex
      transfer; write_read() is called with an empty command so the
      module's real output byte is never discarded mid-transfer.

    A stray idle-filler byte (0xFF on I2C/SPI when the module has nothing
    queued) can never start a sentence (NMEA sentences start with '$');
    if one lands mid-sentence during a buffer underrun, the resulting
    sentence simply fails its checksum and is discarded, same as any
    other corrupted sentence.

    Args:
        transport: Transport instance (UART, I2C, or SPI).
        bus_type: 'uart', 'i2c', or 'spi'; default 'uart'.
    """

    _SENTENCE_START = 0x24  # '$'
    _CR = 0x0D
    _LF = 0x0A
    _MAX_SENTENCE = 96

    def __init__(self, transport, bus_type='uart'):
        self._transport = transport
        self._bus_type = bus_type
        self._buf = bytearray()
        self._in_sentence = False
        self._lat = None
        self._lon = None
        self._alt = None
        self._fix = 0
        self._satellites = 0

    def _read_byte(self):
        """Fetch one byte if available; return None if none is ready yet."""
        if self._bus_type == 'uart':
            try:
                b = self._transport.read(1)
            except OSError:
                return None
        elif self._bus_type == 'i2c':
            # DDC random-read: set the register pointer to 0xFF, then read
            # one stream byte. The pointer saturates at 0xFF once set, so
            # re-sending it on every byte is redundant but harmless.
            b = self._transport.write_read(b'\xff', 1)
        else:
            # SPI has no register-address concept, so the write phase must
            # stay empty: write_read(prefix, n) clocks prefix's response
            # bytes and discards them, and any non-empty prefix here would
            # throw away a real byte of the module's output stream. An
            # empty prefix makes the whole call one true 1:1 full-duplex
            # transfer, so no incoming byte is ever discarded.
            b = self._transport.write_read(b'', 1)
        return b[0] if b else None

    def update(self):
        """Read available bytes and parse at most one complete NMEA sentence.

        Returns:
            bool: True if a GGA sentence with a valid fix (fix status > 0)
                was parsed during this call.
        """
        byte = self._read_byte()
        if byte is None:
            return False
        if byte == self._SENTENCE_START:
            self._buf = bytearray([byte])
            self._in_sentence = True
            return False
        if not self._in_sentence:
            return False
        self._buf.append(byte)
        if len(self._buf) > self._MAX_SENTENCE:
            self._buf = bytearray()
            self._in_sentence = False
            return False
        if byte == self._LF and len(self._buf) >= 2 and self._buf[-2] == self._CR:
            sentence = bytes(self._buf)
            self._buf = bytearray()
            self._in_sentence = False
            return self._on_sentence(sentence)
        return False

    def _on_sentence(self, sentence):
        if not _nmea_checksum_ok(sentence):
            return False
        try:
            body = sentence[1:sentence.index(b'*')].decode('ascii')
        except (ValueError, UnicodeDecodeError):
            return False
        fields = body.split(',')
        if len(fields[0]) < 5:
            return False
        sentence_id = fields[0][2:5]
        result = False
        if sentence_id == 'GGA':
            result = self._parse_gga(fields)
        self._handle_extra(sentence_id, fields)
        return result

    def _parse_gga(self, fields):
        if len(fields) < 15:
            return False
        try:
            fix = int(fields[6]) if fields[6] else 0
        except ValueError:
            fix = 0
        self._fix = fix
        try:
            self._satellites = int(fields[7]) if fields[7] else 0
        except ValueError:
            self._satellites = 0
        if fix > 0 and fields[2] and fields[3] and fields[4] and fields[5]:
            try:
                self._lat = _nmea_to_degrees(fields[2], fields[3])
                self._lon = _nmea_to_degrees(fields[4], fields[5])
                self._alt = float(fields[9]) if fields[9] else None
            except ValueError:
                pass
        return fix > 0

    def _handle_extra(self, sentence_id, fields):
        """Hook for Full to parse additional sentence types. No-op here."""
        pass

    def latitude(self):
        """Latitude of the last valid fix.

        Returns:
            float or None: Decimal degrees, positive north; None until the
                first valid GGA fix.
        """
        return self._lat

    def longitude(self):
        """Longitude of the last valid fix.

        Returns:
            float or None: Decimal degrees, positive east; None until the
                first valid GGA fix.
        """
        return self._lon

    def altitude(self):
        """Height above mean sea level of the last valid fix.

        Returns:
            float or None: Meters; None until the first valid GGA fix.
        """
        return self._alt

    def fix(self):
        """GGA fix quality of the last parsed GGA sentence.

        Returns:
            int: 0 = no fix, 1 = GPS, 2 = DGPS.
        """
        return self._fix

    def satellites(self):
        """Number of satellites used in the last GGA fix.

        Returns:
            int: Satellite count (GGA field 7).
        """
        return self._satellites


class NEO6Full(NEO6Minimal):
    """NEO-6 with UBX binary messaging, rate/platform configuration, and
    richer NMEA fields (speed, course, UTC time/date, HDOP).

    Extends NEO6Minimal; all Minimal methods are inherited unchanged.
    """

    _UBX_SYNC1 = 0xB5
    _UBX_SYNC2 = 0x62
    _CLASS_ACK = 0x05
    _ID_ACK_NAK = 0x00
    _ID_ACK_ACK = 0x01

    def __init__(self, transport, bus_type='uart'):
        super().__init__(transport, bus_type)
        self._speed = None
        self._course = None
        self._utc_time = None
        self._utc_date = None
        self._hdop = None

    def _handle_extra(self, sentence_id, fields):
        if sentence_id == 'GGA':
            if len(fields) > 1 and fields[1]:
                self._utc_time = fields[1]
            if len(fields) > 8 and fields[8]:
                try:
                    self._hdop = float(fields[8])
                except ValueError:
                    pass
        elif sentence_id == 'RMC':
            self._parse_rmc(fields)
        elif sentence_id == 'VTG':
            self._parse_vtg(fields)

    def _parse_rmc(self, fields):
        if len(fields) < 10:
            return
        if fields[1]:
            self._utc_time = fields[1]
        if fields[7]:
            try:
                self._speed = float(fields[7]) * 0.514444
            except ValueError:
                pass
        if fields[8]:
            try:
                self._course = float(fields[8])
            except ValueError:
                pass
        if fields[9]:
            self._utc_date = fields[9]

    def _parse_vtg(self, fields):
        if len(fields) > 1 and fields[1]:
            try:
                self._course = float(fields[1])
            except ValueError:
                pass
        if len(fields) > 7 and fields[7]:
            try:
                self._speed = float(fields[7]) / 3.6
            except ValueError:
                pass

    def speed(self):
        """Speed over ground.

        Returns:
            float or None: Meters per second, converted from RMC/VTG;
                None until the first speed field is parsed.
        """
        return self._speed

    def course(self):
        """Course over ground.

        Returns:
            float or None: Degrees, 0-360, from RMC/VTG; None until the
                first course field is parsed.
        """
        return self._course

    def utc_time(self):
        """UTC time of the last GGA or RMC sentence.

        Returns:
            str or None: 'hhmmss.ss'; None until the first sentence with a
                time field is parsed.
        """
        return self._utc_time

    def utc_date(self):
        """UTC date of the last RMC sentence.

        Returns:
            str or None: 'ddmmyy'; None until the first RMC sentence is
                parsed.
        """
        return self._utc_date

    def hdop(self):
        """Horizontal dilution of precision from the last GGA sentence.

        Returns:
            float or None: Unitless HDOP; None until the first GGA
                sentence with a populated HDOP field is parsed.
        """
        return self._hdop

    def send_ubx(self, msg_class, msg_id, payload=b''):
        """Frame and write a UBX message (adds sync bytes, length, checksum).

        Args:
            msg_class: UBX message class (e.g. 0x06 for CFG).
            msg_id: UBX message ID within the class.
            payload: Message payload bytes; default empty (a poll request).
        """
        length = len(payload)
        body = bytes([msg_class, msg_id, length & 0xFF, (length >> 8) & 0xFF]) + bytes(payload)
        ck_a, ck_b = _ubx_checksum(body)
        frame = bytes([self._UBX_SYNC1, self._UBX_SYNC2]) + body + bytes([ck_a, ck_b])
        self._transport.write(frame)

    def poll_ubx(self, msg_class, msg_id):
        """Send a poll request and return the response payload.

        Args:
            msg_class: UBX message class to poll.
            msg_id: UBX message ID to poll.

        Returns:
            bytes: The response message's payload.

        Raises:
            OSError: If the module answers with ACK-NAK, or no matching
                response arrives before the internal idle budget is spent.
        """
        self.send_ubx(msg_class, msg_id)
        return self._read_ubx_response(msg_class, msg_id)

    def _read_ubx_response(self, want_class, want_id, max_frames=400, max_idle=4000):
        idle = 0
        frames = 0
        while frames < max_frames:
            byte = self._read_byte()
            if byte is None:
                idle += 1
                if idle > max_idle:
                    raise OSError('UBX response timeout')
                continue
            idle = 0
            if byte != self._UBX_SYNC1:
                continue
            if self._read_byte() != self._UBX_SYNC2:
                continue
            header = [self._read_byte() for _ in range(4)]
            if any(b is None for b in header):
                continue
            cls, mid, len_lo, len_hi = header
            length = len_lo | (len_hi << 8)
            payload = bytearray()
            for _ in range(length):
                b = self._read_byte()
                if b is None:
                    break
                payload.append(b)
            if len(payload) != length:
                frames += 1
                continue
            ck_a = self._read_byte()
            ck_b = self._read_byte()
            exp_a, exp_b = _ubx_checksum(bytes([cls, mid, len_lo, len_hi]) + bytes(payload))
            frames += 1
            if ck_a != exp_a or ck_b != exp_b:
                continue
            if cls == self._CLASS_ACK and mid == self._ID_ACK_NAK:
                raise OSError('UBX NAK for class 0x%02X id 0x%02X' % (want_class, want_id))
            if cls == want_class and mid == want_id:
                return bytes(payload)
        raise OSError('UBX response timeout')

    def set_rate(self, hz):
        """Set the navigation update rate via CFG-RATE.

        Args:
            hz: Update rate in Hz (1-5 Hz for standard NEO-6 models).
        """
        meas_rate_ms = int(1000 / hz)
        payload = struct.pack('<HHH', meas_rate_ms, 1, 0)
        self.send_ubx(0x06, 0x08, payload)

    def set_platform(self, model):
        """Set the dynamic platform model via CFG-NAV5.

        Args:
            model: Platform model code -- 0=portable, 2=stationary,
                3=pedestrian, 4=automotive, 5=sea, 6=airborne<1g,
                7=airborne<2g, 8=airborne<4g.
        """
        payload = bytearray(36)
        struct.pack_into('<H', payload, 0, 0x0001)  # mask: apply dynModel only
        payload[2] = model & 0xFF
        self.send_ubx(0x06, 0x24, bytes(payload))

    def cold_start(self):
        """Force a cold start via CFG-RST (clears almanac, ephemeris,
        and last known position)."""
        payload = struct.pack('<HBB', 0xFFFF, 0x02, 0x00)
        self.send_ubx(0x06, 0x04, payload)

    def save_config(self):
        """Persist the current configuration via CFG-CFG (saves to
        battery-backed RAM and flash, where available)."""
        payload = struct.pack('<III', 0x00000000, 0xFFFFFFFF, 0x00000000) + bytes([0x07])
        self.send_ubx(0x06, 0x09, payload)
