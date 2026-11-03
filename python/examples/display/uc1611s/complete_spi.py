# UC1611s complete example (4-wire SPI): EA DOGXL240-7 (240 x 128, 3.3 V only) on a Raspberry Pi Pico (MicroPython).
# Wiring: SI=GP19 (SPI0 MOSI), SCK=GP18 (SPI0 SCK), CS0=GP17, CD=GP20, RST=GP21; VDD=3V3; module pins BM0 = D13 = VSS (4-wire SPI).
# Capacitors: 4 x 4.7 uF/5 V (VA0, VA1, VB0, VB1 pairs), 470 nF/25 V on VLCD. Complete: every Full method - drawing, contrast, invert, flip, scroll, drive tuning, partial display, RAM screens, sleep, raw access, reset (SPI has no read-back).
from machine import SPI
from periph.connection.display_micropython import DisplayConnection
from periph.connection.output_pin import MicroPythonOutputPin
from periph.chips.display.uc1611s import UC1611sSpiFull

spi = SPI(0, baudrate=4_000_000, polarity=0, phase=0)               # Create SPI bus, (id=0, baud=4 MHz, mode=0)
cs = MicroPythonOutputPin(17)                                        # Create CS0 pin, (pin=17) → OutputPin
dc = MicroPythonOutputPin(20)                                        # Create CD (D/C) pin, (pin=20) → OutputPin
res = MicroPythonOutputPin(21)                                       # Create RST pin, (pin=21) → OutputPin
connection = DisplayConnection(spi, cs, dc, res, None)               # Create display connection, (spi, cs, dc, res, busy=None)

lcd = UC1611sSpiFull(connection)                                # Create UC1611s driver, (connection, width=240, height=128, top_view=False, contrast=0x8F)

# Framebuffer drawing: everything is composed in RAM and sent with show().
lcd.clear()                                                     # Clear framebuffer, ()
                                                                # zeroes every pixel of the framebuffer; the panel is unchanged until show()
lcd.fill(True)                                                  # Fill framebuffer, (on=True)
                                                                # sets (or clears) every pixel
lcd.fill(False)                                                 # Fill framebuffer, (on=True)
lcd.draw_rect(0, 0, 240, 128)                                   # Draw rectangle, (x, y, w, h, on=True, fill=False)
                                                                # one-pixel outline around the whole panel
lcd.draw_line(0, 0, 239, 127)                                   # Draw line, (x0, y0, x1, y1, on=True)
                                                                # Bresenham line into the framebuffer
lcd.draw_line(0, 127, 239, 0)                                   # Draw line, (x0, y0, x1, y1, on=True)
lcd.draw_rect(20, 20, 40, 30, True, True)                       # Draw rectangle, (x, y, w, h, on=True, fill=False)
                                                                # filled rectangle
lcd.draw_rect(30, 28, 20, 14, False, True)                      # Draw rectangle, (x, y, w, h, on=True, fill=False)
                                                                # on=False clears pixels: a hole in the filled rectangle
lcd.set_pixel(120, 64, True)                                    # Set pixel, (x, y, on=True)
lit = lcd.get_pixel(120, 64)                                    # Read framebuffer pixel, (x, y) → bool
                                                                # reads the framebuffer, not the panel
icon = bytes((0x3C, 0x42, 0x81, 0xA5, 0x81, 0xA5, 0x99, 0x42, 0x3C, 0x00, 0x7E, 0x81, 0x81, 0x81, 0x7E, 0x00))
lcd.blit(180, 16, icon, 2)                                      # Copy bitmap, (x, y multiple of 8, columns, height_pages=1)
                                                                # two page rows of 8 columns, bit 0 = top pixel
lcd.show()                                                      # Send framebuffer to the display, ()
                                                                # per page: page + column address, then 240 data bytes in one transfer
time.sleep(2)
lcd.show_page(2)                                                # Send one framebuffer page, (page 0..15)
                                                                # cheaper partial update of 8 rows

# Contrast and display modes.
lcd.set_contrast(0x70)                                          # Set contrast, (value 0..255)
                                                                # writes the potentiometer PM; lower = lighter
time.sleep(0.8)
lcd.set_contrast(0x8F)                                          # Set contrast, (value 0..255)
                                                                # 0x8F is the DOG example value
lcd.set_invert(True)                                            # Invert display, (on)
                                                                # bit-wise inversion of what is displayed; RAM unchanged
