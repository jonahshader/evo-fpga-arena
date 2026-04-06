# Communications Architecture

## Overview

The comms layer sits between transport hardware (UART, XDMA, AXI-DMA) and the
Neuroevolution core. It is implemented in SpinalHDL as a set of reusable modules
parameterized over AXI-Stream data width, plus thin per-platform top-level wrappers.

```
                     +-------------------------+
                     |   Neuroevolution Core    |
                     |   (pulse/register I/F)   |
                     +------------+------------+
                                  |
                     +------------+------------+
                     |     Protocol Layer       |
                     |  ProtocolRx / ProtocolTx |
                     |  AXI-Stream[W] in/out    |
                     +------------+------------+
                                  |
               +------------------+------------------+
               |                  |                  |
        +------+------+   +------+------+   +-------+-----+
        | UartBridge  |   |  (native)   |   |  (native)   |
        | W=1 byte    |   | AXI-DMA     |   |   XDMA      |
        | UartRx/Tx   |   | KV260       |   |   PCIe      |
        +-------------+   +-------------+   +-------------+
```

## Module Inventory

### Protocol Layer (`evo.comms`)

#### ProtocolRx

Deserializes AXI-Stream byte sequences into Neuroevolution input signals.

```
Parameters:
  dataWidth: Int          -- AXI-Stream data width in bytes (1, 4, 8, 16)

Ports:
  io.stream    : slave(Axi4Stream)    -- incoming message bytes
  io.config    : out(GaConfigBundle)  -- latched GA configuration
  io.tilemap   : out(Tilemap)         -- latched tilemap
  io.cmd       : out(CommandBundle)   -- pulse outputs (trainingGo, etc.)
  io.input     : out(PlayerInput)     -- latched human input
  io.inputValid: out Bool             -- pulse: new input received
```

**State machine**: IDLE waits for header, parses MSG_TYPE + LENGTH, transitions to
a payload-receive state that shifts in bytes across one or more beats, then returns
to IDLE. Uses a byte counter to track position within the payload regardless of
beat width.

#### ProtocolTx

Serializes Neuroevolution output signals into AXI-Stream byte sequences.

```
Parameters:
  dataWidth: Int          -- AXI-Stream data width in bytes (1, 4, 8, 16)

Ports:
  io.stream           : master(Axi4Stream)   -- outgoing message bytes
  io.stateChange      : in Bool              -- pulse: NE state changed
  io.neState          : in(NeState)          -- current NE state
  io.gaState          : in(GaStateBundle)    -- GA progress
  io.gaStateSend      : in Bool              -- pulse: send GA status
  io.gameState        : in(GameState)        -- live game snapshot
  io.gameStateSend    : in Bool              -- pulse: send game state
  io.bramDumpParam      : in Bits            -- BRAM data
  io.bramDumpParamIndex : in UInt            -- BRAM address
  io.bramDumpParamValid : in Bool            -- BRAM valid pulse
  io.testGo           : in Bool              -- pulse: send test response
```

**Behavior**: Monitors input pulses with priority arbitration. When a pulse fires,
latches the associated data, constructs the header (type + flags + length), then
shifts out header + payload bytes across beats. Asserts `TREADY` backpressure
awareness: won't advance to next byte/beat until downstream accepts.

The BRAM dump path buffers incoming params into a register array (as the VHDL does),
then serializes the full 4608-byte dump once the last param arrives.

#### CommandBundle

Groups all pulse-type command outputs from ProtocolRx into a single bundle for
clean wiring.

```scala
case class CommandBundle() extends Bundle {
  val trainingGo      = Bool()
  val trainingPause   = Bool()
  val trainingResume  = Bool()
  val inferenceGo     = Bool()
  val inferenceStop   = Bool()
  val playAgainstNn   = Bool()    // latched, not pulse
  val dbBramDump      = Bool()
  val dbBramDumpIndex = UInt(8 bits)
  val testGo          = Bool()
}
```

### Transport Adapters (`evo.comms`)

#### UartBridge

