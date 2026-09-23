//! Touchless presence gate with multi-rate ranging: a first measurement picks
//! the profile (long range in a dark room, default otherwise), then timed
//! continuous ranging at 100 ms feeds an out-of-window interrupt — closer than
//! 10 cm is an ENTER event, the scene clearing beyond 80 cm a LEAVE event.
//! After 20 events or 60 s, it prints statistics over 10 fresh samples, stops
//! ranging and recalibrates. Events are polled over I²C (Rust drivers leave
//! the GPIO1 wiring to the caller).

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::tof::{
    Vl53l0xFull, Vl53l0xProfile, VL53L0X_I2C_ADDRESS, VL53L0X_SOURCE_NEW_SAMPLE_READY, VL53L0X_SOURCE_OUT_OF_WINDOW,
};
use std::thread::sleep;
use std::time::{Duration, Instant};

const MAX_EVENTS: u32 = 20;
const MAX_TIME: Duration = Duration::from_secs(60);

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut sensor = Vl53l0xFull::new(dev, VL53L0X_I2C_ADDRESS, Delay).expect("init VL53L0X"); // Create VL53L0X Full driver, (i2c, addr=0x29, delay)

    // --- Pick a profile from the ambient light level ---
    // The long-range profile (0.1 MCPS limit, 18/14 PCLK VCSEL periods) reaches
    // ~2 m, but only without IR background; in daylight it mostly adds invalid
    // readings. One single-shot measurement tells us how bright the scene is.
    sensor.distance().unwrap();                                      // Measure distance, () → u16 mm
    let first = sensor.read_measurement().unwrap();                  // Read result block, () → Vl53l0xMeasurement
    if first.ambient_rate_mcps < 0.5 {
        sensor.set_profile(Vl53l0xProfile::LongRange).unwrap();      // Apply ranging profile, (profile) → ()
        println!("dark scene ({:.2} MCPS ambient): long range profile", first.ambient_rate_mcps);
    } else {
        sensor.set_profile(Vl53l0xProfile::Default).unwrap();        // Apply ranging profile, (profile) → ()
        println!("bright scene ({:.2} MCPS ambient): default profile", first.ambient_rate_mcps);
    }

    // --- Arm the presence gate ---
    // Timed ranging every 100 ms keeps the laser mostly idle. The firmware
    // compares each result with the 100 mm / 800 mm window itself and only
    // flags the status when a reading falls outside it.
    sensor.set_interrupt_thresholds(100, 800).unwrap();              // Set distance thresholds, (low_mm mm, high_mm mm) → ()
    sensor.enable_interrupt(VL53L0X_SOURCE_OUT_OF_WINDOW).unwrap();  // Select interrupt source, (source) → ()
    sensor.start_continuous(100).unwrap();                           // Start continuous ranging, (period_ms=0 ms) → ()

    // --- Classify each event ---
    // poll_interrupt reads and clears the status; the result block still
    // holds the measurement that triggered it.
    let start = Instant::now();
    let mut events = 0;
    while events < MAX_EVENTS && start.elapsed() < MAX_TIME {
        if sensor.poll_interrupt().unwrap() != 0 {                   // Read and clear status, () → u8
            let d = sensor.read_measurement().unwrap().distance_mm;  // Read result block, () → Vl53l0xMeasurement
            println!("{} {} mm", if d < 100 { "ENTER" } else { "LEAVE" }, d);
            events += 1;
        }
        sleep(Duration::from_millis(50));
    }

    // --- Statistics over fresh samples ---
    // Threshold sources hide ordinary samples from data_ready(), so switch
    // back to new-sample-ready before using the blocking continuous reads.
    sensor.enable_interrupt(VL53L0X_SOURCE_NEW_SAMPLE_READY).unwrap(); // Select interrupt source, (source) → ()
    sensor.poll_interrupt().unwrap();                                // Read and clear status, () → u8
    let mut samples = Vec::new();
    let mut rate = 0.0f32;
    for _ in 0..10 {
        samples.push(sensor.read_continuous().unwrap());             // Read next continuous result, () → u16 mm
        rate += sensor.read_measurement().unwrap().signal_rate_mcps; // Read result block, () → Vl53l0xMeasurement
    }
    let mean = samples.iter().map(|&s| s as f32).sum::<f32>() / samples.len() as f32;
    println!("mean {:.0} mm, min {} mm, max {} mm, signal {:.2} MCPS",
             mean, samples.iter().min().unwrap(), samples.iter().max().unwrap(), rate / samples.len() as f32);

    // --- Shut down and recalibrate ---
    // Reference calibration must run in software standby. Repeat it whenever
    // the sensor's temperature has drifted more than 8 °C.
    sensor.stop_continuous().unwrap();                               // Stop continuous ranging, () → ()
    sleep(Duration::from_millis(200));
    sensor.recalibrate().unwrap();                                   // Rerun reference calibration, () → ()
    println!("done");
}
