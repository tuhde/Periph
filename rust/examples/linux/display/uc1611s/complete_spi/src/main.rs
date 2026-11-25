//! UC1611s complete example (4-wire SPI): EA DOGXL240-7 (240 x 128, 3.3 V only) on a Raspberry Pi (Linux host).
//!
//! Wiring: SI=GPIO10 (SPI0 MOSI), SCK=GPIO11, CS0=GPIO8 (CE0), CD=GPIO25, RST=GPIO17; VDD=3V3; module pins BM0 = D13 = VSS (4-wire SPI).
//! Capacitors: 4 x 4.7 uF/5 V (VA0, VA1, VB0, VB1 pairs), 470 nF/25 V on VLCD. Complete: every Full method - drawing, contrast, invert, flip, scroll, drive tuning, partial display, RAM screens, sleep, raw access, reset (SPI has no read-back).
use embedded_hal_bus::spi::ExclusiveDevice;
use gpio_cdev::{Chip, LineRequestFlags};
use linux_embedded_hal::{CdevPin, Delay, SpidevBus};
use periph::chips::display::uc1611s::Uc1611sSpiFull;
use periph::connection::display::{DisplayConnection, NoPin, WireMode};
use spidev::{SpiModeFlags, Spidev, SpidevOptions};
use std::{thread::sleep, time::Duration};

// Dummy CS: spidev manages CS# in the kernel.
struct NullCs;
impl embedded_hal::digital::ErrorType for NullCs {
    type Error = core::convert::Infallible;
}
impl embedded_hal::digital::OutputPin for NullCs {
    fn set_low(&mut self) -> Result<(), Self::Error> { Ok(()) }
    fn set_high(&mut self) -> Result<(), Self::Error> { Ok(()) }
}

