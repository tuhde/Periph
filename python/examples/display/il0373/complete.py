# IL0373 complete example: every Full method, Waveshare 2.13inch e-Paper (D) on a Raspberry Pi Pico.
# Wiring: DIN=GP19, CLK=GP18, CS=GP17, DC=GP20, RST=GP21, BUSY=GP22, DOUT (optional) = GP16 (MISO)
# through a ~1 kOhm resistor from DIN, for the read_* calls. Each refresh blocks until BUSY is high.
from machine import SPI, Pin
from periph.connection.display_base import WireMode
from periph.connection.display_micropython import DisplayConnection
from periph.connection.input_pin import MicroPythonPin
from periph.connection.output_pin import MicroPythonOutputPin
from periph.chips.display.il0373 import IL0373Full

spi = SPI(0, baudrate=1_000_000, polarity=0, phase=0)               # Create SPI bus, (id=0, baud=1 MHz, mode=0)
cs = MicroPythonOutputPin(17)                                        # Create CS# pin, (pin=17) → OutputPin
dc = MicroPythonOutputPin(20)                                        # Create D/C# pin, (pin=20) → OutputPin
res = MicroPythonOutputPin(21)                                       # Create RES# pin, (pin=21) → OutputPin
busy = MicroPythonPin(Pin(22, Pin.IN))                               # Create BUSY pin, (pin=22) → InputPin
connection = DisplayConnection(spi, cs, dc, res, busy,
                               wire_mode=WireMode.HALF_DUPLEX_PIN)   # Create display connection, (spi, cs, dc, res, busy, wire_mode)

epd = IL0373Full(connection)                                        # Create IL0373 driver, (connection, width=104, height=212, busy_timeout_ms=30000)
                                                                     # reset, BTST, PON, PSR 0x1F, TRES, CDI 0x97; panel unchanged until show()
epd.clear(IL0373Full.WHITE)                                         # Fill framebuffer, (color=WHITE)
                                                                     # every pixel set to white
epd.set_pixel(5, 5, IL0373Full.BLACK)                               # Set one pixel, (x, y, color=BLACK)
                                                                     # clears the bit for that pixel
epd.draw_rect(20, 20, 60, 40, IL0373Full.BLACK, True)              # Draw rectangle, (x, y, w, h, color=BLACK, fill=False)
                                                                     # filled black block
epd.draw_line(0, 0, 103, 211, IL0373Full.BLACK)                     # Draw line, (x0, y0, x1, y1, color=BLACK)
                                                                     # Bresenham diagonal
epd.blit(70, 20, b'\xff\x81\x81\xff', 8, 4)                         # Copy 1-bpp bitmap, (x, y, data, w, h, color=BLACK)
                                                                     # 8 x 4 box, MSB first
colour = epd.get_pixel(30, 30)                                       # Read framebuffer pixel, (x, y) → int colour
                                                                     # BLACK here
epd.set_flip(False, False)                                           # Mirror output, (horizontal, vertical)
                                                                     # PSR.SHL / UD at power-on values
epd.set_power(14.0, 14.0, 20.0)                                      # Set source/gate power, (vdh V, vdl V, vgh=20.0 V)
                                                                     # PWR: +-14 V sources, +-20 V gates (the power-on values)
epd.set_frame_rate(50)                                               # Set frame rate, (hz 4–200)
                                                                     # nearest PLL M/N entry (M7 N4)
epd.set_soft_start(0x17, 0x17, 0x17)                                 # Set booster soft start, (a, b, c)
                                                                     # vendor BTST phases
epd.set_interval(7)                                                  # Set VCOM/data interval, (cdi 0–15, border=2, polarity=1)
                                                                     # keeps DDX = 01 so the framebuffer mapping holds
epd.set_temperature_sensor(False, 0)                                 # Select temperature sensor, (external, offset -8..7 °C)
                                                                     # internal sensor, no offset
status = epd.read_status()                                           # Read FLG, () → int bitfield
                                                                     # needs DOUT wired (half-duplex)
temperature = epd.read_temperature()                                 # Read sensor, () → int °C
                                                                     # signed internal temperature
revision = epd.read_revision()                                       # Read REV, () → int LUT_REV
                                                                     # LUT revision byte
epd.show()                                                           # Refresh panel, () → None
                                                                     # OLD/NEW planes, DRF, border floating, POF
epd.draw_rect(16, 100, 64, 40, IL0373Full.BLACK, True)              # Draw rectangle, (x, y, w, h, color=BLACK, fill=False)
epd.show_region(16, 100, 64, 40)                                     # Refresh a window, (x, y, w, h)
                                                                     # sends only the window's rows (RAM saving)
ready = epd.wait_busy(1000)                                          # Wait for BUSY high, (timeout_ms) → bool
                                                                     # True when the controller is idle
epd.send_command(0x50, b'\x97')                                      # Send raw command, (command, data=b'')
                                                                     # rewrites CDI with the initialisation value
epd.sleep()                                                          # Enter deep sleep, () → None
epd.wake()                                                           # Reset and re-initialise, () → None
                                                                     # framebuffer is kept
print('===DONE: 0 passed, 0 failed===')
