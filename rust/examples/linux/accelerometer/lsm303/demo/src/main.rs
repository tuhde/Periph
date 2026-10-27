use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::accelerometer::{Lsm303Full, SOURCE_CLICK, SOURCE_IA1};
use periph::connection::connection::Connection;
use std::thread::sleep;
use std::time::{Duration, Instant};

const COMPASS_POINTS: [&str; 16] = [
    "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
    "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW",
];

fn compass_name(heading_deg: f32) -> &'static str {
    let idx = (((heading_deg + 11.25) / 22.5) as i32).rem_euclid(16) as usize;
    COMPASS_POINTS[idx]
}

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let conn = Connection::new(dev);                                          // Create bus connection, (i2cdev) → Connection
    let mut delay = Delay;
    let mut chip = Lsm303Full::new(conn, &mut delay).expect("init LSM303");   // Create LSM303 full driver, (connection, delay) → Result

    // --- Configure ±2 g / 50 Hz high-resolution accelerometer and ±130 µT / 15 Hz magnetometer ---
    // The demo rates keep every second's 50 samples inside the 16-sample
    // FIFO watermark window, so no sample is lost between drains.
    chip.set_data_rate(50).expect("set_data_rate");                           // Set accelerometer data rate, (rate_hz=50 Hz)
                                                                              // RMW CTRL_REG1_A ODR bits (1620/5376 low-power only)
    chip.set_mag_range(130).expect("set_mag_range");                          // Set magnetometer full scale, (range_ut=130 µT)
                                                                              // CRB_REG_M GN bits; updates cached conversion gains
    chip.set_fifo(2, 16, false).expect("set_fifo");                           // Configure FIFO, (mode=2 stream, watermark=16, trigger_int2=false)
                                                                              // Stream mode with 16-sample watermark on INT1

    // --- Arm double tap on Z and latched free fall, both routed to INT1 ---
    chip.set_click(0, 4, 0.8, 20.0, 40.0, 200.0).expect("set_click");         // Configure click detection, (single=0 off, double=4 Z, threshold_g=0.8 g, time_limit_ms=20 ms, latency_ms=40 ms, window_ms=200 ms)
                                                                              // Z-axis double tap: CLICK_CFG ZD bit
    chip.set_free_fall(1, 0.35, 60.0).expect("set_free_fall");                // Configure free fall, (generator=1, threshold_g=0.35 g, duration_ms=60 ms)
                                                                              // All-axes-low AND event, INT1_CFG = 0x95
    chip.set_latch(1, true).expect("set_latch");                              // Latch generator interrupt, (generator=1, enabled=true)
                                                                              // INT1 stays asserted until the source register is read
    chip.enable_interrupt(SOURCE_CLICK, 1).expect("enable_interrupt");        // Route source to INT pin, (source, pin=1)
                                                                              // CTRL_REG3_A I1_CLICKen: clicks on INT1
    chip.enable_interrupt(SOURCE_IA1, 1).expect("enable_interrupt");          // Route source to INT pin, (source, pin=1)
                                                                              // CTRL_REG3_A I1_AOI1: free fall on INT1

    // --- Run 60 s: drain the FIFO once a second and report compass + events ---
    let start = Instant::now();
    let mut last_drain = start;
    while start.elapsed() < Duration::from_secs(60) {
        let status = chip.poll_interrupt().expect("poll_interrupt");          // Read and clear interrupt sources, () → u16 status
                                                                              // Reading clears latched INT1 flags
        if status & SOURCE_CLICK != 0 {
            println!("DOUBLE TAP");
        }
        if status & SOURCE_IA1 != 0 {
            println!("FREE FALL");
        }

        if last_drain.elapsed() >= Duration::from_secs(1) {
            last_drain = Instant::now();
            let level = chip.fifo_level().expect("fifo_level");               // Read unread sample count, () → u8 samples
                                                                              // FSS<4:0> from FIFO_SRC_REG_A (0–32)
            let samples = chip.read_fifo(None).expect("read_fifo");           // Drain FIFO samples, (count=None) → heapless::Vec<(f32, f32, f32)> g
                                                                              // One 6-byte burst pop per sample, converted at cached sensitivity
            if samples.is_empty() {
                continue;
            }
            let n = samples.len() as f32;
            let (mut ax, mut ay, mut az) = (0.0f32, 0.0f32, 0.0f32);
            for &(x, y, z) in &samples {
                ax += x;
                ay += y;
                az += z;
            }
            ax /= n;
            ay /= n;
            az /= n;
            let (mx, my, mz) = chip.read_magnetic().expect("read_magnetic");  // Read magnetic flux density, () → (f32, f32, f32) µT
                                                                              // Burst-reads X, Z, Y and reorders; converts at cached gain
            let heading = chip.heading(true).expect("heading");               // Compute compass heading, (tilt_compensate=true) → f32 °
                                                                              // Tilt-compensated atan2(y, x) using the averaged acceleration
            let field = (mx * mx + my * my + mz * mz).sqrt();
            let roll = (ay).atan2(az) * 180.0 / core::f32::consts::PI;
            let pitch = (-ax).atan2((ay * ay + az * az).sqrt()) * 180.0 / core::f32::consts::PI;
            let temperature = chip.read_temperature_delta().expect("read_temperature_delta"); // Read relative temperature, () → f32 °C
                                                                              // 8 LSB/°C; enables TEMP_EN on first use
            println!("{:>3}s  fifo={}  heading={:6.1} deg {}  pitch={:+6.1}  roll={:+6.1}  |B|={:6.1} uT  dT={:+.1} C",
                     start.elapsed().as_secs(), level, heading, compass_name(heading), pitch, roll, field, temperature);
        }
        sleep(Duration::from_millis(10));
    }
    println!("done");
}