fn main() {
    let mut chip = Chip::new("/dev/gpiochip0").expect("open gpio chip");
    let dc = CdevPin::new(chip.get_line(25).unwrap().request(LineRequestFlags::OUTPUT, 0, "uc1611s").unwrap()).unwrap(); // Create CD (D/C) pin, (line=25) → CdevPin
    let res = CdevPin::new(chip.get_line(17).unwrap().request(LineRequestFlags::OUTPUT, 1, "uc1611s").unwrap()).unwrap(); // Create RST pin, (line=17) → CdevPin
    let mut spi = Spidev::open("/dev/spidev0.0").expect("open spidev");
    spi.configure(&SpidevOptions::new().max_speed_hz(4_000_000).mode(SpiModeFlags::SPI_MODE_0).build()).unwrap();
    let device = ExclusiveDevice::new_no_delay(SpidevBus(spi), NullCs).unwrap();
    let connection = DisplayConnection::new(device, dc, Some(res), None::<NoPin>, Delay, WireMode::Simplex).unwrap(); // Create display connection, (spi, dc, res, busy=None, delay, wire_mode)

    let mut lcd = Uc1611sSpiFull::new_spi(connection, 240, 128, false, 0x8F).expect("init UC1611s"); // Create UC1611s driver, (connection, width=240, height=128, top_view=false, contrast=0x8F)

    // Framebuffer drawing: everything is composed in RAM and sent with show().
    lcd.clear();                                                              // Clear framebuffer, ()
                                                                              // zeroes every pixel of the framebuffer; the panel is unchanged until show()
    lcd.fill(true);                                                           // Fill framebuffer, (on)
                                                                              // sets (or clears) every pixel
    lcd.fill(false);                                                          // Fill framebuffer, (on)
    lcd.draw_rect(0, 0, 240, 128, true, false);                               // Draw rectangle, (x, y, w, h, on, fill)
                                                                              // one-pixel outline around the whole panel
    lcd.draw_line(0, 0, 239, 127, true);                                      // Draw line, (x0, y0, x1, y1, on)
                                                                              // Bresenham line into the framebuffer
    lcd.draw_line(0, 127, 239, 0, true);                                      // Draw line, (x0, y0, x1, y1, on)
    lcd.draw_rect(20, 20, 40, 30, true, true);                                // Draw rectangle, (x, y, w, h, on, fill)
                                                                              // filled rectangle
    lcd.draw_rect(30, 28, 20, 14, false, true);                               // Draw rectangle, (x, y, w, h, on, fill)
                                                                              // on=false clears pixels: a hole in the filled rectangle
    lcd.set_pixel(120, 64, true);                                             // Set pixel, (x, y, on)
    let lit = lcd.get_pixel(120, 64);                                         // Read framebuffer pixel, (x, y) → bool
                                                                              // reads the framebuffer, not the panel
    let icon = [0x3C, 0x42, 0x81, 0xA5, 0x81, 0xA5, 0x99, 0x42, 0x3C, 0x00, 0x7E, 0x81, 0x81, 0x81, 0x7E, 0x00];
    lcd.blit(180, 16, &icon, 2).expect("blit");                               // Copy bitmap, (x, y multiple of 8, columns, height_pages) → ()
                                                                              // two page rows of 8 columns, bit 0 = top pixel
    lcd.show().expect("show");                                                // Send framebuffer to the display, () → ()
                                                                              // per page: page + column address, then 240 data bytes in one transfer
    sleep(Duration::from_millis(2000));
    lcd.show_page(2).expect("show_page");                                     // Send one framebuffer page, (page 0..15) → ()
                                                                              // cheaper partial update of 8 rows
    
    // Contrast and display modes.
    lcd.set_contrast(0x70).expect("set_contrast");                            // Set contrast, (value 0..255) → ()
                                                                              // writes the potentiometer PM; lower = lighter
    sleep(Duration::from_millis(800));
    lcd.set_contrast(0x8F).expect("set_contrast");                            // Set contrast, (value 0..255) → ()
                                                                              // 0x8F is the DOG example value
    lcd.set_invert(true).expect("set_invert");                                // Invert display, (on) → ()
                                                                              // bit-wise inversion of what is displayed; RAM unchanged
    sleep(Duration::from_millis(800));
    lcd.set_invert(false).expect("set_invert");                               // Invert display, (on) → ()
    lcd.set_all_on(true).expect("set_all_on");                                // Light all pixels, (on) → ()
                                                                              // test mode, RAM unchanged
    sleep(Duration::from_millis(800));
    lcd.set_all_on(false).expect("set_all_on");                               // Light all pixels, (on) → ()
    lcd.set_flip(false, true).expect("set_flip");                             // Mirror display, (mirror_x, mirror_y) → ()
                                                                              // MY = 1: rows mirrored at once; MX is applied to RAM writes
    sleep(Duration::from_millis(800));
    lcd.set_flip(true, false).expect("set_flip");                             // Mirror display, (mirror_x, mirror_y) → ()
                                                                              // back to the bottom-view default
    lcd.show().expect("show");                                                // Send framebuffer to the display, () → ()
    
    // Hardware scroll with a fixed header.
    lcd.set_fixed_lines(2).expect("set_fixed_lines");                         // Set fixed lines, (lines 0..15) → ()
                                                                              // the top 2 x 2 = 4 rows stay put
    for line in (0..=32).step_by(4) {
        lcd.set_scroll_line(line).expect("set_scroll_line");                  // Scroll the image up, (line 0..159 - 2 * fixed) → ()
                                                                              // rows enter from the bottom as the image moves up
        sleep(Duration::from_millis(120));
    }
    lcd.set_scroll_line(0).expect("set_scroll_line");                         // Scroll the image up, (line 0..159 - 2 * fixed) → ()
    lcd.set_fixed_lines(0).expect("set_fixed_lines");                         // Set fixed lines, (lines 0..15) → ()
    
    // LCD drive tuning (the DOG defaults are already sent at init).
    lcd.set_bias_ratio(2).expect("set_bias_ratio");                           // Set bias ratio, (ratio 0..3) → ()
                                                                              // 0 = 1/5, 1 = 1/10, 2 = 1/11 (reset), 3 = 1/12
    lcd.set_temperature_compensation(1).expect("set_temperature_compensation");  // Set temperature compensation, (curve 0..3) → ()
                                                                              // 1 = -0.10 %/degC, the DOG example value
    lcd.set_line_rate(3).expect("set_line_rate");                             // Set line rate, (rate 0..3) → ()
                                                                              // 3 = 9.4 kilo-lines per second in On/Off mode
    lcd.set_pump(true).expect("set_pump");                                    // Select VLCD source, (internal) → ()
                                                                              // internal 11x charge pump
    lcd.set_panel_loading(true).expect("set_panel_loading");                  // Set panel loading, (high) → ()
                                                                              // 33..55 nF panel capacitance
    lcd.set_nline_inversion(0, false).expect("set_nline_inversion");          // Set N-line inversion, (lines 0..63, xor) → ()
                                                                              // 0 = off
    lcd.set_isolation_clock(1, 0).expect("set_isolation_clock");              // Set isolation clock, (front 0..15, back 0..15) → ()
                                                                              // the reset values
    
    // Partial display: scan only the middle 64 rows.
    lcd.set_partial_display(true, 32, Some(95)).expect("set_partial_display");  // Set partial display, (on, start, end) → ()
                                                                              // rows outside 32..95 are not scanned and save power
    sleep(Duration::from_millis(1500));
    lcd.set_partial_display(false, 0, None).expect("set_partial_display");    // Set partial display, (on, start, end) → ()
                                                                              // back to the full panel
    
    // Four RAM screens: draw into screen 1, then switch to it.
    lcd.set_write_pattern(1);                                                 // Select RAM screen to write, (pattern 0..3)
                                                                              // show() now writes RAM screen 1
    lcd.fill(false);                                                          // Fill framebuffer, (on)
    lcd.draw_rect(40, 30, 160, 68, true, true);                               // Draw rectangle, (x, y, w, h, on, fill)
    lcd.show().expect("show");                                                // Send framebuffer to the display, () → ()
                                                                              // screen 0 is still displayed
    lcd.set_display_pattern(1).expect("set_display_pattern");                 // Select displayed RAM screen, (pattern 0..3) → ()
                                                                              // the display now shows RAM screen 1
    sleep(Duration::from_millis(1500));
    lcd.set_display_pattern(0).expect("set_display_pattern");                 // Select displayed RAM screen, (pattern 0..3) → ()
    lcd.set_write_pattern(0);                                                 // Select RAM screen to write, (pattern 0..3)
    
    // Sleep, raw access, reset.
    lcd.sleep().expect("sleep");                                              // Enter sleep, () → ()
                                                                              // drivers, pump and clock stop; registers and RAM are kept
    sleep(Duration::from_millis(1000));
    lcd.wake().expect("wake");                                                // Leave sleep, () → ()
                                                                              // polls Status1.DE while the chip restores VLCD
    lcd.send_command(&[0xE3]).expect("send_command");                         // Send raw command bytes, (bytes) → ()
                                                                              // 0xE3 = NOP
    lcd.send_data(&[0u8; 8]).expect("send_data");                             // Send raw RAM data, (bytes) → ()
                                                                              // written at the current RAM address
    lcd.reset().expect("reset");                                              // System Reset and reconfigure, () → ()
                                                                              // registers back to the DOG settings with the cached contrast and mapping; RAM is kept
    let _ = lit;
    println!("done");
}
