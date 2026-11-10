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
    let mut motor = Tmc5160Full::new_spi(open_spi(), Delay, 0.075, None::<Tmc5160NoPin>, None::<Tmc5160NoPin>, None::<Tmc5160NoPin>).expect("init"); // Create TMC5160 Full driver, (spi, delay, r_sense_ohm=0.075 Ω, en_pin, diag0_pin, diag1_pin) → Result

    motor.set_current(1.0).expect("current"); // Set run current, (rms_a A RMS) → Result<(), Error>
                                              // programs GLOBALSCALER and IRUN/IHOLD
    motor.set_hold_current(0.3).expect("hold"); // Set hold current, (rms_a A RMS) → Result<(), Error>
    motor.set_microsteps(256).expect("microsteps"); // Set microstep resolution, (n 1–256) → Result<(), Error>
    motor.configure_ramp(0.0, 800.0, 400.0, 400.0, 400.0, 400.0, 800.0, 10.0).expect("ramp"); // Program SixPoint ramp, (vstart, a1, v1, amax, vmax, dmax, d1, vstop) → Result<(), Error>
    motor.set_zero_wait(0.1).expect("zerowait"); // Set TZEROWAIT, (seconds s) → Result<(), Error>
    motor.set_stall_threshold(0).expect("sgt"); // Set StallGuard2 threshold, (sgt -64..63) → Result<(), Error>
    motor.enable().expect("enable"); // Power stage on, () → Result<(), Error>
    motor.set_position(0.0).expect("set position"); // Define position, (position full steps) → Result<(), Error>
    motor.move_to(800.0).expect("move"); // Start positioning move, (position full steps) → Result<(), Error>
    motor.wait_until_reached(None).expect("wait"); // Wait for the ramp generator, (timeout_s=None s) → Result<bool, Error>
    println!("position: {}", motor.position().expect("position")); // Read XACTUAL, () → Result<f32, Error> full steps
    println!("velocity: {}", motor.velocity().expect("velocity")); // Read VACTUAL, () → Result<f32, Error> full steps/s
    println!("ramp: {:?}", motor.read_ramp_status().expect("ramp status")); // Read RAMP_STAT, () → Result<Tmc5160RampStatus, Error>
    println!("status: {:?}", motor.read_status().expect("status")); // Read DRV_STATUS, () → Result<Tmc5160Status, Error>
    println!("sg: {}", motor.read_stallguard().expect("sg")); // Read SG_RESULT, () → Result<u16, Error>
    println!("tstep: {}", motor.read_tstep().expect("tstep")); // Read TSTEP, () → Result<u32, Error>
    println!("gstat: {:?}", motor.read_global_status().expect("gstat")); // Read GSTAT, () → Result<Tmc5160GlobalStatus, Error>
    motor.run(300.0).expect("run"); // Velocity mode, (full_steps_per_s full steps/s) → Result<(), Error>
    std::thread::sleep(std::time::Duration::from_millis(500));
    motor.stop().expect("stop"); // Controlled stop, () → Result<(), Error>
    motor.disable().expect("disable"); // Power stage off, () → Result<(), Error>
    println!("===DONE: 0 passed, 0 failed===");
}
