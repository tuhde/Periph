import struct

import sigrokdecode as srd

ANN_SENTENCE = 0
ANN_FIELD    = 1
ANN_WARNING  = 2

# Maximum UBX payload length accepted before a frame is treated as
# corrupt (real messages top out well under 1 kB); guards against a
# runaway length field consuming the rest of the capture.
_UBX_MAX_PAYLOAD = 4096

# Maximum accumulated NMEA/PUBX sentence length (including '$'...'*XX')
# before it's treated as corrupt and discarded. Standard NMEA 0183 caps
# sentences at 82 chars, but PUBX,00 alone runs to ~110 with all fields
# populated, so this is sized well above that with headroom to spare.
_NMEA_MAX_LEN = 256

# Sentence IDs this decoder annotates with field-level detail: the full
# default-configuration NMEA output set of the NEO-6 (Protocol Overview,
# specs/gnss/neo-6.md). Any other sentence ID still gets a sentence-level
# annotation but no per-field breakdown.
_DETAILED = ('GGA', 'RMC', 'GSA', 'GSV', 'VTG', 'GLL', 'TXT')

_MODE_DESC = {
    'A': 'autonomous',
    'D': 'DGPS',
    'E': 'estimated',
    'N': 'not valid',
}

_GSA_FIX_TYPE = {
    '1': 'no fix',
    '2': '2D',
    '3': '3D',
}

_TXT_SEVERITY = {
    '00': 'error',
    '01': 'warning',
    '02': 'notice',
    '07': 'user',
}


def nmea_checksum_ok(sentence):
    """Validate the *XX checksum of a $...*XX NMEA sentence (no CR/LF)."""
    try:
        star = sentence.index('*')
    except ValueError:
        return False
    checksum = 0
    for ch in sentence[1:star]:
        checksum ^= ord(ch)
    try:
        expected = int(sentence[star + 1:star + 3], 16)
    except ValueError:
        return False
    return checksum == expected


def nmea_to_degrees(raw, hemisphere):
    """Convert NMEA ddmm.mmmm / dddmm.mmmm to signed decimal degrees."""
    value = float(raw)
    deg = int(value / 100)
    minutes = value - deg * 100
    decimal = deg + minutes / 60.0
    if hemisphere in ('S', 'W'):
        decimal = -decimal
    return decimal


def nmea_time_to_iso(raw):
    """Convert NMEA hhmmss.ss UTC time to ISO 8601 'HH:MM:SSZ'. Returns
    None if raw is empty or too short to contain hh/mm/ss."""
    if not raw or len(raw) < 6:
        return None
    return '%s:%s:%sZ' % (raw[0:2], raw[2:4], raw[4:])


def nmea_date_to_iso(raw):
    """Convert NMEA ddmmyy date to ISO 8601 'YYYY-MM-DD'. The two-digit
    year is taken as 2000-2099 (the NEO-6's operating lifetime). Returns
    None if raw is empty or not exactly 6 digits."""
    if not raw or len(raw) != 6:
        return None
    dd, mm, yy = raw[0:2], raw[2:4], raw[4:6]
    return '%04d-%s-%s' % (2000 + int(yy), mm, dd)


def _get(fields, i):
    """Return fields[i], or '' if the sentence was truncated short of
    that index (some receivers omit trailing empty fields)."""
    return fields[i] if i < len(fields) else ''


def _entry(name, value, short=None):
    """Build a (long, short) annotation pair sharing one formatted value."""
    return ('%s=%s' % (name, value), '%s=%s' % (short or name, value))


def _lat_lon(fields, lat_i, ns_i, lon_i, ew_i):
    """Decode a lat/lon field group to ('lat deg', 'lon deg') strings, or
    ('n/a', 'n/a') if any of the four fields is missing or malformed."""
    latf, ns, lonf, ew = (_get(fields, lat_i), _get(fields, ns_i),
                          _get(fields, lon_i), _get(fields, ew_i))
    if latf and ns and lonf and ew:
        try:
            return ('%.6f deg' % nmea_to_degrees(latf, ns),
                    '%.6f deg' % nmea_to_degrees(lonf, ew))
        except ValueError:
            pass
    return ('n/a', 'n/a')


def _mode(fields, i):
    raw = _get(fields, i)
    return '%s (%s)' % (raw, _MODE_DESC.get(raw, 'unknown')) if raw else 'n/a'


