//go:build linux && !tinygo

// DRV8830 demo example — Linux host.
//
// A battery-powered toy motor controller: holds a regulated 3.0 V forward,
// then 2.0 V reverse, printing the commanded output every second — the
// DRV8830 keeps that average voltage constant as the battery sags. Brakes,
// then coasts. After every Drive the fault register is checked; a fault
// (e.g. a stalled motor tripping ILIMIT) stops the motor and clears it.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/motor"
	"github.com/tuhde/Periph/go/periph/connection"
)

func envOr(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

func checkFault(m *motor.DRV8830Full) {
	// --- Recover from a fault instead of leaving the bridge latched off ---
	// OCP and ILIMIT disable the H-bridge until CLEAR is written; stop first
	// so the motor does not lurch back to the old command on clear.
	f, err := m.ReadFault() // Read fault status, () → (DRV8830Fault, error)
	if err != nil {
		panic(err)
	}
	if f.Fault {
		fmt.Printf("fault: ocp=%v uvlo=%v ots=%v ilimit=%v\n", f.OCP, f.UVLO, f.OTS, f.ILimit)
		if err := m.Stop(); err != nil { // Coast to standby, () → error
			panic(err)
		}
		if err := m.ClearFault(); err != nil { // Clear fault bits, () → error
			panic(err)
		}
	}
}

func run(m *motor.DRV8830Full, voltage float32, seconds int) {
	// --- Hold a regulated voltage and watch it stay put ---
	// The chip PWM-regulates the bridge against VCC internally, so the
	// commanded voltage (and motor speed) holds while the battery discharges.
	if err := m.Drive(voltage); err != nil { // Drive at regulated voltage, (voltage V, signed) → error
		panic(err)
	}
	checkFault(m)
	for i := 0; i < seconds; i++ {
		time.Sleep(1 * time.Second)
		v, dir, err := m.ReadOutput() // Read back CONTROL, () → (float32 V, DRV8830Direction, error)
		if err != nil {
			panic(err)
		}
		fmt.Printf("%-7s %.2f V\n", dir, v)
	}
}

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		panic(err)
	}

	conn, err := connection.NewI2CConnection(bus, motor.DRV8830I2CAddress, nil, nil) // Create I2C connection, (bus=1, addr=0x60) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	m, err := motor.NewDRV8830Full(conn) // Create DRV8830 Full driver, (conn) → (*DRV8830Full, error)
	if err != nil {
		panic(err)
	}

	run(m, 3.0, 5)
	run(m, -2.0, 5)

	// --- Stop quickly, then release ---
	// Braking shorts the winding for a fast stop; coasting afterwards removes
	// the load so the motor does not sit shorted indefinitely.
	if err := m.Brake(); err != nil { // Short-brake, () → error
		panic(err)
	}
	time.Sleep(500 * time.Millisecond)
	if err := m.Stop(); err != nil { // Coast to standby, () → error
		panic(err)
	}
}
