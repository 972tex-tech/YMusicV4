// noinspection JSUnresolvedReference

import { appendCss } from './yt/append_css';
import { audioOnly } from './yt/audio_only';
import { blankVideoFeeder } from './yt/blank_feeder';
import { mockSignatureDecoder } from './yt/decode_signatures';
import { disableClickTogglePlay } from './yt/disable_click_toggle_play_pause';
import { errorLogCollector } from './yt/error_log_collector';
import { exportInternalPlayer } from './yt/export_internal_player';
import { fakeClient } from './yt/fake_client';
import { fixPremiumQualitySelection } from './yt/fix_premium_quality_selection';
import { hideChromeBottom } from './yt/hide_bottom_controls_js';
import { playerConnect } from './yt/player_connect';
import { tryDisableSabr } from './yt/sabr';
import { skipAds } from './yt/skip_ads';

export function ytScripts(wvApi = window.ymixWvApi) {
  const isMainFrame = window.self == window.top;
  const isDesktop = navigator.userAgent.includes('Macintosh');
  if (!isMainFrame) return;
  if (!window.location.origin.includes('youtube.com')) return;
  exportInternalPlayer(wvApi);
  skipAds(wvApi);
  // disableSeekBar(wvApi);
  fixPremiumQualitySelection(wvApi);
  fakeClient(wvApi);
  audioOnly(wvApi);
  blankVideoFeeder(wvApi);
  playerConnect(wvApi);
  appendCss(isDesktop);
  hideChromeBottom(wvApi, isDesktop);
  tryDisableSabr(wvApi);
  disableClickTogglePlay(wvApi);
  mockSignatureDecoder(wvApi);
  errorLogCollector(wvApi);
  Object.defineProperty(navigator, 'serviceWorker', {
    value: undefined,
    configurable: true,
    writable: false,
  });
}