def decode_fields(sentence_id, fields):
    """Return a list of (long, short) annotation text pairs covering
    every field of every sentence type this decoder details — the NEO-6's
    full default-configuration NMEA output set: GGA, RMC, GSA, GSV, VTG,
    GLL, TXT. One pair is emitted per field, always, so the row stays
    populated even when the module has no fix or a field is absent
    (rendered as 'n/a'). Time/date are ISO 8601; speed is m/s; lat/lon are
    signed decimal degrees. Returns an empty list for any other sentence
    ID."""
    out = []
    if sentence_id == 'GGA':
        time_iso = nmea_time_to_iso(_get(fields, 1)) or 'n/a'
        out.append(_entry('time', time_iso, 't'))

        lat, lon = _lat_lon(fields, 2, 3, 4, 5)
        out.append(_entry('lat', lat))
        out.append(_entry('lon', lon))

        fix = _get(fields, 6) or '0'
        out.append(_entry('fix', fix))

        sats = int(_get(fields, 7)) if _get(fields, 7) else 0
        out.append(_entry('sats', sats, 'sat'))

        hdop = _get(fields, 8) or 'n/a'
        out.append(_entry('hdop', hdop))

        alt = ('%s m' % _get(fields, 9)) if _get(fields, 9) else 'n/a'
        out.append(_entry('alt', alt))

        geoid_sep = ('%s m' % _get(fields, 11)) if _get(fields, 11) else 'n/a'
        out.append(_entry('geoid_sep', geoid_sep, 'geo'))

        diff_age = ('%s s' % _get(fields, 13)) if _get(fields, 13) else 'n/a'
        out.append(_entry('diff_age', diff_age, 'dage'))

        diff_station = _get(fields, 14) or 'n/a'
        out.append(_entry('diff_station', diff_station, 'dstn'))

    elif sentence_id == 'RMC':
        time_iso = nmea_time_to_iso(_get(fields, 1)) or 'n/a'
        out.append(_entry('time', time_iso, 't'))

        status = _get(fields, 2) or 'n/a'
        out.append(_entry('status', status, 'st'))

        lat, lon = _lat_lon(fields, 3, 4, 5, 6)
        out.append(_entry('lat', lat))
        out.append(_entry('lon', lon))

        speed = 'n/a'
        spd_raw = _get(fields, 7)
        if spd_raw:
            try:
                speed = '%.3f m/s' % (float(spd_raw) * 0.514444)
            except ValueError:
                pass
        out.append(_entry('speed', speed, 'v'))

        course = ('%s deg' % _get(fields, 8)) if _get(fields, 8) else 'n/a'
        out.append(_entry('course', course, 'crs'))

        date_iso = nmea_date_to_iso(_get(fields, 9)) or 'n/a'
        out.append(_entry('date', date_iso, 'd'))

        mag_var = 'n/a'
        mv_raw, mv_hemi = _get(fields, 10), _get(fields, 11)
        if mv_raw and mv_hemi:
            try:
                value = float(mv_raw)
                if mv_hemi == 'W':
                    value = -value
                mag_var = '%.1f deg' % value
            except ValueError:
                pass
        out.append(_entry('mag_var', mag_var, 'mvar'))

        out.append(_entry('mode', _mode(fields, 12)))

    elif sentence_id == 'GSA':
        mode1 = _get(fields, 1) or 'n/a'
        out.append(_entry('mode1', mode1))

        mode2_raw = _get(fields, 2)
        mode2 = ('%s (%s)' % (mode2_raw, _GSA_FIX_TYPE.get(mode2_raw, 'unknown'))
                 if mode2_raw else 'n/a')
        out.append(_entry('mode2', mode2, 'fixtype'))

        prns = [f for f in fields[3:15] if f]
        sats = ','.join(prns) if prns else 'n/a'
        out.append(_entry('sats', sats, 'sat'))

        out.append(_entry('pdop', _get(fields, 15) or 'n/a'))
        out.append(_entry('hdop', _get(fields, 16) or 'n/a'))
        out.append(_entry('vdop', _get(fields, 17) or 'n/a'))

    elif sentence_id == 'GSV':
        total_msgs = _get(fields, 1) or 'n/a'
        msg_num = _get(fields, 2) or 'n/a'
        out.append(_entry('msg', '%s/%s' % (msg_num, total_msgs), 'grp'))

        num_sv = _get(fields, 3) or '0'
        out.append(_entry('sats_in_view', num_sv, 'nsv'))

        sat_blocks = fields[4:]
        for i in range(0, len(sat_blocks), 4):
            block = sat_blocks[i:i + 4]
            if not any(block):
                continue
            idx = i // 4 + 1
            prn = block[0] if len(block) > 0 and block[0] else 'n/a'
            elev = ('%s deg' % block[1]) if len(block) > 1 and block[1] else 'n/a'
            azim = ('%s deg' % block[2]) if len(block) > 2 and block[2] else 'n/a'
            snr = ('%s dBHz' % block[3]) if len(block) > 3 and block[3] else 'n/a'
            out.append(_entry('sv%d_prn' % idx, prn, 'p%d' % idx))
            out.append(_entry('sv%d_elev' % idx, elev, 'e%d' % idx))
            out.append(_entry('sv%d_azim' % idx, azim, 'a%d' % idx))
            out.append(_entry('sv%d_snr' % idx, snr, 's%d' % idx))

    elif sentence_id == 'VTG':
        course_t = ('%s deg' % _get(fields, 1)) if _get(fields, 1) else 'n/a'
        out.append(_entry('course_true', course_t, 'crsT'))

        course_m = ('%s deg' % _get(fields, 3)) if _get(fields, 3) else 'n/a'
        out.append(_entry('course_mag', course_m, 'crsM'))

        speed = 'n/a'
        spd_raw = _get(fields, 7)
        if spd_raw:
            try:
                speed = '%.3f m/s' % (float(spd_raw) / 3.6)
            except ValueError:
                pass
        out.append(_entry('speed', speed, 'v'))

        out.append(_entry('mode', _mode(fields, 9)))

    elif sentence_id == 'GLL':
        lat, lon = _lat_lon(fields, 1, 2, 3, 4)
        out.append(_entry('lat', lat))
        out.append(_entry('lon', lon))

        time_iso = nmea_time_to_iso(_get(fields, 5)) or 'n/a'
        out.append(_entry('time', time_iso, 't'))

        status = _get(fields, 6) or 'n/a'
        out.append(_entry('status', status, 'st'))

        out.append(_entry('mode', _mode(fields, 7)))

    elif sentence_id == 'TXT':
        total_msgs = _get(fields, 1) or 'n/a'
        msg_num = _get(fields, 2) or 'n/a'
        out.append(_entry('msg', '%s/%s' % (msg_num, total_msgs), 'grp'))

        sev_raw = _get(fields, 3)
        severity = ('%s (%s)' % (sev_raw, _TXT_SEVERITY.get(sev_raw, 'unknown'))
                    if sev_raw else 'n/a')
        out.append(_entry('severity', severity, 'sev'))

        text = ','.join(fields[4:]) if len(fields) > 4 else 'n/a'
        out.append(_entry('text', text))

    return out


