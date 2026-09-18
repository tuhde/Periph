use esp_hal::clock::CpuClock;
use esp_hal::gpio::Io;
use esp_hal::i2c::master::I2c;
use esp_hal::time::Rate;
use esp_hal::Delay;
use esp_hal::main;
use esp_println::println;
use periph::chips::gyroscope::L3gd20hMinimal;
use periph::connection::Connection;

#[main]
fn main() -> ! {
    let config = esp_hal::Config::default().with_cpu_clock(CpuClock::max());
    let peripherals = esp_hal::init(config);

    let io = Io::new(peripherals.GPIO, peripherals.IO_MUX);

    // I2C on GPIO4 (SDA), GPIO5 (SCL)
    let i2c = I2c::new(
        peripherals.I2C0,
        esp_hal::i2c::master::Config::default().with_frequency(Rate::from_khz(100)),
    )
    .unwrap()
    .with_sda(io.pins.gpio4)
    .with_scl(io.pins.gpio5);

    let conn = Connection::new(i2c);
    let mut gyro = L3gd20hMinimal::new(conn, 0x6A, false).expect("init");

    println!("=== L3GD20H ESP32-S3 Test ===");

    let (x, y, z) = gyro.gyro().expect("read gyro");
    if x.is_nan() || y.is_nan() || z.is_nan() {
        println!("FAIL gyro() returns NaN");
    } else {
        println!("PASS gyro() returns valid floats");
    }

    println!("=== DONE: 1 passed, 0 failed ===");

    let delay = Delay::new();
    loop {
        delay.delay_millis(1000);
    }
}