"""
NEO-6 GNSS module sigrok protocol decoder.

Stacks on the uart decoder (RX channel — the module-to-host direction
carrying NMEA/UBX output). The module can interleave NMEA ASCII sentences
and UBX binary messages on the same byte stream, so this decoder
recognises both framings byte-by-byte and dispatches to whichever one a
given byte starts:

  - NMEA 0183: assembles '$'-to-CR/LF byte runs into sentences, validates
    the trailing *XX checksum, and annotates every recognised sentence
    with its talker+type (e.g. GPGGA) and raw body, plus decoded fields
    for every sentence in the NEO-6's default-configuration output set
    (specs/gnss/neo-6.md Protocol Overview): GGA (time, lat/lon, fix
    quality, satellite count, HDOP, altitude, geoid separation, DGPS
    age/station), RMC (time, status, lat/lon, speed, course, date,
    magnetic variation, mode), GSA (selection/fix mode, satellite PRN
    list, PDOP/HDOP/VDOP), GSV (message group, satellite count,
    per-satellite PRN/elevation/azimuth/SNR), VTG (true/magnetic course,
    speed, mode), GLL (lat/lon, time, status, mode), and TXT (message
    group, severity, text). Every field is emitted on every occurrence of
    its sentence, even with no fix — missing values render as 'n/a'
    rather than being skipped. Time and date use ISO 8601 ('HH:MM:SSZ',
    'YYYY-MM-DD'); speed is always m/s. Any sentence type outside this
    default set still gets a sentence-level annotation but no per-field
    breakdown.
  - PUBX proprietary sentences: same '$'/checksum framing as NMEA, but
    identified by the literal address field 'PUBX' rather than a
    talker+sentence-ID pair, with the message type as the first data
    field. Annotates every field of all five message types u-blox
    documents (specs/gnss/neo-6.md PUBX Proprietary Sentences): 00
    POSITION (time, lat/lon, altitude, nav status, accuracies, speed,
    course, vertical velocity, DGPS age, DOPs, satellite count, DR flag),
    03 SVSTATUS (per-satellite ID/status/azimuth/elevation/SNR/lock
    time), 04 TIME (time, date, UTC time-of-week/week, leap seconds,
    clock bias/drift, time pulse granularity), 40 RATE and 41 CONFIG (the
    two host→receiver configuration commands). Any other PUBX message ID
    still gets a sentence-level annotation but no per-field breakdown.
  - UBX binary: assembles 0xB5 0x62-prefixed frames (CLASS, ID, 2-byte LE
    LENGTH, PAYLOAD, CK_A, CK_B), validates the 8-bit Fletcher checksum,
    and annotates every payload field of the driver's key messages —
    NAV-POSLLH, NAV-STATUS, NAV-SOL, NAV-SVINFO, CFG-PRT, CFG-MSG,
    CFG-RATE, CFG-NAV5, CFG-CFG, CFG-RST, CFG-RXM, ACK-ACK, ACK-NAK — per
    the payload tables in specs/gnss/neo-6.md, with the same mm→m/cm→m
    (or cm/s)/1e-7°→° conversions and enum decoding used on the NMEA
    side. Any other class/ID still gets a sentence-level annotation
    (name, payload length) with the raw payload as hex, no field
    breakdown.
  - A warning annotation for any NMEA or PUBX sentence that fails
    checksum validation or has a malformed address/sentence-ID field, or
    any UBX frame that fails its checksum, instead of raising.

DDC (I2C) captures are not decoded by this module directly — sigrok stacks
protocol decoders on a single declared parent, and the i2c and uart
decoders emit incompatible packet shapes. The NMEA/UBX-parsing core
(`nmea_checksum_ok`, `nmea_to_degrees`, `decode_fields`, `ubx_checksum` in
pd.py) is factored as plain functions so a future `i2c`-stacked variant
can reuse it against the DDC read-data-register byte stream (skipping
0xFF filler).
"""

from .pd import Decoder
