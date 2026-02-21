# SpinalHDL Porting Tasks

## Context

We're porting all VHDL in `fpga/src/` to SpinalHDL in `spinal/src/main/scala/evo/`.
The types and game engine are already done. This document describes the remaining work.

## Already Ported

| VHDL | SpinalHDL | Notes |
|------|-----------|-------|
| `game_types.vhd` | `types/GameTypes.scala` | F4 helper, TileType enum, Player/GameState bundles |
| `ga_types.vhd` | `types/GaTypes.scala` | GaConfigBundle, GaStateBundle, NeState enum |
| `nn_types.vhd` | `types/NnTypes.scala` | NnConfig constants, NeuronParams bundle |
| `bram_types.vhd` | `types/BramTypes.scala` | BramConfig constants, BramCommand enum |
| `ne_types.vhd` | merged into `types/GaTypes.scala` | NeState enum |
| `custom_utils.vhd` | not needed | SpinalHDL has built-in equivalents |
| `game.vhd` | `game/Game.scala` | Game FSM (init/phase1/phase2) |
| `player_funs.vhd` | `game/PlayerPhysics.scala` + `game/TilemapOps.scala` | Physics, tile lookup, spawn sampling |

## Dependency Tiers

Modules within the same tier have no dependencies on each other and CAN be ported
in parallel. Each tier depends on all previous tiers being complete.

### Tier 1 — Leaf modules (all parallel)

These modules instantiate nothing and only use type packages that are already ported.

| Task ID | VHDL Source | Target SpinalHDL | Description |
|---------|-------------|-------------------|-------------|
| T1a | `imports/xormix32.vhd` | `infra/Xormix32.scala` | XORmix32 RNG. Wrap as BlackBox or native port. Has generics for stream count. |
| T1b | `imports/bram_sdp.vhd` | `infra/BramSdp.scala` | Simple dual-port BRAM. Use SpinalHDL `Mem` — no need for a wrapper, but create one for API parity. |
| T1c | `imports/uart_rx.vhd` | `comms/UartRx.scala` | UART receiver. Generic `G_CLKS_PER_BIT`. |
| T1d | `imports/uart_tx.vhd` | `comms/UartTx.scala` | UART transmitter. Generic `G_CLKS_PER_BIT`. |
| T1e | `neural_network/decoder_funs.vhd` | `nn/Decoder.scala` | Pure function: maps param_index → layer/neuron/weight via bit-slicing. |
| T1f | `neural_network/mutate_funs.vhd` | `nn/Mutate.scala` | Pure function: uniform random mutation ±1..4 with clamping. |
| T1g | `tournament.vhd` | `ga/Tournament.scala` | Tournament selection FSM. Uses RNG to pick candidates, tracks winner counts. |
| T1h | `victor_copy.vhd` | `ga/VictorCopy.scala` | Selective reproduction FSM. Pairs multi-victors with non-victors, issues COPY_AND_MUTATE. |
| T1i | `fitness.vhd` | `ga/Fitness.scala` | Fitness evaluation FSM. Iterates chromosomes × opponents × seeds × swap. |

### Tier 2 — Depends on Tier 1

| Task ID | VHDL Source | Target SpinalHDL | Depends On |
|---------|-------------|-------------------|------------|
| T2a | `neural_network/nn.vhd` | `nn/NeuralNetwork.scala` | T1e (Decoder) |
| T2b | `bram_manager.vhd` | `infra/BramManager.scala` | T1b (BramSdp), T1f (Mutate) |
| T2c | `playagame.vhd` | `game/PlayAGame.scala` | Game (already done) |
| T2d | `ga.vhd` | `ga/Ga.scala` | T1a (Xormix32), T1h (VictorCopy) |

### Tier 3 — Depends on Tier 2

| Task ID | VHDL Source | Target SpinalHDL | Depends On |
|---------|-------------|-------------------|------------|
| T3a | `neuroevolution.vhd` | `Neuroevolution.scala` | T2a, T2b, T2c, T2d, T1g, T1i |
| T3b | `comms_rx.vhd` | `comms/CommsRx.scala` | T1c (UartRx) — uses type packages only |
| T3c | `comms_tx.vhd` | `comms/CommsTx.scala` | T1d (UartTx) — uses type packages only |

