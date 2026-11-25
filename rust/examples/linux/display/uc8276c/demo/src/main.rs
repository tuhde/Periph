//! UC8276C demo example: Waveshare 4.2inch e-Paper (B) on a Raspberry Pi (Linux host).
//!
//! Wiring: DIN=GPIO10 (SPI0 MOSI), CLK=GPIO11, CS=GPIO8 (CE0), DC=GPIO25, RST=GPIO17, BUSY=GPIO24.
//! Each refresh blocks for about 17 s.
use embedded_hal_bus::spi::ExclusiveDevice;
use gpio_cdev::{Chip, LineRequestFlags};
use linux_embedded_hal::{CdevPin, Delay, SpidevBus};
use periph::chips::display::uc8276c::{Uc8276cFull, BLACK, WHITE, RED};
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
    let dc = CdevPin::new(chip.get_line(25).unwrap().request(LineRequestFlags::OUTPUT, 0, "uc8276c").unwrap()).unwrap(); // Create D/C# pin, (line=25) → CdevPin
    let res = CdevPin::new(chip.get_line(17).unwrap().request(LineRequestFlags::OUTPUT, 1, "uc8276c").unwrap()).unwrap(); // Create RES# pin, (line=17) → CdevPin
    let busy = CdevPin::new(chip.get_line(24).unwrap().request(LineRequestFlags::INPUT, 0, "uc8276c").unwrap()).unwrap(); // Create BUSY_N pin, (line=24) → CdevPin
    let mut spi = Spidev::open("/dev/spidev0.0").expect("open spidev");
    spi.configure(&SpidevOptions::new().max_speed_hz(4_000_000).mode(SpiModeFlags::SPI_MODE_0).build()).unwrap();
    let device = ExclusiveDevice::new_no_delay(SpidevBus(spi), NullCs).unwrap();
    let connection = DisplayConnection::new(device, dc, Some(res), Some(busy), Delay, WireMode::Simplex).unwrap(); // Create display connection, (spi, dc, res, busy, delay, wire_mode)

    let mut bw = [0u8; 15000];
    let mut red = [0u8; 15000];
    let mut epd = Uc8276cFull::new(connection, &mut bw, Some(&mut red), 400, 300, Some(30_000)).unwrap(); // Create UC8276C driver, (connection, bw, red, width, height, busy_timeout_ms) → Uc8276cFull

    // --- Compose the status card in the framebuffer ---
    // White page, a one-pixel black border inset by 4 px (a frame on the very edge is
    // hidden by the panel bezel) and a red header bar across the top 40 rows.
    epd.clear(WHITE);                                                                // Fill framebuffer, (color)
    epd.draw_rect(4, 4, 392, 292, BLACK, false);                                     // Draw rectangle, (x, y, w, h, color, fill)
    epd.draw_rect(5, 5, 390, 40, RED, true);                                         // Draw rectangle, (x, y, w, h, color, fill)

    // --- Bar chart of eight readings ---
    // Axes first, then one filled bar per value; the tallest bar is red to flag the peak.
    epd.draw_line(30, 270, 370, 270, BLACK);                                         // Draw line, (x0, y0, x1, y1, color)
    epd.draw_line(30, 70, 30, 270, BLACK);                                           // Draw line, (x0, y0, x1, y1, color)
    let values = [30, 55, 80, 45, 120, 90, 60, 100];
    let peak = *values.iter().max().unwrap();
    for (i, v) in values.iter().enumerate() {
        let color = if *v == peak { RED } else { BLACK };
        epd.draw_rect(45 + i as i32 * 40, 270 - v, 28, *v, color, true);             // Draw rectangle, (x, y, w, h, color, fill)
    }

    // --- Refresh and time it ---
    // show() blocks for the whole DTM1/DTM2/PON/DRF/POF sequence.
    let start = std::time::Instant::now();
    epd.show().unwrap();                                                             // Refresh panel, () → Result
    println!("refresh took {:?}", start.elapsed());

    // --- Save the image and power down ---
    // The panel keeps the picture without power; deep sleep stops the controller drawing current.
    epd.sleep().unwrap();                                                            // Enter deep sleep, () → Result
}
