//go:build tinygo

// QMI8658A demo example — TinyGo / Raspberry Pi Pico W.
//
// Motion monitor: self-tests, gyro calibration, ±4 g / ±512 dps at 112 Hz,
// pedometer, tap engine and a streaming FIFO; prints roll, pitch, gyro
// magnitude, temperature and steps every 100 ms for 60 s.
package main

import (
	"fmt"
	"machine"
	"math"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/imu"
	"github.com/tuhde/Periph/go/periph/connection"
)

func must(err error) {
	if err != nil {
		panic(err)
	}
}

func passFail(ok bool) string {
	if ok {
		return "PASS"
	}
	return "FAIL"
}

func main() {
	i2c := machine.I2C1
	must(i2c.Configure(machine.I2CConfig{
		SDA:       machine.GP4,
		SCL:       machine.GP5,
		Frequency: 400_000,
	}))

	conn := connection.NewI2CConnection(i2c, 0x6B, nil, nil) // Create I2C connection, (i2c, addr=0x6B) → (*I2CConnection)

	chip, err := imu.NewQMI8658AFull(conn) // Create QMI8658A driver, (connection) → (*QMI8658AFull, error)
	must(err)

	// --- Identify the part and run the built-in checks ---
	// The revision, firmware version and USID identify the silicon; the
	// self-tests need the board lying still and the gyro calibration trims the
	// X/Y gains before real use.
	revision, firmware, usid, err := chip.DeviceInfo() // Read device info, () → (byte, [3]byte, [6]byte, error)
	must(err)
	fmt.Printf("revision 0x%02X firmware %v usid %x\n", revision, firmware, usid)
	accelOK, _, err := chip.SelfTestAccel() // Run accel self-test, () → (bool, [3]float32 m/s², error)
	must(err)
	fmt.Println("accel self-test:", passFail(accelOK))
	gyroOK, _, err := chip.SelfTestGyro() // Run gyro self-test, () → (bool, [3]float32 rad/s, error)
	must(err)
	fmt.Println("gyro self-test:", passFail(gyroOK))
	calOK, codStatus, err := chip.CalibrateGyro() // Calibrate gyroscope, () → (bool, byte, error)
	must(err)
	fmt.Printf("gyro calibration: %s (0x%02X)\n", passFail(calOK), codStatus)

	// --- Configure ±4 g / ±512 dps at 112.1 Hz ---
	// ODR code 6 gives 112.1 Hz for both sensors; mode 3 filters keep the
	// noise bandwidth near 15 Hz.
	must(chip.ConfigureAccel(1, 6))               // Configure accelerometer, (fullScale, odr) → error
	must(chip.ConfigureGyro(5, 6))                // Configure gyroscope, (fullScale, odr) → error
	must(chip.ConfigureFilters(true, 3, true, 3)) // Configure low-pass filters, (accelFilter, accelMode, gyroFilter, gyroMode) → error

	// --- Step counting and tap detection ---
	// The pedometer defaults suit ~50 Hz, so the window parameters are scaled
	// for 112 Hz; the tap engine runs best above 200 Hz and is only a coarse
	// detector at this rate.
	must(chip.ConfigurePedometer(112, 200, 100, 450, 45, 10, 0, 1)) // Configure pedometer, (sampleCount, …) → error
	must(chip.EnablePedometer(true))                                // Enable pedometer, (enabled) → error
	must(chip.ConfigureTap(0, 20, 50, 250, 0.0625, 0.25, 0.8, 0.4)) // Configure tap engine, (priority, …) → error
	must(chip.EnableTap(true))                                      // Enable tap engine, (enabled) → error

	// --- Stream into the FIFO ---
	// Stream mode with 32 samples and a watermark of 16 is drained once per
	// second; each sample is a 12-byte frame (accel XYZ then gyro XYZ).
	must(chip.ConfigureFifo(2, 1, 16)) // Configure FIFO, (mode, size, watermark) → error
	time.Sleep(200 * time.Millisecond)

	for iteration := 0; iteration < 600; iteration++ {
		// --- Sample, tilt and step count ---
		ready, err := chip.DataReady() // Check for new data, () → (bool, error)
		must(err)
		if ready {
			accel, gyro, temp, err := chip.ReadAll() // Read one coherent sample, () → ([3]float32 m/s², [3]float32 rad/s, float32 °C, error)
			must(err)
			roll := math.Atan2(float64(accel[1]), float64(accel[2])) * 180 / math.Pi
			pitch := math.Atan2(-float64(accel[0]), math.Hypot(float64(accel[1]), float64(accel[2]))) * 180 / math.Pi
			gmag := math.Sqrt(float64(gyro[0]*gyro[0] + gyro[1]*gyro[1] + gyro[2]*gyro[2]))
			steps, err := chip.StepCount() // Read step count, () → (uint32, error)
			must(err)
			fmt.Printf("roll %.1f pitch %.1f |gyro| %.3f rad/s  %.1f C  steps %d\n", roll, pitch, gmag, temp, steps)
		}

		// --- Report taps ---
		// PollInterrupt flags a tap without the INT pin wired.
		status, err := chip.PollInterrupt() // Poll interrupt status, () → (uint16, error)
		must(err)
		if status&imu.QMI8658ASourceTap != 0 {
			taps, axis, _, err := chip.TapStatus() // Read tap event, () → (uint8, uint8, bool, error)
			must(err)
			kind := "single"
			if taps == 2 {
				kind = "double"
			}
			fmt.Printf("TAP %s on axis %c\n", kind, "?XYZ"[axis])
		}

		// --- Drain the FIFO once per second ---
		if iteration%10 == 9 {
			n, err := chip.FifoCount() // Read FIFO fill, () → (int bytes, error)
			must(err)
			data, err := chip.ReadFifo(-1) // Drain the FIFO, (count) → ([]byte, error)
			must(err)
			fmt.Printf("FIFO: %d bytes (%d), %d frames\n", n, len(data), len(data)/12)
		}
		time.Sleep(100 * time.Millisecond)
	}
}
