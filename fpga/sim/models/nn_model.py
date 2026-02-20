"""Python reference implementation of the VHDL neural network forward pass.

Matches nn_types.vhd exactly: same bit widths, same arithmetic, same truncation.
"""

# Constants matching nn_types.vhd
WEIGHT_BITS = 3
BIAS_BITS = 4
NEURON_DATA_WIDTH = 12

WEIGHTS_PER_NEURON_EXP = 5
WEIGHTS_PER_NEURON = 2**WEIGHTS_PER_NEURON_EXP  # 32

LAYER_COUNT_EXP = 2
LAYER_COUNT = 2**LAYER_COUNT_EXP  # 4

TOTAL_WEIGHTS = (WEIGHTS_PER_NEURON**2) * LAYER_COUNT  # 4096
TOTAL_BIAS = LAYER_COUNT * WEIGHTS_PER_NEURON  # 128
TOTAL_PARAMS = TOTAL_WEIGHTS + TOTAL_BIAS  # 4224

# Sum accumulator width: 2 + NEURON_DATA_WIDTH + WEIGHTS_PER_NEURON_EXP
SUM_BITS = 2 + NEURON_DATA_WIDTH + WEIGHTS_PER_NEURON_EXP  # 19
SUM_TO_LOGIT_SHIFT = SUM_BITS - NEURON_DATA_WIDTH - 5  # 2

type Neuron = tuple[list[int], int]


def to_signed(val: int, bits: int) -> int:
  """Interpret an unsigned integer as a signed value with the given bit width."""
  mask = (1 << bits) - 1
  val = val & mask
  if val >= (1 << (bits - 1)):
    val -= 1 << bits
  return val


def clamp_signed(val: int, bits: int) -> int:
  """Wrap a Python integer to fit in a signed two's complement bit width."""
  mask = (1 << bits) - 1
  val = val & mask
  if val >= (1 << (bits - 1)):
    val -= 1 << bits
  return val


def vhdl_resize_signed(val: int, old_bits: int, new_bits: int) -> int:
  """VHDL resize for signed: keeps sign bit + lower (new_bits-1) data bits."""
  unsigned_val = val & ((1 << old_bits) - 1)
  sign = (unsigned_val >> (old_bits - 1)) & 1
  lower = unsigned_val & ((1 << (new_bits - 1)) - 1)
  result = (sign << (new_bits - 1)) | lower
  if result >= (1 << (new_bits - 1)):
    result -= 1 << new_bits
  return result


def weight_mult(data: int, weight: int) -> int:
  """Match weight_mult from nn_types.vhd.

  data: 12-bit signed (neuron_logit_t)
  weight: 3-bit signed (weight_t)
  returns: 13-bit signed (post_mult_t)
  """
  post_mult = data  # resize from 12 to 13 is just sign extension

  if weight == -2:
    post_mult = -(post_mult << 1)
  elif weight == -1:
    post_mult = -post_mult
  elif weight == 1:
    pass
  elif weight == 2:
    post_mult = post_mult << 1
  else:
    # weight is 0, 3, -3, or -4 -> zero
    post_mult = 0

  return clamp_signed(post_mult, NEURON_DATA_WIDTH + 1)


def neuron_forward(
  weights: list[int], bias: int, logits: list[int], activate: bool
) -> int:
  """Match neuron_forward from nn_types.vhd.

  weights: list of 3-bit signed ints (length WEIGHTS_PER_NEURON)
  bias: 4-bit signed int
  logits: list of 12-bit signed ints (length WEIGHTS_PER_NEURON)
  activate: bool (ReLU)
  returns: 12-bit signed int (neuron_logit_t)
  """
  total = 0
  for i in range(WEIGHTS_PER_NEURON):
    total += weight_mult(logits[i], weights[i])
  total = clamp_signed(total, SUM_BITS)

  total += bias
  total = clamp_signed(total, SUM_BITS)

  if activate and total < 0:
    total = 0

  # Arithmetic shift right, then resize
  shifted = total >> SUM_TO_LOGIT_SHIFT  # Python >> is arithmetic for negative ints
  shifted = clamp_signed(shifted, SUM_BITS)
  logit = vhdl_resize_signed(shifted, SUM_BITS, NEURON_DATA_WIDTH)
  return logit


def layer_forward(
  neurons: list[Neuron], logits: list[int], activate: bool
) -> list[int]:
  """Match layer_forward from nn_types.vhd.

  neurons: list of (weights, bias) tuples (length WEIGHTS_PER_NEURON)
  logits: list of 12-bit signed ints (length WEIGHTS_PER_NEURON)
  activate: bool
  returns: list of 12-bit signed ints (length WEIGHTS_PER_NEURON)
  """
  return [
    neuron_forward(neurons[i][0], neurons[i][1], logits, activate)
    for i in range(WEIGHTS_PER_NEURON)
  ]


def decode_params(params: list[int]) -> list[list[Neuron]]:
  """Decode flat param list into layers structure matching decode_address.

  params: list of TOTAL_PARAMS unsigned 4-bit ints (0-15)
  returns: layers[LAYER_COUNT][WEIGHTS_PER_NEURON] = (weights[], bias)
  """
  # Initialize: layers[layer][neuron] = (weights[32], bias)
  layers: list[list[Neuron]] = [
    [([0] * WEIGHTS_PER_NEURON, 0) for _ in range(WEIGHTS_PER_NEURON)]
    for _ in range(LAYER_COUNT)
  ]

  for i in range(TOTAL_PARAMS):
    if i < TOTAL_WEIGHTS:
      weight_idx = i & (WEIGHTS_PER_NEURON - 1)
      neuron_idx = (i >> WEIGHTS_PER_NEURON_EXP) & (WEIGHTS_PER_NEURON - 1)
      layer_idx = (i >> (2 * WEIGHTS_PER_NEURON_EXP)) & (LAYER_COUNT - 1)
      weight_val = to_signed(params[i] & ((1 << WEIGHT_BITS) - 1), WEIGHT_BITS)
      weights, bias = layers[layer_idx][neuron_idx]
      weights[weight_idx] = weight_val
    else:
      # For biases, the decoder reads bits directly from param_index
      neuron_idx = i & (WEIGHTS_PER_NEURON - 1)
      layer_idx = (i >> WEIGHTS_PER_NEURON_EXP) & (LAYER_COUNT - 1)
      bias_val = to_signed(params[i] & ((1 << BIAS_BITS) - 1), BIAS_BITS)
      weights, _ = layers[layer_idx][neuron_idx]
      layers[layer_idx][neuron_idx] = (weights, bias_val)

  return layers


def nn_forward(
  params: list[int], input_logits: list[int]
) -> tuple[list[int], list[bool]]:
  """Run the full NN forward pass.

  params: list of TOTAL_PARAMS unsigned 4-bit ints
  input_logits: list of WEIGHTS_PER_NEURON 12-bit signed ints
  returns: (logits_0_to_2, actions) where actions = [left, right, jump]
  """
  layers = decode_params(params)
  logits = list(input_logits)

  for layer_i in range(LAYER_COUNT):
    activate = layer_i < LAYER_COUNT - 1
    logits = layer_forward(layers[layer_i], logits, activate)

  actions = [logits[0] > 0, logits[1] > 0, logits[2] > 0]
  return logits[:3], actions