# --- PUBX proprietary NMEA-framed sentences -----------------------------
#
# Address field is the literal 'PUBX' (no talker+sentence-ID split); the
# message type is the first data field (fields[1]), a 2-digit code.

_PUBX_MSG_NAMES = {
    '00': 'POSITION',
    '03': 'SVSTATUS',
    '04': 'TIME',
    '40': 'RATE',
    '41': 'CONFIG',
}

_PUBX_NAV_STATUS = {
    'NF': 'no fix',
    'DR': 'dead reckoning only',
    'G2': '2D GPS',
    'G3': '3D GPS',
    'D2': '2D differential',
    'D3': '3D differential',
    'RK': 'GPS+DR combined',
    'TT': 'time only',
}

_PUBX_SV_STATUS = {
    '-': 'not used',
    'U': 'used',
    'e': 'ephemeris available',
}

_PUBX_RATE_MSGID = {
    '00': 'GGA', '01': 'GLL', '02': 'GSA', '03': 'GSV', '04': 'RMC',
    '05': 'VTG', '06': 'GRS', '07': 'GST', '08': 'ZDA', '09': 'GBS',
}


def _decode_pubx_00(fields):
    """PUBX,00 POSITION: fields[2:] per specs/gnss/neo-6.md."""
    out = []
    time_iso = nmea_time_to_iso(_get(fields, 2)) or 'n/a'
    out.append(_entry('time', time_iso, 't'))

    lat, lon = _lat_lon(fields, 3, 4, 5, 6)
    out.append(_entry('lat', lat))
    out.append(_entry('lon', lon))

    alt = ('%s m' % _get(fields, 7)) if _get(fields, 7) else 'n/a'
    out.append(_entry('alt', alt))

    ns_raw = _get(fields, 8)
    nav_stat = ('%s (%s)' % (ns_raw, _PUBX_NAV_STATUS.get(ns_raw, 'unknown'))
                if ns_raw else 'n/a')
    out.append(_entry('nav_stat', nav_stat, 'ns'))

    out.append(_entry('h_acc', ('%s m' % _get(fields, 9)) if _get(fields, 9) else 'n/a'))
    out.append(_entry('v_acc', ('%s m' % _get(fields, 10)) if _get(fields, 10) else 'n/a'))

    speed = 'n/a'
    sog_raw = _get(fields, 11)
    if sog_raw:
        try:
            speed = '%.3f m/s' % (float(sog_raw) / 3.6)
        except ValueError:
            pass
    out.append(_entry('speed', speed, 'v'))

    course = ('%s deg' % _get(fields, 12)) if _get(fields, 12) else 'n/a'
    out.append(_entry('course', course, 'crs'))

    v_vel = ('%s m/s' % _get(fields, 13)) if _get(fields, 13) else 'n/a'
    out.append(_entry('v_vel', v_vel))

    diff_age = ('%s s' % _get(fields, 14)) if _get(fields, 14) else 'n/a'
    out.append(_entry('diff_age', diff_age, 'dage'))

    out.append(_entry('hdop', _get(fields, 15) or 'n/a'))
    out.append(_entry('vdop', _get(fields, 16) or 'n/a'))
    out.append(_entry('tdop', _get(fields, 17) or 'n/a'))

    sats = int(_get(fields, 18)) if _get(fields, 18) else 0
    out.append(_entry('sats', sats, 'sat'))

    dr_raw = _get(fields, 20)
    dr = ('yes' if dr_raw == '1' else 'no') if dr_raw else 'n/a'
    out.append(_entry('dr_used', dr, 'dr'))

    return out


