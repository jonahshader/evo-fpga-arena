package evo.nn

import spinal.core._
import spinal.lib._
import evo.types._

/**
 * Neural Network inference engine.
 *
 * Ported from fpga/src/neural_network/nn.vhd
 *
 * Contains:
 * - observe_state: converts game state to input logits
 * - weight_mult: multiply-by-shift for {-2,-1,0,1,2} weights
 * - neuron_forward: compute one neuron's output (dot product + bias + optional ReLU)
 * - layer_forward: compute all neurons in a layer
 * - Main component: multi-cycle forward pass through LAYER_COUNT layers
 *
 * Parameter loading: a separate process decodes incoming (param, param_index)
 * values and places them into the layers register using Decoder.decodeAddress.
 */
object NeuralNetwork {
  import NnConfig._

  /** Multiply data by weight using shifts only. Weights in {-2,-1,0,1,2}. */
  def weightMult(data: SInt, weight: SInt): SInt = {
    val result = SInt(NEURON_DATA_WIDTH + 1 bits)
    val extended = data.resize(NEURON_DATA_WIDTH + 1)

    result := 0
    when(weight === -2) {
      result := (-extended) |<< 1
    } elsewhen(weight === -1) {
      result := -extended
    } elsewhen(weight === 1) {
      result := extended
    } elsewhen(weight === 2) {
      result := extended |<< 1
    }

    result
  }

  /** Forward pass for one neuron. */
  def neuronForward(neuron: NeuronParams, logits: Vec[SInt], activate: Bool): SInt = {
    // Sum width: NEURON_DATA_WIDTH + 1 (shift) + 1 (bias) + WEIGHTS_PER_NEURON_EXP (additions)
    val sumWidth = 2 + NEURON_DATA_WIDTH + WEIGHTS_PER_NEURON_EXP
    val SUM_TO_LOGIT_SHIFT = sumWidth - NEURON_DATA_WIDTH - 5

    // Compute weighted sum
    val products = Vec(SInt(NEURON_DATA_WIDTH + 1 bits), WEIGHTS_PER_NEURON)
    for (i <- 0 until WEIGHTS_PER_NEURON) {
      products(i) := weightMult(logits(i), neuron.weights(i))
    }

    // Tree reduction sum
    val sum = SInt(sumWidth bits)
    sum := products.map(_.resize(sumWidth)).reduce(_ + _) + neuron.bias.resize(sumWidth)

    // ReLU activation
    val activated = SInt(sumWidth bits)
    activated := sum
    when(activate && sum < 0) {
      activated := 0
    }

    // Scale down to logit size
    val logit = (activated >> SUM_TO_LOGIT_SHIFT).resize(NEURON_DATA_WIDTH)
    logit
  }

  /** Forward pass for an entire layer. */
  def layerForward(
    layer: Vec[NeuronParams],
    logits: Vec[SInt],
    activate: Bool
  ): Vec[SInt] = {
    val result = Vec(SInt(NEURON_DATA_WIDTH bits), WEIGHTS_PER_NEURON)
    for (i <- 0 until WEIGHTS_PER_NEURON) {
      result(i) := neuronForward(layer(i), logits, activate)
    }
    result
  }

  /** Convert game state to input observation logits. */
  def observeState(gs: GameState, p1Perspective: Bool): Vec[SInt] = {
    import MapConfig.TILE_PX_BITS

    // Select first/second player based on perspective
    val first  = Player()
    val second = Player()
    when(p1Perspective) {
      first  := gs.p1
      second := gs.p2
    } otherwise {
      first  := gs.p2
      second := gs.p1
    }

    // Helper: coin position as pixel-scale signed
    val coinPxX = (gs.coinPos.x.resize(NEURON_DATA_WIDTH bits) << TILE_PX_BITS).asSInt.resize(NEURON_DATA_WIDTH)
    val coinPxY = (gs.coinPos.y.resize(NEURON_DATA_WIDTH bits) << TILE_PX_BITS).asSInt.resize(NEURON_DATA_WIDTH)

    // Helper: convert F4 position to signed logit
    def posToLogit(f: SFix): SInt = f.raw.resize(NEURON_DATA_WIDTH)

    // Helper: velocity to logit
    def velToLogit(f: SFix): SInt = f.raw.resize(NEURON_DATA_WIDTH)

    // Helper: dead flag to +32 (alive) or -32 (dead)
    def deadFlag(deadTimeout: UInt): SInt = {
      Mux(deadTimeout === 0, S(32, NEURON_DATA_WIDTH bits), S(-32, NEURON_DATA_WIDTH bits))
    }

    // Helper: comparison delta
    def compDelta(a: SInt, b: SInt): SInt = {
      Mux(a < b, S(32, NEURON_DATA_WIDTH bits), S(-32, NEURON_DATA_WIDTH bits))
    }

    val firstPosX  = posToLogit(first.pos.x)
    val firstPosY  = posToLogit(first.pos.y)
    val secondPosX = posToLogit(second.pos.x)
    val secondPosY = posToLogit(second.pos.y)

    // Build the observation vector: 16 used entries, remaining are 0
    // Each element assigned exactly once to avoid ASSIGNMENT OVERLAP
    val entries = Seq(
      coinPxX,                                // 0
      coinPxY,                                // 1
      firstPosX,                              // 2
      firstPosY,                              // 3
      velToLogit(first.vel.x),                // 4
      velToLogit(first.vel.y),                // 5
      deadFlag(first.deadTimeout),            // 6
      secondPosX,                             // 7
      secondPosY,                             // 8
      velToLogit(second.vel.x),               // 9
      velToLogit(second.vel.y),               // 10
      deadFlag(second.deadTimeout),           // 11
      compDelta(firstPosX, secondPosX),       // 12
      compDelta(firstPosY, secondPosY),       // 13
      compDelta(firstPosX, coinPxX),          // 14
      compDelta(firstPosY, coinPxY)           // 15
    )

    val observation = Vec(SInt(NEURON_DATA_WIDTH bits), WEIGHTS_PER_NEURON)
    for (i <- 0 until WEIGHTS_PER_NEURON) {
      if (i < entries.length) {
        observation(i) := entries(i)
      } else {
        observation(i) := 0
      }
    }

    observation
  }
}

