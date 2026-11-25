use linux_embedded_hal::I2cdev;
use periph::chips::led::{HT16K33Full, COMMONS, ROWS};
use std::thread::sleep;
use std::time::Duration;

fn sleep_ms(ms: u64) { sleep(Duration::from_millis(ms)); }

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x70);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");

    // --- Scrolling pixel, brightness sweep, blink and key matrix on a 16x8 matrix ---
    // A single lit pixel visits all 16 x 8 positions, the brightness is swept from
    // 0 to 15, the display blinks at 2, 1 and 0.5 Hz, then key presses are reported
    // for 20 s via the INT output before standby and wake.
    let mut matrix = HT16K33Full::new(dev, addr).expect("init HT16K33"); // Create HT16K33 driver, (i2c, addr=0x70)

    for com in 0..COMMONS {
        for row in 0..ROWS {
            matrix.clear().expect("clear"); // Blank the display, () → ()
            matrix.set_pixel(com, row, true); // Set one LED, (com 0–7, row 0–15, on) → bool
            matrix.show().expect("show"); // Write the shadow RAM, () → ()
            sleep_ms(50);
        }
    }

    matrix.write_raw(&[0xFF; 16], 0).expect("all on"); // All LEDs on, (data, address) → bool
    for level in 0..16u8 {
        matrix.set_brightness(level).expect("brightness"); // Sweep dimming, (level 0–15) → bool
        sleep_ms(100);
    }

    for f in [2.0f32, 1.0, 0.5, 0.0] {
        matrix.set_blink(f).expect("blink"); // Set blink frequency, (0, 0.5, 1, 2 Hz) → bool
        sleep_ms(3000);
    }

    matrix.set_int_output(true, false).expect("int"); // INT output, active low, (enable, active_high) → ()
    for _ in 0..1000 {                                // 1000 x 20 ms = 20 s
        let keys = matrix.poll_interrupt().expect("poll"); // Read keys if pending, () → u64 bitmask
        if keys != 0 {
            println!("keys: 0x{:x}", keys);
        }
        sleep_ms(20);
    }

    matrix.standby().expect("standby"); // Oscillator off, () → ()
    sleep_ms(1000);
    matrix.wake().expect("wake"); // Oscillator on, state restored, () → ()
}
