//go:build tinygo

// DRV8830 complete example — TinyGo (Raspberry Pi Pico W).
package main

import (
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/motor"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	machine.I2C0.Configure(machine.I2CConfig{Frequency: 400 * machine.KHz})
	conn := connection.NewI2CConnection(machine.I2C0, motor.DRV8830I2CAddress, nil, nil) // Create I2C connection, (i2c, addr=0x60) → *I2CConnection
	defer conn.Close()

	m, err := motor.NewDRV8830Full(conn) // Create DRV8830 Full driver, (conn) → (*DRV8830Full, error)
	if err != nil {                      // one CONTROL read confirms presence; no writes
		panic(err)
	}

	if err := m.Drive(2.5); err != nil { // Drive at regulated voltage, (voltage V, + = forward) → error
		panic(err) // maps 2.5 V to the nearest VSET code and sets IN1=1, IN2=0
	}
	time.Sleep(1 * time.Second)
	v, dir, err := m.ReadOutput() // Read back CONTROL, () → (float32 V, DRV8830Direction, error)
	if err != nil {               // decodes VSET to volts and IN1/IN2 to a direction
		panic(err)
	}
	println("commanded", int32(v*1000), "mV", dir.String())

	if err := m.Drive(-1.5); err != nil { // Drive at regulated voltage, (voltage V, - = reverse) → error
		panic(err) // a negative voltage sets IN1=0, IN2=1
	}
	time.Sleep(1 * time.Second)

	if err := m.SetOutput(37, true, false); err != nil { // Write raw CONTROL fields, (vset 6–63, in1, in2) → error
		panic(err) // VSET 37 is ~2.97 V forward; codes 0–5 return ErrDRV8830InvalidVSet
	}
	time.Sleep(1 * time.Second)

	if err := m.Brake(); err != nil { // Short-brake, () → error
		panic(err) // IN1=IN2=1 drives both outputs high
	}
	time.Sleep(500 * time.Millisecond)
	if err := m.Stop(); err != nil { // Coast to standby, () → error
		panic(err) // IN1=IN2=0 leaves both outputs high-impedance
	}

	f, err := m.ReadFault() // Read fault status, () → (DRV8830Fault, error)
	if err != nil {         // does not clear — latched OCP/ILIMIT keep the bridge off
		panic(err)
	}
	println("fault", f.Fault, f.OCP, f.UVLO, f.OTS, f.ILimit)
	if err := m.ClearFault(); err != nil { // Clear fault bits, () → error
		panic(err) // writes CLEAR=1; re-enables a latched-off bridge
	}

	if err := m.OnInterrupt(func(s motor.DRV8830Fault) { // Subscribe to FAULTn, (callback) → error
		println("fault interrupt", s.Fault) // falls back to a polling goroutine when no IntPin is wired
	}); err != nil {
		panic(err)
	}
	status, err := m.PollInterrupt() // Poll fault status, () → (DRV8830Fault, error)
	if err != nil {                  // same as ReadFault; never clears implicitly
		panic(err)
	}
	if err := m.OffInterrupt(); err != nil { // Unsubscribe, () → error
		panic(err)
	}
	println("poll", status.Fault)
}
