#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::led::{HT16K33Full, COMMONS, ROWS};

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

    // --- Scrolling pixel, brightness sweep, blink and key matrix on a 16x8 matrix ---
    // A single lit pixel visits all 16 x 8 positions, the brightness is swept from
    // 0 to 15, the display blinks at 2, 1 and 0.5 Hz, then key presses are reported
    // for 20 s via the INT output before standby and wake.
    let mut matrix = HT16K33Full::new(i2c, ADDR).expect("init HT16K33"); // Create HT16K33 driver, (i2c, addr=0x70)

    for com in 0..COMMONS {
        for row in 0..ROWS {
            matrix.clear().expect("clear"); // Blank the display, () → ()
            matrix.set_pixel(com, row, true); // Set one LED, (com 0–7, row 0–15, on) → bool
            matrix.show().expect("show"); // Write the shadow RAM, () → ()
            delay.delay_millis(50);
        }
    }

    matrix.write_raw(&[0xFF; 16], 0).expect("all on"); // All LEDs on, (data, address) → bool
    for level in 0..16u8 {
        matrix.set_brightness(level).expect("brightness"); // Sweep dimming, (level 0–15) → bool
        delay.delay_millis(100);
    }

    for f in [2.0f32, 1.0, 0.5, 0.0] {
        matrix.set_blink(f).expect("blink"); // Set blink frequency, (0, 0.5, 1, 2 Hz) → bool
        delay.delay_millis(3000);
    }

    matrix.set_int_output(true, false).expect("int"); // INT output, active low, (enable, active_high) → ()
    for _ in 0..1000 {                                // 1000 x 20 ms = 20 s
        let keys = matrix.poll_interrupt().expect("poll"); // Read keys if pending, () → u64 bitmask
        if keys != 0 {
            println!("keys: {}", keys);
        }
        delay.delay_millis(20);
    }

    matrix.standby().expect("standby"); // Oscillator off, () → ()
    delay.delay_millis(1000);
    matrix.wake().expect("wake"); // Oscillator on, state restored, () → ()
    println!("===DONE: 0 passed, 0 failed===");
    loop {}
}
