#ifndef YMUSIC_AUDIO_FX_H
#define YMUSIC_AUDIO_FX_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define AFX_BANDS 10
#define AFX_LEVELS 64

typedef struct AudioFx AudioFx;

AudioFx *audio_fx_create(void);
void audio_fx_destroy(AudioFx *fx);
void audio_fx_reset(AudioFx *fx);

void audio_fx_set_rate(AudioFx *fx, int rate);
void audio_fx_set_volume(AudioFx *fx, float vol);
void audio_fx_set_eq(AudioFx *fx, const float bands_db[AFX_BANDS]);
void audio_fx_set_bass(AudioFx *fx, float db);
void audio_fx_set_virtualizer(AudioFx *fx, float width);
/* User level trim, -12..+12 dB, on top of the automatic loudness-matched pre-gain. */
void audio_fx_set_preamp(AudioFx *fx, float db);
void audio_fx_set_speed(AudioFx *fx, float speed);
void audio_fx_set_pitch(AudioFx *fx, float pitch);

/* Same-length: EQ, bass, virtualizer, volume. frames = samples per channel.
 * Also feeds the live spectrum used by [audio_fx_levels], even when color is off. */
void audio_fx_color(AudioFx *fx, int16_t *s16, int frames, int ch);

/* Fixed-position log-frequency bars in 0..1 (bass left, treble right), for the window ending
 * [delay_frames] before the newest colored frame — the frames the sink still holds unplayed. */
int audio_fx_levels(AudioFx *fx, float *out, int n, int delay_frames);

/*
 * SoundTouch pair: rate = pitch, tempo = speed/pitch.
 * Cubic+FIR transpose; TDStretch with energy-normalized overlap seek.
 */
int audio_fx_stretch(AudioFx *fx, const int16_t *in, int in_frames, int16_t *out, int out_cap, int ch);

#ifdef __cplusplus
}
#endif

#endif
