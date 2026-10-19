//! ST7036 minimal example: two lines of text on an EA DOGM162W-A (2 x 16, 3.3 V) on a Raspberry Pi (Linux host).
//!
//! Wiring: SI=GPIO10 (SPI0 MOSI), SCL=GPIO11, CSB=GPIO8 (CE0), RS=GPIO25 (C/D), RESET=GPIO17; PSB=GND, E=3V3 (no BUSY line).
//! Module capacitors: 0.1-1 uF between CAP1N and CAP1P, 0.47-2.2 uF from VOUT to GND, VIN = VDD.
use embedded_hal_bus::spi::ExclusiveDevice;
use gpio_cdev::{Chip, LineRequestFlags};
use linux_embedded_hal::{CdevPin, Delay, SpidevBus};
use periph::chips::display::st7036::{St7036Minimal};
use periph::connection::display::{DisplayConnection, NoPin, WireMode};
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
    let dc = CdevPin::new(chip.get_line(25).unwrap().request(LineRequestFlags::OUTPUT, 0, "st7036").unwrap()).unwrap(); // Create RS (C/D) pin, (line=25) → CdevPin
    let res = CdevPin::new(chip.get_line(17).unwrap().request(LineRequestFlags::OUTPUT, 1, "st7036").unwrap()).unwrap(); // Create RESET pin, (line=17) → CdevPin
    let mut spi = Spidev::open("/dev/spidev0.0").expect("open spidev");
    spi.configure(&SpidevOptions::new().max_speed_hz(1_000_000).mode(SpiModeFlags::SPI_MODE_0).build()).unwrap();
    let device = ExclusiveDevice::new_no_delay(SpidevBus(spi), NullCs).unwrap();
    let connection = DisplayConnection::new(device, dc, Some(res), None::<NoPin>, Delay, WireMode::Simplex).unwrap(); // Create display connection, (spi, dc, res, busy=None, delay, wire_mode)

    let mut lcd = St7036Minimal::new(connection).unwrap(); // Create ST7036 driver, (connection) → St7036Minimal

    lcd.print_at(0, 0, "Hello, Periph!").unwrap();       // Write text, (col, row, text) → Result
    lcd.print_at(0, 1, "ST7036 DOGM162").unwrap();       // Write text, (col, row, text) → Result
}
