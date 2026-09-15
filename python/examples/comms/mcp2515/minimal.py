from machine import SPI, Pin
from periph.connection.spi_micropython import SPIConnection
from periph.chips.comms.mcp2515 import MCP2515Minimal

spi = SPI(1, baudrate=10_000_000, polarity=0, phase=0)         # Create SPI bus, (bus=1, baudrate=10 MHz, mode 0) → SPI
cs = Pin(5, Pin.OUT)                                            # Create CS pin, (pin=5, mode=OUT) → Pin
connection = SPIConnection(spi, cs)                              # Create SPI connection, (spi, cs) → SPIConnection
can = MCP2515Minimal(connection)                                 # Create MCP2515 Minimal, (connection, bitrate_kbps=125, osc_mhz=8) → MCP2515Minimal
                                                                # resets the chip, sets 125 kbit/s at 8 MHz, Normal mode, accept-all filters

can.send(0x123, b'\xde\xad\xbe\xef')                            # Send CAN frame, (id=0x123, data=4 bytes, extended=False) → None
frame = can.recv(100)                                           # Receive one CAN frame, (timeout_ms=100) → CanFrame | None
if frame:                                                       # Check frame received, () → bool
    print(frame.id, frame.data.hex(), frame.extended, frame.rtr)
