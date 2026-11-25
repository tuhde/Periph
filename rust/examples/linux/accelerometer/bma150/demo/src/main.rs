use std::time::{Duration, Instant};
use linux_embedded_hal::I2cdev;
use periph::chips::accelerometer::{Bma150Full, STATUS_LG_LATCHED, STATUS_HG_LATCHED};

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x38);

    let dev  = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut chip = Bma150Full::new(dev, addr).expect("init BMA150");

    chip.set_range(8).expect("set_range");
    chip.set_bandwidth(190).expect("set_bandwidth");
    chip.set_latch(true).expect("set_latch");
    chip.set_low_g(0.4, 40, 0.0, 0).expect("set_low_g");
    chip.set_high_g(4.0, 2, 0.0, 0).expect("set_high_g");

    let start = Instant::now();
    let mut last_heartbeat = start;
    let mut last_poll = start;

    while start.elapsed() < Duration::from_secs(60) {
        let now = Instant::now();
        if now.duration_since(last_heartbeat) >= Duration::from_secs(1) {
            let (x, y, z) = chip.read().expect("read");
            let mag = (x * x + y * y + z * z).sqrt();
            let temp = chip.read_temperature().expect("read_temperature");
            println!("{:>5}  x={:+.3}  y={:+.3}  z={:+.3}  |a|={:.3} g  T={:.1} C",
                     start.elapsed().as_secs(), x, y, z, mag, temp);
            last_heartbeat = now;
        }
        if now.duration_since(last_poll) >= Duration::from_millis(50) {
            let status = chip.poll_interrupt().expect("poll_interrupt");
            if status & STATUS_LG_LATCHED != 0 {
                let (x, y, z) = chip.read().expect("read");
                let temp = chip.read_temperature().expect("read_temperature");
                println!("{:>5}  FREE FALL detected  x={:+.3}  y={:+.3}  z={:+.3}  T={:.1} C",
                         start.elapsed().as_secs(), x, y, z, temp);
                chip.clear_interrupt().expect("clear_interrupt");
            }
            if status & STATUS_HG_LATCHED != 0 {
                let (x, y, z) = chip.read().expect("read");
                let temp = chip.read_temperature().expect("read_temperature");
                println!("{:>5}  SHOCK detected     x={:+.3}  y={:+.3}  z={:+.3}  T={:.1} C",
                         start.elapsed().as_secs(), x, y, z, temp);
                chip.clear_interrupt().expect("clear_interrupt");
            }
            last_poll = now;
        }
        std::thread::sleep(Duration::from_millis(10));
    }
    println!("done");
}
