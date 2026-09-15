use linux_embedded_hal::I2cdev;
use periph::chips::gyroscope::{L3g4200dFull, FS_500_DPS, FIFO_STREAM, ODR_200_HZ};

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x68);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");

    // --- Rotation detector: 200 Hz, ±500 dps, FIFO stream with watermark 10 ---
    // 200 Hz ODR gives 5 ms per sample — fast enough to catch hand motion but
    // not so noisy that the FIFO drains before the watermark is reached.
    let mut gyro = L3g4200dFull::new(dev, addr, false).expect("init L3G4200D");      // Create L3G4200D driver, (i2c, addr=0x68, spi=false)
    gyro.configure(ODR_200_HZ, 0, FS_500_DPS).expect("configure");                     // Configure chip, (odr=ODR_200_HZ, bandwidth=0, full_scale=FS_500_DPS) → ()
    gyro.enable_highpass(0, 4).expect("enable_highpass");                             // Enable high-pass, (mode=0, cutoff=4) → ()
                                                                                       // cutoff index 4 at 200 Hz ODR ≈ 1 Hz; strips DC drift
    gyro.enable_fifo(FIFO_STREAM, 10).expect("enable_fifo");                          // Enable FIFO, (mod=FIFO_STREAM=2, watermark=10) → ()

    let threshold_rad_s: f32 = 90.0 * (core::f32::consts::PI / 180.0);
    let mut alerts = 0;

    // --- Loop: wait for FIFO watermark, drain, compute mean, alert on threshold ---
    // Stream mode keeps the oldest samples; the FIFO never blocks but the host
    // only acts once per watermark crossing to amortise I²C overhead.
    for _ in 0..50 {
        while gyro.fifo_samples().expect("fifo_samples") < 10 {                        // Read FIFO count, () → u8
            std::thread::sleep(std::time::Duration::from_millis(5));
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
        std::thread::sleep(std::time::Duration::from_millis(20));
    }

    println!("Total alerts: {} / 50", alerts);
}
