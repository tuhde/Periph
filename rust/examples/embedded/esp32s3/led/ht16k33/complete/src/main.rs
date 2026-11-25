#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::led::{HT16K33Full};

esp_app_desc!();

const ADDR: u8 = 0x70;

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let delay = Delay::new();

    let mut matrix = HT16K33Full::new(i2c, ADDR).expect("init HT16K33"); // Create HT16K33 driver, (i2c, addr=0x70)

    matrix.clear().expect("clear"); // Blank the display, () → ()
                                    // zeros the shadow RAM and writes it
    matrix.set_pixel(2, 5, true); // Set one LED, (com 0–7, row 0–15, on) → bool
    matrix.show().expect("show"); // Write the shadow RAM, () → ()
    let _lit = matrix.get_pixel(2, 5); // Read a pixel from the shadow RAM, (com, row) → bool
    matrix.write_raw(&[0xFF, 0x0F], 0).expect("write_raw"); // Write raw RAM bytes, (data 1–16 bytes, address 0–15) → bool
                                                            // com 0 rows 0–11 lit
    matrix.set_brightness(8).expect("brightness"); // Set dimming, (level 0–15) → bool
                                                   // 9/16 duty
    matrix.set_blink(1.0).expect("blink"); // Set blink frequency, (0, 0.5, 1, 2 Hz) → bool
                                           // blinks at 1 Hz
    delay.delay_millis(3000);
    matrix.set_blink(0.0).expect("blink off");
    matrix.display_off().expect("display_off"); // Display output off, () → ()
    matrix.display_on().expect("display_on"); // Display output on, () → ()
    matrix.set_int_output(true, false).expect("int"); // ROW15/INT pin as INT output, (enable, active_high) → ()
                                                      // active low interrupt output
    let _keys = matrix.read_keys().expect("read_keys"); // Read and clear key data, () → u64 bitmask
    let _pressed = matrix.key_pressed().expect("key_pressed"); // Read the INT flag, () → bool
    let _polled = matrix.poll_interrupt().expect("poll"); // Read keys if INT flag set, () → u64 (0 if none)
    matrix.standby().expect("standby"); // Oscillator off, () → ()
    matrix.wake().expect("wake"); // Oscillator on and restore state, () → ()
    println!("===DONE: 0 passed, 0 failed===");
    loop {}
}
