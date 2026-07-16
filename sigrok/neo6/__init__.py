"""
NEO-6 GNSS module sigrok protocol decoder.

Stacks on the uart decoder (RX channel — the module-to-host direction
carrying NMEA output). Assembles '$'-to-CR/LF byte runs into NMEA 0183
sentences, validates the trailing *XX checksum, and annotates:

  - Every recognised sentence with its talker+type (e.g. GPGGA) and raw body.
  - Decoded fields for GGA (time, lat/lon in decimal degrees, fix quality,
    satellite count, HDOP, altitude), RMC (time, status, speed in m/s,
    course, date), and VTG (course, speed in m/s).
  - A warning annotation for any sentence that fails checksum validation
    or has a malformed talker/sentence-ID field, instead of raising.

DDC (I2C) captures are not decoded by this module directly — sigrok stacks
protocol decoders on a single declared parent, and the i2c and uart
decoders emit incompatible packet shapes. The NMEA-parsing core
(`nmea_checksum_ok`, `nmea_to_degrees`, `decode_fields` in pd.py) is
factored as plain functions so a future `i2c`-stacked variant can reuse it
against the DDC read-data-register byte stream (skipping 0xFF filler).
"""

from .pd import Decoder
