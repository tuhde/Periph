//go:build tinygo

// PCA9541 demo — TinyGo / Raspberry Pi Pico W.
//
// Master failover for one of two redundant masters: prints the power-up registers,
// unmasks all interrupts, takes the bus with a bus initialization, checks the INT wire,
// hands the bus over and back, toggles the connection and prints ISTAT for 10 s.
package main

import (
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/other"
	"github.com/tuhde/Periph/go/periph/connection"
)

func must(err error) {
	if err != nil {
		panic(err)
	}
}

func main() {
	machine.I2C1.Configure(machine.I2CConfig{Frequency: 400 * machine.KHz, SDA: machine.GP4, SCL: machine.GP5})
	conn := connection.NewI2CConnection(machine.I2C1, other.PCA9541I2CAddress, nil, nil) // Create I2C connection, (i2c, addr=0x70) → *I2CConnection
	defer conn.Close()

	selector, err := other.NewPCA9541Full(conn) // Create PCA9541 Full driver, (conn) → (*PCA9541Full, error)
	must(err)

	// --- Power-up state ---
	// CONTROL 0x04 for master 0 means /01 (bus on, master 0); 0x00 means /03 (bus off).
	ie, ctl, istat, err := selector.ReadAll() // Read IE, CONTROL, ISTAT, () → (uint8, uint8, uint8, error)
	must(err)
	println("IE", ie, "CONTROL", ctl, "ISTAT", istat)
	st, err := selector.Status() // Decode CONTROL, () → (PCA9541Status, error)
	must(err)
	println("hasControl", st.HasControl, "connected", st.Connected)

	// --- Interrupts ---
	must(selector.SetInterruptMask(false, false, false, false)) // Unmask all interrupts, (...) → error
	must(selector.OnInterrupt(func(status uint8) {              // Subscribe, (callback(status)) → error
		if status&other.PCA9541SourceBusLost != 0 {
			println("BUS_LOST: the other master took the bus")
		}
		if status&other.PCA9541SourceTest != 0 {
			println("MYTEST: the INT wire works")
		}
		println("ISTAT", status)
	}))

	// --- Take the bus with a bus initialization ---
	// The chip clocks nine pulses, a NACK and a STOP on the downstream bus first, which
	// completes a transfer the previous master left half-done.
	rec, err := selector.RecoverBus(20) // Recover and take the bus, (timeoutMs int) → (bool, error)
	must(err)
	println("recoverBus", rec)
	time.Sleep(10 * time.Millisecond)
	// tBUF: bus-free time before talking to a downstream device through a second Connection

	// --- INT wire check ---
	must(selector.TestInterrupt(true, false)) // Drive this master's INT low, (own, other bool) → error
	time.Sleep(100 * time.Millisecond)
	must(selector.TestInterrupt(false, false)) // Clear the test, (own, other bool) → error

	// --- Hand over and take back ---
	must(selector.Release())          // Give the bus to the other master, () → error
	has, err := selector.HasControl() // Report control, () → (bool, error)
	must(err)
	println("has control after release", has)
	back, err := selector.Acquire(false, 20) // Take the downstream bus, (busInit bool, timeoutMs int) → (bool, error)
	must(err)
	println("acquire", back)
	must(selector.Disconnect()) // Isolate the downstream bus, () → error
	must(selector.Connect())    // Reconnect, () → error

	// --- Watch the flags for 10 s ---
	for i := 0; i < 20; i++ {
		status, err := selector.PollInterrupt() // Read and clear ISTAT, () → (uint8, error)
		must(err)
		println("ISTAT", status)
		time.Sleep(500 * time.Millisecond)
	}
	must(selector.OffInterrupt()) // Unsubscribe, () → error
}
