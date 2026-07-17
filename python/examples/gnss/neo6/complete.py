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

uart = UART(1, baudrate=9600, tx=Pin(4), rx=Pin(5))
transport = UARTTransport(uart, baudrate=9600)
gps = NEO6Full(transport)                             # Create NEO-6 driver, (transport, bus_type='uart')

gps.set_rate(1)                                       # Set navigation update rate, (hz) → None
                                                       # writes CFG-RATE with measRate = 1000/hz ms
gps.set_platform(0)                                   # Set dynamic platform model, (model 0-8) → None
                                                       # writes CFG-NAV5 with mask=dynModel only
gps.save_config()                                     # Persist current configuration, () → None
                                                       # writes CFG-CFG with saveMask=all, deviceMask=BBR|Flash|EEPROM

for _ in range(20):
    if gps.update():                                  # Read + parse one NMEA sentence, () → bool
        print(gps.latitude(), gps.longitude(), gps.altitude())
                                                       # decimal degrees / decimal degrees / meters MSL
        print(gps.speed(), gps.course())               # Speed over ground, () → float | None m/s
                                                       # Course over ground, () → float | None deg
        print(gps.utc_time(), gps.utc_date())           # UTC time of last fix sentence, () → str | None hhmmss.ss
                                                       # UTC date of last RMC sentence, () → str | None ddmmyy
        print(gps.hdop())                              # Horizontal dilution of precision, () → float | None
    time.sleep_ms(50)

nav_status = gps.poll_ubx(0x01, 0x03)                 # Poll a UBX message and return its payload, (msg_class, msg_id) → bytes
print('NAV-STATUS payload:', nav_status)

gps.cold_start()                                      # Force a cold start via CFG-RST, () → None
