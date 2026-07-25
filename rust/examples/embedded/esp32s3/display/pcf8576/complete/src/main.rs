#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::display::{

esp_app_desc!();

const ADDR: u8 = 0x38;

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut delay = Delay::new();

    let mut lcd = Pcf8576Full::new(i2c, ADDR).expect("init PCF8576"); // Create PCF8576 driver, (i2c, ADDR=0x38)
    lcd.clear().expect("clear"); // Blank the display, () → ()
                                  // zeros all 40 columns of display RAM
    lcd.device_select(0).expect("device_select"); // Select device on the bus, (subaddress 0–7) → ()
                                                  // sets the subaddress counter for cascaded use
    lcd.set_mode(BACKPLANES_4, BIAS_1_3).expect("set_mode"); // Set drive mode, (backplanes 1–4, bias 0/1) → ()
                                                              // configures 1:4 multiplex with 1/3 bias
    lcd.set_blink(BLINK_2_HZ, false).expect("set_blink"); // Set blink frequency, (frequency 0–3, alternate_bank=false) → ()
                                                          // ~2 Hz blink for visual attention
    lcd.set_bank(BANK_0, BANK_0).expect("set_bank"); // Select RAM bank, (input_bank 0/1, output_bank 0/1) → ()
                                                      // selects rows 0-1 for both input and output

    let digits = [5u8, 6, 7, 8];
    let out: [u8; 4] = [
        SEVEN_SEG[digits[0] as usize], // Encode 7-segment digit, (digit 0–9) → u8
        SEVEN_SEG[digits[1] as usize], // Encode 7-segment digit, (digit 0–9) → u8
        SEVEN_SEG[digits[2] as usize], // Encode 7-segment digit, (digit 0–9) → u8
        SEVEN_SEG[digits[3] as usize], // Encode 7-segment digit, (digit 0–9) → u8
    ];
    lcd.write_raw(0, &out).expect("write_raw"); // Write raw bytes, (address 0–39, data) → ()
                                                  // sets data pointer to 0 and writes all four digits
    lcd.disable().expect("disable"); // Disable display output, () → ()
                                       // blanks the panel while keeping RAM contents
    lcd.enable().expect("enable"); // Enable display output, () → ()
                                     // resumes output from RAM with the prior configuration
    let _ = BACKPLANES_2;
    let _ = BIAS_1_2;
    let _ = BLINK_OFF;
    loop {}
}
