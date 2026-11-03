#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_hal::main;
use esp_println::println;
use periph::chips::accelerometer::Mma8451QMinimal;

esp_app_desc!();

macro_rules! check_true {
    ($cond:expr, $label:expr, $passed:expr, $failed:expr) => {
        if $cond { println!("PASS {}", $label); $passed += 1; }
        else      { println!("FAIL {}", $label); $failed += 1; }
    };
}

const SDA_PIN: u8 = 1;
const SCL_PIN: u8 = 2;
const ADDR: u8 = 0x1C;

#[main]
fn main() -> ! {
    let mut peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0.reborrow(), Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1.reborrow())
        .with_scl(peripherals.GPIO2.reborrow());

    let mut delay = Delay::new();
    let mut chip = Mma8451QMinimal::new(i2c, ADDR).expect("init MMA8451Q");

    let mut passed = 0i32;
    let mut failed = 0i32;

    // Wait for the STANDBY → ACTIVE turn-on time (2/ODR + 2 ms at 100 Hz).
    delay.delay_millis(22);

    let (x, y, z) = chip.read().expect("read");
    check_true!(x.is_finite() && y.is_finite() && z.is_finite(), "read_returns_floats", passed, failed);
    let mag = libm::sqrtf(x * x + y * y + z * z);
    check_true!(mag >= 0.5 && mag <= 1.5, "magnitude_near_1g", passed, failed);

    let (rx, ry, rz) = chip.read_raw().expect("read_raw");
    check_true!(rx >= -8192 && rx <= 8191, "raw_x_in_range", passed, failed);
    check_true!(ry >= -8192 && ry <= 8191, "raw_y_in_range", passed, failed);
    check_true!(rz >= -8192 && rz <= 8191, "raw_z_in_range", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    if failed > 0 {
        loop { esp_hal::delay::Delay::new().delay_millis(1000); }
    }
    loop { esp_hal::delay::Delay::new().delay_millis(1000); }
}
