package connection

import "errors"

// ErrMockFault is returned by a MockConnection transfer that was told to fail
// with FailNextWrites / FailNextReads.
var ErrMockFault = errors.New("mock: injected bus fault")

// MockConnection is an in-memory RegisterConnection for unit tests: no
// hardware, no bus. It mirrors the Python / Node.js / JVM I²C mocks:
//
//   - SetRegister preloads a register map; WriteRead(data, n) treats data[0]
//     (or the big-endian regBytes prefix) as the start register and returns n
//     consecutive register bytes. Write(data) of regBytes+1 or more bytes stores
//     data[regBytes:] at that register, so a write followed by a read sees it.
//   - QueueRead queues responses for plain Read(n) calls.
//   - Every Write / WriteRead command phase is recorded in Writes.
//   - FailNextWrites / FailNextReads inject faults: the affected calls return
//     ErrMockFault (a NACK or a failed transfer), so tests can assert that a
//     driver surfaces bus errors instead of returning stale or zero data.
//
// Disable gates access like the real connections: reads return zeros and
// writes are dropped.
type MockConnection struct {
	Registers map[uint32]byte
	Writes    [][]byte
	RegBytes  uint8

	readQueue  [][]byte
	failWrites int
	failSkip   int
	failReads  int
	disabled   bool
}

// NewMockConnection returns an empty mock with 1-byte register addresses.
func NewMockConnection() *MockConnection {
	return &MockConnection{Registers: map[uint32]byte{}, RegBytes: 1}
}

// SetRegister preloads consecutive register bytes starting at reg.
func (m *MockConnection) SetRegister(reg uint32, values ...byte) {
	for i, v := range values {
		m.Registers[reg+uint32(i)] = v
	}
}

// QueueRead queues the bytes returned by the next plain Read call.
func (m *MockConnection) QueueRead(data ...byte) {
	m.readQueue = append(m.readQueue, append([]byte(nil), data...))
}

// FailNextWrites makes n Write / WriteRead calls return ErrMockFault after
// letting the next `after` succeed. Failing calls are still recorded in Writes.
func (m *MockConnection) FailNextWrites(n, after int) {
	m.failWrites, m.failSkip = n, after
}

// FailNextReads makes the next n plain Read calls return ErrMockFault; the
// read queue is not consumed.
func (m *MockConnection) FailNextReads(n int) {
	m.failReads = n
}

func (m *MockConnection) maybeFailWrite() error {
	if m.failSkip > 0 {
		m.failSkip--
		return nil
	}
	if m.failWrites > 0 {
		m.failWrites--
		return ErrMockFault
	}
	return nil
}

func (m *MockConnection) reg(prefix []byte) uint32 {
	var r uint32
	for _, b := range prefix {
		r = r<<8 | uint32(b)
	}
	return r
}

// Write records data and, when it carries a register address plus payload,
// stores the payload in the register map.
func (m *MockConnection) Write(data []byte) error {
	if m.disabled {
		return nil
	}
	m.Writes = append(m.Writes, append([]byte(nil), data...))
	if err := m.maybeFailWrite(); err != nil {
		return err
	}
	rb := int(m.RegBytes)
	if len(data) > rb {
		base := m.reg(data[:rb])
		for i, v := range data[rb:] {
			m.Registers[base+uint32(i)] = v
		}
	}
	return nil
}

// Read pops the next queued response (zero-padded to n), or returns n zeros.
func (m *MockConnection) Read(n int) ([]byte, error) {
	if m.disabled {
		return make([]byte, n), nil
	}
	if m.failReads > 0 {
		m.failReads--
		return nil, ErrMockFault
	}
	out := make([]byte, n)
	if len(m.readQueue) > 0 {
		copy(out, m.readQueue[0])
		m.readQueue = m.readQueue[1:]
	}
	return out, nil
}

// WriteRead records the command phase and returns n register bytes starting
// at the register address it carries.
func (m *MockConnection) WriteRead(data []byte, n int) ([]byte, error) {
	if m.disabled {
		return make([]byte, n), nil
	}
	m.Writes = append(m.Writes, append([]byte(nil), data...))
	if err := m.maybeFailWrite(); err != nil {
		return nil, err
	}
	out := make([]byte, n)
	rb := int(m.RegBytes)
	if len(data) >= rb {
		base := m.reg(data[:rb])
		for i := range out {
			out[i] = m.Registers[base+uint32(i)]
		}
	}
	return out, nil
}

// ReadReg reads length bytes starting at register reg.
func (m *MockConnection) ReadReg(reg uint32, length int) ([]byte, error) {
	return m.WriteRead(regAddrBytes(reg, m.RegBytes), length)
}

// WriteReg writes data to register reg.
func (m *MockConnection) WriteReg(reg uint32, data []byte) error {
	return m.Write(append(regAddrBytes(reg, m.RegBytes), data...))
}

// Close is a no-op.
func (m *MockConnection) Close() error { return nil }

// Enable resumes access.
func (m *MockConnection) Enable() { m.disabled = false }

// Disable gates access: reads return zeros, writes are dropped.
func (m *MockConnection) Disable() { m.disabled = true }

// IsEnabled reports the software-gate state.
func (m *MockConnection) IsEnabled() bool { return !m.disabled }

// IntPin returns nil.
func (m *MockConnection) IntPin() InputPin { return nil }

// EnPin returns nil.
func (m *MockConnection) EnPin() OutputPin { return nil }

var _ RegisterConnection = (*MockConnection)(nil)
