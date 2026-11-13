//go:build tinygo

// PCA9541 complete example — TinyGo / Raspberry Pi Pico W.
//
// Exercises every method of the public API: control, status, acquire, recovery,
// connect / disconnect, interrupt masks, the INT test and interrupt handling.
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
	// reads CONTROL and ISTAT; writes nothing

	control, err := selector.GetControl() // Read the CONTROL byte, () → (uint8, error)
	must(err)
	println("CONTROL", control)
	st, err := selector.Status() // Decode CONTROL, () → (PCA9541Status, error)
	must(err)
	println("status", st.HasControl, st.Connected, st.BusInitRequested, st.OwnTest, st.OtherTest)
	ie, ctl, istat, err := selector.ReadAll() // Read IE, CONTROL, ISTAT, () → (uint8, uint8, uint8, error)
	must(err)
	// one auto-increment read; clears the ISTAT flags
	println("IE", ie, "CONTROL", ctl, "ISTAT", istat)

	ok, err := selector.Acquire(false, 20) // Take the downstream bus, (busInit bool, timeoutMs int) → (bool, error)
	must(err)
	// reads CONTROL, writes MYBUS/BUSON per the datasheet rule
	rec, err := selector.RecoverBus(20) // Recover and take the bus, (timeoutMs int) → (bool, error)
	must(err)
	// nine clocks, NACK and STOP on the downstream bus, then connect
	has, err := selector.HasControl() // Report control, () → (bool, error)
	must(err)
	conn2, err := selector.IsConnected() // Report the connection, () → (bool, error)
	must(err)
	// BUSON != NBUSON: some master is connected
	println("acquire", ok, "recover", rec, "control", has, "connected", conn2)
	must(selector.Disconnect()) // Isolate the downstream bus, () → error
	// BUSON = NBUSON; ownership bits are left
	must(selector.Connect()) // Reconnect without changing ownership, () → error

	must(selector.SetInterruptMask(false, true, false, false)) // Write IE, (busLost, busNotIdle, busInit, intIn bool) → error
	// true = masked, no interrupt
	mask, err := selector.GetInterruptMask() // Read IE, () → (uint8, error)
	must(err)
	println("mask", mask)
	must(selector.DisableInterrupt(other.PCA9541SourceIntIn)) // Mask one source, (source uint8) → error
	must(selector.EnableInterrupt(other.PCA9541SourceIntIn))  // Unmask one source, (source uint8) → error

	must(selector.OnInterrupt(func(status uint8) { println("interrupt", status) })) // Subscribe, (callback(status)) → error
	// falling edge on this master's INT, or a polling goroutine without a pin
	must(selector.TestInterrupt(true, false)) // Drive this master's INT low, (own, other bool) → error
	// the bus bits are written back unchanged
	time.Sleep(200 * time.Millisecond)
	must(selector.TestInterrupt(false, false)) // Clear the test, (own, other bool) → error
	status, err := selector.PollInterrupt()    // Read and clear ISTAT, () → (uint8, error)
	must(err)
	println("ISTAT", status)
	must(selector.OffInterrupt()) // Unsubscribe, () → error
	must(selector.Release())      // Give the bus to the other master, () → error
}
