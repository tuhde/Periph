//! ICM-20948 hardware-in-loop test for ESP32-S3.
//! Wiring: GPIO1 -> SDA, GPIO2 -> SCL; AD0 low (address 0x68).
#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_hal::main;
use esp_hal::time::Rate;
use periph::chips::imu::Icm20948Full;

esp_app_desc!();

#[main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default().with_frequency(Rate::from_khz(400)))
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut delay = Delay::new();
    let mut imu = Icm20948Full::new(i2c, 0x68, &mut delay).expect("init");

    esp_println::println!("ICM20948 test started");

    let mut passed = 0i32;
    let mut failed = 0i32;

    macro_rules! check_true {
        ($cond:expr, $label:expr) => {
            if $cond { esp_println::println!("PASS {}", $label); passed += 1; }
            else      { esp_println::println!("FAIL {}", $label); failed += 1; }
        };
    }

    let (ax, ay, az) = imu.accel().expect("read accel");
    check_true!(ax > -200.0 && ax < 200.0 && ay > -200.0 && ay < 200.0 && az > -200.0 && az < 200.0, "accel finite");
    let g_sq = ax * ax + ay * ay + az * az;
    check_true!(g_sq > 25.0 && g_sq < 225.0, "gravity magnitude");

    let (gx, gy, gz) = imu.gyro().expect("read gyro");
    check_true!(gx > -100.0 && gx < 100.0 && gy > -100.0 && gy < 100.0 && gz > -100.0 && gz < 100.0, "gyro finite");

    let t = imu.temperature().expect("read temp");
    check_true!(t > -40.0 && t < 85.0, "temperature range");

    let (_, _, tc) = imu.read_all().expect("read_all");
    check_true!(tc > -40.0 && tc < 85.0, "read_all temp range");

    let (rax, ray, raz) = imu.accel_raw().expect("accel raw");
    let raw_sq = (rax as f32) * (rax as f32) + (ray as f32) * (ray as f32) + (raz as f32) * (raz as f32);
    check_true!(raw_sq > 6.4e7 && raw_sq < 5.76e8, "accel_raw gravity");
    let _ = imu.gyro_raw().expect("gyro raw");

    imu.configure_gyro(1).expect("configure gyro");
    imu.configure_accel(1).expect("configure accel");
    let (ax2, _, _) = imu.accel().expect("accel after reconfig");
    check_true!(ax2 > -200.0 && ax2 < 200.0, "accel after reconfig");

    imu.configure_filters(4, 4, true, true).expect("configure filters");
    imu.configure_sample_rate(9, 9).expect("configure sample rate");
    let (ox, oy, oz) = imu.get_accel_offset().expect("accel offset");
    check_true!(ox >= -16384 && oy >= -16384 && oz >= -16384, "accel offset readable");

    imu.enable_mag(&mut delay).expect("enable mag");
    delay.delay_millis(150);
    let (mx, my, mz) = imu.mag().expect("read mag");
    let m_sq = mx * mx + my * my + mz * mz;
    check_true!(m_sq > 25.0 && m_sq < 22500.0, "mag magnitude");
    check_true!(imu.aux_read_byte(0x0C, 0x01, &mut delay).expect("aux read") == 0x09, "aux_read WIA2");

    imu.set_sleep(true).expect("set sleep");
    delay.delay_millis(10);
    imu.set_sleep(false).expect("clear sleep");
    delay.delay_millis(50);
    let (ax3, _, _) = imu.accel().expect("accel after wake");
    check_true!(ax3 > -200.0 && ax3 < 200.0, "accel after wake");

    imu.enable_fifo(true, true, false).expect("enable fifo");
    delay.delay_millis(50);
    let count = imu.fifo_count().expect("fifo count");
    check_true!(count > 0, "fifo_count > 0");
    let mut data = [0u8; 256];
    let read = imu.read_fifo(&mut data).expect("read fifo");
    check_true!(read > 0, "read_fifo returns bytes");
    imu.reset_fifo().expect("reset fifo");

    imu.set_wake_on_motion(200.0, true).expect("wake on motion");
    let status = imu.poll_interrupt().expect("poll interrupt");
    check_true!(status <= 0x1F, "poll_interrupt callable");

    esp_println::println!("===DONE: {} passed, {} failed===", passed, failed);
    loop {}
}
