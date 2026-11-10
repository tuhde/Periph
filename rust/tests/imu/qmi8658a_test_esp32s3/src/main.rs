//! QMI8658A hardware-in-loop test for ESP32-S3.
//! Wiring: GPIO1 -> SDA, GPIO2 -> SCL; SA0 low (address 0x6B).
#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_hal::main;
use esp_hal::time::Rate;
use periph::chips::imu::Qmi8658aFull;

esp_app_desc!();

#[main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default().with_frequency(Rate::from_khz(400)))
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut delay = Delay::new();
    let mut imu = Qmi8658aFull::new(i2c, 0x6B, &mut delay).expect("init");

    esp_println::println!("QMI8658A test started");

    let mut passed = 0i32;
    let mut failed = 0i32;

    macro_rules! check_true {
        ($cond:expr, $label:expr) => {
            if $cond { esp_println::println!("PASS {}", $label); passed += 1; }
            else      { esp_println::println!("FAIL {}", $label); failed += 1; }
        };
    }

    let (ax, ay, az) = imu.accel().expect("read accel");
    let g_sq = ax * ax + ay * ay + az * az;
    check_true!(g_sq > 49.0 && g_sq < 158.8, "gravity magnitude");

    let (gx, gy, gz) = imu.gyro().expect("read gyro");
    check_true!(gx.abs() < 10.0 && gy.abs() < 10.0 && gz.abs() < 10.0, "gyro finite");

    let (_, _, tc) = imu.read_all().expect("read_all");
    check_true!(tc > -40.0 && tc < 85.0, "read_all temp range");
    let t = imu.temperature().expect("read temp");
    check_true!((t - tc).abs() < 5.0, "temperature matches");

    let (rax, ray, raz) = imu.accel_raw().expect("accel raw");
    let raw_sq = (rax as f32) * (rax as f32) + (ray as f32) * (ray as f32) + (raz as f32) * (raz as f32);
    check_true!(raw_sq > 6.4e7 && raw_sq < 5.76e8, "accel_raw gravity");
    let _ = imu.gyro_raw().expect("gyro raw");

    let ts1 = imu.timestamp().expect("timestamp");
    delay.delay_ms(100);
    check_true!(imu.timestamp().expect("timestamp") != ts1, "timestamp advances");

    imu.configure_accel(1, 5).expect("configure accel");
    imu.configure_gyro(5, 5).expect("configure gyro");
    delay.delay_ms(200);
    let (bx, by, bz) = imu.accel().expect("accel after reconfig");
    let sq2 = bx * bx + by * by + bz * bz;
    check_true!(sq2 > 49.0 && sq2 < 158.8, "accel after reconfig");
    imu.configure_accel(0, 5).expect("configure accel");
    imu.configure_gyro(4, 5).expect("configure gyro");
    imu.configure_filters(true, 3, true, 3).expect("configure filters");
    delay.delay_ms(200);

    imu.configure_fifo(2, 1, 8).expect("configure fifo");
    delay.delay_ms(300);
    check_true!(imu.fifo_count().expect("fifo count") > 0, "fifo_count non-zero");
    let mut fifo = [0u8; 96];
    let got = imu.read_fifo(&mut fifo, &mut delay).expect("read fifo");
    check_true!(got > 0 && got % 12 == 0, "read_fifo returns whole frames");
    imu.reset_fifo(&mut delay).expect("reset fifo");
    imu.configure_fifo(0, 3, 0).expect("bypass");
    delay.delay_ms(100);

    let (_, firmware, usid) = imu.device_info(&mut delay).expect("device info");
    check_true!(firmware.len() == 3 && usid.len() == 6, "device_info callable");
    imu.set_sensors(true, true, false).expect("sensors");

    esp_println::println!("===DONE: {} passed, {} failed===", passed, failed);
    loop {
        delay.delay_ms(1000);
    }
}