Wraps existing UartRx/UartTx with AXI-Stream interfaces.

```
Parameters:
  clksPerBit: Int

Ports:
  io.rxSerial  : in Bool              -- UART RX pin
  io.txSerial  : out Bool             -- UART TX pin
  io.rxStream  : master(Axi4Stream)   -- received bytes (W=1)
  io.txStream  : slave(Axi4Stream)    -- bytes to transmit (W=1)
```

**RX side**: UartRx produces `(byte, valid)` pulses. The bridge wraps these as
`Axi4Stream` with `tvalid`/`tdata` and a small FIFO (depth ~4) for decoupling.
`TLAST` is never asserted (the protocol layer handles framing).

**TX side**: Accepts `Axi4Stream` beats. Drives `UartTx` one byte at a time, holding
`tready` low while transmission is in progress.

#### XdmaBlackBox (for YPCB PCIe card)

A SpinalHDL `BlackBox` declaring the XDMA IP port interface. The actual Xilinx IP is
instantiated by Vivado; this just provides the port signatures so SpinalHDL can wire
to it.

```
Ports (relevant subset):
  -- H2C AXI-Stream (host to card, master outputs from XDMA)
  m_axis_h2c_tdata   : Bits(128 bits)
  m_axis_h2c_tvalid  : Bool
  m_axis_h2c_tready  : Bool    -- driven by our logic
  m_axis_h2c_tkeep   : Bits(16 bits)
  m_axis_h2c_tlast   : Bool

  -- C2H AXI-Stream (card to host, slave inputs to XDMA)
  s_axis_c2h_tdata   : Bits(128 bits)
  s_axis_c2h_tvalid  : Bool    -- driven by our logic
  s_axis_c2h_tready  : Bool
  s_axis_c2h_tkeep   : Bits(16 bits)
  s_axis_c2h_tlast   : Bool

  -- Clock/reset from XDMA
  axi_aclk    : Bool
  axi_aresetn : Bool
```

Not simulated directly. Tests target the AXI-Stream interface boundary; the XDMA
BlackBox is only used in the synthesis top-level.

### Platform Top Levels (`evo.platform`)

Each platform gets a dedicated top-level component that wires the transport to the
protocol layer to the Neuroevolution core.

#### TopUart

For development/debug on any FPGA with UART pins.

```
Ports:
  io.rxSerial : in Bool
  io.txSerial : out Bool
```

Instantiates: UartBridge + ProtocolRx(dataWidth=1) + ProtocolTx(dataWidth=1) +
Neuroevolution.

This is the functional equivalent of the VHDL `top.vhd`, minus the Zynq PS wrapper.

#### TopKv260 (future)

For Xilinx KV260 with AXI-DMA through the Zynq PS.

```
Ports:
  -- AXI-Stream from PS AXI-DMA (MM2S = host-to-FPGA)
  io.s2mm  : master(Axi4Stream)    -- FPGA to PS
  io.mm2s  : slave(Axi4Stream)     -- PS to FPGA
```

The PS block design (Vivado) provides the AXI-DMA IP. SpinalHDL generates the PL
component with AXI-Stream ports; Vivado connects them in the block design.

Data width is configurable (32, 64, or 128 bits) based on PS DMA configuration.

#### TopYpcb (future)

For YPCB PCIe card with XDMA.

```
Ports:
  -- PCIe interface (directly to XDMA BlackBox)
  io.pcie  : PCIe pin bundle (passed through to XDMA BlackBox)
```

Instantiates: XdmaBlackBox + ProtocolRx(dataWidth=16) + ProtocolTx(dataWidth=16) +
Neuroevolution. The XDMA H2C stream feeds ProtocolRx; ProtocolTx feeds the C2H
stream.

## File Organization

