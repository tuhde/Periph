//go:build tinygo

// QMI8658A complete example — TinyGo / Raspberry Pi Pico W.
//
// Calls every method of the Full driver once.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/imu"
	"github.com/tuhde/Periph/go/periph/connection"
)

func must(err error) {
	if err != nil {
		panic(err)
	}
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

	ax, ay, az, err := chip.Accel() // Read 3-axis acceleration, () → (float32, float32, float32 m/s², error)
	// converts raw counts at 16384 LSB/g (±2 g)
	must(err)
	gx, gy, gz, err := chip.Gyro() // Read 3-axis angular rate, () → (float32, float32, float32 rad/s, error)
	// converts raw counts at 128 LSB/dps (±256 dps)
	must(err)
	rax, ray, raz, err := chip.AccelRaw() // Read raw accel counts, () → (int16, int16, int16, error)
	// one 6-byte burst, signed 16-bit
	must(err)
	rgx, rgy, rgz, err := chip.GyroRaw() // Read raw gyro counts, () → (int16, int16, int16, error)
	// one 6-byte burst, signed 16-bit
	must(err)
	accel, gyro, temp, err := chip.ReadAll() // Read one coherent sample, () → ([3]float32 m/s², [3]float32 rad/s, float32 °C, error)
	// one 14-byte burst of registers 0x33…0x40
	must(err)
	t, err := chip.Temperature() // Read die temperature, () → (float32 °C, error)
	// signed 8.8 fixed point, raw / 256
	must(err)
	ts, err := chip.Timestamp() // Read sample counter, () → (uint32, error)
	// 24-bit counter, wraps at 0xFFFFFF
	must(err)
	ready, err := chip.DataReady() // Check for new data, () → (bool, error)
	// STATUS0 aDA / gDA
	must(err)
	err = chip.ConfigureAccel(1, 5) // Configure accelerometer, (fullScale, odr) → error
	// CTRL2: 0=±2, 1=±4, 2=±8, 3=±16 g; ODR code 5 = 224.2 Hz
	must(err)
	err = chip.ConfigureGyro(5, 5) // Configure gyroscope, (fullScale, odr) → error
	// CTRL3: 0=±16 … 7=±2048 dps
	must(err)
	err = chip.ConfigureFilters(true, 3, true, 3) // Configure low-pass filters, (accelFilter, accelMode, gyroFilter, gyroMode) → error
	// CTRL5: mode 3 = 13.37 % of ODR
	must(err)
	err = chip.SetSensors(true, true, false) // Enable sensors, (accel, gyro, gyroSnooze) → error
	// CTRL7 aEN / gEN / gSN
	must(err)
	err = chip.ConfigureFifo(2, 1, 16) // Configure FIFO, (mode, size, watermark) → error
	// stream mode, 32 samples, watermark 16
	must(err)
	time.Sleep(500 * time.Millisecond)
	fifoFlags, err := chip.FifoStatus() // Read FIFO flags, () → (byte, error)
	// raw FIFO_STATUS byte
	must(err)
	fifoBytes, err := chip.FifoCount() // Read FIFO fill, () → (int bytes, error)
	// two bytes per sample word
	must(err)
	data, err := chip.ReadFifo(-1) // Drain the FIFO, (count) → ([]byte, error)
	// REQ_FIFO, chunked FIFO_DATA reads, FIFO_RD_MODE cleared
	must(err)
	err = chip.ResetFifo() // Reset the FIFO, () → error
	// CTRL9 RST_FIFO
	must(err)
	err = chip.ConfigureFifo(0, 3, 0) // Back to bypass, (mode, size, watermark) → error
	// bypass re-enables data-ready
	must(err)
	err = chip.ConfigureAnyMotion(0.25, 3, 0x07, false) // Configure any-motion, (thresholdG, window, axes, logicAnd) → error
	// two CTRL9 CONFIGURE_MOTION sets
	must(err)
	err = chip.ConfigureNoMotion(0.125, 30, 0x07, true) // Configure no-motion, (thresholdG, window, axes, logicAnd) → error
	// same command, no-motion fields
	must(err)
	err = chip.ConfigureSignificantMotion(150, 600) // Configure significant motion, (waitWindow, confirmWindow) → error
	// wait and confirm windows in samples
	must(err)
	err = chip.EnableMotion(true, true, true) // Enable motion engines, (anyMotion, noMotion, significant) → error
	// CTRL8 bits 1–3
	must(err)
	err = chip.EnableMotion(false, false, false) // Disable motion engines, (anyMotion, noMotion, significant) → error
	// clears CTRL8 bits 1–3
	must(err)
	err = chip.ConfigureTap(0, 20, 50, 250, 0.0625, 0.25, 0.8, 0.4) // Configure tap engine, (priority, peakWindow, tapWindow, doubleTapWindow, alpha, gamma, peakThreshold, quietThreshold) → error
	// two CTRL9 CONFIGURE_TAP sets
	must(err)
	err = chip.EnableTap(true) // Enable tap engine, (enabled) → error
	// CTRL8.Tap_EN
	must(err)
	taps, tapAxis, negative, err := chip.TapStatus() // Read tap event, () → (uint8, uint8, bool, error)
	// count 0/1/2, axis 0–3, polarity
	must(err)
	err = chip.EnableTap(false) // Disable tap engine, (enabled) → error
	// clears CTRL8.Tap_EN
	must(err)
	err = chip.ConfigurePedometer(50, 200, 100, 200, 20, 10, 0, 4) // Configure pedometer, (sampleCount, peakToPeakMg, peakMg, timeUp, timeLow, entryCount, precision, reportEvery) → error
	// two CTRL9 CONFIGURE_PEDOMETER sets (50 Hz values)
	must(err)
	err = chip.EnablePedometer(true) // Enable pedometer, (enabled) → error
	// CTRL8.Pedo_EN; 0→1 resets the count
	must(err)
	steps, err := chip.StepCount() // Read step count, () → (uint32, error)
	// 24-bit count
	must(err)
	err = chip.ResetStepCount() // Clear step count, () → error
	// CTRL9 RESET_PEDOMETER
	must(err)
	err = chip.EnablePedometer(false) // Disable pedometer, (enabled) → error
	// clears CTRL8.Pedo_EN
	must(err)
	err = chip.SetAccelOffset(0, 0, 0) // Set accel offset, (x, y, z m/s²) → error
	// signed 4.12 g via CTRL9
	must(err)
	err = chip.SetGyroOffset(0, 0, 0) // Set gyro offset, (x, y, z rad/s) → error
	// signed 11.5 dps via CTRL9
	must(err)
	calOK, codStatus, err := chip.CalibrateGyro() // Calibrate gyroscope, () → (bool, byte, error)
	// calibration-on-demand, ~1.5 s, keep still
	must(err)
	gainX, gainY, gainZ, err := chip.GetGyroGains() // Read gyro gains, () → (uint16, uint16, uint16, error)
	// raw 16-bit gains from dVX…dVZ
	must(err)
	err = chip.SetGyroGains(gainX, gainY, gainZ) // Restore gyro gains, (x, y, z) → error
	// CTRL9 APPLY_GYRO_GAINS
	must(err)
	accelOK, accelSelf, err := chip.SelfTestAccel() // Run accel self-test, () → (bool, [3]float32 m/s², error)
	// passes when every axis exceeds 200 mg
	must(err)
	gyroOK, gyroSelf, err := chip.SelfTestGyro() // Run gyro self-test, () → (bool, [3]float32 rad/s, error)
	// passes when every axis exceeds 300 dps
	must(err)
	revision, firmware, usid, err := chip.DeviceInfo() // Read device info, () → (byte, [3]byte, [6]byte, error)
	// CTRL9 COPY_USID: revision, firmware, usid
	must(err)
	err = chip.SetPullups(true, true, true, true) // Control pull-ups, (aux, sdx, cs, i2c) → error
	// CTRL9 SET_RPU; false disables a group
	must(err)
	err = chip.SetWakeOnMotion(100, 1, 0, 0) // Arm wake-on-motion, (thresholdMg, interruptPin, initialLevel, blankingSamples) → error
	// accel 128 Hz ±2 g, CTRL9 WRITE_WOM_SETTING
	must(err)
	err = chip.SetWakeOnMotion(0, 1, 0, 0) // Leave wake-on-motion, (thresholdMg, interruptPin, initialLevel, blankingSamples) → error
	// threshold 0 exits WoM mode
	must(err)
	err = chip.SetSensors(true, true, false) // Re-enable sensors, (accel, gyro, gyroSnooze) → error
	// restores normal operation
	must(err)
	err = chip.SetInterruptPins(2, 2) // Route interrupts, (fifoPin, activityPin) → error
	// CTRL1.FIFO_INT_SEL and CTRL8.ACTIVITY_INT_SEL
	must(err)
	err = chip.EnableInterrupt(imu.QMI8658ASourceDataReady) // Enable interrupt source, (source) → error
	// clears DRDY_DIS, enables INT2
	must(err)
	status, err := chip.PollInterrupt() // Poll interrupt status, () → (uint16, error)
	// STATUS0, STATUS1 and FIFO_STATUS as source bits
	must(err)
	err = chip.DisableInterrupt(imu.QMI8658ASourceDataReady) // Disable interrupt source, (source) → error
	// sets DRDY_DIS, releases INT2
	must(err)
	err = chip.SetSyncSample(true) // Enable SyncSample, (enabled) → error
	// locks each sample; reads wait for Avail and Locked
	must(err)
	err = chip.SetSyncSample(false) // Disable SyncSample, (enabled) → error
	// restores the AHB clock gating
	must(err)
	err = chip.SetPowerDown(true) // Power down, (down) → error
	// CTRL1.SensorDisable after disabling the sensors
	must(err)
	err = chip.SetPowerDown(false) // Wake, (down) → error
	// clears SensorDisable
	must(err)
	err = chip.SetSensors(true, true, false) // Re-enable sensors, (accel, gyro, gyroSnooze) → error
	// needs 150 ms gyroscope turn-on
	must(err)
	time.Sleep(200 * time.Millisecond)
	err = chip.Reset() // Soft reset, () → error
	// RESET register, waits 15 ms
	must(err)
	fmt.Println(ax, ay, az, gx, gy, gz, rax, ray, raz, rgx, rgy, rgz, accel, gyro, temp, t, ts, ready)
	fmt.Println(fifoFlags, fifoBytes, len(data), taps, tapAxis, negative, steps, calOK, codStatus)
	fmt.Println(gainX, gainY, gainZ, accelOK, accelSelf, gyroOK, gyroSelf, revision, firmware, usid, status)
	fmt.Println("Complete example done")
}
