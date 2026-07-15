#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::gpio::{Level, Output};
use esp_hal::i2c::master::{Config, I2c};
use esp_hal::main;
use esp_hal::time::Rate;
use esp_println::println;
use periph::chips::imu::Mpu6050Full;

esp_app_desc!();

macro_rules! check_true {
    ($cond:expr, $label:expr, $passed:expr, $failed:expr) => {
        if $cond { println!("PASS {}", $label); $passed += 1; }
        else      { println!("FAIL {}", $label); $failed += 1; }
    };
}

const SDA_PIN: u8 = 1;
const SCL_PIN: u8 = 2;
const ADDR: u8 = 0x68;

#[main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());
    let sda = peripherals.GPIO1;
    let scl = peripherals.GPIO2;

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .with_sda(sda)
        .with_scl(scl);

    let mut delay = Delay::new();
    let mut chip = Mpu6050Full::new(i2c, ADDR, &mut delay).expect("init MPU6050");

    let mut passed = 0i32;
    let mut failed = 0i32;

    let (ax, ay, az) = chip.accel().map(|(x, y, z)| (x, y, z)).unwrap_or((0.0, 0.0, 0.0));
    check_true!(ax > -200.0 && ax < 200.0, "accel_x finite", passed, failed);
    check_true!(ay > -200.0 && ay < 200.0, "accel_y finite", passed, failed);
    check_true!(az > -200.0 && az < 200.0, "accel_z finite", passed, failed);

    let (gx, gy, gz) = chip.gyro().map(|(x, y, z)| (x, y, z)).unwrap_or((0.0, 0.0, 0.0));
    check_true!(gx > -100.0 && gx < 100.0, "gyro_x finite", passed, failed);
    check_true!(gy > -100.0 && gy < 100.0, "gyro_y finite", passed, failed);
    check_true!(gz > -100.0 && gz < 100.0, "gyro_z finite", passed, failed);

    let t = chip.temperature().unwrap_or(0.0);
    check_true!(t > -40.0 && t < 85.0, "temperature range", passed, failed);

    let (rax, _, _) = chip.accel_raw().unwrap_or((0, 0, 0));
    check_true!(rax >= -32768 && rax <= 32767, "accel_raw_x range", passed, failed);

    chip.configure_gyro(1).ok();
    chip.configure_accel(1).ok();
    let (ax2, _, _) = chip.accel().unwrap_or((0.0, 0.0, 0.0));
    check_true!(ax2 > -200.0 && ax2 < 200.0, "accel after reconfig", passed, failed);

    chip.set_sleep(true).ok();
    delay.delay_millis(10);
    chip.set_sleep(false).ok();
    delay.delay_millis(50);
    let (ax3, _, _) = chip.accel().unwrap_or((0.0, 0.0, 0.0));
    check_true!(ax3 > -200.0 && ax3 < 200.0, "accel after wake", passed, failed);

    chip.reset_fifo().ok();
    chip.enable_fifo(true, true, false).ok();
    delay.delay_millis(50);
    let count = chip.fifo_count().unwrap_or(0);
    check_true!(count > 0, "fifo_count > 0", passed, failed);
    let mut buf = [0u8; 256];
    let n = chip.read_fifo(&mut buf).unwrap_or(0);
    check_true!(n == count, "read_fifo matches count", passed, failed);

    chip.reset_fifo().ok();

    println!("===DONE: {} passed, {} failed===", passed, failed);

    loop {}
}