### Tier 4 — Top-level integration

| Task ID | VHDL Source | Target SpinalHDL | Depends On |
|---------|-------------|-------------------|------------|
| T4a | `core.vhd` | `Core.scala` | T3a, T3b, T3c |
| T4b | `top.vhd` | `Top.scala` | T4a, T1c, T1d |

## Instructions for Each Task

Each task should follow this pattern:

### 1. Read the VHDL source thoroughly

Understand every signal, every state, every edge case.

### 2. Read the existing SpinalHDL code for conventions

Look at the already-ported files to understand the patterns used:

- **`types/GameTypes.scala`** — `F4` companion object with `.asF4` implicit, `TileType` as
  `SpinalEnum(binarySequential)`, `Bundle` case classes for records
- **`game/Game.scala`** — `StateMachine` FSM, clean separation of FSM from logic
- **`game/PlayerPhysics.scala`** — `Area` blocks for pipeline stages, `F4()` factory,
  `.asF4` for truncation, `.negated` for SFix negation
- **`game/TilemapOps.scala`** — pure combinational functions in an `object`

### 3. Write idiomatic SpinalHDL

Key conventions established in the existing code:

- **Fixed-point**: Use `F4()` factory, `F4(value)` for constants, `.asF4` to truncate
  arithmetic results, `F4.toInt(s)` / `F4.fromInt(s)` for conversion
- **Enums**: Use `SpinalEnum(binarySequential)` when the encoding matters
- **FSMs**: Use `StateMachine` with named `State` vals
- **Records → Bundles**: Use `case class Foo() extends Bundle`
- **VHDL functions → Scala `object` methods**: Combinational functions go in companion objects
- **Avoid combinatorial loops**: VHDL variables have sequential semantics inside functions;
  SpinalHDL signals are concurrent. Use separate intermediate wires for each pipeline stage.
  Never read a signal after conditionally writing it in the same combinational block.
- **No `.raw.resized` soup**: Use `.asF4` instead
- **`Area` blocks**: Group related logic within a component. Each area's outputs feed
  forward to the next.
- **Don't use `getZero` then override fields**: Assign each field individually to avoid
  SpinalHDL's ASSIGNMENT OVERLAP error.

### 4. Write a test

Every ported module should have a test in `src/test/scala/evo/` that:
- Elaborates the component (catches SpinalHDL errors)
- Runs a basic simulation via Verilator
- Verifies at least one meaningful behavior (FSM transitions, correct output for known input)

Use `SimConfig.withWave.compile(new Foo).doSim { dut => ... }` with ScalaTest.

To drive `SpinalEnum` values in simulation: `dut.io.signal #= TileType.GROUND`

### 5. Verify it compiles and passes

```bash
cd spinal && sbt "testOnly evo.YourTest"
```

## Parallel Execution Plan

```
Time -->

  T1a (xormix32) ──────────────┐
  T1b (bram_sdp) ──────────────┤
  T1c (uart_rx)  ──────────────┤
  T1d (uart_tx)  ──────────────┤
  T1e (decoder)  ──────────────┤
  T1f (mutate)   ──────────────┤──> T2a (nn)          ─┐
  T1g (tournament) ────────────┤    T2b (bram_manager) ─┤
  T1h (victor_copy) ───────────┤    T2c (playagame)    ─┤──> T3a (neuroevolution) ─┐
  T1i (fitness)  ──────────────┘    T2d (ga)           ─┤    T3b (comms_rx)        ─┤──> T4a (core) ──> T4b (top)
                                                        ┘    T3c (comms_tx)        ─┘
```

Maximum parallelism:
- **Tier 1**: 9 tasks in parallel
- **Tier 2**: 4 tasks in parallel
- **Tier 3**: 3 tasks in parallel (T3b/T3c can start with Tier 2 since they only need types)
- **Tier 4**: 2 tasks serial
