use embedded_hal_bus::spi::ExclusiveDevice;
use linux_embedded_hal::{Delay, SpidevBus};
use periph::chips::motor::{Tmc5160Minimal, Tmc5160NoPin};
use spidev::{SpiModeFlags, Spidev, SpidevOptions};

struct NullCs;
impl embedded_hal::digital::ErrorType for NullCs {
    type Error = core::convert::Infallible;
}
impl embedded_hal::digital::OutputPin for NullCs {
    fn set_low(&mut self) -> Result<(), Self::Error> { Ok(()) }
    fn set_high(&mut self) -> Result<(), Self::Error> { Ok(()) }
}

fn open_spi() -> ExclusiveDevice<SpidevBus, NullCs, embedded_hal_bus::spi::NoDelay> {
    let bus: u8 = std::env::var("SPI_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(0);
    let dev: u8 = std::env::var("SPI_DEVICE").ok().and_then(|v| v.parse().ok()).unwrap_or(0);
    let mut spi = Spidev::open(format!("/dev/spidev{}.{}", bus, dev)).expect("open spi");
    spi.configure(&SpidevOptions::new()
        .max_speed_hz(1_000_000)
        .mode(SpiModeFlags::SPI_MODE_3)
        .build()).expect("configure spi");
    ExclusiveDevice::new_no_delay(SpidevBus(spi), NullCs).expect("spi device")
}


fn main() {
    let mut motor = Tmc5160Minimal::new_spi(open_spi(), Delay, 0.075, None::<Tmc5160NoPin>).expect("init"); // Create TMC5160 driver, (spi, delay, r_sense_ohm=0.075 Ω, en_pin=None) → Result

    motor.set_current(1.0).expect("current"); // Set run current, (rms_a A RMS) → Result<(), Error>
    motor.enable().expect("enable"); // Power stage on, () → Result<(), Error>
    for target in [400.0, 0.0] {
        motor.move_to(target).expect("move"); // Start positioning move, (position full steps) → Result<(), Error>
        motor.wait_until_reached(None).expect("wait"); // Wait for the ramp generator, (timeout_s=None s) → Result<bool, Error>
        println!("position: {}", motor.position().expect("position")); // Read XACTUAL, () → Result<f32, Error> full steps
        println!("fault: {}", motor.read_fault().expect("fault")); // Read fault bitmask, () → Result<u8, Error>
    }

    motor.disable().expect("disable"); // Power stage off, () → Result<(), Error>
    println!("===DONE: 0 passed, 0 failed===");
}