time.sleep(0.8)
lcd.set_invert(False)                                           # Invert display, (on)
lcd.set_all_on(True)                                            # Light all pixels, (on)
                                                                # test mode, RAM unchanged
time.sleep(0.8)
lcd.set_all_on(False)                                           # Light all pixels, (on)
lcd.set_flip(False, True)                                       # Mirror display, (mirror_x, mirror_y)
                                                                # MY = 1: rows mirrored at once; MX is applied to RAM writes
time.sleep(0.8)
lcd.set_flip(True, False)                                       # Mirror display, (mirror_x, mirror_y)
                                                                # back to the bottom-view default
lcd.show()                                                      # Send framebuffer to the display, ()

# Hardware scroll with a fixed header.
lcd.set_fixed_lines(2)                                          # Set fixed lines, (lines 0..15)
                                                                # the top 2 x 2 = 4 rows stay put
for line in range(0, 33, 4):
    lcd.set_scroll_line(line)                                   # Scroll the image up, (line 0..159 - 2 * fixed)
                                                                # rows enter from the bottom as the image moves up
    time.sleep(0.12)
lcd.set_scroll_line(0)                                          # Scroll the image up, (line 0..159 - 2 * fixed)
lcd.set_fixed_lines(0)                                          # Set fixed lines, (lines 0..15)

# LCD drive tuning (the DOG defaults are already sent at init).
lcd.set_bias_ratio(2)                                           # Set bias ratio, (ratio 0..3)
                                                                # 0 = 1/5, 1 = 1/10, 2 = 1/11 (reset), 3 = 1/12
lcd.set_temperature_compensation(1)                             # Set temperature compensation, (curve 0..3)
                                                                # 1 = -0.10 %/degC, the DOG example value
lcd.set_line_rate(3)                                            # Set line rate, (rate 0..3)
                                                                # 3 = 9.4 kilo-lines per second in On/Off mode
lcd.set_pump(True)                                              # Select VLCD source, (internal=True)
                                                                # internal 11x charge pump
lcd.set_panel_loading(True)                                     # Set panel loading, (high=True)
                                                                # 33..55 nF panel capacitance
lcd.set_nline_inversion(0, False)                               # Set N-line inversion, (lines 0..63, xor=False)
                                                                # 0 = off
lcd.set_isolation_clock(1, 0)                                   # Set isolation clock, (front 0..15, back 0..15)
                                                                # the reset values

# Partial display: scan only the middle 64 rows.
lcd.set_partial_display(True, 32, 95)                           # Set partial display, (on, start=0, end=height-1)
                                                                # rows outside 32..95 are not scanned and save power
time.sleep(1.5)
lcd.set_partial_display(False)                                  # Set partial display, (on, start=0, end=height-1)
                                                                # back to the full panel

# Four RAM screens: draw into screen 1, then switch to it.
lcd.set_write_pattern(1)                                        # Select RAM screen to write, (pattern 0..3)
                                                                # show() now writes RAM screen 1
lcd.fill(False)                                                 # Fill framebuffer, (on=True)
lcd.draw_rect(40, 30, 160, 68, True, True)                      # Draw rectangle, (x, y, w, h, on=True, fill=False)
lcd.show()                                                      # Send framebuffer to the display, ()
                                                                # screen 0 is still displayed
lcd.set_display_pattern(1)                                      # Select displayed RAM screen, (pattern 0..3)
                                                                # the display now shows RAM screen 1
time.sleep(1.5)
lcd.set_display_pattern(0)                                      # Select displayed RAM screen, (pattern 0..3)
lcd.set_write_pattern(0)                                        # Select RAM screen to write, (pattern 0..3)

# Sleep, raw access, reset.
lcd.sleep()                                                     # Enter sleep, ()
                                                                # drivers, pump and clock stop; registers and RAM are kept
time.sleep(1)
lcd.wake()                                                      # Leave sleep, ()
                                                                # polls Status1.DE while the chip restores VLCD
lcd.send_command(0xE3)                                          # Send raw command bytes, (*bytes)
                                                                # 0xE3 = NOP
lcd.send_data(bytes(8))                                         # Send raw RAM data, (bytes)
                                                                # written at the current RAM address
lcd.reset()                                                     # System Reset and reconfigure, ()
                                                                # registers back to the DOG settings with the cached contrast and mapping; RAM is kept
print("done")
