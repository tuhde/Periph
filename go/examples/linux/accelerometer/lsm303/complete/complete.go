//go:build linux && !tinygo

// LSM303 complete example — Linux host, exercise the whole chip API once.
package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/accelerometer"
	"github.com/tuhde/Periph/go/periph/connection"
)

func envOr(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_BUS:", err)
		os.Exit(2)
	}
	accelConn, err := connection.NewI2CConnection(bus, accelerometer.LSM303I2CAddressAccel, nil, nil)
	if err != nil {
		fmt.Fprintln(os.Stderr, "accelerometer connection:", err)
		os.Exit(2)
	}
	defer accelConn.Close()
	magConn, err := connection.NewI2CConnection(bus, accelerometer.LSM303I2CAddressMag, nil, nil)
	if err != nil {
		fmt.Fprintln(os.Stderr, "magnetometer connection:", err)
		os.Exit(2)
	}
	defer magConn.Close()
	chip, err := accelerometer.NewLSM303Full(accelConn, magConn)
	if err != nil {
		fmt.Fprintln(os.Stderr, "init:", err)
		os.Exit(2)
	}

	// --- Accelerometer readings ---
	ax, ay, az, err := chip.ReadAcceleration()
	if err != nil {
		fmt.Fprintln(os.Stderr, "read acceleration:", err)
		os.Exit(1)
	}
	fmt.Printf("accel x=%.4f y=%.4f z=%.4f g\n", ax, ay, az)

	rx, ry, rz, err := chip.ReadAccelerationRaw()
	if err != nil {
		fmt.Fprintln(os.Stderr, "read raw acceleration:", err)
		os.Exit(1)
	}
	fmt.Printf("raw accel %d %d %d\n", rx, ry, rz)

	status, err := chip.ReadStatus()
	if err != nil {
		fmt.Fprintln(os.Stderr, "read status:", err)
		os.Exit(1)
	}
	fmt.Printf("status 0x%02X\n", status)

	newData, err := chip.NewDataAvailable()
	if err != nil {
		fmt.Fprintln(os.Stderr, "new data:", err)
		os.Exit(1)
	}
	fmt.Printf("new data %v\n", newData)

	// --- Accelerometer configuration ---
	if err := chip.SetRange(4); err != nil {
		fmt.Fprintln(os.Stderr, "set range:", err)
		os.Exit(1)
	}
	if err := chip.SetDataRate(50); err != nil {
		fmt.Fprintln(os.Stderr, "set data rate:", err)
		os.Exit(1)
	}
	if err := chip.SetMode(accelerometer.LSM303ModeHighResolution); err != nil {
		fmt.Fprintln(os.Stderr, "set mode:", err)
		os.Exit(1)
	}
	if err := chip.SetAxes(true, true, true); err != nil {
		fmt.Fprintln(os.Stderr, "set axes:", err)
		os.Exit(1)
	}

	// --- High-pass filter ---
	if err := chip.SetHighPass(0, 0, false, true, false, false); err != nil {
		fmt.Fprintln(os.Stderr, "set high pass:", err)
		os.Exit(1)
	}
	ref, err := chip.ResetHighPass()
	if err != nil {
		fmt.Fprintln(os.Stderr, "reset high pass:", err)
		os.Exit(1)
	}
	fmt.Printf("reference %d\n", ref)
	if err := chip.SetReference(0); err != nil {
		fmt.Fprintln(os.Stderr, "set reference:", err)
		os.Exit(1)
	}

	// --- FIFO ---
	if err := chip.SetFifo(accelerometer.LSM303FifoStream, 16, false); err != nil {
		fmt.Fprintln(os.Stderr, "set fifo:", err)
		os.Exit(1)
	}
	level, err := chip.FifoLevel()
	if err != nil {
		fmt.Fprintln(os.Stderr, "fifo level:", err)
		os.Exit(1)
	}
	fmt.Printf("fifo level %d\n", level)
	wm, ov, empty, lvl, err := chip.ReadFifoStatus()
	if err != nil {
		fmt.Fprintln(os.Stderr, "read fifo status:", err)
		os.Exit(1)
	}
	fmt.Printf("fifo wm=%v ov=%v empty=%v level=%d\n", wm, ov, empty, lvl)
	samples, err := chip.ReadFifo(8)
	if err != nil {
		fmt.Fprintln(os.Stderr, "read fifo:", err)
		os.Exit(1)
	}
	fmt.Printf("fifo samples %d\n", len(samples))

	// --- Inertial interrupt generators ---
	if err := chip.SetMotionInterrupt(1, 0x02, 0.5, 10, false); err != nil {
		fmt.Fprintln(os.Stderr, "set motion interrupt:", err)
		os.Exit(1)
	}
	if err := chip.SetFreeFall(1, 0.35, 30); err != nil {
		fmt.Fprintln(os.Stderr, "set free fall:", err)
		os.Exit(1)
	}
	if err := chip.SetOrientation(1, false, false); err != nil {
		fmt.Fprintln(os.Stderr, "set orientation:", err)
		os.Exit(1)
	}
	if err := chip.SetLatch(1, true); err != nil {
		fmt.Fprintln(os.Stderr, "set latch:", err)
		os.Exit(1)
	}
	intSrc, err := chip.ReadInterruptSource(1)
	if err != nil {
		fmt.Fprintln(os.Stderr, "read interrupt source:", err)
		os.Exit(1)
	}
	fmt.Printf("int1 source 0x%02X\n", intSrc)

	// --- Click detection ---
	if err := chip.SetClick(0, 0x04, 0.8, 20, 40, 200); err != nil {
		fmt.Fprintln(os.Stderr, "set click:", err)
		os.Exit(1)
	}
	clickSrc, err := chip.ReadClickSource()
	if err != nil {
		fmt.Fprintln(os.Stderr, "read click source:", err)
		os.Exit(1)
	}
	fmt.Printf("click source 0x%02X\n", clickSrc)

	// --- Interrupt routing ---
	if err := chip.SetInterruptPolarity(false); err != nil {
		fmt.Fprintln(os.Stderr, "set interrupt polarity:", err)
		os.Exit(1)
	}
	if err := chip.EnableInterrupt(accelerometer.LSM303SourceClick, 1); err != nil {
		fmt.Fprintln(os.Stderr, "enable interrupt:", err)
		os.Exit(1)
	}
	if err := chip.DisableInterrupt(accelerometer.LSM303SourceClick, 1); err != nil {
		fmt.Fprintln(os.Stderr, "disable interrupt:", err)
		os.Exit(1)
	}

	// --- Magnetometer ---
	if err := chip.SetMagRange(250); err != nil {
		fmt.Fprintln(os.Stderr, "set mag range:", err)
		os.Exit(1)
	}
	if err := chip.SetMagDataRate(30); err != nil {
		fmt.Fprintln(os.Stderr, "set mag data rate:", err)
		os.Exit(1)
	}
	if err := chip.SetMagMode(accelerometer.LSM303MagModeContinuous); err != nil {
		fmt.Fprintln(os.Stderr, "set mag mode:", err)
		os.Exit(1)
	}
	mx, my, mz, err := chip.ReadMagneticRaw()
	if err != nil {
		fmt.Fprintln(os.Stderr, "read raw magnetic:", err)
		os.Exit(1)
	}
	fmt.Printf("raw mag %d %d %d\n", mx, my, mz)
	drdy, lock, err := chip.ReadMagStatus()
	if err != nil {
		fmt.Fprintln(os.Stderr, "read mag status:", err)
		os.Exit(1)
	}
	fmt.Printf("mag drdy=%v lock=%v\n", drdy, lock)
	identity, err := chip.ReadMagIdentity()
	if err != nil {
		fmt.Fprintln(os.Stderr, "read mag identity:", err)
		os.Exit(1)
	}
	fmt.Printf("identity %02X%02X%02X\n", identity[0], identity[1], identity[2])
	if err := chip.EnableTemperature(true); err != nil {
		fmt.Fprintln(os.Stderr, "enable temperature:", err)
		os.Exit(1)
	}
	temp, err := chip.ReadTemperatureDelta()
	if err != nil {
		fmt.Fprintln(os.Stderr, "read temperature:", err)
		os.Exit(1)
	}
	fmt.Printf("temperature %.2f C\n", temp)

	// --- Compass heading ---
	heading, err := chip.Heading(true)
	if err != nil {
		fmt.Fprintln(os.Stderr, "heading:", err)
		os.Exit(1)
	}
	fmt.Printf("heading %.1f deg\n", heading)
	flat, err := chip.Heading(false)
	if err != nil {
		fmt.Fprintln(os.Stderr, "flat heading:", err)
		os.Exit(1)
	}
	fmt.Printf("flat heading %.1f deg\n", flat)

	// --- Miscellaneous ---
	if err := chip.Reboot(); err != nil {
		fmt.Fprintln(os.Stderr, "reboot:", err)
		os.Exit(1)
	}
}
