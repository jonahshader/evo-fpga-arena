# SpinalHDL Porting Instructions

You are porting VHDL modules from `../fpga/src/` to SpinalHDL in `src/main/scala/evo/`.

## Read First

Before writing any code, read these files to understand the established conventions:
- `src/main/scala/evo/types/GameTypes.scala` — F4 fixed-point helper, TileType enum, Bundle patterns
- `src/main/scala/evo/game/Game.scala` — StateMachine FSM, clean component structure
- `src/main/scala/evo/game/PlayerPhysics.scala` — Area blocks, F4 ops, combinational functions

Also read the task plan: `../doc/PORTING_TASKS.md`

## Project Setup

- SpinalHDL 1.10.2a, Scala 2.12.18, sbt 1.10.7
- Simulator: Verilator 5.032
- Run tests: `sbt test` or `sbt "testOnly evo.YourTestClass"`

## SpinalHDL Conventions

### Fixed-point (F4)

```scala
import evo.types.F4
import evo.types.F4.SFixOps

val a = F4()                    // new wire
val b = F4(1.5)                 // constant
val c = (a + b).asF4            // truncate arithmetic result back to F4 size
val d = a.negated               // negate
val px = F4.toInt(a)            // SFix -> SInt (integer pixel)
val e = F4.fromInt(px)          // SInt -> SFix
```

Do NOT use `.raw.resized` directly — use `.asF4` instead.

### Enums

```scala
object MyEnum extends SpinalEnum(binarySequential) {
  val A, B, C = newElement()
}
```

### FSMs

```scala
val fsm = new StateMachine {
  val idle = new State with EntryPoint
  val run  = new State

  idle.whenIsActive { when(io.go) { goto(run) } }
  run.whenIsActive  { when(done)  { goto(idle) } }
}
```

### Bundles (replacing VHDL records)

```scala
case class MyBundle() extends Bundle {
  val foo = UInt(8 bits)
  val bar = Bool()
}
```

### Combinational functions

Put in an `object`, not inside a `Component`:

```scala
object MyOps {
  def compute(a: UInt, b: UInt): UInt = a + b
}
```

### Area blocks for pipeline stages

```scala
val stage1 = new Area {
  val out = F4()
  out := (a + b).asF4
}

val stage2 = new Area {
  val out = F4()
  out := stage1.out
  when(stage1.out > F4(10.0)) { out := 10.0 }
}

result := stage2.out
```

## Critical Pitfalls

### No combinatorial loops

VHDL functions use variables with sequential semantics. SpinalHDL signals are concurrent.
This creates a loop:

```scala
// BAD: reads vel after writing it
vel := (p.vel + accel).asF4
when(vel > maxVel) { vel := maxVel }  // loop!
```

Fix with separate wires:
```scala
val rawVel = (p.vel + accel).asF4
val clampedVel = F4()
clampedVel := rawVel
when(rawVel > maxVel) { clampedVel := maxVel }
result := clampedVel
```

### No assignment overlap

```scala
// BAD: getZero assigns all fields, then coinPos is overwritten
gs := gs.getZero
gs.coinPos := newCoin  // OVERLAP error!

// GOOD: assign each field individually
gs.p1.score := 0
gs.p2.score := 0
gs.coinPos := newCoin
```

### Driving SpinalEnum in simulation

```scala
dut.io.tile #= TileType.GROUND  // works
dut.io.tile #= 1                // also works (raw encoding value)
```

## Test Template

```scala
package evo

import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

class MyModuleTest extends AnyFunSuite {
  test("MyModule should do the thing") {
    SimConfig.withWave.compile(new MyModule).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)

      // drive inputs
      dut.io.go #= false
      dut.clockDomain.waitRisingEdge(5)

      // stimulus
      dut.io.go #= true
      dut.clockDomain.waitRisingEdge()
      dut.io.go #= false
      dut.clockDomain.waitRisingEdge(10)

      // check
      assert(dut.io.done.toBoolean)
    }
  }
}
```

## VHDL-to-SpinalHDL Mapping Cheat Sheet

| VHDL | SpinalHDL |
|------|-----------|
| `signal x : std_logic` | `val x = Bool()` |
| `signal x : unsigned(7 downto 0)` | `val x = UInt(8 bits)` |
| `signal x : signed(15 downto 0)` | `val x = SInt(16 bits)` |
| `signal x : std_logic_vector(N downto 0)` | `val x = Bits(N+1 bits)` |
| `type state_t is (A, B, C)` | `object State extends SpinalEnum { val A, B, C = newElement() }` |
| `type rec_t is record ... end record` | `case class Rec() extends Bundle { ... }` |
| `x <= y when cond else z` | `x := Mux(cond, y, z)` |
| `process(clk) if rising_edge(clk)` | Signals are registered via `Reg()` |
| `variable x` (in process) | `val x = Type()` (combinational wire) |
| `generic (N : integer)` | Constructor parameter: `class Foo(n: Int) extends Component` |
| `for i in 0 to N-1 generate` | `for (i <- 0 until n)` at elaboration time |
| `entity X port map(...)` | `val x = new X(); x.io.a := b` |
