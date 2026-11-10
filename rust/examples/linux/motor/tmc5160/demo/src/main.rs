use embedded_hal_bus::spi::ExclusiveDevice;
use linux_embedded_hal::{Delay, SpidevBus};
use periph::chips::motor::{Tmc5160Full, Tmc5160NoPin};
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
    // --- Pick-and-place axis: smooth trapezoid between two stations ---
    // The internal ramp generator accelerates at 400 full steps/s^2 up to 600 full steps/s;
    // StealthChop keeps the axis quiet, StallGuard2 reports a crash against the end stop.
    let mut motor = Tmc5160Full::new_spi(open_spi(), Delay, 0.075, None::<Tmc5160NoPin>, None::<Tmc5160NoPin>, None::<Tmc5160NoPin>).expect("init"); // Create TMC5160 Full driver, (spi, delay, r_sense_ohm=0.075 Ω, en_pin, diag0_pin, diag1_pin) → Result
    motor.set_current(1.2).expect("current"); // Set run current, (rms_a A RMS) → Result<(), Error>
    motor.set_speed(600.0).expect("speed"); // Set VMAX, (full_steps_per_s full steps/s) → Result<(), Error>
    motor.set_acceleration(400.0).expect("accel"); // Set acceleration, (full_steps_per_s2 full steps/s²) → Result<(), Error>
    motor.set_stall_threshold(2).expect("sgt"); // Set StallGuard2 threshold, (sgt -64..63) → Result<(), Error>
    motor.enable().expect("enable"); // Power stage on, () → Result<(), Error>
    motor.set_position(0.0).expect("origin"); // Define position, (position full steps) → Result<(), Error>

    // --- Shuttle between the stations and report each stop ---
    for station in [1000.0, 0.0, 1000.0, 0.0] {
        motor.move_to(station).expect("move"); // Start positioning move, (position full steps) → Result<(), Error>
        motor.wait_until_reached(None).expect("wait"); // Wait for the ramp generator, (timeout_s=None s) → Result<bool, Error>
        println!("at {} (SG {})", motor.position().expect("position"), motor.read_stallguard().expect("sg")); // Read XACTUAL, () → Result<f32, Error> full steps
        if motor.read_fault().expect("fault") != 0 { // Read fault bitmask, () → Result<u8, Error>
            println!("driver fault, aborting");
            break;
        }
    }

    motor.disable().expect("disable"); // Power stage off, () → Result<(), Error>
    println!("===DONE: 0 passed, 0 failed===");
}
