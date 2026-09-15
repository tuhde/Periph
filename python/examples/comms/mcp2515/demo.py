from machine import SPI, Pin
import time
from periph.connection.spi_micropython import SPIConnection
from periph.chips.comms.mcp2515 import MCP2515Full

spi = SPI(1, baudrate=10_000_000, polarity=0, phase=0)
cs = Pin(5, Pin.OUT)
connection = SPIConnection(spi, cs)
can = MCP2515Full(connection)

# --- Run the demo in loopback mode — no physical CAN bus required ---
# Loopback mode ties the TX outputs back to the RX inputs internally, so a
# sent frame is received immediately. This shows the full TX→RX path
# without external hardware.
can.set_mode('loopback')                                        # Switch operating mode, (mode='loopback') → None

heartbeat_id = 0x001
heartbeat_data = b'\x00\x00\x00\x01'

uptime = 0
while True:
    # --- Heartbeat: standard 11-bit ID, 4-byte uptime counter (big-endian) ---
    payload = bytes([
        (uptime >> 24) & 0xFF,
        (uptime >> 16) & 0xFF,
        (uptime >> 8)  & 0xFF,
        uptime & 0xFF,
    ])
    can.send(heartbeat_id, payload, extended=False)             # Send CAN frame, (id=0x001, data=4 bytes, extended=False) → None

    # --- Drain any looped-back frames (in loopback mode the heartbeat echoes) ---
    frame = can.recv(10)                                        # Receive one CAN frame, (timeout_ms=10) → CanFrame | None
    while frame is not None:
        kind = 'EXT' if frame.extended else 'STD'
        rtr  = ' RTR' if frame.rtr else ''
        print('RX %s%s id=0x%03X dlc=%d data=%s' % (
            kind, rtr, frame.id, len(frame.data), frame.data.hex(),
        ))
        frame = can.recv(10)                                    # Receive one CAN frame, (timeout_ms=10) → CanFrame | None

    uptime += 1
    time.sleep(1)