```
spinal/src/main/scala/evo/
  comms/
    UartRx.scala          -- (existing) UART receiver
    UartTx.scala          -- (existing) UART transmitter
    UartBridge.scala      -- UART <-> AXI-Stream adapter
    ProtocolRx.scala      -- Message deserializer
    ProtocolTx.scala      -- Message serializer
    CommsDefs.scala       -- Message IDs, CommandBundle, constants
  platform/
    TopUart.scala         -- UART-based top level
    TopKv260.scala        -- (future) KV260 AXI-DMA top level
    TopYpcb.scala         -- (future) YPCB XDMA top level
    XdmaBlackBox.scala    -- (future) XDMA port declaration
```

## Width Parameterization

The protocol layer is parameterized by `dataWidth` (in bytes). The core logic uses a
byte-level counter to track position within a message, and a beat-packing/unpacking
mechanism to map bytes to/from AXI-Stream beats of width W.

### RX Byte Extraction

Each beat delivers W bytes. The deserializer maintains:
- `byteOffset`: position within the current message (0-based, counts up)
- Per-beat, it extracts byte `byteOffset % W` from `tdata`, or processes all W bytes
  in parallel if the target field spans a full beat.

For small messages (e.g. PLAYER_INPUT, 1 byte payload), the entire message fits in one
beat at W >= 8 (header + payload = 5 bytes <= 8).

For large messages (TILEMAP, 518 byte payload), the deserializer processes
ceil((518 + 4) / W) beats.

### TX Byte Packing

The serializer maintains a shift register or mux tree that packs payload bytes into
beat-sized chunks:
- Small messages: header + payload in a single beat (if it fits in W bytes).
- Large messages: fill beats sequentially, set `TKEEP` on the last beat for the
  remaining valid bytes.

### Width Adaptation (alternative approach)

If parameterizing the FSM over width proves too complex, an alternative is to fix the
protocol layer at W=1 (byte-wide) and use SpinalHDL's `Axi4StreamWidthAdapter` to
convert between the transport's native width and byte-wide. This sacrifices some
throughput (the adapter adds latency) but greatly simplifies the protocol FSMs.

For the initial implementation, **the byte-wide + width adapter approach is
recommended**. It can be optimized later if throughput becomes a bottleneck (unlikely
for command/telemetry traffic; the real bandwidth need is NN weight transfer which
comes later).

## Testing Strategy

### Unit Tests

All tests use SpinalHDL simulation with Verilator. No vendor IP is needed.

#### ProtocolRx Tests (`comms/ProtocolRxTest.scala`)

Use `Axi4StreamMaster` to drive message bytes into ProtocolRx, verify output signals.

Test cases:
- **Command parsing**: Send each command type, verify correct pulse/latch output
- **TILEMAP transfer**: Send full tilemap message, verify all tiles and spawns
- **GA_CONFIG transfer**: Send full config, verify all fields
- **PLAYER_INPUT**: Verify left/right/jump bits
- **Unknown message**: Send unknown MSG_TYPE, verify graceful skip (uses LENGTH to
  discard payload)
- **Back-to-back messages**: Send multiple messages without gaps
- **Backpressure**: Use `StreamReadyRandomizer` on the input to stress flow control

#### ProtocolTx Tests (`comms/ProtocolTxTest.scala`)

Use `Axi4StreamSlave` to capture output, verify message encoding.

Test cases:
- **STATE_CHANGE**: Pulse `stateChange`, verify header + correct state byte
- **GA_STATUS**: Pulse `gaStateSend`, verify header + 4-byte payload
- **GAME_STATE**: Pulse `gameStateSend`, verify header + 20-byte payload matches
  expected encoding
- **BRAM_DUMP_RESP**: Feed 4608 param pulses, verify header + 4608-byte payload
- **Priority arbitration**: Trigger multiple events simultaneously, verify all are
  sent (none dropped)
- **Backpressure**: Use `StreamReadyRandomizer` on the output to stress flow control

#### UartBridge Tests (`comms/UartBridgeTest.scala`)

- **RX path**: Drive serial bits, verify AXI-Stream bytes
- **TX path**: Drive AXI-Stream bytes, verify serial output
- **Backpressure**: Fill TX while UART is busy, verify no data loss

### Integration Tests

#### ProtocolRoundtrip (`comms/ProtocolRoundtripTest.scala`)

