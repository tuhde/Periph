use embedded_hal_bus::spi::ExclusiveDevice;
use linux_embedded_hal::SpidevBus;
use periph::chips::accelerometer::Adxl362Minimal;
use spidev::{SpiModeFlags, Spidev, SpidevOptions};

struct NullCs;
impl embedded_hal::digital::ErrorType for NullCs {
    type Error = core::convert::Infallible;
}
impl embedded_hal::digital::OutputPin for NullCs {
    fn set_low(&mut self) -> Result<(), Self::Error> { Ok(()) }
    fn set_high(&mut self) -> Result<(), Self::Error> { Ok(()) }
}

fn main() {
    let bus: u8 = std::env::var("SPI_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(0);
    let dev: u8 = std::env::var("SPI_DEVICE").ok().and_then(|v| v.parse().ok()).unwrap_or(0);
    let dev_path = format!("/dev/spidev{}.{}", bus, dev);

    let mut spi = Spidev::open(dev_path).expect("open spi");
    spi.configure(&SpidevOptions::new()
        .max_speed_hz(8_000_000)
        .mode(SpiModeFlags::SPI_MODE_0)
        .build()).expect("configure spi");
    let bus_obj = SpidevBus(spi);
    let device = ExclusiveDevice::new_no_delay(bus_obj, NullCs).expect("spi device");

    let mut accel = Adxl362Minimal::new(device).expect("init ADXL362");    // Create ADXL362 driver, (spi) → Result

    for _ in 0..10 {
        let (x, y, z) = accel.read().expect("read acceleration");            // Read 3-axis acceleration, () → (f32, f32, f32) g
        println!("x={:+.3}  y={:+.3}  z={:+.3} g", x, y, z);
        std::thread::sleep(std::time::Duration::from_millis(100));
    }
}