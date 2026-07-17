from machine import UART, Pin
from periph.transport.uart_micropython import UARTTransport
from periph.chips.gnss.neo6 import NEO6Full
import time

# To use I2C (DDC) instead of UART:
#   from periph.transport.i2c_micropython import I2CTransport
#   transport = I2CTransport(I2C(0, scl=Pin(9), sda=Pin(8), freq=100_000), 0x42)
#   gps = NEO6Full(transport, bus_type='i2c')
# To use SPI instead of UART:
#   from periph.transport.spi_micropython import SPITransport
#   transport = SPITransport(SPI(1, baudrate=200_000), Pin(5))
#   gps = NEO6Full(transport, bus_type='spi')

# --- Portable GPS logger ---
# The module self-configures at factory defaults (9600 baud NMEA, 1 Hz); no
# CFG messages are needed for a basic position log. Runs for 60 seconds,
# polling update() far faster than the 1 Hz sentence rate so no sentence is
# missed, and prints one line per second once a fresh GGA has been parsed.
uart = UART(1, baudrate=9600, tx=Pin(4), rx=Pin(5))
transport = UARTTransport(uart, baudrate=9600)
gps = NEO6Full(transport)                             # Create NEO-6 driver, (transport, bus_type='uart')

start_ms = time.ticks_ms()
while time.ticks_diff(time.ticks_ms(), start_ms) < 60_000:
    got_fix = gps.update()                            # Read + parse one NMEA sentence, () → bool

    # --- No fix yet: show the wait state ---
    # gpsFix alone would not be trustworthy here; update() already only
    # reports True once the GGA fix-status field confirms a real fix, so
    # a plain fix() == 0 check is enough to detect the waiting state.
    if gps.fix() == 0:
        print('waiting for fix... satellites in use: {}'.format(gps.satellites()))

    # --- Fix acquired: log the full position record ---
    # Cold-start TTFF is ~26 s typical outdoors; once got_fix flips True the
    # position, altitude, and HDOP fields below are all populated together.
    elif got_fix:
        print('{}  lat={:.6f}  lon={:.6f}  alt={:.1f} m  sats={}  hdop={}'.format(
            gps.utc_time(), gps.latitude(), gps.longitude(), gps.altitude(),
            gps.satellites(), gps.hdop()))

    time.sleep_ms(200)
