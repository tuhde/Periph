package connection

import "time"

// DisplayEvent is one recorded call on a DisplayConnectionMock. Kind is one
// of "reset", "command", "data", "read", "waitBusy".
type DisplayEvent struct {
	Kind           string
	Cmd            byte
	Data           []byte
	Length         int
	BusyActiveHigh bool
	Timeout        time.Duration
}

// DisplayConnectionMock is an in-memory DisplayConn for chip unit tests — no
// hardware, no bus. Every call is appended to Events in order.
type DisplayConnectionMock struct {
	connectionBase
	WireMode WireMode
	Events   []DisplayEvent
	// Ready is the WaitBusy result; set false to simulate a timeout.
	Ready bool
	// BusyHigh is the raw level BusyLevel returns.
	BusyHigh bool
	readQ    []byte
}

// NewDisplayConnectionMock returns a mock whose WaitBusy reports ready.
func NewDisplayConnectionMock(mode WireMode) *DisplayConnectionMock {
	return &DisplayConnectionMock{WireMode: mode, Ready: true}
}

// QueueRead appends bytes returned by later ReadData calls, in order.
func (m *DisplayConnectionMock) QueueRead(data ...byte) { m.readQ = append(m.readQ, data...) }

// Commands returns the SendCommand calls recorded so far.
func (m *DisplayConnectionMock) Commands() []DisplayEvent {
	var out []DisplayEvent
	for _, e := range m.Events {
		if e.Kind == "command" {
			out = append(out, e)
		}
	}
	return out
}

func (m *DisplayConnectionMock) Reset() error {
	if m.IsEnabled() {
		m.Events = append(m.Events, DisplayEvent{Kind: "reset"})
	}
	return nil
}

func (m *DisplayConnectionMock) BusyLevel() (bool, error) { return m.BusyHigh, nil }

func (m *DisplayConnectionMock) WaitBusy(busyActiveHigh bool, timeout time.Duration) (bool, error) {
	m.Events = append(m.Events, DisplayEvent{Kind: "waitBusy", BusyActiveHigh: busyActiveHigh, Timeout: timeout})
	return m.Ready, nil
}

func (m *DisplayConnectionMock) SendCommand(cmd byte, data []byte) error {
	if m.IsEnabled() {
		m.Events = append(m.Events, DisplayEvent{Kind: "command", Cmd: cmd, Data: append([]byte(nil), data...)})
	}
	return nil
}

func (m *DisplayConnectionMock) SendData(data []byte) error {
	if m.IsEnabled() {
		m.Events = append(m.Events, DisplayEvent{Kind: "data", Data: append([]byte(nil), data...)})
	}
	return nil
}

func (m *DisplayConnectionMock) ReadData(n int) ([]byte, error) {
	if m.WireMode == WireModeSimplex {
		return nil, ErrReadUnavailable
	}
	out := make([]byte, n)
	if !m.IsEnabled() {
		return out, nil
	}
	m.Events = append(m.Events, DisplayEvent{Kind: "read", Length: n})
	for i := range out {
		if len(m.readQ) > 0 {
			out[i], m.readQ = m.readQ[0], m.readQ[1:]
		}
	}
	return out, nil
}

func (m *DisplayConnectionMock) Close() error { return nil }

var (
	_ DisplayConn = (*DisplayConnection)(nil)
	_ DisplayConn = (*DisplayConnectionMock)(nil)
)
