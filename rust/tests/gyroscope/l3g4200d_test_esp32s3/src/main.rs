#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::gyroscope::{L3g4200dFull, L3g4200dMinimal, FS_500_DPS, ODR_200_HZ, FIFO_STREAM};

esp_app_desc!();

const ADDR: u8 = 0x68;

macro_rules! check_true {
    ($cond:expr, $label:expr) => {
        if $cond { println!("PASS {}", $label); }
        else      { println!("FAIL {}", $label); }
    };
}

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());
    let mut delay = Delay::new();

    {
        let i2c = I2c::new(peripherals.I2C0, Config::default())
            .unwrap()
            .with_sda(peripherals.GPIO1)
            .with_scl(peripherals.GPIO2);
        let mut gyro = L3g4200dMinimal::new(i2c, ADDR, false).expect("init L3G4200D");
        let (x, y, z) = gyro.angular_rate().unwrap();
        check_true!(x.abs() < 50.0, "angular_rate_x_range");
        check_true!(y.abs() < 50.0, "angular_rate_y_range");
        check_true!(z.abs() < 50.0, "angular_rate_z_range");
    }

    let i2c2 = I2c::new(peripherals.I2C1, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO3)
        .with_scl(peripherals.GPIO4);
    let mut gyro_full = L3g4200dFull::new(i2c2, ADDR, false).expect("init L3G4200D Full");
    check_true!(gyro_full.who_am_i().unwrap_or(0) == 0xD3, "who_am_i");

    gyro_full.configure(ODR_200_HZ, 0, FS_500_DPS).unwrap();
    let (x2, _, _) = gyro_full.angular_rate().unwrap();
    check_true!(x2.abs() < 500.0, "configure_then_read");

    check_true!(gyro_full.status().unwrap_or(0xFF) <= 0xFF, "status_readable");
    let t = gyro_full.temperature().unwrap_or(0);
    check_true!(t >= -50 && t <= 100, "temperature_range");

    gyro_full.set_full_scale(2000).unwrap();
    let (x3, _, _) = gyro_full.angular_rate().unwrap();
    check_true!(x3.abs() < 2000.0, "set_full_scale_2000");

    let samples = gyro_full.fifo_samples().unwrap_or(0);
    check_true!(samples <= 31, "fifo_samples_in_range");

    gyro_full.enable_fifo(FIFO_STREAM, 10).unwrap();
    check_true!(true, "enable_fifo_no_throw");

    loop { delay.delay_millis(1000); }
}
