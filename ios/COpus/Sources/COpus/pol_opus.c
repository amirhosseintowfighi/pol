#include "pol_opus.h"

/* Voice settings shared with the Android gateway (core/.../audio/Codecs.kt): 32 kbit/s, wideband (HD voice) cap,
   in-band FEC for 10 % loss, no DTX. Returns OPUS_OK or the first error. */
int pol_opus_configure_voice(OpusEncoder *enc, opus_int32 bitrate) {
    int r;
    if ((r = opus_encoder_ctl(enc, OPUS_SET_BITRATE(bitrate))) != OPUS_OK) return r;
    if ((r = opus_encoder_ctl(enc, OPUS_SET_MAX_BANDWIDTH(OPUS_BANDWIDTH_WIDEBAND))) != OPUS_OK) return r;
    if ((r = opus_encoder_ctl(enc, OPUS_SET_SIGNAL(OPUS_SIGNAL_VOICE))) != OPUS_OK) return r;
    if ((r = opus_encoder_ctl(enc, OPUS_SET_COMPLEXITY(5))) != OPUS_OK) return r;
    if ((r = opus_encoder_ctl(enc, OPUS_SET_INBAND_FEC(1))) != OPUS_OK) return r;
    if ((r = opus_encoder_ctl(enc, OPUS_SET_PACKET_LOSS_PERC(10))) != OPUS_OK) return r;
    return opus_encoder_ctl(enc, OPUS_SET_DTX(0));
}
