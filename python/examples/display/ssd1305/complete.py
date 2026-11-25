# SSD1305 complete example: 1.3" 128 x 64 OLED module (SSD1305, RAM columns 4..131) over I2C (MicroPython, Raspberry Pi Pico; also CircuitPython / Linux via i2c_auto).
# Wiring: SDA=GP4, SCL=GP5 (I2C0), VCC = 3V3 (OLED supply VCC 7..15 V from the module; the chip has no charge pump); address 0x3C (SA0 = low) or 0x3D (SA0 = high). Complete: every Full method - drawing, contrast, invert, flip, scroll, drive tuning, reads, sleep.
import time
from periph.connection.i2c_auto import I2CConnection
from periph.chips.display.ssd1305 import SSD1305Full

connection = I2CConnection(0x3C)                         # Create I2C connection, (addr=0x3C)
lcd = SSD1305Full(connection)                             # Create SSD1305 driver, (connection, width=128, height=64, column_offset=4)

# Framebuffer drawing: everything is composed in RAM and sent with show().
lcd.clear()                                              # Clear framebuffer, ()
                                                         # zeroes every pixel of the framebuffer; the panel is unchanged until show()
lcd.fill(True)                                           # Fill framebuffer, (on=True)
                                                         # sets (or clears) every pixel
lcd.fill(False)                                          # Fill framebuffer, (on=True)
lcd.draw_rect(0, 0, 128, 64)                             # Draw rectangle, (x, y, w, h, on=True, fill=False)
                                                         # one-pixel outline around the whole panel
lcd.draw_line(0, 0, 127, 63)                             # Draw line, (x0, y0, x1, y1, on=True)
                                                         # Bresenham line into the framebuffer
lcd.draw_line(0, 63, 127, 0)                             # Draw line, (x0, y0, x1, y1, on=True)
lcd.draw_rect(10, 10, 30, 20, True, True)                # Draw rectangle, (x, y, w, h, on=True, fill=False)
                                                         # filled rectangle
lcd.draw_rect(18, 16, 14, 8, False, True)                # Draw rectangle, (x, y, w, h, on=True, fill=False)
                                                         # on=False clears pixels: a hole in the filled rectangle
lcd.set_pixel(64, 32, True)                              # Set pixel, (x, y, on=True)
lit = lcd.get_pixel(64, 32)                              # Read framebuffer pixel, (x, y) → bool
                                                         # reads the framebuffer, not the panel
icon = bytes((0x3C, 0x42, 0x81, 0xA5, 0x81, 0xA5, 0x99, 0x42, 0x3C, 0x00, 0x7E, 0x81, 0x81, 0x81, 0x7E, 0x00))
lcd.blit(100, 16, icon, 2)                               # Copy bitmap, (x, y multiple of 8, columns, height_pages=1)
                                                         # two page rows of 8 columns, bit 0 = top pixel
lcd.show()                                               # Send framebuffer to the display, ()
                                                         # per page: page + column command, then 128 data bytes in 31-byte chunks
time.sleep(2)
lcd.show_page(2)                                         # Send one framebuffer page, (page 0..7)
                                                         # cheaper partial update of 8 rows

# Contrast and display modes.
lcd.set_contrast(0x20)                                   # Set contrast, (value 0..255)
                                                         # segment drive current; reset value is 0x80
time.sleep(1)
lcd.set_contrast(0x80)                                   # Set contrast, (value 0..255)
lcd.set_invert(True)                                     # Invert display, (on)
                                                         # RAM 0 = pixel on; RAM is unchanged
time.sleep(1)
lcd.set_invert(False)                                    # Invert display, (on)
lcd.set_all_on(True)                                     # Force all pixels on, (on)
                                                         # test mode, RAM is kept
time.sleep(1)
lcd.set_all_on(False)                                    # Force all pixels on, (on)
lcd.set_flip(True, True)                                 # Mirror display, (horizontal, vertical)
                                                         # segment re-map and COM scan direction; framebuffer unchanged
time.sleep(1.5)
lcd.set_flip(False, False)                               # Mirror display, (horizontal, vertical)

