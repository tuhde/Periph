//go:build linux && !tinygo

// APDS-9960 complete example — Linux host.
//
// Exercises every method in the APDS9960Full API: chip ID, RGBC
// reads, ALS configuration, wait engine, proximity, thresholds,
// interrupts, gesture engine, and status flags.
package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/transport"
	"github.com/tuhde/Periph/go/periph/chips/light"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		panic(err)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x39"), 0, 8)
	if err != nil {
		panic(err)
	}

	tr, err := transport.NewI2CTransport(bus, uint8(addr)) // Create I2C transport, (bus=1, addr=0x39) → (*I2CTransport, error)
	if err != nil {
		panic(err)
	}
	defer tr.Close()

	chip, err := light.NewAPDS9960Full(tr) // Create APDS-9960 full driver, (transport) → (*APDS9960Full, error)
	if err != nil {
		panic(err)
	}

	id, err := chip.ChipID() // Read device ID, () → (uint8, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("chip_id: 0x%02X\n", id) // expect 0xAB

	c, r, g, b, err := chip.Color() // Read all four RGBC channels, () → (uint16, uint16, uint16, uint16, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("C=%d R=%d G=%d B=%d\n", c, r, g, b)
	// Reading CDATAL at 0x94 atomically latches all eight bytes 0x94–0x9B

	clear, err := chip.ColorClear() // Read clear channel via 2-byte LE, () → (uint16, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("clear: %d\n", clear)
	red, err := chip.ColorRed() // Read red channel via 8-byte burst, () → (uint16, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("red: %d\n", red)
	green, err := chip.ColorGreen() // Read green channel via 8-byte burst, () → (uint16, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("green: %d\n", green)
	blue, err := chip.ColorBlue() // Read blue channel via 8-byte burst, () → (uint16, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("blue: %d\n", blue)

	if err := chip.ConfigureALS(0xB6, 1); err != nil { // Configure ALS, (atime=0xB6, again=1 → 4×) → error
		panic(err)
	}
	// atime 0xB6 = 72 cycles ≈ 200 ms; again 1 = 4× ALS gain
	if err := chip.ConfigureWait(0xFF, false); err != nil { // Configure wait time, (wtime=0xFF, long=false) → error
		panic(err)
	}
	if err := chip.EnableWait(true); err != nil { // Enable wait engine, (enabled=true) → error
		panic(err)
	}

	if err := chip.EnableProximity(true); err != nil { // Enable proximity engine, (enabled=true) → error
		panic(err)
	}
	if err := chip.ConfigureProximityLED(0, 0, 0, 1); err != nil { // Configure proximity LED, (ldrive=0, pgain=0, ppulse=0, pplen=1) → error
		panic(err)
	}
	if err := chip.SetLEDBoost(0); err != nil { // Set LED boost, (boost=0 → 100%) → error
		panic(err)
	}
	prox, err := chip.Proximity() // Read proximity count, () → (uint8, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("proximity: %d\n", prox)

	if err := chip.AlsThreshold(100, 60000); err != nil { // Set ALS thresholds, (low=100, high=60000) → error
		panic(err)
	}
	if err := chip.ProximityThreshold(10, 200); err != nil { // Set proximity thresholds, (low=10, high=200) → error
		panic(err)
	}
	if err := chip.SetPersistence(0, 1); err != nil { // Set persistence, (ppers=0, apers=1) → error
		panic(err)
	}

	if err := chip.EnableAlsInterrupt(true); err != nil { // Enable ALS interrupt, (enabled=true) → error
		panic(err)
	}
	if err := chip.EnableProximityInterrupt(true); err != nil { // Enable proximity interrupt, (enabled=true) → error
		panic(err)
	}
	if err := chip.ClearAlsInterrupt(); err != nil { // Clear ALS interrupt, () → error
		panic(err)
	}
	if err := chip.ClearProximityInterrupt(); err != nil { // Clear proximity interrupt, () → error
		panic(err)
	}
	if err := chip.ClearAllInterrupts(); err != nil { // Clear all non-gesture interrupts, () → error
		panic(err)
	}

	if err := chip.SetProximityOffset(10, -5); err != nil { // Set proximity offset, (ur=10, dl=-5) → error
		panic(err)
	}
	if err := chip.SetProximityMask(false, false, false, false); err != nil { // Set proximity mask, (u, d, l, r) → error
		panic(err)
	}

	if err := chip.EnableGesture(true); err != nil { // Enable gesture engine, (enabled=true) → error
		panic(err)
	}
	if err := chip.ConfigureGesture(1, 0, 0, 1, 1, 50, 20); err != nil { // Configure gesture, (ggain, gldrive, gpulse, gplen, gwtime, gpenth, gexth) → error
		panic(err)
	}
	avail, err := chip.GestureAvailable() // Check gesture FIFO valid, () → (bool, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("gesture_available: %v\n", avail)
	level, err := chip.GestureFIFOLevel() // Read FIFO level, () → (uint8, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("gesture_fifo_level: %d\n", level)
	fifo, err := chip.ReadGestureFIFO(32) // Read gesture FIFO, (maxSets=32) → ([][4]uint8, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("gesture_fifo read: %d datasets\n", len(fifo))
	if err := chip.ClearGestureFIFO(); err != nil { // Clear gesture FIFO, () → error
		panic(err)
	}
	if err := chip.EnableGestureInterrupt(false); err != nil { // Disable gesture interrupt, (enabled=false) → error
		panic(err)
	}
	if err := chip.EnableGesture(false); err != nil { // Disable gesture engine, (enabled=false) → error
		panic(err)
	}

	status, err := chip.Status() // Read STATUS register, () → (uint8, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("status: 0x%02X\n", status)
	validAls, err := chip.IsAlsValid() // Check AVALID, () → (bool, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("is_als_valid: %v\n", validAls)
	validProx, err := chip.IsProximityValid() // Check PVALID, () → (bool, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("is_proximity_valid: %v\n", validProx)
	satAls, err := chip.IsAlsSaturated() // Check CPSAT, () → (bool, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("is_als_saturated: %v\n", satAls)
	satProx, err := chip.IsProximitySaturated() // Check PGSAT, () → (bool, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("is_proximity_saturated: %v\n", satProx)

	if err := chip.EnableProximity(false); err != nil { // Disable proximity engine, (enabled=false) → error
		panic(err)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
