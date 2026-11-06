//! MMA8451Q demo — orientation, tap and freefall monitor with Auto-SLEEP.
//!
//! Configures ±4 g at 100 Hz with high-resolution oversampling, runs the
//! self-test, enables orientation detection, double-tap on Z and the
//! freefall detector, routes the three interrupts to INT1, puts the FIFO in
//! circular mode with a 16-sample watermark and enables Auto-SLEEP. Every
//! 250 ms the acceleration and the system mode are printed; detected events
//! are reported as they occur. After 60 s the FIFO is drained and the mean
//! acceleration is printed.

use linux_embedded_hal::I2cdev;
use periph::chips::accelerometer::{
    Mma8451QFull,
    MMA8451Q_SOURCE_FF_MT, MMA8451Q_SOURCE_ORIENTATION, MMA8451Q_SOURCE_PULSE,
};
use std::time::{Duration, Instant};

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x1C);

    let dev  = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    // Create MMA8451Q driver, (i2c bus, i2c addr) → Result
    let mut chip = Mma8451QFull::new(dev, addr).expect("init MMA8451Q");

    // Wait for the STANDBY → ACTIVE turn-on time, (ms) → None
    std::thread::sleep(Duration::from_millis(22));

    // --- Configure the measurement chain ---
    // ±4 g gives enough headroom for the tap and freefall thresholds while
    // still resolving 0.5 g steps.
    chip.set_range(4).expect("set_range");
    chip.set_data_rate(100.0).expect("set_data_rate");
    // High-resolution oversampling lowers the noise so the orientation
    // detector does not chatter around the trip angle.
    chip.set_oversampling(2, Some(0)).expect("set_oversampling");

    // --- Verify the device with the electrostatic self-test ---
    let (st_ok, dx, dy, dz) = chip.self_test().expect("self_test");
    println!("self-test: {} (dx={} dy={} dz={})",
             if st_ok { "PASS" } else { "FAIL" }, dx, dy, dz);

    // --- Arm the three detectors ---
    chip.set_orientation(true, 100.0, 45, 4, 4, 1).expect("set_orientation");
    chip.set_pulse(0, 0x04, (0.5, 0.5, 1.0), 40.0, 100.0, 300.0,
                    true, false, true, true).expect("set_pulse");
    chip.set_freefall_motion(false, 0x07, 0.2, 60.0, true, false)
        .expect("set_freefall_motion");

    // --- Sleep after 10 s of inactivity, wake on a tap or freefall ---
    chip.set_auto_sleep(true, 12.5, 10.0,
                        MMA8451Q_SOURCE_PULSE | MMA8451Q_SOURCE_FF_MT)
        .expect("set_auto_sleep");

    // --- Route the events to INT1 and buffer samples in the FIFO ---
    chip.enable_interrupt(MMA8451Q_SOURCE_ORIENTATION, 1).expect("enable_interrupt");
    chip.enable_interrupt(MMA8451Q_SOURCE_PULSE, 1).expect("enable_interrupt");
    chip.enable_interrupt(MMA8451Q_SOURCE_FF_MT, 1).expect("enable_interrupt");
    chip.set_fifo(1, 16).expect("set_fifo");

    // --- Monitor for 60 s ---
    let start = Instant::now();
    let mut last_print = Instant::now() - Duration::from_millis(250);
    let mut last_poll = Instant::now();

    while start.elapsed() < Duration::from_secs(60) {
        let now = Instant::now();

        if now.duration_since(last_print) >= Duration::from_millis(250) {
            // Read 3-axis acceleration, () → (f32 x, f32 y, f32 z) g
            let (x, y, z) = chip.read().expect("read");
            // Read the system mode, () → u8
            let mode = chip.read_system_mode().expect("read_system_mode");
            let mode_name = match mode {
                0 => "STANDBY",
                1 => "WAKE",
                2 => "SLEEP",
                _ => "?",
            };
            // Read the FIFO level, () → u8
            let level = chip.fifo_level().expect("fifo_level");
            println!("{:>5}  x={:+.3}  y={:+.3}  z={:+.3}  {}  fifo={}",
                     start.elapsed().as_secs(), x, y, z, mode_name, level);
            last_print = now;
        }

        if now.duration_since(last_poll) >= Duration::from_millis(50) {
            // Read and clear the interrupt status, () → u8
            let status = chip.poll_interrupt().expect("poll_interrupt");
            if status & MMA8451Q_SOURCE_ORIENTATION != 0 {
                // Read the orientation status, () → (bool changed, bool lockout, u8 portrait_landscape, bool back)
                let (_, _, lapo, back) = chip.read_orientation().expect("read_orientation");
                let name = match lapo {
                    0 => "portrait up",
                    1 => "portrait down",
                    2 => "landscape right",
                    _ => "landscape left",
                };
                println!("{:>5}  ORIENTATION: {} ({})",
                         start.elapsed().as_secs(), name,
                         if back { "back" } else { "front" });
            }
            if status & MMA8451Q_SOURCE_PULSE != 0 {
                // Read the tap source register, () → u8
                let src = chip.read_pulse_source().expect("read_pulse_source");
                println!("{:>5}  DOUBLE TAP (src=0x{:02X})", start.elapsed().as_secs(), src);
            }
            if status & MMA8451Q_SOURCE_FF_MT != 0 {
                // Read the freefall/motion source register, () → u8
                let src = chip.read_motion_source().expect("read_motion_source");
                println!("{:>5}  FREE FALL (src=0x{:02X})", start.elapsed().as_secs(), src);
            }
            last_poll = now;
        }

        std::thread::sleep(Duration::from_millis(10));
    }

    // --- Drain the FIFO and report the mean acceleration ---
    // Read the FIFO samples, (count) → Vec<(f32 x, f32 y, f32 z)> g
    let samples = chip.read_fifo(None).expect("read_fifo");
    let n = samples.len();
    let (mut sx, mut sy, mut sz) = (0.0f32, 0.0f32, 0.0f32);
    for s in &samples {
        sx += s.0;
        sy += s.1;
        sz += s.2;
    }
    if n > 0 {
        println!("fifo: {} samples, mean=({:.3}, {:.3}, {:.3}) g",
                 n, sx / n as f32, sy / n as f32, sz / n as f32);
    } else {
        println!("fifo: empty");
    }
    println!("done");
}
