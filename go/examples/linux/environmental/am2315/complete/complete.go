//go:build linux && !tinygo

// AM2315 complete example — Linux host.
//
// Constructs the Full driver and calls every public method once.
package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/environmental"
	"github.com/tuhde/Periph/go/periph/connection"
)

func chk(err error) {
	if err != nil {
		panic(err)
	}
}

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	chk(err)
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x5C"), 0, 8)
	chk(err)

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x5C) → (*I2CConnection, error)
	chk(err)
	defer conn.Close()

	chip := environmental.NewAM2315Full(conn) // Create AM2315 driver, (connection) → *AM2315Full
	t, rh, err := chip.Read()                 // Read temperature and humidity, () → (float32 °C, float32 %RH, error)
	// previous measurement; reads are kept >= 2 s apart, CRC-16 checked
	chk(err)
	fmt.Println(t, rh)
	temp, err := chip.ReadTemperature() // Read temperature only, () → (float32 °C, error)
	chk(err)
	fmt.Println("temperature", temp)
	hum, err := chip.ReadHumidity() // Read humidity only, () → (float32 %RH, error)
	chk(err)
	fmt.Println("humidity", hum)
	rawRH, rawT, err := chip.ReadRaw() // Read raw words, () → (uint16, uint16, error)
	// humidity x10, temperature x10 as sign + magnitude
	chk(err)
	fmt.Println("raw", rawRH, rawT)
	t, rh, err = chip.ReadFresh() // Real-time value, () → (float32 °C, float32 %RH, error)
	// two reads >= 2 s apart; returns the second
	chk(err)
	fmt.Println("fresh", t, rh)
	regs, err := chip.ReadRegisters(0x08, 7) // Read registers, (start, count byte) → ([]byte, error)
	// function 0x03, 1-10 registers, CRC verified
	chk(err)
	fmt.Printf("registers 0x08.. % X\n", regs)
	model, version, id, err := chip.ReadDeviceInfo() // Read device information, () → (uint16, byte, uint32, error)
	chk(err)
	fmt.Printf("model %d version %d device id 0x%08X\n", model, version, id)
	chk(chip.WriteUserRegisters(0x1234, 0xABCD)) // Write user registers, (user1, user2 uint16) → error
	u1, u2, err := chip.ReadUserRegisters()      // Read user registers, () → (uint16, uint16, error)
	chk(err)
	fmt.Printf("user registers 0x%04X 0x%04X\n", u1, u2)
	chk(chip.WriteRegisters(0x10, []byte{0x00, 0x01})) // Write registers, (start byte, data []byte) → error
	// only the user registers 0x10-0x13 are allowed
	chip.Wake() // Wake the sensor, ()
	// dummy write, the NACK is ignored
	fmt.Println("done")
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