def _decode_pubx_03(fields):
    """PUBX,03 SVSTATUS: fields[2] = count, then {sv,status,azi,ele,cno,lck}
    blocks of 6 fields each starting at fields[3]."""
    out = []
    num_sv = _get(fields, 2) or '0'
    out.append(_entry('num_sv', num_sv, 'nsv'))

    blocks = fields[3:]
    for i in range(0, len(blocks), 6):
        block = blocks[i:i + 6]
        if not any(block):
            continue
        idx = i // 6 + 1
        sv = block[0] if len(block) > 0 and block[0] else 'n/a'
        status_raw = block[1] if len(block) > 1 else ''
        status = ('%s (%s)' % (status_raw, _PUBX_SV_STATUS.get(status_raw, 'unknown'))
                  if status_raw else 'n/a')
        azim = ('%s deg' % block[2]) if len(block) > 2 and block[2] else 'n/a'
        elev = ('%s deg' % block[3]) if len(block) > 3 and block[3] else 'n/a'
        cno = ('%s dBHz' % block[4]) if len(block) > 4 and block[4] else 'n/a'
        lck = ('%s s' % block[5]) if len(block) > 5 and block[5] else 'n/a'
        out.append(_entry('sv%d_id' % idx, sv, 'id%d' % idx))
        out.append(_entry('sv%d_status' % idx, status, 'st%d' % idx))
        out.append(_entry('sv%d_azim' % idx, azim, 'a%d' % idx))
        out.append(_entry('sv%d_elev' % idx, elev, 'e%d' % idx))
        out.append(_entry('sv%d_cno' % idx, cno, 'c%d' % idx))
        out.append(_entry('sv%d_lock' % idx, lck, 'l%d' % idx))

    return out


def _decode_pubx_04(fields):
    """PUBX,04 TIME: fields[2:] per specs/gnss/neo-6.md."""
    out = [
        _entry('time', nmea_time_to_iso(_get(fields, 2)) or 'n/a', 't'),
        _entry('date', nmea_date_to_iso(_get(fields, 3)) or 'n/a', 'd'),
        _entry('utc_tow', ('%s s' % _get(fields, 4)) if _get(fields, 4) else 'n/a', 'utow'),
        _entry('utc_week', _get(fields, 5) or 'n/a', 'uwk'),
        _entry('leap_sec', _get(fields, 6) or 'n/a', 'leap'),
        _entry('clk_bias', ('%s ns' % _get(fields, 7)) if _get(fields, 7) else 'n/a'),
        _entry('clk_drift', ('%s ns/s' % _get(fields, 8)) if _get(fields, 8) else 'n/a'),
        _entry('tp_gran', ('%s ns' % _get(fields, 9)) if _get(fields, 9) else 'n/a'),
    ]
    return out


def _decode_pubx_40(fields):
    """PUBX,40 RATE (input): fields[2:] per specs/gnss/neo-6.md."""
    msgid_raw = _get(fields, 2)
    msgid = ('%s (%s)' % (msgid_raw, _PUBX_RATE_MSGID.get(msgid_raw, 'unknown'))
             if msgid_raw else 'n/a')
    out = [_entry('msg_id', msgid, 'mid')]
    for name, i in (('rate_ddc', 3), ('rate_uart1', 4), ('rate_uart2', 5),
                    ('rate_usb', 6), ('rate_spi', 7)):
        out.append(_entry(name, _get(fields, i) or 'n/a'))
    out.append(_entry('reserved', _get(fields, 8) or 'n/a'))
    return out


def _decode_pubx_41(fields):
    """PUBX,41 CONFIG (input): fields[2:] per specs/gnss/neo-6.md."""
    return [
        _entry('port_id', _get(fields, 2) or 'n/a', 'port'),
        _entry('in_proto', _get(fields, 3) or 'n/a'),
        _entry('out_proto', _get(fields, 4) or 'n/a'),
        _entry('baud_rate', ('%s bd' % _get(fields, 5)) if _get(fields, 5) else 'n/a'),
        _entry('autobauding', _get(fields, 6) or 'n/a'),
    ]


_PUBX_DECODERS = {
    '00': _decode_pubx_00,
    '03': _decode_pubx_03,
    '04': _decode_pubx_04,
    '40': _decode_pubx_40,
    '41': _decode_pubx_41,
}


# --- UBX binary protocol -----------------------------------------------
#
# Frame: 0xB5 0x62 CLASS ID LENGTH(2B LE) PAYLOAD CK_A CK_B
# Checksum: 8-bit Fletcher over CLASS, ID, LENGTH(2B), PAYLOAD.

_UBX_MSG_NAMES = {
    (0x01, 0x02): 'NAV-POSLLH',
    (0x01, 0x03): 'NAV-STATUS',
    (0x01, 0x06): 'NAV-SOL',
    (0x01, 0x30): 'NAV-SVINFO',
    (0x06, 0x00): 'CFG-PRT',
    (0x06, 0x01): 'CFG-MSG',
    (0x06, 0x08): 'CFG-RATE',
    (0x06, 0x24): 'CFG-NAV5',
    (0x06, 0x09): 'CFG-CFG',
    (0x06, 0x04): 'CFG-RST',
    (0x06, 0x11): 'CFG-RXM',
    (0x05, 0x01): 'ACK-ACK',
    (0x05, 0x00): 'ACK-NAK',
}

_NAV_FIX_TYPE = {
    0x00: 'no fix',
    0x02: '2D-fix',
    0x03: '3D-fix',
    0x04: 'GPS+DR',
    0x05: 'time only',
}

_CFG_RST_MODE = {
    0x00: 'hardware reset',
    0x01: 'controlled software reset',
    0x02: 'controlled software reset (GNSS only)',
    0x04: 'hardware reset after shutdown',
    0x08: 'controlled GNSS stop',
    0x09: 'controlled GNSS start',
}


