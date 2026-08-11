/* eslint-disable no-debugger,prefer-const */
if (DEBUG) {
  function objWatch(obj: any, propertyName: string) {
    let currentValue = obj[propertyName];
    let enableGetDebugger = true;
    let enableSetDebugger = true;
    Object.defineProperty(obj, propertyName, {
      configurable: true,
      enumerable: true,
      get: function () {
        if (enableGetDebugger) debugger;
        return currentValue;
      },
      set(newValue: any) {
        if (enableSetDebugger) debugger;
        currentValue = newValue;
      },
    });
  };
  (window as any).objWatch = objWatch;
  // objWatch(window, 'trayride');
}
