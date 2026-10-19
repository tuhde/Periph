//! ST7036 complete example: every Full method on an EA DOGM162W-A (2 x 16, 3.3 V) on a Raspberry Pi (Linux host).
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

    lcd.clear().unwrap();                                // Clear display, () → Result
                                                         // writes spaces to all of DDRAM, cursor to home (2 ms)
    lcd.print_at(0, 0, "Complete example").unwrap();     // Write text, (col, row, text) → Result
    lcd.set_cursor(0, 1).unwrap();                       // Move cursor, (col, row) → Result
    lcd.write("Cursor + blink").unwrap();                // Write text at the cursor, (text) → Result
    lcd.cursor(true, true).unwrap();                     // Cursor, (on, blink) → Result
    sleep(Duration::from_secs(2));
    lcd.cursor(false, false).unwrap();                   // Cursor, (on, blink) → Result

    let heart = [0x00, 0x0A, 0x1F, 0x1F, 0x0E, 0x04, 0x00, 0x00];
    let arrow = [0x04, 0x02, 0x1F, 0x02, 0x04, 0x00, 0x00, 0x00];
    lcd.create_char(0, &heart).unwrap();                 // Define user character, (index 0..7, rows[8]) → Result
                                                         // 8 rows of 5 bits into CGRAM, DDRAM address restored
    lcd.create_char(1, &arrow).unwrap();                 // Define user character, (index 0..7, rows[8]) → Result
    lcd.clear().unwrap();                                // Clear display, () → Result
    lcd.set_cursor(0, 0).unwrap();                       // Move cursor, (col, row) → Result
    lcd.write("I ").unwrap();                            // Write text at the cursor, (text) → Result
    lcd.write_char(0).unwrap();                          // Write raw character code, (code) → Result
    lcd.write(" custom ").unwrap();                      // Write text at the cursor, (text) → Result
    lcd.write_char(1).unwrap();                          // Write raw character code, (code) → Result
    sleep(Duration::from_secs(2));

    lcd.set_contrast(0x08).unwrap();                     // Set contrast, (value 0..63) → Result
    lcd.print_at(0, 1, "low contrast").unwrap();         // Write text, (col, row, text) → Result
    sleep(Duration::from_millis(1500));
    lcd.set_contrast(0x30).unwrap();                     // Set contrast, (value 0..63) → Result
    lcd.print_at(0, 1, "high contrast").unwrap();        // Write text, (col, row, text) → Result
    sleep(Duration::from_millis(1500));
    lcd.set_contrast(0x18).unwrap();                     // Set contrast, (value 0..63) → Result
                                                         // 0x18 is the DOGM162 3.3 V preset
    lcd.set_bias(false).unwrap();                        // LCD bias, (quarter) → Result, 1/5 bias
    lcd.set_follower(true, 5).unwrap();                  // Voltage follower, (on, ratio 0..7) → Result, 1 + Rb/Ra = 2.5
    lcd.set_booster(true).unwrap();                      // Voltage booster, (on) → Result

    lcd.clear().unwrap();                                // Clear display, () → Result
    lcd.set_entry_mode(true, false).unwrap();            // Entry mode, (increment, shift) → Result
    lcd.print_at(0, 0, "Shift display ->").unwrap();     // Write text, (col, row, text) → Result
    lcd.print_at(0, 1, "0123456789ABCDEF").unwrap();     // Write text, (col, row, text) → Result
    sleep(Duration::from_secs(1));
    for _ in 0..6 {
        lcd.shift_display(true).unwrap();                // Shift display, (right) → Result
        sleep(Duration::from_millis(300));
    }
    lcd.return_home().unwrap();                          // Return home, () → Result, undoes the display shift
    lcd.shift_cursor(true).unwrap();                     // Shift cursor, (right) → Result
    lcd.set_ddram_address(0x40).unwrap();                // Set DDRAM address, (address) → Result
    sleep(Duration::from_secs(1));

    lcd.display(false).unwrap();                         // Display on/off, (on) → Result
    sleep(Duration::from_millis(800));
    lcd.display(true).unwrap();                          // Display on/off, (on) → Result
}
