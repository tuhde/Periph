//! IL0373 demo example: Waveshare 2.13inch e-Paper (D) on a Raspberry Pi (Linux host).
//!
//! Wiring: DIN=GPIO10 (SPI0 MOSI), CLK=GPIO11, CS=GPIO8 (CE0), DC=GPIO25, RST=GPIO17, BUSY=GPIO24.
//! Each refresh blocks until BUSY goes high (the panel datasheet gives no update time).
use embedded_hal_bus::spi::ExclusiveDevice;
use gpio_cdev::{Chip, LineRequestFlags};
use linux_embedded_hal::{CdevPin, Delay, SpidevBus};
use periph::chips::display::il0373::{Il0373Full, BLACK, WHITE};
use periph::connection::display::{DisplayConnection, WireMode};
use spidev::{SpiModeFlags, Spidev, SpidevOptions};

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
    let dc = CdevPin::new(chip.get_line(25).unwrap().request(LineRequestFlags::OUTPUT, 0, "il0373").unwrap()).unwrap(); // Create D/C# pin, (line=25) → CdevPin
    let res = CdevPin::new(chip.get_line(17).unwrap().request(LineRequestFlags::OUTPUT, 1, "il0373").unwrap()).unwrap(); // Create RES# pin, (line=17) → CdevPin
    let busy = CdevPin::new(chip.get_line(24).unwrap().request(LineRequestFlags::INPUT, 0, "il0373").unwrap()).unwrap(); // Create BUSY pin, (line=24) → CdevPin
    let mut spi = Spidev::open("/dev/spidev0.0").expect("open spidev");
    spi.configure(&SpidevOptions::new().max_speed_hz(4_000_000).mode(SpiModeFlags::SPI_MODE_0).build()).unwrap();
    let device = ExclusiveDevice::new_no_delay(SpidevBus(spi), NullCs).unwrap();
    let connection = DisplayConnection::new(device, dc, Some(res), Some(busy), Delay, WireMode::Simplex).unwrap(); // Create display connection, (spi, dc, res, busy, delay, wire_mode)

    let mut fb = [0u8; 2756];
    let mut epd = Il0373Full::new(connection, &mut fb, 104, 212, Some(30_000)).unwrap(); // Create IL0373 driver, (connection, buffer, width, height, busy_timeout_ms) → Il0373Full

    // --- Compose the status card in the framebuffer ---
    // White page, a one-pixel black border inset by 2 px (a frame on the very edge is
    // hidden by the panel bezel) and a black header bar across the top 24 rows.
    epd.clear(WHITE);                                                                // Fill framebuffer, (color)
    epd.draw_rect(2, 2, 100, 208, BLACK, false);                                     // Draw rectangle, (x, y, w, h, color, fill)
    epd.draw_rect(3, 3, 98, 24, BLACK, true);                                      // Draw rectangle, (x, y, w, h, color, fill)

    // --- Bar chart of eight readings ---
    // Axes first, then one filled bar per value; the tallest bar is filled and the others are outlines.
    epd.draw_line(10, 190, 95, 190, BLACK);                                         // Draw line, (x0, y0, x1, y1, color)
    epd.draw_line(10, 60, 10, 190, BLACK);                                           // Draw line, (x0, y0, x1, y1, color)
    let values = [30, 55, 80, 45, 120, 90, 60, 100];
    let peak = *values.iter().max().unwrap();
    for (i, v) in values.iter().enumerate() {
        epd.draw_rect(14 + i as i32 * 10, 190 - v, 8, *v, BLACK, *v == peak);        // Draw rectangle, (x, y, w, h, color, fill)
    }

    // --- Refresh and time it ---
    // show() blocks for the whole DTM1/DTM2/DRF/POF sequence.
    let start = std::time::Instant::now();
    epd.show().unwrap();                                                             // Refresh panel, () → Result
    println!("refresh took {:?}", start.elapsed());

    // --- Save the image and power down ---
    // The panel keeps the picture without power; deep sleep stops the controller drawing current.
    epd.sleep().unwrap();                                                            // Enter deep sleep, () → Result
}