def ubx_checksum(buf):
    """Compute the 8-bit Fletcher (CK_A, CK_B) checksum over CLASS, ID,
    LENGTH, and PAYLOAD bytes (i.e. everything between the 0xB5 0x62
    sync bytes and the trailing checksum bytes)."""
    ck_a = ck_b = 0
    for byte in buf:
        ck_a = (ck_a + byte) & 0xFF
        ck_b = (ck_b + ck_a) & 0xFF
    return ck_a, ck_b


def _truncated(name, payload):
    return [('payload=truncated (%d bytes)' % len(payload), 'trunc')]


def _decode_nav_posllh(payload):
    if len(payload) < 28:
        return _truncated('NAV-POSLLH', payload)
    iTOW, lon, lat, height, hMSL, hAcc, vAcc = struct.unpack_from('<IiiiiII', payload, 0)
    return [
        _entry('iTOW', '%d ms' % iTOW, 'itow'),
        _entry('lon', '%.7f deg' % (lon * 1e-7)),
        _entry('lat', '%.7f deg' % (lat * 1e-7)),
        _entry('height', '%.3f m' % (height / 1000.0)),
        _entry('hMSL', '%.3f m' % (hMSL / 1000.0)),
        _entry('hAcc', '%.3f m' % (hAcc / 1000.0)),
        _entry('vAcc', '%.3f m' % (vAcc / 1000.0)),
    ]


def _decode_nav_status(payload):
    if len(payload) < 16:
        return _truncated('NAV-STATUS', payload)
    iTOW, gpsFix, flags, fixStat, flags2, ttff, msss = struct.unpack_from('<IBBBBII', payload, 0)
    return [
        _entry('iTOW', '%d ms' % iTOW, 'itow'),
        _entry('gpsFix', '0x%02X (%s)' % (gpsFix, _NAV_FIX_TYPE.get(gpsFix, 'unknown'))),
        _entry('gpsFixOk', 'yes' if flags & 0x01 else 'no'),
        _entry('fixStat', '0x%02X' % fixStat),
        _entry('flags2', '0x%02X' % flags2),
        _entry('ttff', '%d ms' % ttff),
        _entry('msss', '%d ms' % msss),
    ]


def _decode_nav_sol(payload):
    if len(payload) < 52:
        return _truncated('NAV-SOL', payload)
    (iTOW, fTOW, week, gpsFix, flags, ecefX, ecefY, ecefZ, pAcc, ecefVX, ecefVY,
     ecefVZ, sAcc, pDOP, reserved1, numSV, reserved2) = struct.unpack_from(
        '<IiHBBiiiIiiiIHBBI', payload, 0)
    return [
        _entry('iTOW', '%d ms' % iTOW, 'itow'),
        _entry('fTOW', '%d ns' % fTOW),
        _entry('week', week),
        _entry('gpsFix', '0x%02X (%s)' % (gpsFix, _NAV_FIX_TYPE.get(gpsFix, 'unknown'))),
        _entry('flags', '0x%02X' % flags),
        _entry('ecefX', '%.2f m' % (ecefX / 100.0)),
        _entry('ecefY', '%.2f m' % (ecefY / 100.0)),
        _entry('ecefZ', '%.2f m' % (ecefZ / 100.0)),
        _entry('pAcc', '%.2f m' % (pAcc / 100.0)),
        _entry('ecefVX', '%.2f m/s' % (ecefVX / 100.0)),
        _entry('ecefVY', '%.2f m/s' % (ecefVY / 100.0)),
        _entry('ecefVZ', '%.2f m/s' % (ecefVZ / 100.0)),
        _entry('sAcc', '%.2f m/s' % (sAcc / 100.0)),
        _entry('pDOP', '%.2f' % (pDOP / 100.0)),
        _entry('reserved1', '0x%02X' % reserved1),
        _entry('numSV', numSV, 'nsv'),
        _entry('reserved2', '0x%08X' % reserved2),
    ]


def _decode_nav_svinfo(payload):
    if len(payload) < 8:
        return _truncated('NAV-SVINFO', payload)
    iTOW, numCh, globalFlags, reserved2 = struct.unpack_from('<IBBH', payload, 0)
    out = [
        _entry('iTOW', '%d ms' % iTOW, 'itow'),
        _entry('numCh', numCh, 'nch'),
        _entry('globalFlags', '0x%02X' % globalFlags),
        _entry('reserved2', '0x%04X' % reserved2),
    ]
    offset = 8
    for i in range(numCh):
        if offset + 12 > len(payload):
            break
        chn, svid, flags, quality, cno, elev, azim, prRes = struct.unpack_from(
            '<BBBBBbhi', payload, offset)
        idx = i + 1
        out.append(_entry('sv%d_chn' % idx, chn, 'c%d' % idx))
        out.append(_entry('sv%d_svid' % idx, svid, 'id%d' % idx))
        out.append(_entry('sv%d_flags' % idx, '0x%02X' % flags, 'f%d' % idx))
        out.append(_entry('sv%d_quality' % idx, '0x%02X' % quality, 'q%d' % idx))
        out.append(_entry('sv%d_cno' % idx, '%d dBHz' % cno, 'sn%d' % idx))
        out.append(_entry('sv%d_elev' % idx, '%d deg' % elev, 'e%d' % idx))
        out.append(_entry('sv%d_azim' % idx, '%d deg' % azim, 'a%d' % idx))
        out.append(_entry('sv%d_prRes' % idx, '%.2f m' % (prRes / 100.0), 'r%d' % idx))
        offset += 12
    return out


