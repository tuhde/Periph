# SSD1306 complete example: 0.96" 128 x 64 OLED module (SSD1306) over I2C (MicroPython, Raspberry Pi Pico; also CircuitPython / Linux via i2c_auto).
# Wiring: SDA=GP4, SCL=GP5 (I2C0), VCC = 3V3 (the module generates the OLED supply with the SSD1306 charge pump); address 0x3C (SA0 = low) or 0x3D (SA0 = high). Complete: every Full method - drawing, contrast, invert, flip, scroll, drive tuning, reads, sleep.
import time
from periph.connection.i2c_auto import I2CConnection
from periph.chips.display.ssd1306 import SSD1306Full

connection = I2CConnection(0x3C)                         # Create I2C connection, (addr=0x3C)
lcd = SSD1306Full(connection)                             # Create SSD1306 driver, (connection, width=128, height=64, external_vcc=False)

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
                                                         # segment drive current; reset value is 0x7F
time.sleep(1)
lcd.set_contrast(0x7F)                                   # Set contrast, (value 0..255)
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
lcd.set_clock(1, 8)                                      # Set clock, (divide 1..16, osc 0..15)
                                                         # divide ratio 1 at the reset oscillator setting
lcd.set_precharge(2, 2)                                  # Set pre-charge, (phase1 1..15, phase2 1..15)
                                                         # pre-charge and discharge periods in display clocks
lcd.set_com_config(True)                                 # Set COM pin configuration, (alternative, remap=False)
                                                         # alternative is the reset value and right for 128 x 64 modules
lcd.set_vcomh(0x20)                                      # Set VCOMH deselect level, (value 0x00..0x70)
                                                         # 0x20 (about 0.77 x VCC) is the reset value
lcd.set_charge_pump(True)                                # Enable the charge pump, (enabled)
                                                         # 0x8D 0x14; takes effect with the display on

# Addressing modes.
lcd.set_addressing_mode('horizontal')                    # Select addressing mode, (mode 'page'|'horizontal'|'vertical')
                                                         # show() then streams the frame in one transfer
lcd.show()                                               # Update display, ()
lcd.set_addressing_mode('page')                          # Select addressing mode, (mode 'page'|'horizontal'|'vertical')

# Hardware scrolling (RAM access is refused while a scroll is active).
lcd.set_scroll_horizontal(True, 0, 7, 7)                 # Set horizontal scroll, (right, start_page, end_page, interval 0..7)
                                                         # interval index 7 = 2 frames per step
lcd.scroll_start()                                       # Start scrolling, ()
time.sleep(2)
lcd.scroll_stop()                                        # Stop scrolling, ()
                                                         # deactivates and rewrites the RAM from the framebuffer
lcd.set_scroll_area(0, 64)                               # Set vertical scroll area, (top_fixed 0..63, scroll_rows 1..64)
lcd.set_scroll_vertical(True, 0, 7, 7, 1)                # Set diagonal scroll, (right, start_page, end_page, interval 0..7, rows 1..63)
lcd.scroll_start()                                       # Start scrolling, ()
time.sleep(2)
lcd.scroll_stop()                                        # Stop scrolling, ()

# Raw commands.
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
