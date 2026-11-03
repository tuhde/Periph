//go:build tinygo

// LSM303 complete example — TinyGo / Raspberry Pi Pico W, exercise the whole chip API once.
package main

import (
	"fmt"
	"machine"

	"github.com/tuhde/Periph/go/periph/chips/accelerometer"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	accelConn, err := connection.NewI2CConnection(machine.I2C0, accelerometer.LSM303I2CAddressAccel, nil, nil)
	if err != nil {
		fmt.Println("accelerometer connection:", err)
		return
	}
	magConn, err := connection.NewI2CConnection(machine.I2C0, accelerometer.LSM303I2CAddressMag, nil, nil)
	if err != nil {
		fmt.Println("magnetometer connection:", err)
		return
	}
	chip, err := accelerometer.NewLSM303Full(accelConn, magConn)
	if err != nil {
		fmt.Println("init:", err)
		return
	}

	// --- Accelerometer readings ---
	ax, ay, az, err := chip.ReadAcceleration()
	if err != nil {
		fmt.Println("read acceleration:", err)
		return
	}
	fmt.Printf("accel x=%.4f y=%.4f z=%.4f g\n", ax, ay, az)

	rx, ry, rz, err := chip.ReadAccelerationRaw()
	if err != nil {
		fmt.Println("read raw acceleration:", err)
		return
	}
	fmt.Printf("raw accel %d %d %d\n", rx, ry, rz)

	status, err := chip.ReadStatus()
	if err != nil {
		fmt.Println("read status:", err)
		return
	}
	fmt.Printf("status 0x%02X\n", status)

	newData, err := chip.NewDataAvailable()
	if err != nil {
		fmt.Println("new data:", err)
		return
	}
	fmt.Printf("new data %v\n", newData)

	// --- Accelerometer configuration ---
	if err := chip.SetRange(4); err != nil {
		fmt.Println("set range:", err)
		return
	}
	if err := chip.SetDataRate(50); err != nil {
		fmt.Println("set data rate:", err)
		return
	}
	if err := chip.SetMode(accelerometer.LSM303ModeHighResolution); err != nil {
		fmt.Println("set mode:", err)
		return
	}
	if err := chip.SetAxes(true, true, true); err != nil {
		fmt.Println("set axes:", err)
		return
	}

	// --- High-pass filter ---
	if err := chip.SetHighPass(0, 0, false, true, false, false); err != nil {
		fmt.Println("set high pass:", err)
		return
	}
	ref, err := chip.ResetHighPass()
	if err != nil {
		fmt.Println("reset high pass:", err)
		return
	}
	fmt.Printf("reference %d\n", ref)
	if err := chip.SetReference(0); err != nil {
		fmt.Println("set reference:", err)
		return
	}

	// --- FIFO ---
	if err := chip.SetFifo(accelerometer.LSM303FifoStream, 16, false); err != nil {
		fmt.Println("set fifo:", err)
		return
	}
	level, err := chip.FifoLevel()
	if err != nil {
		fmt.Println("fifo level:", err)
		return
	}
	fmt.Printf("fifo level %d\n", level)
	wm, ov, empty, lvl, err := chip.ReadFifoStatus()
	if err != nil {
		fmt.Println("read fifo status:", err)
		return
	}
	fmt.Printf("fifo wm=%v ov=%v empty=%v level=%d\n", wm, ov, empty, lvl)
	samples, err := chip.ReadFifo(8)
	if err != nil {
		fmt.Println("read fifo:", err)
		return
	}
	fmt.Printf("fifo samples %d\n", len(samples))

	// --- Inertial interrupt generators ---
	if err := chip.SetMotionInterrupt(1, 0x02, 0.5, 10, false); err != nil {
		fmt.Println("set motion interrupt:", err)
		return
	}
	if err := chip.SetFreeFall(1, 0.35, 30); err != nil {
		fmt.Println("set free fall:", err)
		return
	}
	if err := chip.SetOrientation(1, false, false); err != nil {
		fmt.Println("set orientation:", err)
		return
	}
	if err := chip.SetLatch(1, true); err != nil {
		fmt.Println("set latch:", err)
		return
	}
	intSrc, err := chip.ReadInterruptSource(1)
	if err != nil {
		fmt.Println("read interrupt source:", err)
		return
	}
	fmt.Printf("int1 source 0x%02X\n", intSrc)

	// --- Click detection ---
	if err := chip.SetClick(0, 0x04, 0.8, 20, 40, 200); err != nil {
		fmt.Println("set click:", err)
		return
	}
	clickSrc, err := chip.ReadClickSource()
	if err != nil {
		fmt.Println("read click source:", err)
		return
	}
	fmt.Printf("click source 0x%02X\n", clickSrc)

	// --- Interrupt routing ---
	if err := chip.SetInterruptPolarity(false); err != nil {
		fmt.Println("set interrupt polarity:", err)
		return
	}
	if err := chip.EnableInterrupt(accelerometer.LSM303SourceClick, 1); err != nil {
		fmt.Println("enable interrupt:", err)
		return
	}
	if err := chip.DisableInterrupt(accelerometer.LSM303SourceClick, 1); err != nil {
		fmt.Println("disable interrupt:", err)
		return
	}

	// --- Magnetometer ---
	if err := chip.SetMagRange(250); err != nil {
		fmt.Println("set mag range:", err)
		return
	}
	if err := chip.SetMagDataRate(30); err != nil {
		fmt.Println("set mag data rate:", err)
		return
	}
	if err := chip.SetMagMode(accelerometer.LSM303MagModeContinuous); err != nil {
		fmt.Println("set mag mode:", err)
		return
	}
	mx, my, mz, err := chip.ReadMagneticRaw()
	if err != nil {
		fmt.Println("read raw magnetic:", err)
		return
	}
	fmt.Printf("raw mag %d %d %d\n", mx, my, mz)
	drdy, lock, err := chip.ReadMagStatus()
	if err != nil {
		fmt.Println("read mag status:", err)
		return
	}
	fmt.Printf("mag drdy=%v lock=%v\n", drdy, lock)
	identity, err := chip.ReadMagIdentity()
	if err != nil {
		fmt.Println("read mag identity:", err)
		return
	}
	fmt.Printf("identity %02X%02X%02X\n", identity[0], identity[1], identity[2])
	if err := chip.EnableTemperature(true); err != nil {
		fmt.Println("enable temperature:", err)
		return
	}
	temp, err := chip.ReadTemperatureDelta()
	if err != nil {
		fmt.Println("read temperature:", err)
		return
	}
	fmt.Printf("temperature %.2f C\n", temp)

	// --- Compass heading ---
	heading, err := chip.Heading(true)
	if err != nil {
		fmt.Println("heading:", err)
		return
	}
	fmt.Printf("heading %.1f deg\n", heading)
	flat, err := chip.Heading(false)
	if err != nil {
		fmt.Println("flat heading:", err)
		return
	}
	fmt.Printf("flat heading %.1f deg\n", flat)

	// --- Miscellaneous ---
	if err := chip.Reboot(); err != nil {
		fmt.Println("reboot:", err)
		return
	}
}
