# SSD1331 complete example: every Full method on a Waveshare 0.95inch RGB OLED (MicroPython, Raspberry Pi Pico).
# Wiring: DIN=GP19 (SPI0 MOSI), CLK=GP18 (SPI0 SCK), CS=GP17, DC=GP20, RST=GP21 (no BUSY line).
import time
from machine import SPI
from periph.connection.display_micropython import DisplayConnection
from periph.connection.output_pin import MicroPythonOutputPin
from periph.chips.display.ssd1331 import SSD1331Full

spi = SPI(0, baudrate=4_000_000, polarity=0, phase=0)               # Create SPI bus, (id=0, baud=4 MHz, mode=0)
cs = MicroPythonOutputPin(17)                                        # Create CS# pin, (pin=17) → OutputPin
dc = MicroPythonOutputPin(20)                                        # Create D/C# pin, (pin=20) → OutputPin
res = MicroPythonOutputPin(21)                                       # Create RES# pin, (pin=21) → OutputPin
connection = DisplayConnection(spi, cs, dc, res, None)               # Create display connection, (spi, cs, dc, res, busy=None)

oled = SSD1331Full(connection)                                       # Create SSD1331 driver, (connection)

oled.clear(oled.BLACK)                                               # Fill framebuffer, (color=BLACK)
                                                                     # sets every pixel; the display is unchanged until show()
oled.draw_rect(0, 0, 96, 64, oled.WHITE)                             # Rectangle outline, (x, y, w, h, color=WHITE, fill=False)
                                                                     # outline into the framebuffer
oled.draw_rect(4, 4, 30, 20, oled.RED, True)                         # Filled rectangle, (x, y, w, h, color=WHITE, fill=False)
oled.draw_line(0, 0, 95, 63, oled.GREEN)                             # Software line, (x0, y0, x1, y1, color=WHITE)
                                                                     # Bresenham diagonal
oled.draw_line(0, 63, 95, 0, oled.CYAN)                              # Software line, (x0, y0, x1, y1, color=WHITE)
sprite = [oled.YELLOW if ((i // 8) + (i % 8)) & 1 else oled.MAGENTA for i in range(64)]
oled.blit(60, 8, sprite, 8, 8)                                       # Copy RGB565 bitmap, (x, y, pixels, w, h)
                                                                     # checkerboard into the framebuffer
oled.set_pixel(48, 32, oled.WHITE)                                   # Set one pixel, (x, y, color=WHITE)
print(hex(oled.get_pixel(48, 32)))                                   # Read framebuffer pixel, (x, y) → int RGB565
oled.show()                                                          # Send framebuffer, () → None
                                                                     # window 0..95 x 0..63 then 12288 bytes
time.sleep(2)

oled.clear(oled.BLUE)                                                # Fill framebuffer, (color=BLACK)
oled.draw_rect(20, 16, 56, 32, oled.WHITE, True)                     # Filled rectangle, (x, y, w, h, color=WHITE, fill=False)
oled.show_region(16, 12, 64, 40)                                     # Send region, (x, y, w, h)
                                                                     # only the rows of the window are transferred
time.sleep(2)

oled.hw_clear(0, 0, 95, 63)                                          # Clear window in display RAM, (x0, y0, x1, y1)
                                                                     # chip clears its own RAM, framebuffer untouched
oled.hw_rect(10, 10, 76, 44, oled.YELLOW, oled.RED, True)            # Hardware rectangle, (x, y, w, h, line_color, fill_color=0, fill=False)
                                                                     # outline and fill drawn by the chip
oled.hw_line(0, 0, 95, 63, oled.WHITE)                               # Hardware line, (x0, y0, x1, y1, color)
time.sleep(2)
oled.hw_copy(10, 10, 40, 40, 50, 20)                                 # Hardware copy, (x0, y0, x1, y1, x, y)
time.sleep(2)
oled.hw_dim(0, 0, 47, 63)                                            # Hardware dim window, (x0, y0, x1, y1)
time.sleep(2)

oled.set_display_mode(oled.MODE_INVERSE)                             # Display mode, (mode)
time.sleep(1.5)
oled.set_display_mode(oled.MODE_ALL_ON)                              # Display mode, (mode)
time.sleep(1)
oled.set_display_mode(oled.MODE_ALL_OFF)                             # Display mode, (mode)
time.sleep(1)
oled.set_display_mode(oled.MODE_NORMAL)                              # Display mode, (mode)

oled.set_contrast(0x30, 0x30, 0x30)                                  # Channel contrast, (a, b, c) 0..255
time.sleep(1)
oled.set_contrast(0xFF, 0xFF, 0xFF)                                  # Channel contrast, (a, b, c) 0..255
oled.set_master_current(4)                                           # Master current, (n 1..16) n/16
time.sleep(1)
oled.set_master_current(7)                                           # Master current, (n 1..16) n/16

oled.set_flip(False, True)                                           # Mirror, (horizontal, vertical)
oled.show()                                                          # Send framebuffer, () → None
time.sleep(1.5)
oled.set_flip(True, True)                                            # Mirror, (horizontal, vertical)
oled.show()                                                          # Send framebuffer, () → None
time.sleep(1.5)

oled.scroll(1, 0, 64, 0, 0)                                          # Scroll, (horizontal, start_row, rows, vertical, interval 0..3)
                                                                     # continuous scroll one column per 6 frames
time.sleep(4)
oled.scroll_stop()                                                   # Stop scrolling, ()

oled.dim_mode(0x10, 0x10, 0x10, 0x10)                                # Dim mode, (a, b, c, precharge 0..31)
time.sleep(1.5)
oled.set_power_save(True)                                            # Power save, (on)
oled.display_off()                                                   # Display off, ()
