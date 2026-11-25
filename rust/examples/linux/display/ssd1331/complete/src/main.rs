//! SSD1331 complete example: every Full method on a Waveshare 0.95inch RGB OLED (Linux host).
//!
//! Wiring: DIN=GPIO10 (SPI0 MOSI), CLK=GPIO11, CS=GPIO8 (CE0), DC=GPIO25, RST=GPIO17 (no BUSY line).
use embedded_hal_bus::spi::ExclusiveDevice;
use gpio_cdev::{Chip, LineRequestFlags};
use linux_embedded_hal::{CdevPin, Delay, SpidevBus};
use periph::chips::display::ssd1331::{Ssd1331Full, DisplayMode, BUFFER_SIZE, BLACK, BLUE, CYAN, GREEN, MAGENTA, RED, WHITE, YELLOW};
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
    let dc = CdevPin::new(chip.get_line(25).unwrap().request(LineRequestFlags::OUTPUT, 0, "ssd1331").unwrap()).unwrap(); // Create D/C# pin, (line=25) → CdevPin
    let res = CdevPin::new(chip.get_line(17).unwrap().request(LineRequestFlags::OUTPUT, 1, "ssd1331").unwrap()).unwrap(); // Create RES# pin, (line=17) → CdevPin
    let mut spi = Spidev::open("/dev/spidev0.0").expect("open spidev");
    spi.configure(&SpidevOptions::new().max_speed_hz(4_000_000).mode(SpiModeFlags::SPI_MODE_0).build()).unwrap();
    let device = ExclusiveDevice::new_no_delay(SpidevBus(spi), NullCs).unwrap();
    let connection = DisplayConnection::new(device, dc, Some(res), None::<NoPin>, Delay, WireMode::Simplex).unwrap(); // Create display connection, (spi, dc, res, busy=None, delay, wire_mode)

    let mut fb = [0u8; BUFFER_SIZE];
    let mut oled = Ssd1331Full::new(connection, &mut fb).unwrap(); // Create SSD1331 driver, (connection, buffer) → Ssd1331Full

    let s = |ms| sleep(Duration::from_millis(ms));
    oled.clear(BLACK);                                                                  // Fill framebuffer, (color)
                                                                                        // sets every pixel; the display is unchanged until show()
    oled.draw_rect(0, 0, 96, 64, WHITE, false);                                         // Draw rectangle, (x, y, w, h, color, fill)
    oled.draw_rect(4, 4, 30, 20, RED, true);                                            // Draw rectangle, (x, y, w, h, color, fill)
    oled.draw_line(0, 0, 95, 63, GREEN);                                                // Software line, (x0, y0, x1, y1, color)
                                                                                        // Bresenham diagonal
    oled.draw_line(0, 63, 95, 0, CYAN);                                                 // Software line, (x0, y0, x1, y1, color)
    let mut sprite = [0u16; 64];
    for (i, p) in sprite.iter_mut().enumerate() {
        *p = if ((i / 8) + (i % 8)) & 1 == 1 { YELLOW } else { MAGENTA };
    }
    oled.blit(60, 8, &sprite, 8, 8);                                                    // Copy RGB565 bitmap, (x, y, pixels, w, h)
                                                                                        // checkerboard into the framebuffer
    oled.set_pixel(48, 32, WHITE);                                                      // Set one pixel, (x, y, color)
    println!("{:#06x}", oled.get_pixel(48, 32));                                        // Read framebuffer pixel, (x, y) → u16 RGB565
    oled.show().unwrap();                                                               // Send framebuffer, () → Result
                                                                                        // window 0..95 x 0..63 then 12288 bytes
    s(2000);

    oled.clear(BLUE);                                                                   // Fill framebuffer, (color)
    oled.draw_rect(20, 16, 56, 32, WHITE, true);                                        // Draw rectangle, (x, y, w, h, color, fill)
    oled.show_region(16, 12, 64, 40).unwrap();                                          // Send region, (x, y, w, h) → Result
                                                                                        // only the rows of the window are transferred
    s(2000);

    oled.hw_clear(0, 0, 95, 63).unwrap();                                               // Clear window in display RAM, (x0, y0, x1, y1) → Result
                                                                                        // chip clears its own RAM, framebuffer untouched
    oled.hw_rect(10, 10, 76, 44, YELLOW, RED, true).unwrap();                           // Hardware rectangle, (x, y, w, h, line_color, fill_color, fill) → Result
                                                                                        // outline and fill drawn by the chip
    oled.hw_line(0, 0, 95, 63, WHITE).unwrap();                                         // Hardware line, (x0, y0, x1, y1, color) → Result
    s(2000);
    oled.hw_copy(10, 10, 40, 40, 50, 20).unwrap();                                      // Hardware copy, (x0, y0, x1, y1, x, y) → Result
    s(2000);
    oled.hw_dim(0, 0, 47, 63).unwrap();                                                 // Hardware dim window, (x0, y0, x1, y1) → Result
    s(2000);

    oled.set_display_mode(DisplayMode::Inverse).unwrap();                               // Display mode, (mode) → Result
    s(1500);
    oled.set_display_mode(DisplayMode::AllOn).unwrap();                                 // Display mode, (mode) → Result
    s(1000);
    oled.set_display_mode(DisplayMode::AllOff).unwrap();                                // Display mode, (mode) → Result
    s(1000);
    oled.set_display_mode(DisplayMode::Normal).unwrap();                                // Display mode, (mode) → Result

    oled.set_contrast(0x30, 0x30, 0x30).unwrap();                                       // Channel contrast, (a, b, c) 0..255
    s(1000);
    oled.set_contrast(0xFF, 0xFF, 0xFF).unwrap();                                       // Channel contrast, (a, b, c) 0..255
    oled.set_master_current(4).unwrap();                                                // Master current, (n 1..16) n/16
    s(1000);
    oled.set_master_current(7).unwrap();                                                // Master current, (n 1..16) n/16

    oled.set_flip(false, true).unwrap();                                                // Mirror, (horizontal, vertical) → Result
    oled.show().unwrap();                                                               // Send framebuffer, () → Result
    s(1500);
    oled.set_flip(true, true).unwrap();                                                 // Mirror, (horizontal, vertical) → Result
    oled.show().unwrap();                                                               // Send framebuffer, () → Result
    s(1500);

    oled.scroll(1, 0, 64, 0, 0).unwrap();                                               // Scroll, (horizontal, start_row, rows, vertical, interval 0..3)
                                                                                        // continuous scroll one column per 6 frames
    s(4000);
    oled.scroll_stop().unwrap();                                                        // Stop scrolling, () → Result

    oled.dim_mode(0x10, 0x10, 0x10, 0x10).unwrap();                                     // Dim mode, (a, b, c, precharge 0..31)
    s(1500);
    oled.set_power_save(true).unwrap();                                                 // Power save, (on)
    oled.display_off().unwrap();                                                        // Display off, () → Result
}
