//! IL0373 complete example: Waveshare 2.13inch e-Paper (D) on a Raspberry Pi (Linux host).
//!
//! Wiring: DIN=GPIO10 (SPI0 MOSI), CLK=GPIO11, CS=GPIO8 (CE0), DC=GPIO25, RST=GPIO17, BUSY=GPIO24.
//! The read_* calls need DOUT: join DIN and DOUT through a ~1 kOhm resistor to MISO (GPIO9).
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
    spi.configure(&SpidevOptions::new().max_speed_hz(1_000_000).mode(SpiModeFlags::SPI_MODE_0).build()).unwrap();
    let device = ExclusiveDevice::new_no_delay(SpidevBus(spi), NullCs).unwrap();
    let connection = DisplayConnection::new(device, dc, Some(res), Some(busy), Delay, WireMode::HalfDuplexPin).unwrap(); // Create display connection, (spi, dc, res, busy, delay, wire_mode)

    let mut fb = [0u8; 2756];
    let mut epd = Il0373Full::new(connection, &mut fb, 104, 212, Some(30_000)).unwrap(); // Create IL0373 driver, (connection, buffer, width, height, busy_timeout_ms) → Il0373Full
    epd.clear(WHITE);                                                                // Fill framebuffer, (color)
                                                                                         // every pixel set to white
    epd.set_pixel(5, 5, BLACK);                                                      // Set one pixel, (x, y, color)
                                                                                         // clears the bit for that pixel
    epd.draw_rect(20, 20, 60, 40, BLACK, true);                                       // Draw rectangle, (x, y, w, h, color, fill)
                                                                                         // filled black block
    epd.draw_line(0, 0, 103, 211, BLACK);                                            // Draw line, (x0, y0, x1, y1, color)
                                                                                         // Bresenham diagonal
    epd.blit(70, 20, &[0xFF, 0x81, 0x81, 0xFF], 8, 4, BLACK);                       // Copy 1-bpp bitmap, (x, y, data, w, h, color)
                                                                                         // 8 x 4 box, MSB first
    let colour = epd.get_pixel(30, 30);                                              // Read framebuffer pixel, (x, y) → u8 colour
                                                                                         // BLACK here
    epd.set_flip(false, false).unwrap();                                             // Mirror output, (horizontal, vertical)
                                                                                         // PSR.SHL / UD at power-on values
    epd.set_power(14.0, 14.0, 20.0).unwrap();                                        // Set source/gate power, (vdh V, vdl V, vgh V)
                                                                                         // PWR: +-14 V sources, +-20 V gates (the power-on values)
    epd.set_frame_rate(50).unwrap();                                                 // Set frame rate, (hz 4–200)
                                                                                         // nearest PLL M/N entry (M7 N4)
    epd.set_soft_start(0x17, 0x17, 0x17).unwrap();                                   // Set booster soft start, (a, b, c)
                                                                                         // vendor BTST phases
    epd.set_interval(7, 2, 1).unwrap();                                              // Set VCOM/data interval, (cdi 0–15, border, polarity)
                                                                                         // keeps DDX = 01 so the framebuffer mapping holds
    epd.set_temperature_sensor(false, 0).unwrap();                                   // Select temperature sensor, (external, offset -8..7 °C)
                                                                                         // internal sensor, no offset
    let status = epd.read_status().unwrap();                                         // Read FLG, () → u8 bitfield
                                                                                         // needs DOUT wired (half-duplex)
    let temperature = epd.read_temperature().unwrap();                               // Read sensor, () → i8 °C
                                                                                         // signed internal temperature
    let revision = epd.read_revision().unwrap();                                     // Read REV, () → u8
                                                                                         // LUT revision byte
    epd.show().unwrap();                                                             // Refresh panel, () → Result
                                                                                         // OLD/NEW planes, DRF, border floating, POF
    epd.draw_rect(16, 100, 64, 40, BLACK, true);                                     // Draw rectangle, (x, y, w, h, color, fill)
    epd.show_region(16, 100, 64, 40).unwrap();                                       // Refresh a window, (x, y, w, h)
                                                                                         // sends only the window's rows (RAM saving)
    let ready = epd.wait_busy(Some(1000)).unwrap();                                  // Wait for BUSY high, (timeout_ms) → bool
                                                                                         // true when the controller is idle
    epd.send_command(0x50, &[0x97]).unwrap();                                        // Send raw command, (command, data)
                                                                                         // rewrites CDI with the initialisation value
    epd.sleep().unwrap();                                                            // Enter deep sleep, () → Result
    epd.wake().unwrap();                                                             // Reset and re-initialise, () → Result
                                                                                         // framebuffer is kept
    println!("colour={} status={} temperature={} revision={} ready={}", colour, status, temperature, revision, ready);
    println!("===DONE: 0 passed, 0 failed===");
}
