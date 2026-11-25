//go:build linux && !tinygo

// DS3502 demo — Linux host.
//
// VCOM-style voltage setter with RH = 15.0 V and RL = 0 V (V+ = 15.5 V): prints the
// power-up wiper, sets 6.0 V, sweeps the wiper in 16 steps, steps up and down,
// shows the MODE bit and finally persists the chosen code in the EEPROM.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/adc_dac"
	"github.com/tuhde/Periph/go/periph/connection"
)

func must(err error) {
	if err != nil {
		panic(err)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	must(err)

	conn, err := connection.NewI2CConnection(bus, adcdac.DS3502I2CAddress, nil, nil) // Create I2C connection, (bus=1, addr=0x28) → (*I2CConnection, error)
	must(err)
	defer conn.Close()

	pot, err := adcdac.NewDS3502Full(conn, 15.0, 0.0) // Create DS3502 Full driver, (conn, vHigh=1.0 V, vLow=0.0 V) → (*DS3502Full, error)
	must(err)

	// --- Power-up position ---
	// The constructor leaves the wiper where the IVR put it (factory default 0x40).
	up, err := pot.GetWiper() // Read wiper position, () → (uint8 0–127, error)
	must(err)
	v, _ := pot.GetVoltage()
	fmt.Println("power-up wiper", up, "->", v, "V")

	// --- Set 6.0 V ---
	must(pot.SetVoltage(6.0))    // Set RW output voltage, (volts V) → error
	ratio, err := pot.GetRatio() // Read divider ratio, () → (float32, error)
	must(err)
	w, _ := pot.GetWiper()
	v, _ = pot.GetVoltage()
	fmt.Println("code", w, "ratio", ratio, "voltage", v, "V")

	// --- Sweep in 16 steps ---
	// The step size is (V_RH − V_RL)/127, about 118 mV at a 15 V span.
	for code := 0; code <= 127; code += 8 {
		must(pot.SetWiper(code))   // Set wiper position, (code 0–127) → error
		v, err := pot.GetVoltage() // Read unloaded RW voltage, () → (float32 V, error)
		must(err)
		fmt.Println("code", code, "->", v, "V")
		time.Sleep(200 * time.Millisecond)
	}

	// --- Step up and down ---
	must(pot.SetVoltage(6.0))
	up4, err := pot.Increment(4) // Step the wiper, (steps int) → (uint8 0–127, error)
	must(err)
	dn4, err := pot.Increment(-4)
	must(err)
	fmt.Println("+4 ->", up4, ", -4 ->", dn4)

	// --- MODE ---
	// MODE = 1 (the working mode) keeps wiper writes in SRAM; StoreWiper switches
	// to MODE = 0 only for the duration of the EEPROM write.
	eeprom, err := pot.GetMode() // Read MODE, () → (bool, error)
	must(err)
	fmt.Println("eeprom mode", eeprom)
	must(pot.SetMode(false)) // Select SRAM-only writes, (eeprom bool) → error

	// --- Persist the chosen code ---
	// This consumes one of the EEPROM's limited write cycles.
	code, err := pot.GetWiper()
	must(err)
	must(pot.StoreWiper(int(code))) // Store power-up position, (code int, negative = current) → error
	must(pot.WaitReady(30))         // Acknowledge polling, (timeoutMs int) → error
	back, _ := pot.GetWiper()
	fmt.Println("read back", back)
}