/**
 * Neural Network component.
 *
 * Stores layer parameters in registers. Runs forward propagation
 * through LAYER_COUNT layers when pulsed with `go`, taking one
 * clock cycle per layer. Outputs `action` and pulses `done`.
 *
 * Parameters are loaded via (param, paramIndex, paramValid) using
 * Decoder.decodeAddress.
 */
class NeuralNetwork extends Component {
  import NnConfig._

  val io = new Bundle {
    // BRAM parameter loading
    val param      = in Bits(BramConfig.BRAM_WIDTH bits)
    val paramIndex = in UInt(BramConfig.BRAM_ADDR_BITS bits)
    val paramValid = in Bool()

    // NN inference
    val gs            = in(GameState())
    val p1Perspective = in Bool()
    val action        = out(PlayerInput())
    val go            = in Bool()
    val done          = out Bool()
  }

  // --- Layer parameter registers ---
  val layers = Vec(Vec(Reg(NeuronParams()) init(NeuronParams().getZero), WEIGHTS_PER_NEURON), LAYER_COUNT)

  // --- Inference state ---
  val logits       = Vec(Reg(SInt(NEURON_DATA_WIDTH bits)) init(0), WEIGHTS_PER_NEURON)
  val layerCounter = Reg(UInt(LAYER_COUNT_EXP bits)) init 0
  val running      = Reg(Bool()) init False

  // --- Continuous: input logits from game state observation ---
  val inputLogits = NeuralNetwork.observeState(io.gs, io.p1Perspective)

  // --- Continuous: action output from logits ---
  io.action.left  := logits(0) > 0
  io.action.right := logits(1) > 0
  io.action.jump  := logits(2) > 0

  // --- Main inference process ---
  io.done := False

  val runningV = Bool()
  runningV := running

  when(io.go) {
    running  := True
    runningV := True
  }

  when(runningV) {
    when(layerCounter === 0) {
      // First layer: input comes from observations
      val result = NeuralNetwork.layerForward(layers(0), inputLogits, True)
      for (i <- 0 until WEIGHTS_PER_NEURON) {
        logits(i) := result(i)
      }
      layerCounter := layerCounter + 1
    } elsewhen(layerCounter < LAYER_COUNT - 1) {
      // Hidden layers
      val result = NeuralNetwork.layerForward(layers(layerCounter), logits, True)
      for (i <- 0 until WEIGHTS_PER_NEURON) {
        logits(i) := result(i)
      }
      layerCounter := layerCounter + 1
    } otherwise {
      // Output layer (no activation)
      val result = NeuralNetwork.layerForward(layers(layerCounter), logits, False)
      for (i <- 0 until WEIGHTS_PER_NEURON) {
        logits(i) := result(i)
      }
      layerCounter := 0
      running := False
      io.done := True
    }
  }

  // --- Decoder process: load parameters into layers ---
  when(io.paramValid) {
    val updated = Decoder.decodeAddress(
      Vec(layers.map(l => Vec(l.map(n => {
        val np = NeuronParams()
        np := n
        np
      })))),
      io.param,
      io.paramIndex
    )
    for (l <- 0 until LAYER_COUNT; n <- 0 until WEIGHTS_PER_NEURON) {
      layers(l)(n) := updated(l)(n)
    }
  }
}
