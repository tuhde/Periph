use std::time::Duration;
use linux_embedded_hal::I2cdev;
use periph::chips::accelerometer::{
    Lsm303Full, MODE_HIGH_RESOLUTION, FIFO_STREAM, SOURCE_CLICK, SOURCE_IA1,
};

const DIRECTIONS: [&str; 16] = ["N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
                                "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"];

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut chip = Lsm303Full::new(dev).expect("init LSM303");

    // --- Configure the sensor for a responsive compass ---
    // 50 Hz gives a fresh sample every 20 ms without flooding the FIFO, and
    // the high-resolution mode keeps 1 mg/LSB so the tilt compensation stays
    // precise.
    chip.set_data_rate(50).expect("set data rate");
    chip.set_mode(MODE_HIGH_RESOLUTION).expect("set mode");
    chip.set_mag_range(130).expect("set mag range");
    chip.set_mag_data_rate(15.0).expect("set mag data rate");

    // --- Arm the gestures ---
    // A double tap on the Z axis and a free-fall event both end up on INT1.
    // The latch keeps the line asserted until the source register is read, so
    // a short tap is never missed between two polling rounds.
    chip.set_click(0, 0x04, 0.8, 20.0, 40.0, 200.0).expect("set click");
    chip.set_free_fall(1, 0.35, 60.0).expect("set free fall");
    chip.set_latch(1, true).expect("set latch");
    chip.enable_interrupt(SOURCE_CLICK, 1).expect("enable click interrupt");
    chip.enable_interrupt(SOURCE_IA1, 1).expect("enable ia1 interrupt");

    // --- Collect the samples in the FIFO ---
    // Stream mode keeps the newest 32 sample sets; the watermark interrupts as
    // soon as 16 of them have piled up.
    chip.set_fifo(FIFO_STREAM, 16, false).expect("set fifo");

    let mut samples = [(0.0f32, 0.0f32, 0.0f32); 8];
    for _round in 0..60 {
        // --- Average the samples the FIFO collected ---
        // Averaging over the whole burst cancels most of the vibration noise a
        // handheld compass would otherwise show.
        let mut ax = 0.0f32;
        let mut ay = 0.0f32;
        let mut az = 0.0f32;
        let count = chip.read_fifo(8, &mut samples).expect("read fifo");
        if count > 0 {
            for s in samples.iter().take(count) {
                ax += s.0;
                ay += s.1;
                az += s.2;
            }
            ax /= count as f32;
            ay /= count as f32;
            az /= count as f32;
        } else {
            let (x, y, z) = chip.read_acceleration().expect("read acceleration");
            ax = x;
            ay = y;
            az = z;
        }

        // --- Compute the heading and the tilt ---
        let heading = chip.heading(true).expect("heading");
        let roll = ay.atan2(az) * 180.0 / core::f32::consts::PI;
        let pitch = (-ax).atan2((ay * ay + az * az).sqrt()) * 180.0 / core::f32::consts::PI;
        let (mx, my, mz) = chip.read_magnetic().expect("read magnetic");
        let magnitude = (mx * mx + my * my + mz * mz).sqrt();
        let temperature = chip.read_temperature_delta().expect("read temperature");
        let mut index = ((heading + 11.25) / 22.5) as i32 % 16;
        if index < 0 {
            index += 16;
        }

        println!("heading {:.1} deg ({})  pitch {:.1} deg  roll {:.1} deg  field {:.1} uT  temp {:.1} C",
                 heading, DIRECTIONS[index as usize], pitch, roll, magnitude, temperature);

        // --- Report the gestures ---
        let status = chip.poll_interrupt().expect("poll interrupt");
        if status & SOURCE_CLICK != 0 {
            println!("DOUBLE TAP");
        }
        if status & SOURCE_IA1 != 0 {
            println!("FREE FALL");
        }

        std::thread::sleep(Duration::from_millis(1000));
    }
    println!("done");
}
