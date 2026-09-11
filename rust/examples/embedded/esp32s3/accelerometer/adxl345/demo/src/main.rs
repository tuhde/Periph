#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::accelerometer::Adxl345Minimal;
use libm::sqrtf;

esp_app_desc!();

const ADDR: u8 = 0x53;

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let delay = Delay::new();

    let mut accel = Adxl345Minimal::new(i2c, ADDR, false).expect("init ADXL345"); // Create ADXL345 driver, (i2c, addr=0x53, spi=false)

    // --- Stationary tilt characterization at 10 Hz ---
    // With the sensor flat and the Z axis up, gravity should project entirely
    // onto Z. Tilting the board visibly redistributes the 1 *g* magnitude
    // across X and Y; the total vector magnitude stays near 1 *g*.
    let mut mag_min = f32::INFINITY;
    let mut mag_max = f32::NEG_INFINITY;

    loop {
        let (x, y, z) = accel.read().expect("read acceleration"); // Read 3-axis acceleration, () → (f32, f32, f32) g
        let mag = sqrtf(x * x + y * y + z * z);
        if mag < mag_min { mag_min = mag; }
        if mag > mag_max { mag_max = mag; }
        println!("x={:+.3}  y={:+.3}  z={:+.3}  |a|={:.3} g  (min={:.3} max={:.3})",
                 x, y, z, mag, mag_min, mag_max);
        delay.delay_ms(100);
    }
}