# Hardware vertical scroll and drive tuning.
for line in range(0, 64, 2):
    lcd.set_start_line(line)                             # Scroll the image up, (line 0..63)
                                                         # RAM line shown at COM0; the image wraps around
    time.sleep(0.03)
lcd.set_start_line(0)                                    # Scroll the image up, (line 0..63)
lcd.set_display_offset(0)                                # Set COM offset, (offset 0..63)
                                                         # shifts the COM mapping
lcd.set_multiplex(64)                                    # Set multiplex ratio, (ratio 16..64)
                                                         # number of driven rows
lcd.set_clock(1, 7)                                      # Set clock, (divide 1..16, osc 0..15)
                                                         # divide ratio 1 at the reset oscillator setting
lcd.set_precharge(2, 2)                                  # Set pre-charge, (phase1 1..15, phase2 1..15)
                                                         # pre-charge and discharge periods in display clocks
lcd.set_com_config(True)                                 # Set COM pin configuration, (alternative, remap=False)
                                                         # alternative is the reset value and right for 128 x 64 modules
lcd.set_vcomh(0x34)                                      # Set VCOMH deselect level, (value 0x00..0x3C)
                                                         # 0x34 (about 0.77 x VCC) is the reset value
lcd.set_dim_mode(0x40, 0x40)                             # Configure dim levels, (contrast 0..255, brightness 0..255)
                                                         # used while dim mode is on
lcd.set_dim(True)                                        # Dim mode, (on)
                                                         # 0xAC display on in dim mode
time.sleep(1)
lcd.set_dim(False)                                       # Dim mode, (on)
lcd.set_brightness(0x80)                                 # Set colour-bank brightness, (value 0..255)
                                                         # no visible effect in monochrome mode
lcd.set_area_color(False)                                # Set area-colour mode, (enable, low_power=False)
                                                         # keep disabled on monochrome panels
lcd.set_lut(49, 63, 63, 63)                              # Set pulse widths, (bank0, a, b, c each 31..63)
                                                         # reset values
lcd.set_bank_colors(bytes(4), bytes(4))                  # Assign bank colours, (banks_1_16 4 bytes, banks_17_32 4 bytes)
                                                         # all banks colour A
lcd.set_addressing_mode('horizontal')                    # Select addressing mode, (mode 'page'|'horizontal'|'vertical')
                                                         # show() then streams the frame in one transfer
lcd.show()                                               # Update display, ()
lcd.set_addressing_mode('page')                          # Select addressing mode, (mode 'page'|'horizontal'|'vertical')

# Hardware scrolling.
lcd.set_scroll_horizontal(True, 0, 7, 0, 1)              # Set horizontal scroll, (right, start_page, end_page, interval 0..6, columns=1)
                                                         # interval index 0 = 6 frames per step
lcd.scroll_start()                                       # Start scrolling, ()
time.sleep(2)
lcd.scroll_stop()                                        # Stop scrolling, ()
                                                         # deactivates and rewrites the RAM from the framebuffer
lcd.set_scroll_area(0, 64)                               # Set vertical scroll area, (top_fixed 0..63, scroll_rows 1..64)
lcd.set_scroll_vertical(True, 0, 7, 0, 1, 1)             # Set vertical+horizontal scroll, (right, start_page, end_page, interval, columns, rows 1..63)
lcd.scroll_start()                                       # Start scrolling, ()
time.sleep(2)
lcd.scroll_stop()                                        # Stop scrolling, ()

# Reads (I2C only) and raw commands.
status = lcd.read_status()                               # Read status, () → int
                                                         # bit 6 = 1 when the display is off
ram = lcd.read_page(0, 2, 8)                             # Read display RAM, (page 0..7, column, n) → bytes
                                                         # sets the address and discards the dummy byte
lcd.send_command(0xE3)                                   # Send raw command stream, (*bytes)
                                                         # NOP

# Power.
lcd.set_display(False)                                   # Display on/off, (on)
                                                         # RAM and settings are kept
time.sleep(1)
lcd.set_display(True)                                    # Display on/off, (on)
lcd.sleep()                                              # Enter sleep, ()
                                                         # display off; switch VCC off afterwards
time.sleep(1)
lcd.wake()                                               # Leave sleep, ()
                                                         # display on and a 100 ms wait (tAF)
