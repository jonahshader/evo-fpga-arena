package evo.nn

import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite
import evo.types._
import evo.types.BramConfig._

/**
 * Test for the Mutate object functions.
 *
 * We create a simple wrapper component to test the pure functions.
 */
class MutateTest extends AnyFunSuite {

  /** Minimal wrapper component to test mutateParam in simulation. */
  class MutateWrapper extends Component {
    val io = new Bundle {
      val param = in(Bits(4 bits))
      val paramIndex = in(UInt(BRAM_ADDR_BITS bits))
      val rng = in(Bits(32 bits))
      val mutationRate = in(UInt(8 bits))
      val result = out(Bits(4 bits))
    }

    io.result := Mutate.mutateParam(io.param, io.paramIndex, io.rng, io.mutationRate)
  }

  test("Mutate should elaborate") {
    SimConfig.withWave.compile(new MutateWrapper).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.clockDomain.waitRisingEdge(5)
    }
  }

  test("Mutate should not mutate when rng >= mutationRate") {
    SimConfig.withWave.compile(new MutateWrapper).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)

      // Set up: param = 0, mutationRate = 100
      dut.io.param #= 0
      dut.io.paramIndex #= 0  // weight index
      dut.io.mutationRate #= 100
      dut.io.rng #= 150L  // rng[7:0] = 150 >= 100, no mutation
      dut.clockDomain.waitRisingEdge()

      // Result should be unchanged
      assert(dut.io.result.toBigInt == 0)
    }
  }

  test("Mutate should mutate when rng < mutationRate") {
    SimConfig.withWave.compile(new MutateWrapper).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)

      // Set up: param = 0, mutationRate = 200
      dut.io.param #= 0
      dut.io.paramIndex #= 0  // weight index
      dut.io.mutationRate #= 200
      // rng[7:0] = 4 < 200, rng[10:8] = 0 => delta = -1
      // So we need rng = 0x000 (rng[10:8]=0) + 4 (rng[7:0]=4) = 4
      dut.io.rng #= 4L  // rng[10:8] = 0, rng[7:0] = 4
      dut.clockDomain.waitRisingEdge()

      // Result should be 0 - 1 = -1 (which is 0xF as signed in 4 bits, or 15 unsigned)
      // In 4-bit signed: -1 = 1111 binary = 15 decimal
      assert(dut.io.result.toBigInt == 15)
    }
  }

  test("Mutate should apply correct deltas based on rng[10:8]") {
    SimConfig.withWave.compile(new MutateWrapper).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)

      val startParam = 0
      dut.io.param #= startParam
      dut.io.paramIndex #= 0  // weight index
      dut.io.mutationRate #= 255  // always mutate

      // Test each delta value with weight clamping in mind
      // Weights are clamped to [-2, 2], so some values will be clamped
      // Map: rng[10:8] -> delta -> (start=0) -> clamped
      // 0 -> -1 -> -1 -> -1 (no clamp) -> 15
      // 1 -> +1 -> +1 -> +1 (no clamp) -> 1
      // 2 -> -2 -> -2 -> -2 (no clamp) -> 14
      // 3 -> +2 -> +2 -> +2 (no clamp) -> 2
      // 4 -> -3 -> -3 -> -2 (clamped) -> 14
      // 5 -> +3 -> +3 -> +2 (clamped) -> 2
      // 6 -> -4 -> -4 -> -2 (clamped) -> 14
      // 7 -> +4 -> +4 -> +2 (clamped) -> 2
      val testCases = Seq(
        (0, -1, 15),   // -1 in 4-bit signed = 15 unsigned
        (1, 1, 1),     // 1
        (2, -2, 14),   // -2 in 4-bit signed = 14 unsigned
        (3, 2, 2),     // 2
        (4, -3, 14),   // -3 clamped to -2 = 14 unsigned
        (5, 3, 2),     // +3 clamped to +2 = 2
        (6, -4, 14),   // -4 clamped to -2 = 14 unsigned
        (7, 4, 2)      // +4 clamped to +2 = 2
      )

      for ((rngSelect, delta, expected) <- testCases) {
        // Set rng[10:8] to rngSelect, and keep rng[7:0] = 0 (which is < 255)
        dut.io.rng #= (rngSelect << 8).toLong
        dut.clockDomain.waitRisingEdge()

        val actual = dut.io.result.toBigInt
        assert(actual == expected,
          s"rng[10:8]=$rngSelect (delta=$delta): expected $expected, got $actual")
      }
    }
  }

  test("Mutate should clamp weights to [-2, 2]") {
    SimConfig.withWave.compile(new MutateWrapper).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)

      dut.io.paramIndex #= 0  // weight index
      dut.io.mutationRate #= 255  // always mutate

      // Test upper clamp: start at 2, add +4, should stay at 2
      // rng[10:8]=7 means +4 delta
      dut.io.param #= 2  // 2 in signed 4-bit
      dut.io.rng #= (7 << 8).toLong   // rng[10:8] = 7 => +4
      dut.clockDomain.waitRisingEdge()
      // 2 + 4 = 6, but should clamp to 2
      // 2 in signed 4-bit = 0010 binary = 2 decimal
      assert(dut.io.result.toBigInt == 2, s"Upper clamp: expected 2, got ${dut.io.result.toBigInt}")

      // Test lower clamp: start at -2 (14 unsigned), add -4, should stay at -2
      // rng[10:8]=6 means -4 delta
      dut.io.param #= 14  // -2 in signed 4-bit (1110)
      dut.io.rng #= (6 << 8).toLong    // rng[10:8] = 6 => -4
      dut.clockDomain.waitRisingEdge()
      // -2 - 4 = -6, but should clamp to -2
      // -2 in signed 4-bit = 1110 binary = 14 decimal
      assert(dut.io.result.toBigInt == 14, s"Lower clamp: expected 14 (-2), got ${dut.io.result.toBigInt}")
    }
  }

  test("Mutate should clamp biases to [-7, 7]") {
    SimConfig.withWave.compile(new MutateWrapper).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)

      // Set paramIndex to a bias index (>= TOTAL_WEIGHTS)
      dut.io.paramIndex #= NnConfig.TOTAL_WEIGHTS
      dut.io.mutationRate #= 255  // always mutate

      // Test upper clamp: start at 7, add +4, should stay at 7
      dut.io.param #= 7  // 7 in signed 4-bit
      dut.io.rng #= (7 << 8).toLong   // rng[10:8] = 7 => +4
      dut.clockDomain.waitRisingEdge()
      // 7 + 4 = 11, but should clamp to 7
      assert(dut.io.result.toBigInt == 7, s"Bias upper clamp: expected 7, got ${dut.io.result.toBigInt}")

      // Test lower clamp: start at -7 (9 unsigned in 4 bits), add -4, should stay at -7
      dut.io.param #= 9   // -7 in signed 4-bit (1001)
      dut.io.rng #= (6 << 8).toLong    // rng[10:8] = 6 => -4
      dut.clockDomain.waitRisingEdge()
      // -7 - 4 = -11, but should clamp to -7
      assert(dut.io.result.toBigInt == 9, s"Bias lower clamp: expected 9 (-7), got ${dut.io.result.toBigInt}")
    }
  }

  test("Mutate should distinguish between weights and biases") {
    SimConfig.withWave.compile(new MutateWrapper).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)

      dut.io.mutationRate #= 255  // always mutate
      dut.io.param #= 5  // same starting value for both
      dut.io.rng #= (7 << 8).toLong   // +4 delta

      // Weight: should clamp to 2
      dut.io.paramIndex #= 0  // weight index
      dut.clockDomain.waitRisingEdge()
      val weightResult = dut.io.result.toBigInt

      // Bias: 5 + 4 = 9, should clamp to 7 (bias max)
      dut.io.paramIndex #= NnConfig.TOTAL_WEIGHTS  // bias index
      dut.clockDomain.waitRisingEdge()
      val biasResult = dut.io.result.toBigInt

      assert(weightResult == 2, s"Weight result should clamp to 2, got $weightResult")
      assert(biasResult == 7, s"Bias result should clamp to 7, got $biasResult")
      assert(weightResult != biasResult, "Weight and bias should have different clamp behavior")
    }
  }

  test("Mutate boundary case: mutation rate of 0 never mutates") {
    SimConfig.withWave.compile(new MutateWrapper).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)

      dut.io.param #= 5
      dut.io.paramIndex #= 0
      dut.io.mutationRate #= 0  // never mutate
      dut.io.rng #= 0L  // rng[7:0] = 0, but 0 is not < 0
      dut.clockDomain.waitRisingEdge()

      assert(dut.io.result.toBigInt == 5, "Should not mutate when mutationRate is 0")
    }
  }

  test("Mutate boundary case: mutation rate of 255 always mutates") {
    SimConfig.withWave.compile(new MutateWrapper).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)

      // Test with a starting value that won't clamp
      dut.io.param #= 0
      dut.io.paramIndex #= 0
      dut.io.mutationRate #= 255  // always mutate (rng[7:0] max is 255)
      // With rng[10:8] = 1 (delta = +1) and rng[7:0] = 254 (which is < 255)
      dut.io.rng #= (1 << 8 | 254).toLong  // rng[10:8] = 1 => +1
      dut.clockDomain.waitRisingEdge()

      // 0 + 1 = 1
      assert(dut.io.result.toBigInt == 1, s"Should mutate to 1, got ${dut.io.result.toBigInt}")
    }
  }
}
