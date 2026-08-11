export function appendCss(isDesktop?: boolean) {
  let pxRatio = window.devicePixelRatio;
  if (pxRatio == null || pxRatio <= 0) {
    pxRatio = 1;
  }
  const css = `
    ${isDesktop ? '' : '.ytp-chrome-bottom,'} .ytp-watch-later-button, .ytp-share-button, .ytp-overflow-button { display: none !important; }
    .ytp-caption-window-bottom { margin-bottom: 0px !important; }
    .ytp-caption-segment { font-size: ${36 / pxRatio}px !important; }
    #ytp-caption-window-container { visibility: hidden; }
    .ytp-spinner-container { visibility: hidden; }
    video { 
      width: 100% !important; 
      height: 100vh !important; 
      top: 0px !important;
      left: 0px !important;
      right: 0px !important;
      bottom: 0px !important;
    }
    .object-fit-cover {
      object-fit: cover !important;
    }
    .object-fit-contain {
      object-fit: contain !important;
    }
    .object-fit-fill {
      object-fit: fill !important;
    }
    ytm-watch-player-controls > *:not(player-control-overlay),
     .player-controls-content > *:not(double-tap-to-seek) {
      visibility: hidden; 
    }
    .player-controls-background {
      background: transparent !important;
    }
  `;
  const style = document.createElement('style');
  style.textContent = css;
  document.head.appendChild(style);
  if (DEBUG) console.log('Append css', css);
}
