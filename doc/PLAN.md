# SpinalHDL Port & Kintex-7 PCIe Migration Plan

## Target Hardware

**Board:** YPCB-00388-1P1 (used data center accelerator, ~$80)
- **FPGA:** Xilinx Kintex-7 XC7K480T-2FFG1156I
- **Logic:** ~478K cells, 1920 DSP48 slices
- **PCIe:** Gen2 x8 (~4 GB/s)
- **Memory:** 18x DDR3-1600 chips, two 72-bit ECC channels
- **Flash:** 512Mb NOR for bitstream storage
- **Clocks:** 2x 200MHz differential oscillators (DDR reference)
- **I/O:** JTAG, 3 LEDs, 2 buttons — no external I/O besides PCIe

## Existing VHDL Architecture

```
top (KV260 wrapper, UART)
└── core
    ├── comms_rx / comms_tx (UART protocol)
    └── neuroevolution (mode: IDLE / TRAINING / PLAYING)
        ├── bram_manager (144 BRAMs, 4-bit wide, 4608 deep)
        ├── ga (generation loop FSM)
        │   ├── fitness (evaluate population via game matchups)
        │   │   └── playagame (frame coordinator)
        │   │       └── game (physics: collision, coins, movement)
        │   ├── tournament (RNG-based selection)
        │   └── victor_copy (reproduction + mutation)
        ├── nn ×2 (forward pass, param streaming from BRAM)
        └── xormix32 (RNG, multiple streams)
```

Key characteristics:
- Fixed-point: `sfixed(11 downto -4)` (12.4 signed, 16-bit) throughout physics
- 4-bit NN weights/biases stored across 144 BRAMs (one BRAM per population member)
- Deep FSM nesting: ga → fitness → playagame → game
- Parameter streaming: BRAM manager streams params to NN decoder pipeline
- No hard IP besides inferred BRAM/DSP — very portable

## SpinalHDL Project Structure

```
src/main/scala/evo/
├── types/
│   ├── GameTypes.scala        # PlayerState, GameState, TileType, physics constants
│   ├── GaTypes.scala          # GaConfig, GaState, fitness arrays
│   ├── NnTypes.scala          # NnConfig, weight types, layer types
│   └── BramTypes.scala        # Memory config constants
├── game/
│   ├── Game.scala             # Physics FSM (6 states)
│   ├── PlayerPhysics.scala    # Phase1/Phase2 pure functions
│   └── PlayAGame.scala        # Frame coordinator
├── nn/
│   ├── NeuralNetwork.scala    # Forward pass pipeline
│   ├── Decoder.scala          # Param index → weight routing
│   └── Mutate.scala           # Mutation logic
├── ga/
│   ├── Ga.scala               # Generation loop FSM
│   ├── Fitness.scala          # Evaluation orchestrator
│   ├── Tournament.scala       # Selection
│   └── VictorCopy.scala       # Reproduction + mutation
├── infra/
│   ├── BramManager.scala      # Memory array + arbitration
│   └── Xormix32.scala         # RNG (BlackBox wrapping existing VHDL or native port)
├── comms/
│   ├── CommsRx.scala          # Protocol parser
│   └── CommsTx.scala          # State serializer
├── Core.scala                 # Central orchestrator
├── Neuroevolution.scala       # Mode manager (IDLE/TRAINING/PLAYING)
└── Top.scala                  # Board-specific wrapper
```

## Idiomatic SpinalHDL Patterns

### Types → Bundles + SFix

VHDL records become `Bundle`, fixed-point becomes `SFix`:

```scala
case class PlayerState() extends Bundle {
  val x     = SFix(11 exp, -4 exp)
  val y     = SFix(11 exp, -4 exp)
  val vx    = SFix(11 exp, -4 exp)
  val vy    = SFix(11 exp, -4 exp)
  val score = UInt(8 bits)
  val age   = UInt(16 bits)
}

case class GameState() extends Bundle {
  val p1      = PlayerState()
  val p2      = PlayerState()
  val tilemap = Vec(Vec(TileType(), TILEMAP_WIDTH), TILEMAP_HEIGHT)
}

object TileType extends SpinalEnum {
  val EMPTY, WALL, COIN, SPAWN_1, SPAWN_2 = newElement()
}
```

