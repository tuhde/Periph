//! ST7036 demo example: a two-line status display with a bar graph on an EA DOGM162W-A (2 x 16, 3.3 V) on a Raspberry Pi (Linux host).
//!
//! Wiring: SI=GPIO10 (SPI0 MOSI), SCL=GPIO11, CSB=GPIO8 (CE0), RS=GPIO25 (C/D), RESET=GPIO17; PSB=GND, E=3V3 (no BUSY line).
//! Module capacitors: 0.1-1 uF between CAP1N and CAP1P, 0.47-2.2 uF from VOUT to GND, VIN = VDD.
use embedded_hal_bus::spi::ExclusiveDevice;
use gpio_cdev::{Chip, LineRequestFlags};
use linux_embedded_hal::{CdevPin, Delay, SpidevBus};
use periph::chips::display::st7036::{St7036Full, Supply};
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
    let dc = CdevPin::new(chip.get_line(25).unwrap().request(LineRequestFlags::OUTPUT, 0, "st7036").unwrap()).unwrap(); // Create RS (C/D) pin, (line=25) → CdevPin
    let res = CdevPin::new(chip.get_line(17).unwrap().request(LineRequestFlags::OUTPUT, 1, "st7036").unwrap()).unwrap(); // Create RESET pin, (line=17) → CdevPin
    let mut spi = Spidev::open("/dev/spidev0.0").expect("open spidev");
    spi.configure(&SpidevOptions::new().max_speed_hz(1_000_000).mode(SpiModeFlags::SPI_MODE_0).build()).unwrap();
    let device = ExclusiveDevice::new_no_delay(SpidevBus(spi), NullCs).unwrap();
    let connection = DisplayConnection::new(device, dc, Some(res), None::<NoPin>, Delay, WireMode::Simplex).unwrap(); // Create display connection, (spi, dc, res, busy=None, delay, wire_mode)

    let mut lcd = St7036Full::new(connection, 2, None, Supply::V3_3, None).unwrap(); // Create ST7036 driver, (connection, lines=2, columns=None, supply=V3_3, contrast=None) → St7036Full

    // --- Define the bar-graph glyphs ---
    // Custom characters 0..4 are columns of 1..5 filled pixel columns, so a 16-character bar
    // can show 80 steps with the eight CGRAM slots.
    for g in 0..5u8 {
        let pattern = (0x1Fu8 << (4 - g)) & 0x1F;
        let rows = [0, pattern, pattern, pattern, pattern, pattern, pattern, 0];
        lcd.create_char(g, &rows).unwrap();              // Define user character, (index 0..7, rows[8]) → Result
    }

    // --- Static title line ---
    lcd.clear().unwrap();                                // Clear display, () → Result
    lcd.print_at(0, 0, "Periph ST7036").unwrap();        // Write text, (col, row, text) → Result

    // --- Animate a progress bar on line 2 ---
    // Each step fills 1/5 of a character cell; a full bar is 16 x 5 = 80 steps.
    for step in 0..=80i32 {
        lcd.set_cursor(0, 1).unwrap();                   // Move cursor, (col, row) → Result
        for col in 0..16i32 {
            let fill = step - col * 5;                   // pixel columns of this cell that are filled
            if fill >= 5 {
                lcd.write_char(4).unwrap();              // Write raw character code, (code) → Result
            } else if fill > 0 {
                lcd.write_char((fill - 1) as u8).unwrap(); // Write raw character code, (code) → Result
            } else {
                lcd.write_char(b' ').unwrap();           // Write raw character code, (code) → Result
            }
        }
        sleep(Duration::from_millis(40));
    }
    sleep(Duration::from_secs(1));

    // --- Contrast sweep ---
    // The contrast is set once in normal use; sweeping it shows the usable range of the module.
    lcd.print_at(0, 0, "Contrast sweep ").unwrap();      // Write text, (col, row, text) → Result
    for c in 0x10..=0x30u8 {
        lcd.set_contrast(c).unwrap();                    // Set contrast, (value 0..63) → Result
        sleep(Duration::from_millis(80));
    }
    lcd.set_contrast(0x18).unwrap();                     // Set contrast, (value 0..63) → Result
    lcd.print_at(0, 0, "Periph ST7036   ").unwrap();     // Write text, (col, row, text) → Result
}
