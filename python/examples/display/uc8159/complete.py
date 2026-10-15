# UC8159 complete example: every Full method, Waveshare 5.65inch e-Paper (F) on a Raspberry Pi Pico.
# Wiring: DIN=GP19, CLK=GP18, CS=GP17, DC=GP20, RST=GP21, BUSY=GP22, DOUT (optional) = GP16 (MISO)
# through a ~1 kOhm resistor from DIN, for the read_* calls. Each refresh blocks about 30 s.
from machine import SPI, Pin
from periph.connection.display_base import WireMode
from periph.connection.display_micropython import DisplayConnection
from periph.connection.input_pin import MicroPythonPin
from periph.connection.output_pin import MicroPythonOutputPin
from periph.chips.display.uc8159 import UC8159Full

spi = SPI(0, baudrate=1_000_000, polarity=0, phase=0)               # Create SPI bus, (id=0, baud=1 MHz, mode=0)
cs = MicroPythonOutputPin(17)                                        # Create CS# pin, (pin=17) → OutputPin
dc = MicroPythonOutputPin(20)                                        # Create D/C# pin, (pin=20) → OutputPin
res = MicroPythonOutputPin(21)                                       # Create RES# pin, (pin=21) → OutputPin
busy = MicroPythonPin(Pin(22, Pin.IN))                               # Create BUSY pin, (pin=22) → InputPin
connection = DisplayConnection(spi, cs, dc, res, busy,
                               wire_mode=WireMode.HALF_DUPLEX_PIN)   # Create display connection, (spi, cs, dc, res, busy, wire_mode)

epd = UC8159Full(connection)                                         # Create UC8159 driver, (connection, busy_timeout_ms=60000)
                                                                     # reset and the vendor init commands; panel unchanged until show()
epd.clear(UC8159Full.WHITE)                                          # Fill framebuffer, (color=WHITE)
                                                                     # every pixel set to white
epd.set_pixel(5, 5, UC8159Full.BLACK)                                # Set one pixel, (x, y, color=BLACK)
                                                                     # colour code 0..7
epd.draw_rect(20, 20, 200, 120, UC8159Full.RED, True)                # Draw rectangle, (x, y, w, h, color=BLACK, fill=False)
                                                                     # filled red block
epd.draw_rect(240, 20, 200, 120, UC8159Full.BLUE)                    # Draw rectangle, (x, y, w, h, color=BLACK, fill=False)
                                                                     # blue outline
epd.draw_line(0, 0, 599, 447, UC8159Full.GREEN)                      # Draw line, (x0, y0, x1, y1, color=BLACK)
                                                                     # Bresenham diagonal
epd.blit(460, 20, b'\xff\x81\x81\xff', 8, 4, UC8159Full.ORANGE)    # Copy 1-bpp bitmap, (x, y, data, w, h, color=BLACK)
                                                                     # 8 x 4 box, MSB first
colour = epd.get_pixel(30, 30)                                       # Read framebuffer pixel, (x, y) → int colour code
                                                                     # RED here
epd.set_flip(False, False)                                           # Mirror output, (horizontal, vertical)
                                                                     # PSR.SHL / UD at power-on values
epd.set_frame_rate(0x3C)                                             # Set PLL frame rate code, (code)
                                                                     # 50 Hz, the vendor value
epd.set_interval(7, UC8159Full.WHITE, 1)                             # Set VCOM/data interval, (cdi 0–15, border=WHITE, polarity=1)
                                                                     # white border, 10 hsync
epd.set_temperature_sensor(False, 0)                                 # Select temperature sensor, (external, offset -8..7 °C)
                                                                     # internal sensor, no offset
status = epd.read_status()                                           # Read FLG, () → int bitfield
                                                                     # needs DOUT wired (half-duplex)
temperature = epd.read_temperature()                                 # Read sensor, () → int °C
                                                                     # signed internal temperature
epd.show()                                                           # Refresh panel, () → None
                                                                     # DTM1, PON, DRF, POF; blocks ~30 s
ready = epd.wait_busy(1000)                                          # Wait for BUSY high, (timeout_ms) → bool
                                                                     # True when the controller is idle
epd.send_command(0x50, b'\x37')                                      # Send raw command, (command, data=b'')
                                                                     # rewrites CDI with the vendor value
epd.sleep()                                                          # Enter deep sleep, () → None
epd.wake()                                                           # Reset and re-initialise, () → None
                                                                     # framebuffer is kept
print('===DONE: 0 passed, 0 failed===')
