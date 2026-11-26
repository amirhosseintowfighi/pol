// Non-variadic wrappers around opus_encoder_ctl / opus_decoder_ctl, which Swift cannot call directly.
#ifndef TELBRIDGE_OPUS_H
#define TELBRIDGE_OPUS_H
#include "opus.h"
int pol_opus_configure_voice(OpusEncoder *enc, opus_int32 bitrate);
#endif
