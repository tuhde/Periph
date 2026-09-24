//! Long-range doorway people counter with a split ROI. The sensor hangs
//! overhead in a doorway (up to 2.5 m). Two narrow 8x16 ROIs (centre SPADs 167
//! and 231, the left/right half-array centres used by ST's own
//! people-counting code) form two virtual beams. Each zone learns its floor
//! distance, then counts as occupied when something is more than 300 mm
//! closer; the order in which the zones become occupied tells IN from OUT.
//! Every 30 s a signal/ambient snapshot is printed and strong sunlight
//! switches to short distance mode. After 60 s or 50 events the full ROI is
//! restored.

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::tof::{Vl53l1xDistanceMode, Vl53l1xFull, VL53L1X_I2C_ADDRESS};
use std::time::{Duration, Instant};

const ZONE_CENTRES: [u8; 2] = [167, 231]; // left, right
const OCCUPIED_MM: f32 = 300.0;
const MAX_EVENTS: u32 = 50;
const MAX_TIME: Duration = Duration::from_secs(60);
const SNAPSHOT: Duration = Duration::from_secs(30);
const BRIGHT_MCPS: f32 = 5.0;

fn measure(sensor: &mut Vl53l1xFull<I2cdev, Delay>, zone: usize) -> Option<u16> {
    sensor.set_roi_center(ZONE_CENTRES[zone]).unwrap();              // Set ROI centre, (spad) → ()
    let d = sensor.distance().unwrap();                              // Measure distance, () → u16 mm
    if sensor.range_valid() { Some(d) } else { None }                // Check last measurement, () → bool
}

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut sensor = Vl53l1xFull::new(dev, VL53L1X_I2C_ADDRESS, Delay).expect("init VL53L1X"); // Create VL53L1X Full driver, (i2c, addr=0x29, delay)

    // --- Two virtual beams ---
    // Long mode reaches the floor from a 2.5 m ceiling; a 33 ms budget keeps
    // the two zones fast enough to catch a walking person. An 8x16 ROI covers
    // one half of the SPAD array, so alternating the centre alternates beams.
    sensor.set_distance_mode(Vl53l1xDistanceMode::Long).unwrap();    // Set distance mode, (mode) → ()
    sensor.set_timing_budget(33000).unwrap();                        // Set timing budget, (budget_us µs) → ()
    sensor.set_roi(8, 16).unwrap();                                  // Set ROI size, (width SPADs, height SPADs) → ()
    println!("optical centre SPAD {}, zone centres {:?}", sensor.optical_center().unwrap(), ZONE_CENTRES); // Read optical-centre SPAD, () → u8

    // --- Learn the empty doorway ---
    // Twenty readings per zone give the floor distance each beam sees when
    // nobody is there; invalid readings are ignored.
    let mut baseline = [4000.0f32; 2];
    for zone in 0..2 {
        let values: Vec<u16> = (0..20).filter_map(|_| measure(&mut sensor, zone)).collect();
        if !values.is_empty() {
            baseline[zone] = values.iter().map(|&v| v as f32).sum::<f32>() / values.len() as f32;
        }
    }
    println!("baseline left {:.0} mm, right {:.0} mm", baseline[0], baseline[1]);

    // --- Count crossings ---
    // A person entering blocks the left beam first, then the right one (and
    // the reverse when leaving). Once both beams clear, the recorded order
    // decides the direction.
    let (mut count_in, mut count_out, mut events) = (0u32, 0u32, 0u32);
    let mut sequence: Vec<usize> = Vec::new();
    let start = Instant::now();
    let mut last_snapshot = start;
    while events < MAX_EVENTS && start.elapsed() < MAX_TIME {
        let readings = [measure(&mut sensor, 0), measure(&mut sensor, 1)];
        let occupied: Vec<bool> = (0..2)
            .map(|z| readings[z].map(|r| (r as f32) < baseline[z] - OCCUPIED_MM).unwrap_or(false))
            .collect();
        for zone in 0..2 {
            if occupied[zone] && !sequence.contains(&zone) {
                sequence.push(zone);
            }
        }
        if !occupied[0] && !occupied[1] && !sequence.is_empty() {
            match sequence.as_slice() {
                [0, 1] => count_in += 1,
                [1, 0] => count_out += 1,
                _ => {}
            }
            events += 1;
            println!("IN {} OUT {} (left {:?}, right {:?})", count_in, count_out, readings[0], readings[1]);
            sequence.clear();
        }

        // --- Watch the light ---
        // Sunlight through an open door raises the ambient rate and eats
        // long-mode range; short mode keeps working up to ~1.3 m.
        if last_snapshot.elapsed() >= SNAPSHOT {
            last_snapshot = Instant::now();
            sensor.distance().unwrap();                              // Measure distance, () → u16 mm
            let m = sensor.read_measurement().unwrap();              // Read result block, () → Vl53l1xMeasurement
            println!("signal {:.2} MCPS, ambient {:.2} MCPS", m.signal_rate_mcps, m.ambient_rate_mcps);
            if m.ambient_rate_mcps > BRIGHT_MCPS
                && sensor.distance_mode().unwrap() == Vl53l1xDistanceMode::Long // Read distance mode, () → Vl53l1xDistanceMode
            {
                sensor.set_distance_mode(Vl53l1xDistanceMode::Short).unwrap(); // Set distance mode, (mode) → ()
                println!("bright ambient light: switched to short distance mode");
            }
        }
    }

    // --- Restore the full field of view ---
    sensor.set_roi(16, 16).unwrap();                                 // Set ROI size, (width SPADs, height SPADs) → ()
    println!("done: IN {} OUT {}", count_in, count_out);
}
