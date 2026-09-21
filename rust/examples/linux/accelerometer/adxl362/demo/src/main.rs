use embedded_hal_bus::spi::ExclusiveDevice;
use linux_embedded_hal::SpidevBus;
use periph::chips::accelerometer::{
    Adxl362Full, LINKLOOP_LOOP, SOURCE_AWAKE,
};
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

    let mut chip = Adxl362Full::new(device).expect("init ADXL362");              // Create ADXL362 full driver, (spi) → Result

    // --- Configure referenced activity/inactivity thresholds ---
    chip.set_activity_threshold(0.25, true).expect("set_activity_threshold");   // Set activity threshold, (threshold_g=0.25, referenced=true) → Result<()>
    chip.set_inactivity_threshold(0.15, true).expect("set_inactivity_threshold"); // Set inactivity threshold, (threshold_g=0.15, referenced=true) → Result<()>
    chip.set_inactivity_time(30).expect("set_inactivity_time");                  // Set inactivity time, (samples=30) → Result<()>

    // --- Engage linked/loop mode and enable both detectors ---
    chip.enable_activity_detection(true).expect("enable_activity_detection");    // Enable activity detection, (enabled=true) → Result<()>
    chip.enable_inactivity_detection(true).expect("enable_inactivity_detection"); // Enable inactivity detection, (enabled=true) → Result<()>
    chip.set_link_loop_mode(LINKLOOP_LOOP).expect("set_link_loop_mode(LOOP)");   // Set link/loop mode, (mode=LOOP) → Result<()>

    // --- Map AWAKE to INT2 and enter wake-up mode ---
    chip.set_interrupt(2, SOURCE_AWAKE, true).expect("set_interrupt(2, AWAKE, true)");  // Map AWAKE to INT2, (pin=2, source=AWAKE, enabled=true) → Result<()>
    chip.set_wakeup_mode(true).expect("set_wakeup_mode(true)");                   // Enter wake-up mode, (enabled=true) → Result<()>

    // --- Poll AWAKE for 60 s and count asleep<->awake transitions ---
    println!("Watching for motion. Pick up or tap the board to wake; let it settle to sleep.");
    let start = std::time::Instant::now();
    let mut last_awake: Option<bool> = None;
    let mut transitions = 0;
    while start.elapsed().as_secs() < 60 {                                       // Loop until 60 s elapsed, () → bool
        let now_awake = chip.awake().expect("awake");                            // Read AWAKE bit, () → Result<bool>
        if last_awake.map_or(true, |prev| prev != now_awake) {
            println!("{:6.2}s  {}",
                     start.elapsed().as_secs_f64(),
                     if now_awake { "AWAKE" } else { "asleep" });               // Print timestamped state, () → None
            transitions += 1;
            last_awake = Some(now_awake);
        }
        std::thread::sleep(std::time::Duration::from_millis(200));              // Sleep 200 ms between polls, () → None
    }

    println!("Total transitions observed: {}", transitions);                    // Print final count, () → None
    println!("Note: during 'asleep' periods the ADXL362 draws ~270 nA — roughly two orders of \
              magnitude below the ~1.8 µA of the continuous 100 Hz measurement mode used by the \
              Minimal read() example.");
}