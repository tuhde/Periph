//go:build tinygo

// DHTxxConnection is the TinyGo implementation of the DHT11/DHT22
// single-wire connection. Switches a machine.Pin between input and
// output to drive the start signal and read the response.
package connection

import (
	"fmt"
	"machine"
	"time"
)

// DHTxx timing constants.
const (
	dhtStartLowMs     = 20
	dhtBitThresholdUs = 40
	dhtBitTimeoutUs   = 200
)

// DHTxxConnection is a TinyGo machine.Pin-backed DHTxx connection. It does
// not implement the Connection interface — see the Linux variant's doc
// comment — but embeds connectionBase for the shared Enable/Disable/
// IsEnabled/EnPin gating. There is no INT line, so IntPin is always nil.
type DHTxxConnection struct {
	connectionBase
	pin machine.Pin
}

// NewDHTxxConnection binds the data GPIO pin. enPin may be nil if the
// device's EN line is not wired.
func NewDHTxxConnection(pin machine.Pin, enPin OutputPin) *DHTxxConnection {
	return &DHTxxConnection{connectionBase: connectionBase{enPin: enPin}, pin: pin}
}

// Close is a no-op on TinyGo.
func (t *DHTxxConnection) Close() error {
	return nil
}

// setOutput reconfigures the pin as output and drives it.
func (t *DHTxxConnection) setOutput(high bool) {
	t.pin.Configure(machine.PinConfig{Mode: machine.PinOutput})
	if high {
		t.pin.High()
	} else {
		t.pin.Low()
	}
}

// setInput reconfigures the pin as input.
func (t *DHTxxConnection) setInput() {
	t.pin.Configure(machine.PinConfig{Mode: machine.PinInput})
}

// read returns the current pin value (requires input configuration).
func (t *DHTxxConnection) read() bool {
	return t.pin.Get()
}

// Read executes the full start/response/bit-read sequence and returns
// the raw 5-byte frame. Returns a zero-filled frame without touching the
// bus when disabled.
func (t *DHTxxConnection) Read() ([]byte, error) {
	if !t.IsEnabled() {
		return make([]byte, 5), nil
	}
	// 1. Host start signal: drive LOW for >=18 ms.
	t.setOutput(false)
	time.Sleep(dhtStartLowMs * time.Millisecond)
	// Release the bus (set as input, external pull-up brings it HIGH).
	t.setInput()
	time.Sleep(30 * time.Microsecond)

	// 2. Wait for sensor to pull DATA LOW.
	deadline := time.Now().Add(time.Microsecond * dhtBitTimeoutUs)
	for t.read() {
		if time.Now().After(deadline) {
			return nil, fmt.Errorf("dhtxx: timeout waiting for response LOW")
		}
	}
	// Wait for sensor to release DATA HIGH.
	deadline = time.Now().Add(time.Microsecond * dhtBitTimeoutUs)
	for !t.read() {
		if time.Now().After(deadline) {
			return nil, fmt.Errorf("dhtxx: timeout waiting for response HIGH")
		}
	}

	// 3. Read 40 bits.
	frame := make([]byte, 5)
	for byteIdx := 0; byteIdx < 5; byteIdx++ {
		for bitIdx := 7; bitIdx >= 0; bitIdx-- {
			// Wait for the LOW pre-bit pulse to end.
			deadline = time.Now().Add(time.Microsecond * dhtBitTimeoutUs)
			for !t.read() {
				if time.Now().After(deadline) {
					return nil, fmt.Errorf("dhtxx: timeout waiting for bit HIGH")
				}
			}
			// Measure the HIGH pulse.
			highStart := time.Now()
			deadline = time.Now().Add(time.Microsecond * dhtBitTimeoutUs)
			for t.read() {
				if time.Now().After(deadline) {
					return nil, fmt.Errorf("dhtxx: timeout waiting for bit LOW")
				}
			}
			if time.Since(highStart).Microseconds() > dhtBitThresholdUs {
				frame[byteIdx] |= 1 << bitIdx
			}
		}
	}
	return frame, nil
}
