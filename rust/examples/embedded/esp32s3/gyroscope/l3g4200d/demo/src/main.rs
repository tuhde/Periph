#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::gyroscope::{L3g4200dFull, FS_500_DPS, FIFO_STREAM, ODR_200_HZ};

esp_app_desc!();

const ADDR: u8 = 0x68;

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut delay = Delay::new();

    // --- Rotation detector: 200 Hz, ±500 dps, FIFO stream with watermark 10 ---
    // 200 Hz ODR gives 5 ms per sample — fast enough to catch hand motion but
    // not so noisy that the FIFO drains before the watermark is reached.
    let mut gyro = L3g4200dFull::new(i2c, ADDR, false).expect("init L3G4200D");      // Create L3G4200D driver, (i2c, ADDR=0x68)
    gyro.configure(ODR_200_HZ, 0, FS_500_DPS).expect("configure");                     // Configure chip, (odr=ODR_200_HZ, bandwidth=0, full_scale=FS_500_DPS) → ()
    gyro.enable_highpass(0, 4).expect("enable_highpass");                             // Enable high-pass, (mode=0, cutoff=4) → ()
                                                                                       // cutoff index 4 at 200 Hz ODR ≈ 1 Hz; strips DC drift
    gyro.enable_fifo(FIFO_STREAM, 10).expect("enable_fifo");                          // Enable FIFO, (mod=FIFO_STREAM=2, watermark=10) → ()

    let threshold_rad_s: f32 = 90.0 * (core::f32::consts::PI / 180.0);
    let mut alerts: u32 = 0;

    // --- Loop: wait for FIFO watermark, drain, compute mean, alert on threshold ---
    // Stream mode keeps the oldest samples; the FIFO never blocks but the host
    // only acts once per watermark crossing to amortise I²C overhead.
    for _ in 0..50 {
        while gyro.fifo_samples().expect("fifo_samples") < 10 {                        // Read FIFO count, () → u8
            delay.delay_ms(5);
        }
        let burst = gyro.read_fifo().expect("read_fifo");                               // Drain FIFO, () → Vec<(x, y, z) rad/s>
        if burst.is_empty() { continue; }
        let n = burst.len() as f32;
        let mx = burst.iter().map(|s| s.0).sum::<f32>() / n;
        let my = burst.iter().map(|s| s.1).sum::<f32>() / n;
        let mz = burst.iter().map(|s| s.2).sum::<f32>() / n;
        if mx.abs() > threshold_rad_s || my.abs() > threshold_rad_s || mz.abs() > threshold_rad_s {
            alerts += 1;
            println!("ALERT  X={:.2} Y={:.2} Z={:.2} rad/s", mx, my, mz);
        } else {
            println!("       X={:.2} Y={:.2} Z={:.2} rad/s", mx, my, mz);
        }
        delay.delay_ms(20);
    }

    println!("Total alerts: {} / 50", alerts);
    loop { delay.delay_ms(1000); }
}
