use linux_embedded_hal::I2cdev;
use periph::chips::accelerometer::{Bma180Full, STATUS_LOW_G, STATUS_TAP};

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x40);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut chip = Bma180Full::new(dev, addr).expect("init BMA180");

    // --- Configure for tilt + tap + free-fall demo at low-noise, 40 Hz, ±2 g ---
    chip.set_bandwidth(40).expect("set bandwidth");                              // Set bandwidth, (bandwidth_hz=40 Hz) → ()
    // --- Calibrate zero-g while the board sits level ---
    chip.calibrate_offset(0x07, 1).expect("calibrate offset");                   // Calibrate offset, (axes, mode) → ()
    // --- Arm tap and free-fall detection with latching so we never miss an event ---
    chip.set_tap(0.5, 250).expect("set tap");                                    // Configure tap, (threshold_g, window_ms) → ()
    chip.set_low_g(0.3, 40).expect("set low-g");                                 // Configure low-g, (threshold_g, duration_ms) → ()
    chip.set_latch(true).expect("set latch");                                    // Set latch, (enabled=True) → ()

    // --- Print tilt + temperature every 100 ms; poll interrupts for tap/free-fall ---
    let start = std::time::Instant::now();
    while start.elapsed().as_secs() < 60 {
        let (x, y, z) = chip.read().expect("read");                              // Read 3-axis acceleration, () → (f32, f32, f32) g
        let pitch = (x.atan2((y * y + z * z).sqrt())).to_degrees();
        let roll  = (y.atan2((x * x + z * z).sqrt())).to_degrees();
        let mag   = (x * x + y * y + z * z).sqrt();
        let t     = chip.read_temperature().expect("read temp");                 // Read temperature, () → float °C
        println!("pitch={:+.1} roll={:+.1} |a|={:.3} g  T={:+.1} C",
                 pitch, roll, mag, t);

        let flags = chip.poll_interrupt().expect("poll interrupt");              // Read STATUS_REG3, () → u8
        if flags & STATUS_TAP != 0 {
            println!("DOUBLE TAP");
            chip.clear_interrupt().expect("clear");                              // Clear latched interrupts, () → ()
        }
        if flags & STATUS_LOW_G != 0 {
            println!("FREE FALL");
            chip.clear_interrupt().expect("clear");                              // Clear latched interrupts, () → ()
        }
        std::thread::sleep(std::time::Duration::from_millis(100));
    }
}