### FSMs → StateMachine plugin

```scala
val fsm = new StateMachine {
  val idle          = new State with EntryPoint
  val initBram      = new State
  val runFitness    = new State
  val runTournament = new State
  val runVictorCopy = new State

  idle.whenIsActive {
    when(io.go) { goto(initBram) }
  }
  runFitness.whenIsActive {
    when(fitness.io.done) { goto(runTournament) }
  }
}
```

### Memory → Mem with Scala generation

```scala
case class BramManager(config: BramConfig) extends Component {
  val brams = Vec(Mem(Bits(config.width bits), config.depth), config.numBrams)

  val paramIndex = Counter(config.totalParams)
  val bramSelect = paramIndex.value(/* upper bits */)
  val bramAddr   = paramIndex.value(/* lower bits */)
}
```

### Neural Network → Parameterized pipeline

```scala
case class NnConfig(
  layerSizes: Seq[Int],               // e.g., Seq(32, 32, 32, 3)
  weightBits: Int = 4,
  fixedExp:   (Int, Int) = (11, -4)
)

case class NeuralNetwork(config: NnConfig) extends Component {
  def layerForward(
    inputs: Vec[SFix], weights: Vec[Vec[SFix]], biases: Vec[SFix], relu: Boolean
  ): Vec[SFix] = {
    val outputs = Vec(SFix(/* ... */), biases.length)
    for (n <- 0 until biases.length) {
      val acc = (inputs, weights(n)).zipped.map(_ * _).reduce(_ + _) + biases(n)
      outputs(n) := if (relu) acc.max(0) else acc
    }
    outputs
  }
}
```

### Physics → Pure functions on Bundles

```scala
object PlayerPhysics {
  def phase1(player: PlayerState, input: PlayerInput, tilemap: TilemapType): PlayerState = {
    val next = PlayerState()
    next.vx := player.vx + Mux(input.left, -MOVE_ACCEL,
                            Mux(input.right, MOVE_ACCEL, SFix(0)))
    next.vy := player.vy + GRAVITY
    next
  }
}
```

### Comms → Abstract interface (UART now, PCIe later)

```scala
case class HostInterface() extends Bundle with IMasterSlave {
  val config    = Stream(GaConfig())
  val gameState = Stream(GameState())
  val bramDump  = Stream(Fragment(Bits(32 bits)))

  override def asMaster(): Unit = {
    master(config)
    slave(gameState, bramDump)
  }
}
```

## Porting Order

Bottom-up, testing each layer before moving up:

1. **Types** — GameTypes, GaTypes, NnTypes, BramTypes
2. **Game + PlayerPhysics** — self-contained physics, easy to unit test
3. **NeuralNetwork + Decoder** — verify against VHDL golden vectors
4. **BramManager** — memory infrastructure
5. **GA modules** — Fitness → Tournament → VictorCopy → Ga
6. **Neuroevolution + Core** — top-level integration
7. **Comms** — UART first (validate against existing software), then PCIe

## Future: PCIe & Kintex-7 Enhancements

Once the SpinalHDL port is validated (functionally equivalent to VHDL on KV260):

- **PCIe DMA** — Xilinx XDMA IP as BlackBox, SpinalHDL Axi4 bus for host↔FPGA
- **DDR3 population storage** — move genomes off BRAM into DDR for much larger populations
- **Parallel game engines** — 1920 DSP slices allow many simultaneous fitness evaluations
- **Wider NN weights** — 8-bit or 16-bit weights now that memory isn't the bottleneck
- **Multi-objective fitness** — richer evaluation with PCIe bandwidth for real-time monitoring