def _decode_cfg_prt(payload):
    if not payload:
        return [_entry('poll', 'all ports')]
    if len(payload) == 1:
        portID, = struct.unpack_from('<B', payload, 0)
        return [_entry('poll_portID', portID)]
    if len(payload) < 20:
        return _truncated('CFG-PRT', payload)
    (portID, reserved0, txReady, mode, baudRate, inProtoMask, outProtoMask,
     flags, reserved5) = struct.unpack_from('<BBHIIHHHH', payload, 0)
    baud = ('%d bd' % baudRate) if portID == 1 else 'n/a (portID != UART)'
    return [
        _entry('portID', portID),
        _entry('reserved0', '0x%02X' % reserved0),
        _entry('txReady', '0x%04X' % txReady),
        _entry('mode', '0x%08X' % mode),
        _entry('baudRate', baud),
        _entry('inProtoMask', '0x%04X' % inProtoMask),
        _entry('outProtoMask', '0x%04X' % outProtoMask),
        _entry('flags', '0x%04X' % flags),
        _entry('reserved5', '0x%04X' % reserved5),
    ]


def _decode_cfg_msg(payload):
    if len(payload) == 2:
        msgClass, msgID = struct.unpack_from('<BB', payload, 0)
        return [_entry('poll_msgClass', '0x%02X' % msgClass),
                _entry('poll_msgID', '0x%02X' % msgID)]
    if len(payload) == 3:
        msgClass, msgID, rate = struct.unpack_from('<BBB', payload, 0)
        return [_entry('msgClass', '0x%02X' % msgClass),
                _entry('msgID', '0x%02X' % msgID),
                _entry('rate', rate)]
    if len(payload) == 8:
        msgClass, msgID = struct.unpack_from('<BB', payload, 0)
        rates = struct.unpack_from('<6B', payload, 2)
        out = [_entry('msgClass', '0x%02X' % msgClass), _entry('msgID', '0x%02X' % msgID)]
        for port_name, rate in zip(('DDC', 'UART1', 'UART2', 'USB', 'SPI', 'reserved'), rates):
            out.append(_entry('rate_%s' % port_name, rate))
        return out
    return _truncated('CFG-MSG', payload)


def _decode_cfg_rate(payload):
    if len(payload) < 6:
        return _truncated('CFG-RATE', payload)
    measRate, navRate, timeRef = struct.unpack_from('<HHH', payload, 0)
    time_ref_desc = {0: 'UTC', 1: 'GPS'}.get(timeRef, 'unknown')
    return [
        _entry('measRate', '%d ms' % measRate),
        _entry('navRate', '%d cycles' % navRate),
        _entry('timeRef', '%d (%s)' % (timeRef, time_ref_desc)),
    ]


def _decode_cfg_nav5(payload):
    if len(payload) < 36:
        return _truncated('CFG-NAV5', payload)
    (mask, dynModel, fixMode, fixedAlt, fixedAltVar, minElev, drLimit, pDop,
     tDop, pAcc, tAcc, staticHoldThresh, dgpsTimeOut, reserved2, reserved3,
     reserved4) = struct.unpack_from('<HBBiIbBHHHHBBIII', payload, 0)
    return [
        _entry('mask', '0x%04X' % mask),
        _entry('dynModel', dynModel),
        _entry('fixMode', fixMode),
        _entry('fixedAlt', '%.2f m' % (fixedAlt * 0.01)),
        _entry('fixedAltVar', '%.4f m^2' % (fixedAltVar * 0.0001)),
        _entry('minElev', '%d deg' % minElev),
        _entry('drLimit', '%d s' % drLimit),
        _entry('pDop', '%.1f' % (pDop * 0.1)),
        _entry('tDop', '%.1f' % (tDop * 0.1)),
        _entry('pAcc', '%d m' % pAcc),
        _entry('tAcc', '%d m' % tAcc),
        _entry('staticHoldThresh', '%d cm/s' % staticHoldThresh),
        _entry('dgpsTimeOut', '%d s' % dgpsTimeOut),
        _entry('reserved2', '0x%08X' % reserved2),
        _entry('reserved3', '0x%08X' % reserved3),
        _entry('reserved4', '0x%08X' % reserved4),
    ]


def _decode_cfg_cfg(payload):
    if len(payload) not in (12, 13):
        return _truncated('CFG-CFG', payload)
    clearMask, saveMask, loadMask = struct.unpack_from('<III', payload, 0)
    out = [
        _entry('clearMask', '0x%08X' % clearMask),
        _entry('saveMask', '0x%08X' % saveMask),
        _entry('loadMask', '0x%08X' % loadMask),
    ]
    if len(payload) == 13:
        deviceMask, = struct.unpack_from('<B', payload, 12)
        out.append(_entry('deviceMask', '0x%02X' % deviceMask))
    return out


def _decode_cfg_rst(payload):
    if len(payload) < 4:
        return _truncated('CFG-RST', payload)
    navBbrMask, resetMode, reserved1 = struct.unpack_from('<HBB', payload, 0)
    return [
        _entry('navBbrMask', '0x%04X' % navBbrMask),
        _entry('resetMode', '0x%02X (%s)' % (resetMode, _CFG_RST_MODE.get(resetMode, 'unknown'))),
        _entry('reserved1', '0x%02X' % reserved1),
    ]


