//! BNO055 hardware-in-loop test for ESP32-S3.
//! Wiring: GPIO1 -> SDA, GPIO2 -> SCL; COM3 high (address 0x29).
#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_hal::main;
use esp_hal::time::Rate;
use periph::chips::imu::bno055::{Bno055Full, MODE_AMG, MODE_NDOF, SOURCE_ACC_BSX_DRDY};

esp_app_desc!();

#[main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default().with_frequency(Rate::from_khz(100)))
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut delay = Delay::new();
    let mut imu = Bno055Full::new(i2c, 0x29, &mut delay).expect("init");

    esp_println::println!("BNO055 test started");

    let mut passed = 0i32;
    let mut failed = 0i32;

    macro_rules! check_true {
        ($cond:expr, $label:expr) => {
            if $cond { esp_println::println!("PASS {}", $label); passed += 1; }
            else      { esp_println::println!("FAIL {}", $label); failed += 1; }
        };
    }

    let rev = imu.revisions().expect("revisions");
    check_true!(rev.chip_id == 0xA0, "chip_id");
    check_true!(rev.acc_id == 0xFB && rev.mag_id == 0x32 && rev.gyr_id == 0x0F, "sensor ids");
    check_true!(imu.get_mode().expect("mode") == 0x0C, "mode_ndof");

    let (h, r, p) = imu.euler().expect("euler");
    check_true!(h >= 0.0 && h < 6.5 && r > -1.6 && r < 1.6 && p > -3.2 && p < 3.2, "euler range");
    let (w, x, y, z) = imu.quaternion().expect("quaternion");
    let q = w * w + x * x + y * y + z * z;
    check_true!(q > 0.9 && q < 1.1, "quaternion unit");
    let (gx, gy, gz) = imu.gravity().expect("gravity");
    let g2 = gx * gx + gy * gy + gz * gz;
    check_true!(g2 > 72.0 && g2 < 121.0, "gravity ~1 g");
    let (lx, ly, lz) = imu.linear_acceleration().expect("linear");
    check_true!(lx.abs() < 5.0 && ly.abs() < 5.0 && lz.abs() < 5.0, "linear acceleration small");
    let t = imu.temperature().expect("temperature");
    check_true!(t > -40 && t < 85, "temperature range");
    let (cs, cg, ca, cm) = imu.calibration_status().expect("calibration");
    check_true!(cs <= 3 && cg <= 3 && ca <= 3 && cm <= 3, "calibration status range");
    let (status, error) = imu.system_status().expect("status");
    check_true!(status == 5 && error == 0, "system status");
    let _ = imu.unique_id().expect("unique id");
    let _ = imu.get_calibration(&mut delay).expect("get calibration");
    check_true!(imu.get_mode().expect("mode") == 0x0C, "mode restored after get_calibration");

    imu.set_mode(MODE_AMG, &mut delay).expect("set mode");
    check_true!(imu.get_mode().expect("mode") == 0x07, "mode_amg");
    let (ax, ay, az) = imu.acceleration().expect("acceleration");
    let a2 = ax * ax + ay * ay + az * az;
    check_true!(a2 > 64.0 && a2 < 132.0, "accel magnitude ~1 g");
    let (mx, my, mz) = imu.magnetic_field().expect("magnetic field");
    let m2 = mx * mx + my * my + mz * mz;
    check_true!(m2 > 25.0 && m2 < 40000.0, "mag field range");
    imu.set_mode(MODE_NDOF, &mut delay).expect("set mode");
    check_true!(imu.get_mode().expect("mode") == 0x0C, "mode_ndof_again");

    let st = imu.self_test(&mut delay).expect("self test");
    check_true!(st.acc && st.mag && st.gyr && st.mcu, "self_test passes");
    check_true!(imu.get_mode().expect("mode") == 0x0C, "mode restored after self_test");

    imu.enable_interrupt(SOURCE_ACC_BSX_DRDY).expect("enable interrupt");
    let _ = imu.poll_interrupt().expect("poll interrupt");
    imu.disable_interrupt(SOURCE_ACC_BSX_DRDY).expect("disable interrupt");
    check_true!(true, "interrupt api");

    esp_println::println!("===DONE: {} passed, {} failed===", passed, failed);
    loop {
        delay.delay_ms(1000);
    }
}
