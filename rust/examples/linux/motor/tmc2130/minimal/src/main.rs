use embedded_hal_bus::spi::ExclusiveDevice;
use gpio_cdev::{Chip, LineRequestFlags};
use linux_embedded_hal::{CdevPin, Delay, SpidevBus};
use periph::chips::motor::{Tmc2130Minimal, Tmc2130NoPin};
use spidev::{SpiModeFlags, Spidev, SpidevOptions};

struct NullCs;
impl embedded_hal::digital::ErrorType for NullCs {
    type Error = core::convert::Infallible;
}
impl embedded_hal::digital::OutputPin for NullCs {
    fn set_low(&mut self) -> Result<(), Self::Error> { Ok(()) }
    fn set_high(&mut self) -> Result<(), Self::Error> { Ok(()) }
}

fn pin(chip: &mut Chip, line: u32, name: &str, flags: LineRequestFlags) -> CdevPin {
    let handle = chip.get_line(line).expect("get line")
        .request(flags, 0, name).expect("request line");
    CdevPin::new(handle).expect("pin")
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
    let mut chip = Chip::new("/dev/gpiochip0").expect("open gpio chip");
    let step = pin(&mut chip, 27, "tmc2130_step", LineRequestFlags::OUTPUT);
    let dir = pin(&mut chip, 22, "tmc2130_dir", LineRequestFlags::OUTPUT);

    let mut motor = Tmc2130Minimal::new(open_spi(), step, dir, Delay, 0.11, None::<Tmc2130NoPin>).expect("init"); // Create TMC2130 driver, (spi, step_pin, dir_pin, delay, r_sense_ohm=0.11 Ω, en_pin=None) → Result

    motor.set_current(0.8).expect("current"); // Set run current, (rms_a A RMS) → Result<(), Error>
    motor.enable().expect("enable"); // Power stage on, () → Result<(), Error>
    for steps in [400, -400] {
        motor.move_steps(steps, 200.0).expect("move"); // Move, (full_steps, full_steps_per_s full steps/s) → Result<(), Error>
        std::thread::sleep(std::time::Duration::from_millis(500));
        println!("standstill: {}", motor.is_standstill().expect("stst")); // Read standstill flag, () → Result<bool, Error>
        println!("fault: {}", motor.read_fault().expect("fault")); // Read fault bitmask, () → Result<u8, Error>
    }

    motor.disable().expect("disable"); // Power stage off, () → Result<(), Error>
    println!("===DONE: 0 passed, 0 failed===");
}