def _decode_cfg_rxm(payload):
    if len(payload) < 2:
        return _truncated('CFG-RXM', payload)
    reserved1, lpMode = struct.unpack_from('<BB', payload, 0)
    lp_desc = {0: 'max performance', 1: 'power save'}.get(lpMode, 'unknown')
    return [
        _entry('reserved1', '0x%02X' % reserved1),
        _entry('lpMode', '%d (%s)' % (lpMode, lp_desc)),
    ]


def _decode_ack(payload):
    if len(payload) < 2:
        return _truncated('ACK', payload)
    clsID, msgID = struct.unpack_from('<BB', payload, 0)
    acked = _UBX_MSG_NAMES.get((clsID, msgID), 'UBX-%02X-%02X' % (clsID, msgID))
    return [
        _entry('clsID', '0x%02X' % clsID),
        _entry('msgID', '0x%02X' % msgID),
        _entry('ackedMsg', acked),
    ]


_UBX_DECODERS = {
    (0x01, 0x02): _decode_nav_posllh,
    (0x01, 0x03): _decode_nav_status,
    (0x01, 0x06): _decode_nav_sol,
    (0x01, 0x30): _decode_nav_svinfo,
    (0x06, 0x00): _decode_cfg_prt,
    (0x06, 0x01): _decode_cfg_msg,
    (0x06, 0x08): _decode_cfg_rate,
    (0x06, 0x24): _decode_cfg_nav5,
    (0x06, 0x09): _decode_cfg_cfg,
    (0x06, 0x04): _decode_cfg_rst,
    (0x06, 0x11): _decode_cfg_rxm,
    (0x05, 0x01): _decode_ack,
    (0x05, 0x00): _decode_ack,
}


