package connection_test

// Conformance test for connection error handling (#767, spec §5): a fault
// injected at the connection must surface as an error from the chip driver,
// never as a stale or zero reading.

import (
	"errors"
	"testing"

	"github.com/tuhde/Periph/go/periph/chips/power"
	"github.com/tuhde/Periph/go/periph/connection"
)

func TestMockFaultInjection(t *testing.T) {
	m := connection.NewMockConnection()
	m.SetRegister(0x02, 0x12, 0x34)

	m.FailNextWrites(1, 1) // second write-phase call fails
	if _, err := m.ReadReg(0x02, 2); err != nil {
		t.Fatalf("first ReadReg should succeed: %v", err)
	}
	if _, err := m.ReadReg(0x02, 2); !errors.Is(err, connection.ErrMockFault) {
		t.Fatalf("second ReadReg: got %v, want ErrMockFault", err)
	}
	if got, err := m.ReadReg(0x02, 2); err != nil || got[0] != 0x12 || got[1] != 0x34 {
		t.Fatalf("third ReadReg: got %x, %v", got, err)
	}
	if len(m.Writes) != 3 {
		t.Fatalf("failing call must still be logged: %d writes", len(m.Writes))
	}

	m.QueueRead(0xAA)
	m.FailNextReads(1)
	if _, err := m.Read(1); !errors.Is(err, connection.ErrMockFault) {
		t.Fatalf("Read: got %v, want ErrMockFault", err)
	}
	if got, _ := m.Read(1); got[0] != 0xAA {
		t.Fatalf("failed Read must not consume the queue: got %x", got)
	}

	m.Disable()
	m.FailNextReads(1)
	if got, err := m.Read(2); err != nil || got[0] != 0 || got[1] != 0 {
		t.Fatalf("disabled Read: got %x, %v (want zeros, no error)", got, err)
	}
}

func TestDriverSurfacesBusFaults(t *testing.T) {
	m := connection.NewMockConnection()
	m.FailNextWrites(1, 0)
	if _, err := power.NewINA226Minimal(m, 0.1, 0.8); !errors.Is(err, connection.ErrMockFault) {
		t.Fatalf("constructor: got %v, want ErrMockFault from the config write", err)
	}

	m = connection.NewMockConnection()
	ina, err := power.NewINA226Minimal(m, 0.1, 0.8)
	if err != nil {
		t.Fatalf("constructor: %v", err)
	}
	m.SetRegister(0x02, 0x0F, 0xA0) // bus voltage register: 4000 * 1.25 mV = 5 V
	if v, err := ina.Voltage(); err != nil || v < 4.99 || v > 5.01 {
		t.Fatalf("Voltage: got %v, %v", v, err)
	}
	m.FailNextWrites(1, 0)
	if _, err := ina.Voltage(); !errors.Is(err, connection.ErrMockFault) {
		t.Fatalf("Voltage after a NACK: got %v, want ErrMockFault", err)
	}
}
