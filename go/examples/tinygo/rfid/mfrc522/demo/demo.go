//go:build tinygo

// MFRC522 demo example — TinyGo / Raspberry Pi Pico W.
//
// Prepaid-card credit counter. Simulates a simple transit-gate /
// vending-machine credit system using a MIFARE Classic card's
// value-block feature:
//
//  1. Poll for a card (Full SelectCard).
//  2. On detection, authenticate sector 1's block 4 with the
//     well-known MIFARE factory default key A (FF FF FF FF FF FF) —
//     the demo notes this key must be replaced with a per-deployment
//     secret in any real access-control system.
//  3. Read the value block; if uninitialized, write an initial
//     credit balance of 10 via writeBlock in the value-block layout,
//     then restoreValue to normalize it.
//  4. DecrementValue(block, 1) to "spend" one credit; ReadBlock to
//     print the remaining balance.
//  5. If the balance reaches 0, print "Access denied — no credits
//     remaining" instead of decrementing.
//  6. StopCrypto, HaltCard.
//
// This exercises authentication, block read/write, and the
// increment/decrement/restore/transfer value-block cycle — the parts
// of the API a simple UID-only demo would never touch.
package main

import (
	"encoding/binary"
	"encoding/hex"
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/rfid"
	"github.com/tuhde/Periph/go/periph/connection"
)

// block4ValueLayout encodes 4 LE copies of value (3 + 1 inverted each),
// then 4 copies of the same address byte. This is the MIFARE Classic
// value-block layout described in the spec.
func block4ValueLayout(value uint32, addr uint8) []byte {
	inv := ^value
	b := make([]byte, 16)
	binary.LittleEndian.PutUint32(b[0:4], value)
	binary.LittleEndian.PutUint32(b[4:8], inv)
	binary.LittleEndian.PutUint32(b[8:12], value)
	b[12] = addr
	b[13] = addr
	b[14] = addr
	b[15] = addr
	return b
}

// readValueBlock decodes the 32-bit LE value from a MIFARE Classic
// value block, taking the first copy. Returns an error if the value
// triple is internally inconsistent (the inverted copy is not ~value).
func readValueBlock(block []byte) (uint32, error) {
	if len(block) < 4 {
		return 0, fmt.Errorf("block too short: %d", len(block))
	}
	v := binary.LittleEndian.Uint32(block[0:4])
	inv := binary.LittleEndian.Uint32(block[4:8])
	if v != ^inv {
		return 0, fmt.Errorf("value block inconsistent: value=0x%08X inv=0x%08X", v, inv)
	}
	return v, nil
}

func main() {
	i2c := machine.I2C1
	if err := i2c.Configure(machine.I2CConfig{
		SDA:       machine.GP4,
		SCL:       machine.GP5,
		Frequency: 400_000,
	}); err != nil {
		panic(err)
	}

	conn := connection.NewI2CConnection(i2c, 0x28, nil, nil) // Create I2C connection, (i2c, addr=0x28) → (*I2CConnection)

	chip, err := rfid.NewMFRC522Full(conn) // Create MFRC522 driver, (connection) → (*MFRC522Full, error)
	if err != nil {
		panic(err)
	}

	// --- Poll for a card and authenticate against sector 1's block 4 ---
	// The well-known factory default key A opens the MIFARE Classic
	// "connection configuration" sector on every fresh card. Any
	// production deployment would replace this with a unique key per
	// card.
	defaultKey := []byte{0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF}

	fmt.Println("Waiting for a MIFARE Classic card...")
	var uid []byte
	for {
		uid, err = chip.SelectCard() // Anticollision + select only, () → ([]byte, error)
		if err != nil {
			fmt.Printf("select_card: %v\n", err)
			time.Sleep(time.Second)
			continue
		}
		if uid != nil {
			break
		}
		time.Sleep(500 * time.Millisecond)
	}
	fmt.Printf("Card UID: %s\n", hex.EncodeToString(uid))

	ok, err := chip.Authenticate(4, rfid.KEY_A, defaultKey, uid) // Run MFAuthent, (block, key_type, key, uid) → (bool, error)
	if err != nil || !ok {
		fmt.Printf("authenticate failed: %v\n", err)
		_ = chip.HaltCard() // Send HLTA, () → error
		return
	}
	defer chip.StopCrypto() // Clear MFCrypto1On, () → error
	defer chip.HaltCard()

	// --- Read the existing value block, or seed with 10 credits ---
	block, err := chip.ReadBlock(4) // Read MIFARE block, (block_address) → ([]byte, error)
	if err != nil {
		fmt.Printf("read_block: %v\n", err)
		return
	}
	value, err := readValueBlock(block)
	if err != nil {
		// Uninitialized — write a fresh value block with 10 credits.
		fmt.Println("Card uninitialized; seeding 10 credits.")
		seed := block4ValueLayout(10, 4)
		if ok, err := chip.WriteBlock(4, seed); err != nil || !ok { // Write MIFARE block, (block, data) → (bool, error)
			fmt.Printf("write_block: %v\n", err)
			return
		}
		if ok, err := chip.RestoreValue(4); err != nil || !ok { // Restore + transfer, (block) → (bool, error)
			fmt.Printf("restore_value: %v\n", err)
			return
		}
		value = 10
	}
	fmt.Printf("Initial balance: %d credits\n", value)

	// --- Spend one credit per loop iteration ---
	for i := 0; i < 20; i++ {
		if value == 0 {
			fmt.Println("Access denied — no credits remaining")
			break
		}
		ok, err := chip.DecrementValue(4, 1) // Decrement + transfer, (block, delta) → (bool, error)
		if err != nil || !ok {
			fmt.Printf("decrement_value: %v\n", err)
			break
		}
		block, err = chip.ReadBlock(4) // Read MIFARE block, (block_address) → ([]byte, error)
		if err != nil {
			fmt.Printf("read_block: %v\n", err)
			break
		}
		value, err = readValueBlock(block)
		if err != nil {
			fmt.Printf("decode: %v\n", err)
			break
		}
		fmt.Printf("Spent 1 credit; balance: %d\n", value)
	}
}
