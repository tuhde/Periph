//! SSD1331 demo example: a colour test card on a Waveshare 0.95inch RGB OLED (Linux host).
//!
//! Wiring: DIN=GPIO10 (SPI0 MOSI), CLK=GPIO11, CS=GPIO8 (CE0), DC=GPIO25, RST=GPIO17 (no BUSY line).
use embedded_hal_bus::spi::ExclusiveDevice;
use gpio_cdev::{Chip, LineRequestFlags};
use linux_embedded_hal::{CdevPin, Delay, SpidevBus};
use periph::chips::display::ssd1331::{Ssd1331Full, rgb, BUFFER_SIZE, BLACK, BLUE, CYAN, GREEN, MAGENTA, RED, WHITE, YELLOW};
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

    // --- Draw the test card into the framebuffer ---
    // Eight 12-pixel colour bars over a 26-row gray ramp show that all channels and the
    // 5-6-5 depth work; the one-pixel frame shows that the whole 96 x 64 area is addressed.
    let bars = [WHITE, YELLOW, CYAN, GREEN, MAGENTA, RED, BLUE, BLACK];
    oled.clear(BLACK);                                                                  // Fill framebuffer, (color)
    for x in 0..96i32 {
        oled.draw_rect(x, 2, 1, 30, bars[(x / 12) as usize], true);                     // Draw rectangle, (x, y, w, h, color, fill)
        let g = (x * 255 / 95) as u8;
        oled.draw_rect(x, 34, 1, 26, rgb(g, g, g), true);                               // Draw rectangle, (x, y, w, h, color, fill)
    }
    oled.draw_rect(0, 0, 96, 64, WHITE, false);                                         // Draw rectangle, (x, y, w, h, color, fill)

    // --- Send it once ---
    oled.show().unwrap();                                                               // Send framebuffer, () → Result
    sleep(Duration::from_secs(3));

    // --- Animate with hardware drawing ---
    // The chip draws the marker straight into its RAM: no framebuffer transfer per step.
    for step in 0..60u32 {
        let x = (4 + (step * 3) % 80) as u8;
        oled.show().unwrap();                                                           // Send framebuffer, () → Result
        oled.hw_rect(x, 36, 10, 10, WHITE, RED, true).unwrap();                         // Hardware rectangle, (x, y, w, h, line_color, fill_color, fill) → Result
        sleep(Duration::from_millis(50));
    }

    // --- Scroll the test card ---
    // The chip scrolls its own RAM, one column every 6 frames.
    oled.show().unwrap();                                                               // Send framebuffer, () → Result
    oled.scroll(1, 0, 64, 0, 0).unwrap();                                               // Scroll, (horizontal, start_row, rows, vertical, interval 0..3)
}