class Decoder(srd.Decoder):
    api_version = 3
    id = 'neo6'
    name = 'NEO-6'
    longname = 'u-blox NEO-6 GNSS NMEA/UBX stream'
    desc = 'Decode NMEA 0183 sentences and UBX binary messages from a NEO-6 GNSS module UART stream.'
    license = 'gplv2+'
    inputs = ['uart']
    outputs = ['neo6']
    tags = ['Sensor', 'IC']

    annotations = (
        ('sentence', 'NMEA sentence / UBX message'),
        ('field',    'Decoded field'),
        ('warning',  'Warning'),
    )
    annotation_rows = (
        ('sentences', 'Sentences', (ANN_SENTENCE,)),
        ('fields',    'Fields',    (ANN_FIELD,)),
        ('warnings',  'Warnings',  (ANN_WARNING,)),
    )

    def __init__(self):
        self.reset()

    def reset(self):
        self.text     = ''
        self.ss_block = None
        self.in_sentence = False

        # UBX frame state machine: 'IDLE' -> 'SYNC2' -> 'CLASS' -> 'ID' ->
        # 'LEN_LO' -> 'LEN_HI' -> 'PAYLOAD' -> 'CK_A' -> 'CK_B' -> 'IDLE'.
        self.ubx_state = 'IDLE'
        self.ubx_ss = None
        self.ubx_buf = bytearray()
        self.ubx_payload = bytearray()
        self.ubx_class = 0
        self.ubx_id = 0
        self.ubx_len = 0
        self.ubx_len_lo = 0
        self.ubx_ck_a = 0

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)

    def decode(self, ss, es, data):
        ptype, rxtx, pdata = data
        if ptype != 'DATA':
            return

        # 'rxtx' identifies which UART line this byte came from. NEO-6's
        # NMEA/UBX output is the module-to-host direction, which the uart
        # PD calls RX (index/name 0 or 'rx' depending on libsigrokdecode
        # version) when the capture's RX channel is wired to the module's
        # TxD pin, per the standard wiring in the transport spec.
        if rxtx not in (0, 'rx'):
            return

        self._process_byte(pdata[0], ss, es)

    def _process_byte(self, byte, ss, es):
        if self.ubx_state != 'IDLE':
            resync = self._ubx_byte(byte, ss, es)
            if resync is None:
                return
            # UBX framing failed right after a false-positive sync1 byte;
            # re-evaluate this byte as a fresh potential frame start below.
            byte = resync

        if self.in_sentence:
            self._nmea_byte(byte, es)
            return

        char = chr(byte) if 0x20 <= byte < 0x7F else None
        if char == '$':
            self.text = '$'
            self.ss_block = ss
            self.in_sentence = True
            return

        if byte == 0xB5:
            self.ubx_state = 'SYNC2'
            self.ubx_ss = ss
            return

        # Otherwise: idle byte between messages (e.g. 0xFF filler on
        # DDC/SPI, or a byte that doesn't start either framing) — ignore.

    def _nmea_byte(self, byte, es):
        char = chr(byte) if 0x20 <= byte < 0x7F else None

        if char is None or byte in (0x0D, 0x0A):
            if byte == 0x0A:
                self._finish_sentence(es)
            return

        self.text += char
        if len(self.text) > _NMEA_MAX_LEN:
            self.in_sentence = False
            self.text = ''

    def _ubx_byte(self, byte, ss, es):
        """Advance the UBX frame state machine by one byte. Returns None
        if the byte was consumed, or the byte itself if framing failed
        and it must be re-evaluated as a fresh frame start."""
        if self.ubx_state == 'SYNC2':
            if byte == 0x62:
                self.ubx_state = 'CLASS'
                self.ubx_buf = bytearray()
                return None
            self.ubx_state = 'IDLE'
            return byte

        if self.ubx_state == 'CLASS':
            self.ubx_class = byte
            self.ubx_buf.append(byte)
            self.ubx_state = 'ID'
            return None

        if self.ubx_state == 'ID':
            self.ubx_id = byte
            self.ubx_buf.append(byte)
            self.ubx_state = 'LEN_LO'
            return None

        if self.ubx_state == 'LEN_LO':
            self.ubx_len_lo = byte
            self.ubx_buf.append(byte)
            self.ubx_state = 'LEN_HI'
            return None

        if self.ubx_state == 'LEN_HI':
            self.ubx_buf.append(byte)
            self.ubx_len = self.ubx_len_lo | (byte << 8)
            if self.ubx_len > _UBX_MAX_PAYLOAD:
                self.ubx_state = 'IDLE'
                return None
            self.ubx_payload = bytearray()
            self.ubx_state = 'PAYLOAD' if self.ubx_len > 0 else 'CK_A'
            return None

        if self.ubx_state == 'PAYLOAD':
            self.ubx_payload.append(byte)
            self.ubx_buf.append(byte)
            if len(self.ubx_payload) >= self.ubx_len:
                self.ubx_state = 'CK_A'
            return None

        if self.ubx_state == 'CK_A':
            self.ubx_ck_a = byte
            self.ubx_state = 'CK_B'
            return None

        if self.ubx_state == 'CK_B':
            self._finish_ubx(es, self.ubx_ck_a, byte)
            self.ubx_state = 'IDLE'
            return None

        return None

    def _finish_ubx(self, es, rx_ck_a, rx_ck_b):
        name = _UBX_MSG_NAMES.get((self.ubx_class, self.ubx_id),
                                   'UBX-%02X-%02X' % (self.ubx_class, self.ubx_id))
        calc_ck_a, calc_ck_b = ubx_checksum(self.ubx_buf)
        if (calc_ck_a, calc_ck_b) != (rx_ck_a, rx_ck_b):
            self.put(self.ubx_ss, es, self.out_ann,
                     [ANN_WARNING, ['Bad UBX checksum (%s)' % name, 'UBX CS err']])
            return

        payload = bytes(self.ubx_payload)
        self.put(self.ubx_ss, es, self.out_ann,
                 [ANN_SENTENCE, ['%s: %d-byte payload' % (name, len(payload)), name]])

        decoder_fn = _UBX_DECODERS.get((self.ubx_class, self.ubx_id))
        if decoder_fn:
            for long_text, short_text in decoder_fn(payload):
                self.put(self.ubx_ss, es, self.out_ann,
                         [ANN_FIELD, [long_text, short_text]])
        elif payload:
            hex_str = ' '.join('%02X' % b for b in payload)
            self.put(self.ubx_ss, es, self.out_ann,
                     [ANN_FIELD, ['payload=%s' % hex_str, 'payload=%s' % hex_str]])

    def _finish_sentence(self, es):
        sentence = self.text
        self.in_sentence = False
        self.text = ''

        if not nmea_checksum_ok(sentence):
            self.put(self.ss_block, es, self.out_ann,
                     [ANN_WARNING, ['Bad checksum', 'CS err']])
            return

        star = sentence.index('*')
        body = sentence[1:star]
        fields = body.split(',')

        if fields[0] == 'PUBX':
            self._finish_pubx(fields, body, es)
            return

        if len(fields[0]) < 5:
            self.put(self.ss_block, es, self.out_ann,
                     [ANN_WARNING, ['Malformed talker/sentence ID', 'ID err']])
            return

        talker = fields[0][:2]
        sentence_id = fields[0][2:5]
        self.put(self.ss_block, es, self.out_ann,
                 [ANN_SENTENCE, ['%s%s: %s' % (talker, sentence_id, body),
                                 sentence_id]])

        if sentence_id in _DETAILED:
            for long_text, short_text in decode_fields(sentence_id, fields):
                self.put(self.ss_block, es, self.out_ann,
                         [ANN_FIELD, [long_text, short_text]])

    def _finish_pubx(self, fields, body, es):
        if len(fields) < 2 or not fields[1]:
            self.put(self.ss_block, es, self.out_ann,
                     [ANN_WARNING, ['Malformed PUBX message ID', 'PUBX ID err']])
            return

        msg_id = fields[1]
        name = _PUBX_MSG_NAMES.get(msg_id, 'PUBX-%s' % msg_id)
        self.put(self.ss_block, es, self.out_ann,
                 [ANN_SENTENCE, ['PUBX,%s (%s): %s' % (msg_id, name, body),
                                 'PUBX%s' % msg_id]])

        decoder_fn = _PUBX_DECODERS.get(msg_id)
        if decoder_fn:
            for long_text, short_text in decoder_fn(fields):
                self.put(self.ss_block, es, self.out_ann,
                         [ANN_FIELD, [long_text, short_text]])