Wire ProtocolRx + ProtocolTx back-to-back with a mock Neuroevolution stub that
echoes commands as telemetry. Verify that a command sent in produces the expected
telemetry out.

#### Full System (`NeuroevolutionCommsTest.scala`)

Wire Protocol + real Neuroevolution core. Send TILEMAP + GA_CONFIG + TRAINING_GO,
verify STATE_CHANGE(TRAINING) and GA_STATUS messages appear on the TX stream.

This extends the existing `NeuroevolutionTest.scala` but drives inputs through
the protocol layer instead of direct signal manipulation.

### What We Don't Test in Simulation

- **XDMA IP behavior**: Tested on hardware only. In sim, we test up to the
  AXI-Stream boundary.
- **AXI-DMA IP behavior**: Same — tested on hardware via PS software.
- **Physical UART signal integrity**: Out of scope (that's a transport concern).

## Implementation Order

```
Phase 1 (foundation):
  1. CommsDefs.scala        -- Message IDs, CommandBundle
  2. UartBridge.scala       -- UART <-> AXI-Stream adapter
  3. UartBridge tests

Phase 2 (protocol):
  4. ProtocolRx.scala       -- Message deserializer (byte-wide)
  5. ProtocolRx tests
  6. ProtocolTx.scala       -- Message serializer (byte-wide)
  7. ProtocolTx tests

Phase 3 (integration):
  8. TopUart.scala           -- UART top level (replaces VHDL core.vhd + top.vhd)
  9. Integration tests

Phase 4 (future, per-platform):
  10. TopKv260.scala + AXI-DMA wiring
  11. XdmaBlackBox.scala + TopYpcb.scala
  12. Width-optimized protocol (if needed)
```

Phases 1-3 complete the VHDL port with a clean abstraction. Phase 4 adds new
platforms without touching the protocol logic.

## Design Decisions and Rationale

### Why explicit length in the header?

The VHDL protocol uses implicit lengths (the receiver knows how many bytes to expect
per message type). This works but is fragile:
- Adding a field to a message silently breaks the receiver if not updated in lockstep.
- The receiver can't skip unknown message types.
- No forward compatibility.

Explicit length costs 3 extra bytes per message (FLAGS + LENGTH) but enables:
- Receiver can skip unknown messages by consuming LENGTH bytes.
- Host and FPGA can run different protocol versions without hard faults.
- NN_UPLOAD has variable payload size.

### Why little-endian?

- PCIe is natively little-endian.
- x86/ARM hosts are little-endian.
- The VHDL protocol was big-endian (UART convention), but this added byte-swap logic
  on the host side. Switching to LE removes that.

### Why byte-wide protocol + width adapter (initial approach)?

The protocol FSMs (ProtocolRx/ProtocolTx) operate on bytes: parse a command byte,
shift in N payload bytes. Making them width-aware means the FSM must handle partial
beats, byte lanes, TKEEP masking, and cross-beat field alignment.

Starting byte-wide:
- Simpler FSM logic, easier to verify.
- Functionally identical to the VHDL (just with AXI-Stream instead of raw UART).
- SpinalHDL's `Axi4StreamWidthAdapter` handles the width conversion.
- Throughput is not a bottleneck for command/telemetry traffic.

The optimization to native-width FSMs can come later, particularly for the NN weight
transfer path where 4608+ bytes at 128-bit width matters.

### Why consolidate NE state messages?

The VHDL uses three separate TX message IDs (NE_IS_IDLE, NE_IS_TRAINING,
NE_IS_PLAYING) for state changes. This wastes ID space and requires three code paths
that do the same thing. A single STATE_CHANGE message with a 1-byte payload is cleaner
and extensible (future states don't need new message IDs).

### Why fold PLAY_AGAINST_NN into INFERENCE_GO?

In the VHDL, sending PLAY_AGAINST_NN_TRUE also triggers inference_go. The two
concepts are coupled. Making it an INFERENCE_GO parameter makes the coupling explicit
and removes two message IDs.
