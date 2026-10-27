use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::accelerometer::lsm303::{FIFO_STREAM, SOURCE_CLICK, SOURCE_IA1};
use periph::chips::accelerometer::Lsm303Full;
use periph::connection::Connection;

const NAMES: [&str; 16] = ["N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
                           "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"];

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut chip = Lsm303Full::new(Connection::new(dev), 0x19, 0x1E, Delay).expect("init LSM303");

    chip.set_range(2).expect("set_range");                           // Set accelerometer range, (range_g) -> g
    chip.set_mode(2).expect("set_mode");                             // Set accelerometer mode, (mode) -> None
    chip.set_data_rate(50).expect("set_data_rate");                  // Set accelerometer data rate, (rate_hz) -> Hz
    chip.set_mag_range(130).expect("set_mag_range");                 // Set magnetometer range, (range_ut) -> µT
    chip.set_mag_data_rate(15.0).expect("set_mag_data_rate");        // Set magnetometer data rate, (rate_hz) -> Hz

    chip.set_click(0, 0x04, 0.8, 20.0, 40.0, 200.0).expect("set_click"); // Configure click detection, (...)
    chip.set_free_fall(1, 0.35, 60.0).expect("set_free_fall");       // Configure free-fall, (...)
    chip.set_latch(1, true).expect("set_latch");                     // Latch generator 1, (generator, enabled)
    chip.enable_interrupt(SOURCE_CLICK, 1).expect("enable_interrupt"); // Route source to INT1, (source, pin)
    chip.enable_interrupt(SOURCE_IA1, 1).expect("enable_interrupt");   // Route source to INT1, (source, pin)
    chip.set_fifo(FIFO_STREAM, 16, false).expect("set_fifo");        // Configure FIFO, (mode, watermark, trigger_int2)

    let start = std::time::Instant::now();
    let mut last = start;
    while start.elapsed().as_secs() < 60 {
        if last.elapsed().as_millis() >= 1000 {
            let level = chip.fifo_level().expect("fifo_level");      // Read FIFO level, () -> u8
            let samples = chip.read_fifo(None).expect("read_fifo");  // Drain FIFO, (count) -> Vec
            let (ax, ay, az) = if samples.is_empty() {
                chip.read_acceleration().expect("read_acceleration") // Read 3-axis acceleration, () -> (g, g, g)
            } else {
                let n = samples.len() as f32;
                let mut a = (0.0, 0.0, 0.0);
                for s in &samples { a.0 += s.0; a.1 += s.1; a.2 += s.2; }
                (a.0 / n, a.1 / n, a.2 / n)
            };
            let (mx, my, mz) = chip.read_magnetic().expect("read_magnetic"); // Read 3-axis magnetic field, () -> (µT, µT, µT)
            let field = f32::sqrt(mx * mx + my * my + mz * mz);
            let pitch = f32::atan2(ax, f32::sqrt(ay * ay + az * az)) * 180.0 / core::f32::consts::PI;
            let roll = f32::atan2(ay, az) * 180.0 / core::f32::consts::PI;
            let hdg = chip.heading(true).expect("heading");         // Compute compass heading, (tilt_compensate) -> deg
            let name = NAMES[((hdg + 11.25) / 22.5) as usize % 16];
            let temp = chip.read_temperature_delta().expect("read_temperature_delta"); // Read relative temperature, () -> °C
            println!("{:5.1}° {}  pitch={:+.1} roll={:+.1} |B|={:.1} µT  T={:+.1} °C  fifo={}",
                     hdg, name, pitch, roll, field, temp, level);
            let isr = chip.poll_interrupt().expect("poll_interrupt"); // Read interrupt status, () -> u16
            if isr & SOURCE_CLICK != 0 { println!("DOUBLE TAP"); }
            if isr & SOURCE_IA1 != 0 { println!("FREE FALL"); }
            last = std::time::Instant::now();
        }
        std::thread::sleep(std::time::Duration::from_millis(10));
    }
    println!("done");
}
