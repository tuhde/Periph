from machine import SPI, Pin
from periph.connection.spi_micropython import SPIConnection
from periph.chips.comms.mcp2515 import MCP2515Full

spi = SPI(1, baudrate=10_000_000, polarity=0, phase=0)
cs = Pin(5, Pin.OUT)
connection = SPIConnection(spi, cs)
can = MCP2515Full(connection)                                    # Create MCP2515 Full, (connection) → MCP2515Full
                                                                # inherits init/send/recv from Minimal; adds mode/filter/error API

can.init(bitrate_kbps=250, osc_mhz=8)                           # Re-run init sequence, (bitrate_kbps=250, osc_mhz=8) → None
                                                                # re-initialises at 250 kbit/s with the existing connection

can.set_mode('loopback')                                        # Switch operating mode, (mode='loopback') → None
                                                                # 'loopback' | 'normal' | 'listen_only' | 'sleep' | 'config'
print('mode =', can.get_mode())                                 # Read operating mode, () → str
                                                                # 'loopback'

can.set_mask(0, 0x7FF, extended=False)                          # Configure acceptance mask 0, (mask_num=0, mask=0x7FF, extended=False) → None
                                                                # mask bit=1 means filter bit must match; 0x7FF = exact 11-bit match
can.set_filter(0, 0x123, extended=False)                        # Configure filter 0, (filter_num=0, id=0x123, extended=False) → None
                                                                # RXB0 will only accept standard ID 0x123
can.set_filter(1, 0x456, extended=False)                        # Configure filter 1, (filter_num=1, id=0x456, extended=False) → None
                                                                # RXB0's second filter
can.set_rx_mode(0, 0)                                           # Set RX mode, (buf=0, mode=0) → None
                                                                # 0=standard filter, 1=extended filter, 3=accept all

can.set_one_shot(True)                                          # Set one-shot mode, (enable=True) → None
                                                                # OSM=1: do not retransmit on error or loss of arbitration

can.send_buffered(0x123, b'\x01\x02', extended=False, buf=1)    # Send on TX buffer 1, (id=0x123, data=2 bytes, extended=False, buf=1) → None
                                                                # explicit per-buffer TX selection (0/1/2)

frame = can.recv(100)                                           # Receive one CAN frame, (timeout_ms=100) → CanFrame | None
if frame:
    print('RX:', frame.id, frame.data.hex())

errs = can.read_errors()                                        # Read error counters, () → dict
                                                                # {'tec': int, 'rec': int, 'eflg': int} from TEC, REC, EFLG
print('TEC =', errs['tec'], 'REC =', errs['rec'])

can.abort_tx()                                                  # Abort all pending TX, () → None
                                                                # sets ABAT in CANCTRL; polls until cleared

can.reset()                                                     # Issue SPI RESET, () → None
                                                                # chip returns to Configuration mode; all regs at POR defaults

can.set_mode('normal')                                          # Switch operating mode, (mode='normal') → None
